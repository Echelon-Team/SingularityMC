//! Integration tests for the state machine in `app::run_update_flow_with_base_url`.
//!
//! Covers the three top-level paths that `src/app.rs` unit tests cannot reach
//! (because they require real HTTP + filesystem + an async mpsc driven by the
//! UI):
//!
//! 1. **Happy path** — API returns release + manifest + file, flow completes
//!    with `FlowOutcome::Updated(launcher_rel)`.
//! 2. **Retry → Offline** — API fails 4× (initial + 3 retries), a local
//!    manifest exists on disk, the UI sends `UserAction::Offline`, flow
//!    returns `FlowOutcome::UserRequestedOffline(old_launcher_rel)`.
//! 3. **Retry → Fatal** — API fails 4×, no local manifest, flow returns
//!    `Err(UpdaterError::Network)` and leaves `UiState::FatalError` set.
//!
//! The retry scenarios use `#[tokio::test(start_paused = true)]` +
//! `tokio::time::advance` so the 30 s × 3 retry window resolves in the
//! microseconds of wall time, keeping the suite fast.

use sha2::{Digest, Sha256 as Sha256Hasher};
use singularitymc_auto_update::{
    Channel, OsTarget, UpdaterError,
    app::{
        FlowOutcome, REPO_NAME, REPO_OWNER, RunUpdateFlowConfig, UserAction,
        run_update_flow_with_config,
    },
    manifest::{self, Manifest},
    ui::states::UiState,
};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

/// Test-tuned config: zero retry interval + zero checking floor so paused-
/// time advance doesn't race wiremock I/O. Production callers use
/// `RunUpdateFlowConfig::default()` which keeps the 30 s × 3 spec values.
fn test_config(github_base_url: String) -> RunUpdateFlowConfig {
    RunUpdateFlowConfig {
        github_base_url,
        retry_interval_secs: 0,
        max_api_retries: 3,
        checking_min_ms: 0,
    }
}

// ---------- helpers ----------

fn sha256_hex(content: &[u8]) -> String {
    hex::encode(Sha256Hasher::digest(content))
}

/// Build a minimal but schema-valid GitHub Release JSON referencing exactly
/// one `manifest-windows.json` asset at `manifest_url`.
fn release_json(tag: &str, manifest_url: &str) -> String {
    format!(
        r#"{{
            "tag_name":"{tag}","name":"Release {tag}","body":null,
            "prerelease":false,"html_url":"https://github.com/test/test",
            "assets":[{{"name":"manifest-windows.json","browser_download_url":"{manifest_url}","size":100}}]
        }}"#
    )
}

/// Build a valid per-OS manifest JSON with the given `files` list. The first
/// entry's `path` is also used as `launcherExecutable` so tests can assert the
/// returned launcher path. Fields match `manifest::Manifest`'s Serde schema
/// (pinned by `manifest.rs` tests — don't drift).
fn manifest_json(version: &str, files: &[(&str, &str, u64, &str)]) -> String {
    let launcher_rel = files
        .first()
        .map_or("launcher/app.jar", |(p, _, _, _)| *p);
    let files_json: Vec<String> = files
        .iter()
        .map(|(p, url, size, sha)| {
            format!(
                r#"{{"path":"{p}","url":"{url}","size":{size},"sha256":"{sha}"}}"#
            )
        })
        .collect();
    format!(
        r#"{{
            "version":"{version}","os":"windows","releasedAt":"2026-04-15T10:00:00Z",
            "minAutoUpdateVersion":"0.1.0","launcherExecutable":"{launcher_rel}",
            "changelog":"- test",
            "files":[{}]
        }}"#,
        files_json.join(",")
    )
}

/// Drop a pre-existing `local-manifest.json` in `install_dir` so the
/// OfflineAvailable path becomes reachable. `launcher_rel` is echoed back
/// by the flow as `FlowOutcome::UserRequestedOffline(...)`.
fn seed_local_manifest(install_dir: &std::path::Path, launcher_rel: &str) {
    let json = format!(
        r#"{{
            "version":"0.0.5","os":"windows","releasedAt":"2026-01-01T00:00:00Z",
            "minAutoUpdateVersion":"0.1.0","launcherExecutable":"{launcher_rel}",
            "changelog":"- initial",
            "files":[]
        }}"#
    );
    let parsed: Manifest =
        Manifest::parse(&json).expect("seed manifest must be valid for the test");
    manifest::save_local(install_dir, &parsed).expect("save_local in tempdir");
}

/// GitHub stable-release endpoint path for the pinned repo. Built from the
/// `app::REPO_OWNER` / `REPO_NAME` constants so any rename flows through
/// automatically — no drift risk between the wiremock matcher and the
/// production URL.
fn latest_release_path() -> String {
    format!("/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest")
}

// ---------- scenarios ----------

