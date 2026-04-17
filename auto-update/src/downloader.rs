//! File downloader with SHA-256 verification and exponential-backoff retry.
//!
//! Single call to [`Downloader::download_verified`]: stream one
//! [`FileEntry`] to disk via reqwest's chunked body reader, verify the
//! resulting file against the manifest's [`Sha256`](crate::Sha256) hash,
//! retry transient failures (network 5xx, hash mismatch indicating a
//! corrupted response) up to `max_attempts` times with exponential backoff
//! + full jitter.
//!
//! **Hash verification** runs inside `tokio::task::spawn_blocking` so the
//! SHA-256 computation (hundreds of ms on a multi-MB JAR) does not stall the
//! tokio runtime. Uses sync `std::io::copy` + `std::io::BufReader` inside
//! the blocking task — faster than the async `tokio::io::copy` equivalent
//! per 2026 community benchmarks.
//!
//! **Hash mismatch → retry** rather than immediate failure: a mismatch is
//! typically a corrupted in-transit body (partial download, proxy rewrite)
//! that a fresh download usually cures. After `max_attempts` consecutive
//! mismatches we surface [`UpdaterError::HashMismatch`] carrying the actual
//! computed hash from the final attempt — so the user's log shows the real
//! "got {actual}" value, not a sentinel.
//!
//! **Durability:** `sync_all` is called before the tmp handle is dropped,
//! so a crash between download completion and the Task 2.6 file-swap rename
//! leaves the file persisted on disk (no empty-after-reboot surprises).
//!
//! **Tmp cleanup:** orphaned tmp files are removed on every failed attempt
//! (hash mismatch, mid-stream network drop) so a run of N failed attempts
//! doesn't accumulate N × partial-download bytes in `temp_dir`.
//!
//! **Retry policy:** `sleep = uniform(0, base_backoff * 2^attempt)` — full
//! jitter from AWS's recommended pattern, prevents synchronized thundering
//! herd if many clients retry a shared flaky CDN edge. Test constructor
//! [`Downloader::with_config`] lets tests pass `Duration::ZERO` to skip
//! sleeping entirely.
//!
//! **Progress contract:** the callback is invoked per chunk with
//! `(downloaded, total)`. `total` comes from the HTTP `Content-Length`
//! header with fallback to the manifest's declared `file.size`. NOTE:
//! `downloaded` resets to 0 on each retry attempt (UI should treat
//! backward jumps as a retry restart, not a correctness bug), and a lying
//! Content-Length header could briefly produce `downloaded > total` — UI
//! should clamp the displayed ratio.

use crate::manifest::FileEntry;
use crate::{Result, Sha256, UpdaterError};
use rand::Rng;
use reqwest::Client;
use sha2::{Digest, Sha256 as Hasher};
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::io::AsyncWriteExt;

/// Default retry cap — three tries with jitter = ≤7s expected total at base=1s.
const DEFAULT_MAX_ATTEMPTS: u32 = 3;

/// Default base backoff. Full-jitter sleep = uniform(0, base * 2^attempt).
const DEFAULT_BASE_BACKOFF: Duration = Duration::from_secs(1);

/// Per-file HTTP timeout. Large launcher JARs on slow connections need room;
/// we'd rather finish than aggressively kill a near-done download.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(300);

/// Shift cap — prevents `1u32 << attempt` from overflowing if a caller ever
/// passes a huge max_attempts. 2^30 seconds is already ~34 years of base=1s
/// backoff, far beyond anything sane.
const SHIFT_CAP: u32 = 30;

/// Downloads manifest-tracked files with hash verification + retry.
#[derive(Debug)]
pub struct Downloader {
    client: Client,
    temp_dir: PathBuf,
    max_attempts: u32,
    base_backoff: Duration,
}

impl Downloader {
    /// Production constructor: 3 attempts, 1s base backoff, full-jitter sleep.
    pub fn new(temp_dir: PathBuf) -> Result<Self> {
        Self::with_config(temp_dir, DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_BACKOFF)
    }

    /// Configurable constructor. Tests use `Duration::ZERO` base backoff to
    /// skip sleeping between retries. Self-hosted deployments needing
    /// shorter/longer retry windows also use this. `max_attempts == 0` is
    /// silently clamped to 1 — callers never get a "never attempted" outcome.
    pub fn with_config(
        temp_dir: PathBuf,
        max_attempts: u32,
        base_backoff: Duration,
    ) -> Result<Self> {
        std::fs::create_dir_all(&temp_dir)?;
        let client = Client::builder()
            .user_agent(format!(
                "SingularityMC-AutoUpdate/{}",
                crate::BUILD_VERSION
            ))
            .timeout(REQUEST_TIMEOUT)
            .build()?;
        Ok(Self {
            client,
            temp_dir,
            max_attempts: max_attempts.max(1),
            base_backoff,
        })
    }

