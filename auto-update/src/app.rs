//! State machine glue — chains the earlier modules (github_api →
//! downloader → updater → launcher) and drives the shared UI state.
//!
//! Call [`run_update_flow`] from a tokio task with a [`UiState`] mutex
//! shared with the egui app, plus an mpsc `Receiver<UserAction>` the UI
//! callbacks push into when the user clicks "Offline mode" or "Retry".
//! On success the fn returns [`FlowOutcome`] the caller hands off to
//! [`launcher::spawn_launcher`] (with `offline=false` for `Updated`,
//! `offline=true` for `UserRequestedOffline`). On failure the UI state
//! is already set to `FatalError`, and the error is also returned so
//! the caller can log it.
//!
//! **Why mpsc, not Notify:** the spec lists two distinct user actions
//! (offline / retry) — a single `Notify` cannot distinguish them, and
//! the bounded channel drops duplicate clicks instead of stacking them.
//!
//! **Structure:** extracted from `main.rs` so the flow is testable
//! without booting eframe. Pure helpers ([`current_os_target`],
//! [`fresh_install_version`], [`set_state`]) have unit tests; the async
//! pipeline is integration-level (real HTTP + filesystem) and covered
//! by `tests/app_flow.rs` with wiremock.

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
use tokio::sync::mpsc;

/// User-initiated action surfaced by the UI when the state machine is
/// parked waiting for a decision (currently in `OfflineAvailable` or
/// `DownloadFailed`). Variants are exhaustive by design — adding one
/// forces every match site to handle it.
#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum UserAction {
    /// Click on the "Offline mode" button — caller should spawn the
    /// launcher with `--offline` from the last known-good install.
    Offline,
    /// Click on the "Retry" button — caller should restart the update
    /// flow from the top (re-check GitHub).
    Retry,
}

/// Terminal outcome of a single [`run_update_flow`] call. On `Updated`
/// the caller spawns the launcher normally; on `UserRequestedOffline`
/// the caller spawns with `offline=true`. `Retry` is handled internally
/// (the flow loops back to the top), so it never surfaces here.
#[derive(Debug)]
pub enum FlowOutcome {
    /// Normal update path succeeded — launcher path is the freshly
    /// installed manifest's `launcher_executable`.
    Updated(ManifestPath),
    /// User explicitly chose "Offline mode" because the API was
    /// unreachable. Path is read from the on-disk `local-manifest.json`.
    UserRequestedOffline(ManifestPath),
}

/// GitHub repo coordinates — fixed at compile time per spec 4.x.
pub const REPO_OWNER: &str = "Echelon-Team";
pub const REPO_NAME: &str = "SingularityMC";

/// Default GitHub Releases API base URL used by production callers of
/// [`run_update_flow`]. Tests (`tests/app_flow.rs`) route the flow
/// through [`run_update_flow_with_base_url`] pointing at a `wiremock`
/// instance — the parameter is a `&str` rather than a compile-time
/// feature flag so the test crate doesn't need a cfg-gated build.
pub const GITHUB_API_BASE_URL_DEFAULT: &str = "https://api.github.com";

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

/// Internal outcome of a single `run_update_flow` attempt before the
/// retry-loop collapses it to a [`FlowOutcome`]. `UserRetry` is the only
/// signal that loops back to the top — everything else terminates.
enum AttemptOutcome {
    Succeeded(FlowOutcome),
    UserRetry,
}

/// Top-level update flow: check → download → verify → install. Loops on
/// explicit user `Retry` action (from the `OfflineAvailable` screen); all
/// other paths terminate with a [`FlowOutcome`] or an error.
///
/// `user_rx` is consumed by the retry parking point in [`handle_api_failure`].
/// The caller (main.rs) owns the matching `Sender` and wires it into the
/// UI callbacks so button clicks become channel pushes.
pub async fn run_update_flow(
    install_dir: PathBuf,
    channel: Channel,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
    user_rx: &mut mpsc::Receiver<UserAction>,
) -> Result<FlowOutcome> {
    run_update_flow_with_config(
        install_dir,
        channel,
        os,
        state,
        user_rx,
        RunUpdateFlowConfig::default(),
    )
    .await
}