#[tokio::test]
async fn happy_path_transitions_to_updated() {
    let install_dir = tempfile::tempdir().unwrap();
    let server = MockServer::start().await;

    let file_bytes: &[u8] = b"fake launcher jar content";
    let file_sha = sha256_hex(file_bytes);
    let file_url = format!("{}/download/launcher-app.jar", server.uri());
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    Mock::given(method("GET"))
        .and(path("/download/launcher-app.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(file_bytes.to_vec()))
        .mount(&server)
        .await;

    let m_json = manifest_json(
        "0.1.0",
        &[(
            "launcher/app.jar",
            &file_url,
            file_bytes.len() as u64,
            &file_sha,
        )],
    );
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(ResponseTemplate::new(200).set_body_string(m_json))
        .mount(&server)
        .await;

    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);

    let result = run_update_flow_with_config(
        install_dir.path().to_path_buf(),
        Channel::Stable,
        OsTarget::Windows,
        Arc::clone(&state),
        &mut user_rx,
        test_config(server.uri()),
    )
    .await;

    match result {
        Ok(FlowOutcome::Updated(launcher_rel)) => {
            assert_eq!(launcher_rel.as_str(), "launcher/app.jar");
        }
        other => panic!("expected Updated(launcher/app.jar), got {other:?}"),
    }

    // Terminal state must be Starting? — no: the flow itself stops right
    // after `process_release` returns `Ok`; main.rs sets Starting in the
    // post-success spawner. The flow's final state is whatever the last
    // `set_state` left it at — either `Installing` (set before the final
    // ok-path updater work) or the initial `Checking` if the file was
    // already present. Both are valid terminal-pre-main states. The point
    // of the assertion is: no FatalError, no NoInternet.
    let final_state = state.lock().unwrap().clone();
    assert!(
        !matches!(
            final_state,
            UiState::FatalError { .. }
                | UiState::NoInternet { .. }
                | UiState::OfflineAvailable
                | UiState::DownloadFailed
        ),
        "happy path must not end in any error state, got {final_state:?}"
    );
}

#[tokio::test]
async fn exhausted_retries_with_local_manifest_offers_offline() {
    let install_dir = tempfile::tempdir().unwrap();
    seed_local_manifest(install_dir.path(), "launcher/old-app.jar");

    let server = MockServer::start().await;
    // Every call to /releases/latest fails — drives the retry loop to
    // exhaustion (initial call + 3 retries = 4 total 500s).
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);

    let state_bg = Arc::clone(&state);
    let install_path = install_dir.path().to_path_buf();
    let cfg = test_config(server.uri());

    let flow_handle = tokio::spawn(async move {
        run_update_flow_with_config(
            install_path,
            Channel::Stable,
            OsTarget::Windows,
            state_bg,
            &mut user_rx,
            cfg,
        )
        .await
    });

    // Poll until the bg task parks in OfflineAvailable. With
    // retry_interval_secs=0 + checking_min_ms=0, the retries burn through
    // in a handful of runtime ticks — bounded wait with 10 ms polling is
    // ample slack on any CI runner. Real-wall-clock polling (not paused
    // time) so wiremock HTTP I/O advances normally.
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        if matches!(*state.lock().unwrap(), UiState::OfflineAvailable) {
            break;
        }
        if std::time::Instant::now() > deadline {
            panic!(
                "never reached OfflineAvailable; last state was {:?}",
                *state.lock().unwrap()
            );
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // Now drive the UI side: click "Tryb offline".
    user_tx.send(UserAction::Offline).await.unwrap();

    let result = flow_handle.await.unwrap();
    match result {
        Ok(FlowOutcome::UserRequestedOffline(launcher_rel)) => {
            assert_eq!(launcher_rel.as_str(), "launcher/old-app.jar");
        }
        other => panic!(
            "expected UserRequestedOffline(launcher/old-app.jar), got {other:?}"
        ),
    }
}

#[tokio::test]
async fn exhausted_retries_without_local_manifest_is_fatal() {
    let install_dir = tempfile::tempdir().unwrap();
    // NO seed_local_manifest — first-run scenario.

    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);

    let state_bg = Arc::clone(&state);
    let install_path = install_dir.path().to_path_buf();
    let cfg = test_config(server.uri());

    let result = tokio::time::timeout(
        Duration::from_secs(10),
        run_update_flow_with_config(
            install_path,
            Channel::Stable,
            OsTarget::Windows,
            state_bg,
            &mut user_rx,
            cfg,
        ),
    )
    .await
    .expect("flow must finish within 10 s with zero retry interval");

    assert!(
        matches!(result, Err(UpdaterError::Network(_))),
        "expected Err(Network), got {result:?}"
    );

    // handle_api_failure sets FatalError on the no-local-manifest branch.
    let final_state = state.lock().unwrap().clone();
    assert!(
        matches!(final_state, UiState::FatalError { .. }),
        "expected FatalError terminal state, got {final_state:?}"
    );
}
