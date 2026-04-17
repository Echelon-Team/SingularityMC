//! State machine glue — chains the earlier modules (github_api →
//! downloader → updater → launcher) and drives the shared UI state.
//!
//! Call [`run_update_flow`] from a tokio task with a [`UiState`] mutex
//! shared with the egui app. On success it returns the relative launcher
//! path ([`ManifestPath`]) the caller should hand off to
//! [`launcher::spawn_launcher`]. On failure the UI state is already set
//! to `FatalError`/`NoInternet`/`OfflineAvailable` as appropriate, and
//! the error is also returned so the caller can log it.
//!
//! **Structure:** extracted from `main.rs` so the flow is testable
//! without booting eframe. Pure helpers ([`current_os_target`],
//! [`fresh_install_version`], [`set_state`]) have unit tests; the async
//! pipeline is integration-level (real HTTP + filesystem) and left to
//! Phase 5 manual smoke tests.

use crate::downloader::Downloader;
use crate::github_api::{GitHubClient, Release};
use crate::manifest::{self, FileEntry};
use crate::ui::states::UiState;
use crate::updater::Updater;
use crate::{
    Channel, ManifestPath, OsTarget, Result, UpdaterError, Version,
};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// GitHub repo coordinates — fixed at compile time per spec 4.x.
pub const REPO_OWNER: &str = "Echelon-Team";
pub const REPO_NAME: &str = "SingularityMC";

/// Minimum time to show the initial "Checking..." screen — prevents
/// flicker on fast connections where GitHub returns in <50ms.
const CHECKING_MIN_MS: u64 = 500;

/// NoInternet retry cadence + cap. After exhausting retries, if a local
/// manifest exists the user is offered offline mode; otherwise the flow
/// transitions to FatalError.
const RETRY_INTERVAL_SECS: u32 = 30;
const MAX_API_RETRIES: u32 = 3;

/// Detect the current platform's OS target for manifest selection.
/// Currently supports Windows + Linux per spec; any other target
/// defaults to Linux (least-surprise for BSD/macOS dev boxes).
#[must_use]
pub fn current_os_target() -> OsTarget {
    if cfg!(windows) {
        OsTarget::Windows
    } else {
        OsTarget::Linux
    }
}

/// Version placeholder for fresh installs (no prior `local-manifest.json`).
/// Used purely for backup dir naming — `"0.0.0"` makes log lines like
/// `updating 0.0.0 -> 0.1.0` instantly readable as a fresh install.
#[must_use]
pub fn fresh_install_version() -> Version {
    Version::parse("0.0.0").expect("0.0.0 is a valid non-empty version")
}

/// Top-level update flow: check → download → verify → install. Returns
/// the launcher executable's relative path on success so the caller can
/// spawn it and exit.
pub async fn run_update_flow(
    install_dir: PathBuf,
    channel: Channel,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
) -> Result<ManifestPath> {
    // Guarantee the "Checking..." screen is visible for long enough to
    // register — prevents flash-then-gone on fast networks.
    tokio::time::sleep(Duration::from_millis(CHECKING_MIN_MS)).await;

    let github = GitHubClient::new(REPO_OWNER, REPO_NAME)?;
    match github.latest_release(channel).await {
        Ok(release) => process_release(install_dir, github, release, os, state).await,
        Err(e) => {
            handle_api_failure(install_dir, github, channel, os, state, e).await
        }
    }
}

async fn handle_api_failure(
    install_dir: PathBuf,
    github: GitHubClient,
    channel: Channel,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
    original_err: UpdaterError,
) -> Result<ManifestPath> {
    log::warn!("GitHub API failed: {original_err}; entering retry loop");

    for attempt in 0..MAX_API_RETRIES {
        set_state(
            &state,
            UiState::NoInternet {
                retry_in_seconds: RETRY_INTERVAL_SECS,
            },
        );
        tokio::time::sleep(Duration::from_secs(u64::from(RETRY_INTERVAL_SECS))).await;
        match github.latest_release(channel).await {
            Ok(release) => {
                return process_release(install_dir, github, release, os, state).await;
            }
            Err(e) => log::warn!("retry {} of {MAX_API_RETRIES} failed: {e}", attempt + 1),
        }
    }

    // Exhausted retries. If a local manifest exists, surface offline mode
    // and let the UI callback drive process exit.
    if manifest::load_local(&install_dir).is_some() {
        set_state(&state, UiState::OfflineAvailable);
        // Park the worker. UI button callback handles offline-mode spawn +
        // process_exit. 24h is effectively forever for this process.
        tokio::time::sleep(Duration::from_secs(60 * 60 * 24)).await;
        return Err(UpdaterError::NotFound(
            "user did not pick offline mode within 24h".to_string(),
        ));
    }

    // First run + no internet = fatal. Surface the message; caller will
    // also log::error the error.
    set_state(
        &state,
        UiState::FatalError {
            message: format!("{original_err}"),
        },
    );
    Err(original_err)
}