/// Knobs the production flow hardcodes, broken out as a struct so tests
/// (`tests/app_flow.rs`) can dial down `retry_interval_secs` and
/// `checking_min_ms` to keep paused-time scenarios deterministic. Callers
/// should use `RunUpdateFlowConfig::default()` in production and mutate
/// only the retry timing in tests — not the URL, which is the whole
/// point of the wiremock seam.
#[derive(Debug, Clone)]
pub struct RunUpdateFlowConfig {
    /// GitHub REST API base URL — production pins [`GITHUB_API_BASE_URL_DEFAULT`],
    /// tests point at a `wiremock` instance.
    pub github_base_url: String,
    /// Number of seconds to wait between API retries in the NoInternet loop.
    /// Production: 30. Tests: 0 to advance through retries in a handful of
    /// `tokio::time::advance` calls without wiremock I/O stalling.
    pub retry_interval_secs: u32,
    /// How many retries to attempt before giving up and surfacing
    /// OfflineAvailable / FatalError.
    pub max_api_retries: u32,
    /// Floor on the "Checking..." screen duration so it doesn't flash-and-
    /// disappear on fast networks. Tests drop this to 0.
    pub checking_min_ms: u64,
}

impl Default for RunUpdateFlowConfig {
    fn default() -> Self {
        Self {
            github_base_url: GITHUB_API_BASE_URL_DEFAULT.to_string(),
            retry_interval_secs: RETRY_INTERVAL_SECS,
            max_api_retries: MAX_API_RETRIES,
            checking_min_ms: CHECKING_MIN_MS,
        }
    }
}

/// Full-config entry point. Prefer [`run_update_flow`] in production —
/// this one exists so integration tests can swap the retry-timing knobs
/// without plumbing every constant through layer by layer.
pub async fn run_update_flow_with_config(
    install_dir: PathBuf,
    channel: Channel,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
    user_rx: &mut mpsc::Receiver<UserAction>,
    cfg: RunUpdateFlowConfig,
) -> Result<FlowOutcome> {
    loop {
        // Reset to Checking on every iteration — a retry from the offline
        // screen needs to visually re-enter the flow from the top.
        set_state(&state, UiState::Checking);

        // Guarantee the "Checking..." screen is visible for long enough
        // to register — prevents flash-then-gone on fast networks.
        tokio::time::sleep(Duration::from_millis(cfg.checking_min_ms)).await;

        let github =
            GitHubClient::with_base_url(&cfg.github_base_url, REPO_OWNER, REPO_NAME)?;
        let attempt = match github.latest_release(channel).await {
            Ok(release) => {
                let launcher_rel =
                    process_release(install_dir.clone(), github, release, os, Arc::clone(&state))
                        .await?;
                AttemptOutcome::Succeeded(FlowOutcome::Updated(launcher_rel))
            }
            Err(e) => {
                handle_api_failure(
                    install_dir.clone(),
                    github,
                    channel,
                    os,
                    Arc::clone(&state),
                    user_rx,
                    &cfg,
                    e,
                )
                .await?
            }
        };

        match attempt {
            AttemptOutcome::Succeeded(outcome) => return Ok(outcome),
            AttemptOutcome::UserRetry => continue,
        }
    }
}

