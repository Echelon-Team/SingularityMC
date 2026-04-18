//! GitHub Releases API client.
//!
//! Two operations we care about:
//! 1. [`GitHubClient::latest_release`] — resolve the "current" release for a
//!    channel (Stable: GitHub's `/releases/latest`; Beta: newest `prerelease`
//!    in the recent list).
//! 2. [`GitHubClient::fetch_manifest`] — download the per-OS
//!    `manifest-{windows,linux}.json` asset from a [`Release`] and parse it
//!    through [`Manifest::parse`].
//!
//! **Error taxonomy:**
//! - [`UpdaterError::Network`] for transport/parse failures (reqwest `?`).
//! - [`UpdaterError::NotFound`] when the server returned fine but the
//!   expected resource wasn't there (no prerelease on Beta, no manifest
//!   asset on the picked release, 404 from `/releases/latest` meaning "repo
//!   has no stable release yet").
//! - [`UpdaterError::Json`] / [`UpdaterError::Manifest`] for manifest
//!   content validation via [`Manifest::parse`].
//! - [`UpdaterError::InvalidConfig`] for malformed base URL at construction.
//!
//! **Retry:** none at this layer — the downloader in Task 2.5 owns retry
//! policy for file downloads. A rate-limit (HTTP 429/403 with rate-limit
//! headers) or transient 5xx from the API surfaces as `Network` and Task
//! 2.11's state machine decides whether to back off.
//!
//! TODO(Task 2.11): parse `X-RateLimit-Remaining` / `X-RateLimit-Reset` /
//! `Retry-After` on rate-limit responses to surface actionable timing info
//! instead of a generic Network error.

use crate::manifest::Manifest;
use crate::{Channel, OsTarget, Result, UpdaterError};
use reqwest::{Client, StatusCode};
use serde::Deserialize;
use std::time::Duration;

/// GitHub's `/releases` list page size. GitHub API maximum is 100 per
/// request; we use the max so a project that cuts many stable releases in
/// a row before a prerelease doesn't make Beta miss a legitimate prerelease
/// at position 21+.
const RECENT_RELEASES_WINDOW: u32 = 100;

/// GitHub Releases API "release" object. Only fields we consume are modeled;
/// unknown fields are silently ignored by serde's default behavior, so
/// future GitHub API additions don't break parsing.
///
/// Fields are `pub` so tests and (hypothetical) other consumers in this
/// crate can access them directly — this type models GitHub's wire shape,
/// not our domain, so manual construction outside of `serde_json::from_str`
/// / `.json::<Release>()` is legal but only sensible in tests.
///
/// `tag_name` is deliberately kept as `String` rather than parsed to our
/// domain [`Version`](crate::Version): GitHub tags are free-form (`v1.2.3`,
/// `nightly`, `release-2026-04` are all valid), and strict parsing would
/// make a single non-semver tag poison the entire list-fetch path. Parsing
/// to `Version` happens at consumption sites that need semver ordering
/// (Task 2.7 min_auto_update_version comparison).
#[derive(Debug, Clone, Deserialize)]
pub struct Release {
    /// The git tag (e.g. `"v0.1.0"`).
    pub tag_name: String,
    /// Human-readable release title.
    pub name: String,
    /// Markdown body on the Releases page. `None` when the release was
    /// created without a body.
    #[serde(default)]
    pub body: Option<String>,
    pub prerelease: bool,
    pub html_url: String,
    pub assets: Vec<Asset>,
}

/// An asset attached to a Release. Used here to locate the per-OS manifest
/// JSON (via [`GitHubClient::fetch_manifest`] → `browser_download_url`).
/// Files listed INSIDE that manifest (i.e. `FileEntry`) are fetched in
/// Task 2.5 via their own `FileEntry.url`, not through this type.
#[derive(Debug, Clone, Deserialize)]
pub struct Asset {
    pub name: String,
    pub browser_download_url: String,
    pub size: u64,
}