async fn process_release(
    install_dir: PathBuf,
    github: GitHubClient,
    release: Release,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
) -> Result<ManifestPath> {
    let remote = github.fetch_manifest(&release, os).await?;

    let local = manifest::load_local(&install_dir);
    let old_version = local
        .as_ref()
        .map_or_else(fresh_install_version, |m| m.version.clone());
    let diff = manifest::diff_manifests(local.as_ref(), &remote);

    if diff.is_empty() {
        log::info!("already up-to-date at {}", remote.version);
        return Ok(remote.launcher_executable);
    }

    log::info!(
        "updating {old_version} -> {}: {} file(s) to download",
        remote.version,
        diff.len()
    );

    // --- Download phase ---
    let temp_dir = install_dir.join(".tmp-update");
    let downloader = Downloader::new(temp_dir.clone())?;
    let total_bytes: u64 = diff.iter().map(|f| f.size).sum();
    let downloaded_so_far = Arc::new(Mutex::new(0_u64));
    let mut downloaded_files: Vec<(FileEntry, PathBuf)> = Vec::with_capacity(diff.len());

    for file in &diff {
        let ds = Arc::clone(&downloaded_so_far);
        let s = Arc::clone(&state);
        let total_bytes_c = total_bytes;
        let progress = move |curr_file_bytes: u64, _file_total: u64| {
            let base = *ds.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let total_done = base + curr_file_bytes;
            let pct = progress_percent(total_done, total_bytes_c);
            let mut guard = s.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            *guard = UiState::Downloading {
                percent: pct,
                downloaded_bytes: total_done,
                total_bytes: total_bytes_c,
            };
        };
        let path = downloader.download_verified(file, progress).await?;
        *downloaded_so_far
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) += file.size;
        downloaded_files.push((file.clone(), path));
    }

    // --- Verify phase (brief UI state — actual hash check happens per
    // file during download) ---
    set_state(&state, UiState::Verifying);
    tokio::time::sleep(Duration::from_millis(300)).await;

    // --- Install phase ---
    set_state(&state, UiState::Installing);
    let updater = Updater::new(&install_dir);
    updater.swap_files(&downloaded_files, &old_version)?;
    updater.write_local_manifest(&remote)?;
    updater.write_version_file(&remote.version)?;
    let _ = updater.cleanup_old_backups(crate::updater::DEFAULT_KEEP_BACKUPS);

    // Cleanup temp dir best-effort (next run will truncate anyway).
    let _ = std::fs::remove_dir_all(&temp_dir);

    Ok(remote.launcher_executable)
}

/// Clamped percent computation for progress updates. Extracted for tests;
/// caller doesn't need to remember the division-by-zero guard.
#[must_use]
pub fn progress_percent(done_bytes: u64, total_bytes: u64) -> u8 {
    if total_bytes == 0 {
        return 100;
    }
    #[allow(clippy::cast_precision_loss, clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let pct = ((done_bytes as f64 / total_bytes as f64) * 100.0) as u32;
    pct.min(100) as u8
}

/// Write a new UI state, recovering from a poisoned mutex so a single
/// writer panic doesn't deadlock the state machine.
pub fn set_state(state: &Arc<Mutex<UiState>>, new: UiState) {
    let mut guard = state
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *guard = new;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_os_target_matches_cfg() {
        let os = current_os_target();
        if cfg!(windows) {
            assert_eq!(os, OsTarget::Windows);
        } else {
            assert_eq!(os, OsTarget::Linux);
        }
    }

    #[test]
    fn fresh_install_version_is_placeholder_zero() {
        let v = fresh_install_version();
        assert_eq!(v.as_str(), "0.0.0");
    }

    // --- progress_percent ---

    #[test]
    fn progress_percent_zero_total_returns_100() {
        // Edge case: empty download batch (all files cache-verified as
        // already present). Percent is meaningless; returning 100 reads
        // as "done" rather than crashing on divide-by-zero.
        assert_eq!(progress_percent(0, 0), 100);
    }

    #[test]
    fn progress_percent_half_done() {
        assert_eq!(progress_percent(500, 1000), 50);
    }

    #[test]
    fn progress_percent_clamps_at_100() {
        // Server lied about Content-Length — downloaded > total. UI
        // should show 100%, not 150%.
        assert_eq!(progress_percent(1500, 1000), 100);
    }

    #[test]
    fn progress_percent_large_u64_values() {
        // 10 GB scale — f64 cast precision is lossy but the result
        // still rounds to a sane percent.
        const TEN_GB: u64 = 10 * 1024 * 1024 * 1024;
        assert_eq!(progress_percent(TEN_GB / 2, TEN_GB), 50);
    }

    // --- set_state ---

    #[test]
    fn set_state_updates_shared_mutex() {
        let state = Arc::new(Mutex::new(UiState::Checking));
        set_state(&state, UiState::Verifying);
        let guard = state.lock().unwrap();
        assert!(matches!(*guard, UiState::Verifying));
    }

    #[test]
    fn set_state_recovers_from_poisoned_mutex() {
        // Parallel to ui::tests::poisoned_state_mutex_still_yields_state_for_ui
        // — same recovery pattern at the writer side.
        let state = Arc::new(Mutex::new(UiState::Checking));
        let p = Arc::clone(&state);
        let _ = std::thread::spawn(move || {
            let _g = p.lock().unwrap();
            std::panic::panic_any("intentional poison");
        })
        .join();
        // Poisoned — set_state must still succeed.
        set_state(&state, UiState::Installing);
        let guard = state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        assert!(matches!(*guard, UiState::Installing));
    }
}