async fn handle_api_failure(
    install_dir: PathBuf,
    github: GitHubClient,
    channel: Channel,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
    user_rx: &mut mpsc::Receiver<UserAction>,
    cfg: &RunUpdateFlowConfig,
    original_err: UpdaterError,
) -> Result<AttemptOutcome> {
    log::warn!("GitHub API failed: {original_err}; entering retry loop");

    for attempt in 0..cfg.max_api_retries {
        set_state(
            &state,
            UiState::NoInternet {
                retry_in_seconds: cfg.retry_interval_secs,
            },
        );
        tokio::time::sleep(Duration::from_secs(u64::from(cfg.retry_interval_secs))).await;
        match github.latest_release(channel).await {
            Ok(release) => {
                let launcher_rel =
                    process_release(install_dir, github, release, os, state).await?;
                return Ok(AttemptOutcome::Succeeded(FlowOutcome::Updated(launcher_rel)));
            }
            Err(e) => log::warn!(
                "retry {} of {} failed: {e}",
                attempt + 1,
                cfg.max_api_retries
            ),
        }
    }

    // Exhausted retries. If a local manifest exists, surface offline mode
    // and park waiting for a UI button press (Offline / Retry). Channel
    // close => UI gone => treat as fatal channel error.
    if let Some(local) = manifest::load_local(&install_dir) {
        set_state(&state, UiState::OfflineAvailable);
        match user_rx.recv().await {
            Some(UserAction::Offline) => Ok(AttemptOutcome::Succeeded(
                FlowOutcome::UserRequestedOffline(local.launcher_executable),
            )),
            Some(UserAction::Retry) => Ok(AttemptOutcome::UserRetry),
            None => Err(UpdaterError::NotFound(
                "UI action channel closed before user decision".to_string(),
            )),
        }
    } else {
        // First run + no internet = fatal. Surface the message; caller
        // will also log::error the error.
        set_state(
            &state,
            UiState::FatalError {
                message: format!("{original_err}"),
            },
        );
        Err(original_err)
    }
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

    // --- UserAction / FlowOutcome enum pins (T2.11f1 review follow-up) ---

    #[test]
    fn user_action_variants_are_distinct() {
        // Regression guard: a future `#[derive(Clone)]` that drops
        // `PartialEq` or a merge of variants would silently let the
        // callbacks' `try_send` interchange Offline and Retry. Cheap pin.
        assert_ne!(UserAction::Offline, UserAction::Retry);
        // Copy + Debug: used across mpsc channel + log::warn sites.
        let _copy: UserAction = UserAction::Offline;
        let _ = format!("{:?}", UserAction::Retry);
    }

    #[test]
    fn flow_outcome_variants_carry_manifest_path() {
        // Pin that the caller-observable success axes both expose the
        // launcher path — main.rs matches on `path.as_str()` to build
        // the subprocess command line.
        let p = crate::ManifestPath::parse("launcher/app.jar").unwrap();
        match FlowOutcome::Updated(p.clone()) {
            FlowOutcome::Updated(x) => assert_eq!(x.as_str(), "launcher/app.jar"),
            FlowOutcome::UserRequestedOffline(_) => unreachable!(),
        }
        match FlowOutcome::UserRequestedOffline(p.clone()) {
            FlowOutcome::UserRequestedOffline(x) => {
                assert_eq!(x.as_str(), "launcher/app.jar");
            }
            FlowOutcome::Updated(_) => unreachable!(),
        }
    }

    // --- RunUpdateFlowConfig defaults ---

    #[test]
    fn run_update_flow_config_default_matches_spec_constants() {
        // `RunUpdateFlowConfig::default()` is what production
        // `run_update_flow` passes through — pins that any future edit
        // to the `Default` impl surfaces as a test diff, preventing
        // silent drift of the spec 4.x retry cadence (30 s × 3).
        let cfg = RunUpdateFlowConfig::default();
        assert_eq!(cfg.github_base_url, GITHUB_API_BASE_URL_DEFAULT);
        assert_eq!(cfg.retry_interval_secs, RETRY_INTERVAL_SECS);
        assert_eq!(cfg.max_api_retries, MAX_API_RETRIES);
        assert_eq!(cfg.checking_min_ms, CHECKING_MIN_MS);
    }
}