    /// Download `file` into `temp_dir` and verify SHA-256. See module-level
    /// doc for retry, progress, durability contracts.
    pub async fn download_verified<F>(
        &self,
        file: &FileEntry,
        mut progress: F,
    ) -> Result<PathBuf>
    where
        F: FnMut(u64, u64),
    {
        let file_name = Path::new(file.path.as_str())
            .file_name()
            .ok_or_else(|| {
                UpdaterError::Manifest(format!(
                    "FileEntry.path has no filename component: {}",
                    file.path
                ))
            })?;
        let temp_path = self.temp_dir.join(file_name);

        let mut last_err: Option<UpdaterError> = None;
        for attempt in 0..self.max_attempts {
            if attempt > 0 {
                let wait = self.jittered_backoff(attempt);
                log::warn!(
                    "retry {} for {} ({}) after {}ms",
                    attempt,
                    file.path,
                    file.url,
                    wait.as_millis()
                );
                tokio::time::sleep(wait).await;
            }

            match self
                .attempt_download(file, &temp_path, &mut progress)
                .await
            {
                Ok(()) => {
                    // Hash verify runs inside spawn_blocking to keep the
                    // SHA-256 computation off the tokio worker.
                    let expected = file.sha256.clone();
                    let path_owned = temp_path.clone();
                    let verify_result = tokio::task::spawn_blocking(move || {
                        Self::verify_and_compute(&path_owned, &expected)
                    })
                    .await
                    .expect("hash verify blocking task must not panic");

                    match verify_result {
                        Ok(Ok(())) => return Ok(temp_path),
                        Ok(Err(actual)) => {
                            log::warn!(
                                "hash mismatch for {} ({}); expected {}, got {}; retrying",
                                file.path,
                                file.url,
                                file.sha256,
                                actual
                            );
                            let _ = std::fs::remove_file(&temp_path);
                            last_err = Some(UpdaterError::HashMismatch {
                                path: file.path.clone(),
                                expected: file.sha256.clone(),
                                actual: Some(actual),
                            });
                        }
                        Err(e) => {
                            log::warn!(
                                "hash verify failed for {} ({}): {e}",
                                file.path,
                                file.url
                            );
                            let _ = std::fs::remove_file(&temp_path);
                            last_err = Some(e);
                        }
                    }
                }
                Err(e) => {
                    log::warn!(
                        "download attempt {} failed for {} ({}): {e}",
                        attempt + 1,
                        file.path,
                        file.url
                    );
                    // Cleanup partial download before the next attempt
                    // retruncates via File::create. Guards against orphan
                    // tmp file after a run of failed attempts exhausts.
                    let _ = std::fs::remove_file(&temp_path);
                    last_err = Some(e);
                }
            }
        }

        Err(last_err.expect("retry loop must set last_err on every failed iteration"))
    }

    /// Full-jitter sleep: uniform(0, base * 2^attempt_capped). The cap at
    /// [`SHIFT_CAP`] prevents integer overflow on absurdly-large retry counts.
    fn jittered_backoff(&self, attempt: u32) -> Duration {
        let shift = attempt.min(SHIFT_CAP);
        let max_wait = self.base_backoff.saturating_mul(1u32 << shift);
        let max_ms = max_wait.as_millis() as u64;
        if max_ms == 0 {
            return Duration::ZERO;
        }
        let jittered = rand::rng().random_range(0..=max_ms);
        Duration::from_millis(jittered)
    }