/// Client for the GitHub Releases REST API.
///
/// Holds a configured [`reqwest::Client`] (30s timeout, pinned User-Agent
/// and API-Version headers) plus the target repo owner/name. Constructor
/// accepts a configurable base URL so tests can point at a `wiremock`
/// server; production callers use [`GitHubClient::new`] which pins the
/// public API endpoint.
///
/// `Clone` is cheap: all three fields are inexpensive to clone
/// (`reqwest::Client` is `Arc<Inner>` internally, owner/repo are short
/// `String`s, base_url too). Callers that retry across an auto-retry
/// loop clone the client so each tick reuses the TCP/TLS connection
/// pool instead of paying a fresh-handshake cost.
#[derive(Debug, Clone)]
pub struct GitHubClient {
    client: Client,
    base_url: String,
    owner: String,
    repo: String,
}

impl GitHubClient {
    /// Standard constructor — uses the public GitHub API base URL.
    pub fn new(owner: &str, repo: &str) -> Result<Self> {
        Self::with_base_url("https://api.github.com", owner, repo)
    }

    /// Constructor accepting a custom base URL. Public for tests (point at
    /// a wiremock server) and for self-hosted GitHub Enterprise proxies.
    ///
    /// Validates the URL shape at construction so a malformed config fails
    /// fast with [`UpdaterError::InvalidConfig`] rather than a confusing
    /// [`UpdaterError::Network`] on the first request.
    pub fn with_base_url(base_url: &str, owner: &str, repo: &str) -> Result<Self> {
        let after_scheme = base_url
            .strip_prefix("https://")
            .or_else(|| base_url.strip_prefix("http://"))
            .ok_or_else(|| {
                UpdaterError::InvalidConfig(format!(
                    "base_url must start with http:// or https://, got: {base_url}"
                ))
            })?;
        // Require a non-empty authority (host) component before any path.
        let host = after_scheme.split('/').next().unwrap_or("");
        if host.is_empty() {
            return Err(UpdaterError::InvalidConfig(format!(
                "base_url must include a host component: {base_url}"
            )));
        }
        if owner.is_empty() || repo.is_empty() {
            return Err(UpdaterError::InvalidConfig(
                "owner and repo must be non-empty".into(),
            ));
        }

        let client = Client::builder()
            .user_agent(format!(
                "SingularityMC-AutoUpdate/{}",
                crate::BUILD_VERSION
            ))
            // 10 s per-request timeout. Short enough that a stalled
            // GitHub edge fails fast and the auto-retry ladder (8 s, 10 s,
            // 12 s, ...) gets a chance to surface progress to the user
            // instead of leaving them staring at a spinner for 30 s.
            .timeout(Duration::from_secs(10))
            .build()?;
        Ok(Self {
            client,
            base_url: base_url.trim_end_matches('/').to_string(),
            owner: owner.to_string(),
            repo: repo.to_string(),
        })
    }

    /// Resolve the current release for the given channel.
    pub async fn latest_release(&self, channel: Channel) -> Result<Release> {
        match channel {
            Channel::Stable => self.fetch_latest_stable().await,
            Channel::Beta => self.fetch_latest_prerelease().await,
        }
    }

    async fn fetch_latest_stable(&self) -> Result<Release> {
        // `/releases/latest` returns the highest released non-draft
        // non-prerelease — exactly Stable semantics.
        let url = format!(
            "{}/repos/{}/{}/releases/latest",
            self.base_url, self.owner, self.repo
        );
        let response = self
            .client
            .get(&url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .send()
            .await?;

        // 404 on /releases/latest means "this repo has no stable release
        // yet" (empty repo, drafts only, or prerelease-only). Map to
        // NotFound so Task 2.11's state machine distinguishes this from a
        // transient network fault — it's "no update available", not a
        // retryable failure. Mirrors the Beta channel's NotFound path
        // (no-prerelease-in-list).
        if response.status() == StatusCode::NOT_FOUND {
            return Err(UpdaterError::NotFound(format!(
                "no stable release in {}/{}",
                self.owner, self.repo
            )));
        }

        let release = response.error_for_status()?.json::<Release>().await?;
        Ok(release)
    }

    async fn fetch_latest_prerelease(&self) -> Result<Release> {
        // No dedicated endpoint; list recent releases (default sort is
        // created_at desc) and take the first prerelease.
        let url = format!(
            "{}/repos/{}/{}/releases?per_page={RECENT_RELEASES_WINDOW}",
            self.base_url, self.owner, self.repo
        );
        let all: Vec<Release> = self
            .client
            .get(&url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        all.into_iter().find(|r| r.prerelease).ok_or_else(|| {
            UpdaterError::NotFound(format!(
                "no prerelease in the last {RECENT_RELEASES_WINDOW} releases of {}/{}",
                self.owner, self.repo
            ))
        })
    }

    /// Download and parse the per-OS manifest asset from a resolved release.
    pub async fn fetch_manifest(&self, release: &Release, os: OsTarget) -> Result<Manifest> {
        let manifest_name = match os {
            OsTarget::Windows => "manifest-windows.json",
            OsTarget::Linux => "manifest-linux.json",
        };
        let asset = release
            .assets
            .iter()
            .find(|a| a.name == manifest_name)
            .ok_or_else(|| {
                UpdaterError::NotFound(format!(
                    "asset '{manifest_name}' not found in release '{}'",
                    release.tag_name
                ))
            })?;
        let content = self
            .client
            .get(&asset.browser_download_url)
            .send()
            .await?
            .error_for_status()?
            .text()
            .await?;
        Manifest::parse(&content)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{header, header_exists, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn release_json(tag: &str, prerelease: bool, assets: &[(&str, &str)]) -> String {
        let assets_json: Vec<String> = assets
            .iter()
            .map(|(name, url)| {
                format!(r#"{{"name":"{name}","browser_download_url":"{url}","size":100}}"#)
            })
            .collect();
        format!(
            r#"{{"tag_name":"{tag}","name":"Release {tag}","body":null,"prerelease":{prerelease},"html_url":"https://github.com/test/test","assets":[{}]}}"#,
            assets_json.join(",")
        )
    }

    // Well-formed manifest content (matches manifest.rs test patterns).
    const VALID_MANIFEST: &str = r#"{
        "version":"0.1.0","os":"windows","releasedAt":"2026-04-15T10:00:00Z",
        "minAutoUpdateVersion":"0.1.0","launcherExecutable":"launcher/SingularityMC.exe",
        "changelog":"- first release",
        "files":[{"path":"launcher/app.jar","url":"https://example.com/app.jar","size":100,"sha256":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"}]
    }"#;

    #[tokio::test]
    async fn latest_release_stable_returns_release_from_latest_endpoint() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/repos/test-owner/test-repo/releases/latest"))
            .and(header("Accept", "application/vnd.github+json"))
            .and(header("X-GitHub-Api-Version", "2022-11-28"))
            .respond_with(ResponseTemplate::new(200).set_body_string(release_json(
                "v1.2.3",
                false,
                &[],
            )))
            .expect(1)
            .mount(&server)
            .await;

        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let release = client.latest_release(Channel::Stable).await.unwrap();
        assert_eq!(release.tag_name, "v1.2.3");
        assert!(!release.prerelease);
    }

    #[tokio::test]
    async fn latest_release_stable_returns_notfound_on_404() {
        // 404 on /releases/latest means "no stable release exists yet" —
        // must surface as NotFound (actionable state: no update available)
        // not Network (retryable transport failure).
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/repos/test-owner/test-repo/releases/latest"))
            .respond_with(ResponseTemplate::new(404))
            .expect(1)
            .mount(&server)
            .await;

        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let result = client.latest_release(Channel::Stable).await;
        assert!(
            matches!(result, Err(UpdaterError::NotFound(_))),
            "404 on stable must surface as NotFound, got {result:?}"
        );
    }

    #[tokio::test]
    async fn latest_release_beta_picks_first_prerelease() {
        let server = MockServer::start().await;
        let releases_list = format!(
            "[{},{},{}]",
            release_json("v2.0.0-beta.2", true, &[]),
            release_json("v1.9.0", false, &[]),
            release_json("v2.0.0-beta.1", true, &[]),
        );
        Mock::given(method("GET"))
            .and(path("/repos/test-owner/test-repo/releases"))
            .respond_with(ResponseTemplate::new(200).set_body_string(releases_list))
            .mount(&server)
            .await;

        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let release = client.latest_release(Channel::Beta).await.unwrap();
        // First prerelease in list order wins (GitHub returns created_at desc).
        assert_eq!(release.tag_name, "v2.0.0-beta.2");
    }

    #[tokio::test]
    async fn latest_release_beta_returns_notfound_when_no_prereleases() {
        let server = MockServer::start().await;
        let releases_list = format!("[{}]", release_json("v1.0.0", false, &[]));
        Mock::given(method("GET"))
            .and(path("/repos/test-owner/test-repo/releases"))
            .respond_with(ResponseTemplate::new(200).set_body_string(releases_list))
            .mount(&server)
            .await;

        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let result = client.latest_release(Channel::Beta).await;
        assert!(
            matches!(result, Err(UpdaterError::NotFound(_))),
            "expected NotFound, got {result:?}"
        );
    }

    #[tokio::test]
    async fn latest_release_propagates_5xx_as_network_without_retry() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/repos/test-owner/test-repo/releases/latest"))
            .respond_with(ResponseTemplate::new(503))
            // .expect(1) pins the no-retry contract: a future middleware
            // adding retries would hammer the server silently without this.
            .expect(1)
            .mount(&server)
            .await;

        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let result = client.latest_release(Channel::Stable).await;
        assert!(
            matches!(result, Err(UpdaterError::Network(_))),
            "expected Network, got {result:?}"
        );
    }

    #[tokio::test]
    async fn fetch_manifest_downloads_and_parses_windows_asset() {
        let server = MockServer::start().await;
        let manifest_url = format!("{}/download/manifest-windows.json", server.uri());
        Mock::given(method("GET"))
            .and(path("/download/manifest-windows.json"))
            .respond_with(ResponseTemplate::new(200).set_body_string(VALID_MANIFEST))
            .expect(1)
            .mount(&server)
            .await;

        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let release: Release = serde_json::from_str(&release_json(
            "v0.1.0",
            false,
            &[("manifest-windows.json", &manifest_url)],
        ))
        .unwrap();

        let manifest = client
            .fetch_manifest(&release, OsTarget::Windows)
            .await
            .unwrap();
        assert_eq!(manifest.version.as_str(), "0.1.0");
        assert_eq!(manifest.files.len(), 1);
    }

    #[tokio::test]
    async fn fetch_manifest_returns_notfound_when_asset_missing() {
        let server = MockServer::start().await;
        let release: Release = serde_json::from_str(&release_json(
            "v0.1.0",
            false,
            // Only Linux manifest present; Windows fetch should fail.
            &[(
                "manifest-linux.json",
                &format!("{}/download/manifest-linux.json", server.uri()),
            )],
        ))
        .unwrap();
        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let result = client.fetch_manifest(&release, OsTarget::Windows).await;
        assert!(
            matches!(result, Err(UpdaterError::NotFound(_))),
            "expected NotFound, got {result:?}"
        );
    }

    #[tokio::test]
    async fn fetch_manifest_propagates_download_5xx_as_network() {
        // Asset-endpoint (browser_download_url → CDN) failure path.
        let server = MockServer::start().await;
        let manifest_url = format!("{}/download/manifest-windows.json", server.uri());
        Mock::given(method("GET"))
            .and(path("/download/manifest-windows.json"))
            .respond_with(ResponseTemplate::new(500))
            .expect(1)
            .mount(&server)
            .await;

        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let release: Release = serde_json::from_str(&release_json(
            "v0.1.0",
            false,
            &[("manifest-windows.json", &manifest_url)],
        ))
        .unwrap();
        let result = client.fetch_manifest(&release, OsTarget::Windows).await;
        assert!(
            matches!(result, Err(UpdaterError::Network(_))),
            "expected Network, got {result:?}"
        );
    }

    #[tokio::test]
    async fn fetch_manifest_propagates_parse_error_as_json() {
        let server = MockServer::start().await;
        let manifest_url = format!("{}/download/manifest-windows.json", server.uri());
        Mock::given(method("GET"))
            .and(path("/download/manifest-windows.json"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{"version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0","launcherExecutable":"a"}"#,
            ))
            .mount(&server)
            .await;
        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        let release: Release = serde_json::from_str(&release_json(
            "v0.1.0",
            false,
            &[("manifest-windows.json", &manifest_url)],
        ))
        .unwrap();
        let result = client.fetch_manifest(&release, OsTarget::Windows).await;
        assert!(
            matches!(result, Err(UpdaterError::Json(_))),
            "expected Json error, got {result:?}"
        );
    }

    #[tokio::test]
    async fn requests_include_user_agent_header() {
        // GitHub API requires a non-empty User-Agent; pin that we send one
        // so a future refactor removing the builder line is caught.
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/repos/test-owner/test-repo/releases/latest"))
            .and(header_exists("user-agent"))
            .respond_with(ResponseTemplate::new(200).set_body_string(release_json(
                "v1.0.0",
                false,
                &[],
            )))
            .expect(1)
            .mount(&server)
            .await;
        let client = GitHubClient::with_base_url(&server.uri(), "test-owner", "test-repo").unwrap();
        client.latest_release(Channel::Stable).await.unwrap();
    }

    #[test]
    fn client_trims_trailing_slash_from_base_url() {
        let client = GitHubClient::with_base_url("https://api.github.com/", "o", "r").unwrap();
        assert_eq!(client.base_url, "https://api.github.com");
    }

    #[test]
    fn client_accepts_base_url_without_trailing_slash() {
        let client = GitHubClient::with_base_url("https://api.github.com", "o", "r").unwrap();
        assert_eq!(client.base_url, "https://api.github.com");
    }

    #[test]
    fn client_trims_multiple_trailing_slashes() {
        let client = GitHubClient::with_base_url("https://api.github.com///", "o", "r").unwrap();
        assert_eq!(client.base_url, "https://api.github.com");
    }

    #[test]
    fn client_rejects_missing_scheme() {
        let err = GitHubClient::with_base_url("api.github.com", "o", "r").unwrap_err();
        assert!(matches!(err, UpdaterError::InvalidConfig(_)));
    }

    #[test]
    fn client_rejects_non_http_scheme() {
        let err = GitHubClient::with_base_url("ftp://example.com", "o", "r").unwrap_err();
        assert!(matches!(err, UpdaterError::InvalidConfig(_)));
    }

    #[test]
    fn client_rejects_empty_host() {
        let err = GitHubClient::with_base_url("https://", "o", "r").unwrap_err();
        assert!(matches!(err, UpdaterError::InvalidConfig(_)));
        let err = GitHubClient::with_base_url("https:///only/path", "o", "r").unwrap_err();
        assert!(matches!(err, UpdaterError::InvalidConfig(_)));
    }

    #[test]
    fn client_rejects_empty_owner_or_repo() {
        assert!(matches!(
            GitHubClient::with_base_url("https://api.github.com", "", "r"),
            Err(UpdaterError::InvalidConfig(_))
        ));
        assert!(matches!(
            GitHubClient::with_base_url("https://api.github.com", "o", ""),
            Err(UpdaterError::InvalidConfig(_))
        ));
    }

    #[test]
    fn client_can_be_constructed_with_public_api() {
        let _ = GitHubClient::new("Echelon-Team", "SingularityMC").unwrap();
    }
}