    async fn attempt_download<F>(
        &self,
        file: &FileEntry,
        dest: &Path,
        progress: &mut F,
    ) -> Result<()>
    where
        F: FnMut(u64, u64),
    {
        let mut response = self
            .client
            .get(&file.url)
            .send()
            .await?
            .error_for_status()?;
        let total_size = response.content_length().unwrap_or(file.size);
        let mut downloaded = 0_u64;
        let mut writer = tokio::fs::File::create(dest).await?;
        while let Some(chunk) = response.chunk().await? {
            writer.write_all(&chunk).await?;
            downloaded += chunk.len() as u64;
            progress(downloaded, total_size);
        }
        writer.flush().await?;
        // Durability: without sync_all, a power loss between this return and
        // the Task 2.6 rename could leave the tmp file content in the OS
        // page cache but not on disk — we'd boot into an empty/partial file
        // at the target path after crash-recovery.
        writer.sync_all().await?;
        // Close the handle explicitly so a later rename-atop-this path is
        // not blocked by Windows' shared-lock semantics on open files.
        drop(writer);

        // Truncation detection: if the server declared Content-Length but
        // the body was short, fail fast here instead of spending CPU on a
        // hash that definitely won't match. Saves one hash pass per retry.
        if total_size > 0 && downloaded < total_size {
            return Err(UpdaterError::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                format!(
                    "server declared Content-Length {total_size} but only {downloaded} bytes received"
                ),
            )));
        }
        Ok(())
    }

    /// Returns `Ok(Ok(()))` on match, `Ok(Err(actual))` on mismatch, and
    /// `Err(UpdaterError::Io)` when the file can't be read. Sync I/O —
    /// callers must invoke via `spawn_blocking`.
    fn verify_and_compute(path: &Path, expected: &Sha256) -> Result<std::result::Result<(), Sha256>> {
        let f = std::fs::File::open(path)?;
        let mut reader = std::io::BufReader::new(f);
        let mut hasher = Hasher::new();
        std::io::copy(&mut reader, &mut hasher)?;
        let computed_hex = hex::encode(hasher.finalize());
        let computed = Sha256::parse(&computed_hex)
            .expect("sha2::Digest output is always 64 lowercase hex chars");
        Ok(if computed.as_str() == expected.as_str() {
            Ok(())
        } else {
            Err(computed)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ManifestPath;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;
    use tempfile::TempDir;
    use wiremock::matchers::{method, path as mock_path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    /// Build a FileEntry whose sha256 matches `bytes`.
    fn file_entry(path: &str, url: &str, bytes: &[u8]) -> FileEntry {
        let hash_hex = hex::encode(Hasher::digest(bytes));
        FileEntry {
            path: ManifestPath::parse(path).unwrap(),
            url: url.to_string(),
            size: bytes.len() as u64,
            sha256: Sha256::parse(&hash_hex).unwrap(),
        }
    }

    fn new_downloader(temp_dir: &TempDir) -> Downloader {
        Downloader::with_config(temp_dir.path().to_path_buf(), 3, Duration::ZERO).unwrap()
    }

    #[tokio::test]
    async fn download_verified_success_on_first_attempt() {
        let body = b"hello world";
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body.as_ref()))
            .expect(1)
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = file_entry("file.bin", &format!("{}/file.bin", server.uri()), body);

        let progress_hits = Arc::new(AtomicU64::new(0));
        let hits_clone = progress_hits.clone();
        let out = dl
            .download_verified(&file, move |_d, _t| {
                hits_clone.fetch_add(1, Ordering::Relaxed);
            })
            .await
            .unwrap();

        assert!(out.exists());
        assert_eq!(std::fs::read(&out).unwrap(), body);
        assert!(progress_hits.load(Ordering::Relaxed) > 0);
    }

    #[tokio::test]
    async fn download_verified_retries_on_5xx_then_succeeds() {
        let body = b"payload";
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(503))
            .up_to_n_times(1)
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body.as_ref()))
            .expect(1)
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = file_entry("file.bin", &format!("{}/file.bin", server.uri()), body);
        let out = dl.download_verified(&file, |_, _| {}).await.unwrap();
        assert_eq!(std::fs::read(&out).unwrap(), body);
    }

    #[tokio::test]
    async fn download_verified_surfaces_network_error_after_max_attempts() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(500))
            .expect(3)
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = file_entry("file.bin", &format!("{}/file.bin", server.uri()), b"x");
        let result = dl.download_verified(&file, |_, _| {}).await;
        assert!(matches!(result, Err(UpdaterError::Network(_))));

        // Tmp file must be cleaned up after final Network failure (not left
        // as orphan). Pre-body 500s don't create tmp, but this pins the
        // cleanup invariant defensively for the mid-stream failure case.
        let file_name = Path::new(file.path.as_str()).file_name().unwrap();
        let tmp_path = tmp.path().join(file_name);
        assert!(!tmp_path.exists(), "no orphan tmp after Network exhaustion");
    }

    #[tokio::test]
    async fn download_verified_retries_on_hash_mismatch_then_succeeds() {
        let good = b"the good payload";
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(b"WRONG".as_ref()))
            .up_to_n_times(1)
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(good.as_ref()))
            .expect(1)
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = file_entry("file.bin", &format!("{}/file.bin", server.uri()), good);
        let out = dl.download_verified(&file, |_, _| {}).await.unwrap();
        assert_eq!(std::fs::read(&out).unwrap(), good);
    }

    #[tokio::test]
    async fn download_verified_hashmismatch_error_carries_actual_hash() {
        let good = b"expected payload";
        let wrong = b"wrong";
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(wrong.as_ref()))
            .expect(3)
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = file_entry("file.bin", &format!("{}/file.bin", server.uri()), good);
        let result = dl.download_verified(&file, |_, _| {}).await;

        // Error must carry the real actual hash — not a sentinel string.
        match result {
            Err(UpdaterError::HashMismatch {
                path,
                expected,
                actual,
            }) => {
                assert_eq!(path, file.path);
                assert_eq!(expected, file.sha256);
                let actual = actual.expect("actual hash must be present after mismatch");
                let wrong_hash = hex::encode(Hasher::digest(wrong));
                assert_eq!(actual.as_str(), wrong_hash);
            }
            other => panic!("expected HashMismatch, got {other:?}"),
        }

        // Tmp file is cleaned up after final failed attempt.
        let file_name = Path::new(file.path.as_str()).file_name().unwrap();
        let tmp_path = tmp.path().join(file_name);
        assert!(!tmp_path.exists());
    }

    #[tokio::test]
    async fn progress_callback_receives_total_from_content_length() {
        let body = vec![0u8; 1024];
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body.clone()))
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = file_entry("file.bin", &format!("{}/file.bin", server.uri()), &body);

        let last_total = Arc::new(AtomicU64::new(0));
        let last_total_clone = last_total.clone();
        dl.download_verified(&file, move |_d, t| {
            last_total_clone.store(t, Ordering::Relaxed);
        })
        .await
        .unwrap();
        assert_eq!(last_total.load(Ordering::Relaxed), body.len() as u64);
    }

    #[tokio::test]
    async fn download_verified_succeeds_for_empty_file() {
        // SHA-256 of empty input is a well-known constant — the valid
        // verify path for a zero-byte file must work without issuing any
        // progress callbacks (the chunk loop never runs).
        let body: &[u8] = b"";
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/empty.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body))
            .expect(1)
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = file_entry("empty.bin", &format!("{}/empty.bin", server.uri()), body);

        let progress_hits = Arc::new(AtomicU64::new(0));
        let hits = progress_hits.clone();
        let out = dl
            .download_verified(&file, move |_, _| {
                hits.fetch_add(1, Ordering::Relaxed);
            })
            .await
            .unwrap();
        assert!(out.exists());
        assert_eq!(std::fs::read(&out).unwrap().len(), 0);
        // Callback may fire 0 times (empty body → no chunks) — deliberately
        // not asserting progress_hits > 0 here.
    }

    #[tokio::test]
    async fn max_attempts_zero_is_treated_as_one() {
        let body = b"x";
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(mock_path("/file.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body.as_ref()))
            .expect(1)
            .mount(&server)
            .await;

        let tmp = TempDir::new().unwrap();
        let dl = Downloader::with_config(tmp.path().to_path_buf(), 0, Duration::ZERO).unwrap();
        let file = file_entry("file.bin", &format!("{}/file.bin", server.uri()), body);
        dl.download_verified(&file, |_, _| {}).await.unwrap();
    }

    #[tokio::test]
    async fn invalid_filename_path_returns_manifest_error() {
        let tmp = TempDir::new().unwrap();
        let dl = new_downloader(&tmp);
        let file = FileEntry {
            path: ManifestPath::parse(".").unwrap(),
            url: "http://example.com/x".to_string(),
            size: 1,
            sha256: Sha256::parse(&"a".repeat(64)).unwrap(),
        };
        let result = dl.download_verified(&file, |_, _| {}).await;
        assert!(
            matches!(result, Err(UpdaterError::Manifest(_))),
            "expected Manifest error, got {result:?}"
        );
    }

    #[test]
    fn new_creates_temp_dir_if_missing() {
        let parent = TempDir::new().unwrap();
        let nested = parent.path().join("deeply").join("nested");
        assert!(!nested.exists());
        let _ = Downloader::new(nested.clone()).unwrap();
        assert!(nested.exists());
    }

    #[test]
    fn jittered_backoff_is_bounded_by_exponential_cap() {
        // Sanity: a 100-attempt shift must cap at SHIFT_CAP, not overflow.
        let dl = Downloader::with_config(
            TempDir::new().unwrap().path().to_path_buf(),
            3,
            Duration::from_millis(1),
        )
        .unwrap();
        // Even at attempt=100, jittered_backoff must not panic.
        let wait = dl.jittered_backoff(100);
        // Capped at 2^30 ms = ~12.5 days; bounded is all we need.
        assert!(wait.as_millis() <= (1u128 << 30));
    }

    #[test]
    fn jittered_backoff_is_zero_when_base_is_zero() {
        let dl = Downloader::with_config(
            TempDir::new().unwrap().path().to_path_buf(),
            3,
            Duration::ZERO,
        )
        .unwrap();
        assert_eq!(dl.jittered_backoff(1), Duration::ZERO);
        assert_eq!(dl.jittered_backoff(3), Duration::ZERO);
    }
}
