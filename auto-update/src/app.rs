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

use crate::downloader::{DownloadTarget, Downloader};
use crate::github_api::{GitHubClient, Release};
use crate::manifest::Manifest;
use crate::ui::states::UiState;
use crate::updater;
use crate::{
    Channel, ManifestPath, OsTarget, Result, UpdaterError, Version,
};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::mpsc;

/// User-initiated action surfaced by the UI when the state machine is
/// parked waiting for a decision — emitted from the `OfflineAvailable`
/// or `DownloadFailed` screens.
///
/// Only one variant exists today: `Offline` (user consciously chose to
/// launch the locally-installed version instead of waiting for the
/// update). Retry is no longer a UI affordance — the state machine
/// auto-retries on its own cadence (see the cooldown ladder), so the
/// user never clicks "Retry" anywhere. Other exit paths (closing the
/// window, clicking Help / Exit) don't go through this channel.
///
/// `#[non_exhaustive]` because this is part of the crate's public
/// surface (UI ↔ state machine contract) — adding a future variant
/// (e.g. `QuitNow`, `SkipUpdate`) should be non-breaking. Internal
/// match sites in this crate still need to be exhaustive.
#[derive(Debug, Clone, Copy, Eq, PartialEq)]
#[non_exhaustive]
pub enum UserAction {
    /// Click on the "Offline mode" button — caller should spawn the
    /// launcher with `--offline` from the last known-good install.
    Offline,
}

/// Terminal outcome of a single [`run_update_flow`] call. On `Updated`
/// the caller spawns the launcher normally; on `UserRequestedOffline`
/// the caller spawns with `offline=true`. `Retry` is handled internally
/// (the flow loops back to the top), so it never surfaces here.
///
/// `#[non_exhaustive]` for the same reason as [`UserAction`] — this is
/// a public return-type axis, and future additions (`Cancelled`,
/// `AlreadyUpToDate`, `SkippedByUser`) should be non-breaking.
#[derive(Debug)]
#[non_exhaustive]
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
/// through [`run_update_flow_with_config`] with a `RunUpdateFlowConfig`
/// pointing at a `wiremock` instance — the knob is a runtime field
/// rather than a compile-time feature flag so the test crate doesn't
/// need a cfg-gated build.
pub const GITHUB_API_BASE_URL_DEFAULT: &str = "https://api.github.com";

/// Minimum time to show the initial "Checking..." screen — prevents
/// flicker on fast connections where GitHub returns in <50ms.
const CHECKING_MIN_MS: u64 = 500;

/// Base retry cadence between consecutive auto-retry ticks. Production
/// default; used as the "retry delays enabled" flag (0 = skip sleeps,
/// tests). Actual per-iteration wait comes from [`backoff_floor_secs`].
const RETRY_INTERVAL_SECS: u32 = 30;

/// Number of failed API attempts before the state machine transitions
/// from `NoInternet` → `OfflineAvailable` (if local install exists) or
/// `FatalError` (if not). Matches spec 4.x "after a few tries, if
/// there's a local install offer Tryb offline; else it's fatal".
/// Kept as a crate-private const so tests can observe the exact
/// threshold without the transition becoming a tuning knob.
const API_FAILURES_BEFORE_OFFLINE_PROMPT: u32 = 3;

/// Detect the current platform's OS target for manifest selection.
/// Unsupported targets are rejected at compile time by the `compile_error!`
/// gate at the top of this module, so the runtime `if/else` is total over
/// the gate's accepted set.
#[must_use]
pub fn current_os_target() -> OsTarget {
    if cfg!(target_os = "windows") {
        OsTarget::Windows
    } else {
        OsTarget::Linux
    }
}

/// Human-readable rendering of the current install's prior version for
/// log lines. `None` (fresh install) formats as `"(fresh install)"` —
/// no `"0.0.0"` sentinel leaking into prose or into the `Version`
/// semver namespace. Display-only; don't compare.
fn old_version_display(old_version: Option<&Version>) -> String {
    match old_version {
        Some(v) => v.to_string(),
        None => "(fresh install)".to_string(),
    }
}

/// Bundle of the values threaded through every layer of the update
/// flow (`run_update_flow_with_config` → `handle_api_failure` → ...).
/// Before T2.11f7-followup each of those fns carried 7-9 positional
/// args; the shared six moved here so adding a new context field
/// (e.g. a telemetry client, a cancellation token) touches one struct
/// instead of four call paths.
///
/// `user_rx` is `&'a mut` (single-consumer mpsc side — borrowed by
/// whichever retry loop is currently active), `cfg` is `&'a` (read-only
/// after construction in main.rs / tests). `install_dir` / `channel` /
/// `os` are cheap (`PathBuf` + two tiny enums) so they live by-value.
/// `state` is `Arc<Mutex>` shared with the eframe UI thread.
struct FlowContext<'a> {
    install_dir: PathBuf,
    channel: Channel,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
    user_rx: &'a mut mpsc::Receiver<UserAction>,
    cfg: &'a RunUpdateFlowConfig,
}

/// Top-level update flow: check → download → verify → install. Runs
/// once per call — auto-retries happen internally inside
/// [`handle_api_failure`] / [`park_on_download_failure`] via the
/// cooldown-ladder tokio::select! loops. Only terminal exits bubble
/// back here (Updated, UserRequestedOffline, FatalError + channel
/// close variants of Err).
///
/// `user_rx` is consumed by those parking points. The caller
/// (main.rs) owns the matching `Sender` and wires it into the UI
/// callbacks so button clicks become channel pushes.
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
/// `checking_min_ms` to keep scenarios deterministic without waiting on
/// real 30 s sleeps. Callers should use `RunUpdateFlowConfig::default()`
/// in production and mutate only the retry timing in tests — not the
/// URL, which is the whole point of the wiremock seam.
///
/// `#[non_exhaustive]` so adding a future knob (e.g. a `user_agent` or
/// `max_concurrent_downloads`) is a non-breaking change for anyone
/// constructing the struct via `RunUpdateFlowConfig { ..Default::default() }`.
#[derive(Debug, Clone)]
#[non_exhaustive]
pub struct RunUpdateFlowConfig {
    /// GitHub REST API base URL — production pins [`GITHUB_API_BASE_URL_DEFAULT`],
    /// tests point at a `wiremock` instance. Validation happens lazily
    /// via `GitHubClient::with_base_url` on the first flow iteration;
    /// an invalid URL surfaces as `UpdaterError::InvalidConfig` which
    /// bubbles into `FatalError`. Fields are `pub` for reader
    /// ergonomics, but `#[non_exhaustive]` at the struct level blocks
    /// struct-literal construction outside the crate — external callers
    /// must go through [`RunUpdateFlowConfig::new`] + the `with_*`
    /// builder methods.
    pub github_base_url: String,
    /// Acts as a delays-enabled flag for the auto-retry loops. Production
    /// is nonzero (the exact value is unused — actual per-iteration wait
    /// comes from the [`backoff_floor_secs`] ladder); tests set it to
    /// `0` to skip all retry sleeps so scenarios resolve in a handful of
    /// runtime ticks. Kept as a `u32` rather than a `bool` so future
    /// knobs (e.g. "minimum floor add") can repurpose it without a
    /// breaking rename.
    pub retry_interval_secs: u32,
    /// Floor on the "Checking..." screen duration so it doesn't flash-and-
    /// disappear on fast networks. Tests drop this to 0.
    pub checking_min_ms: u64,
    /// Localized string bundle used for user-facing `FatalError`
    /// messages the state machine constructs itself (e.g. the
    /// "no offline install" error when a download fails on a fresh
    /// install). Production main.rs resolves `Lang::Auto` / `Pl` / `En`
    /// and passes the matching bundle here; tests take the `En` default.
    pub strings: &'static crate::Strings,
}

impl Default for RunUpdateFlowConfig {
    fn default() -> Self {
        Self {
            github_base_url: GITHUB_API_BASE_URL_DEFAULT.to_string(),
            retry_interval_secs: RETRY_INTERVAL_SECS,
            checking_min_ms: CHECKING_MIN_MS,
            // Tests pick `En` — production overrides via `with_strings`
            // after `resolve_lang()` picks the concrete bundle.
            strings: crate::i18n::strings(crate::Lang::En),
        }
    }
}

impl RunUpdateFlowConfig {
    /// Constructor + setter-chain for tests and any future external
    /// consumer. `#[non_exhaustive]` makes struct-literal construction
    /// inaccessible outside this crate, so callers have to go through
    /// this API — which is the whole point: adding a field becomes a
    /// non-breaking change.
    #[must_use]
    pub fn new(github_base_url: impl Into<String>) -> Self {
        Self {
            github_base_url: github_base_url.into(),
            ..Self::default()
        }
    }

    /// Override the auto-retry delay flag. Production uses the default
    /// (nonzero → cooldown ladder active); **tests** call this with `0`
    /// to drop all retry sleeps so integration scenarios resolve in
    /// milliseconds. Passing `0` in production builds collapses every
    /// backoff wait, which turns the parked retry loops into a tight
    /// `select!` spin against GitHub and will quickly hit API rate
    /// limits — only ever set `0` from tests.
    #[must_use]
    pub fn with_retry_interval_secs(mut self, secs: u32) -> Self {
        self.retry_interval_secs = secs;
        self
    }

    /// Override the minimum `Checking...` screen duration. Tests use 0
    /// to skip the UX-jitter guard in production.
    #[must_use]
    pub fn with_checking_min_ms(mut self, ms: u64) -> Self {
        self.checking_min_ms = ms;
        self
    }

    /// Override the localized string bundle used for user-facing fatal
    /// error messages. Production calls this with the bundle chosen by
    /// `resolve_lang()`; tests that want PL error messages can override
    /// explicitly, otherwise they inherit the `En` default.
    #[must_use]
    pub fn with_strings(mut self, strings: &'static crate::Strings) -> Self {
        self.strings = strings;
        self
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
    // Checking-screen floor so "Sprawdzanie aktualizacji..." doesn't
    // flash-and-gone on fast networks.
    set_state(&state, UiState::Checking);
    tokio::time::sleep(Duration::from_millis(cfg.checking_min_ms)).await;

    let github =
        GitHubClient::with_base_url(&cfg.github_base_url, REPO_OWNER, REPO_NAME)?;

    // Bundle the plumbing so the retry helpers don't each take 8 args.
    let mut ctx = FlowContext {
        install_dir,
        channel,
        os,
        state,
        user_rx,
        cfg: &cfg,
    };

    match github.latest_release(channel).await {
        Ok(release) => process_release_or_park(&mut ctx, github, release).await,
        Err(e) => handle_api_failure(&mut ctx, github, e).await,
    }
}

/// Classify an `UpdaterError` as transient (auto-retry worthwhile) vs
/// permanent (retry won't help — e.g. corrupt manifest hash, file-
/// system permission). Permanent errors short-circuit out of the auto-
/// retry loops to `FatalError` instead of hammering a broken artifact
/// forever (which would also waste every client's bandwidth if the
/// broken artifact is a remote manifest).
///
/// `Json` is permanent because `Manifest::parse` wraps
/// `serde_json::from_str` via `#[from]` — every schema-level failure
/// (missing field, invalid enum, bad sha256, zip-slip path) surfaces
/// here, not as `UpdaterError::Manifest` (which is raised only by
/// `validate()` for duplicate paths). Without this arm, a corrupt
/// remote manifest JSON triggers infinite retry at every client
/// simultaneously.
///
/// `Io` is permanent ONLY for `PermissionDenied` / `ReadOnlyFilesystem`
/// — read-only install dir (Program Files without admin, NTFS read-only
/// attribute, EROFS) can't be fixed by waiting 8-30 s and retrying.
/// Other Io kinds stay transient: the downloader synthesises
/// `UnexpectedEof` for truncated Content-Length (next fetch usually
/// completes), and `Interrupted` shows up when an AV scanner briefly
/// locks a write. Exhaustive `match` (not `matches!`) so any future
/// `UpdaterError` variant must be classified to compile.
fn is_permanent_error(err: &UpdaterError) -> bool {
    match err {
        UpdaterError::Manifest(_)
        | UpdaterError::HashMismatch { .. }
        | UpdaterError::Permission(_)
        | UpdaterError::SwapFailed { .. }
        | UpdaterError::InvalidConfig(_)
        | UpdaterError::SelfUpdateSwapFailed(_)
        | UpdaterError::SelfUpdateRespawnFailed(_)
        | UpdaterError::Json(_) => true,
        UpdaterError::Io(e) => matches!(
            e.kind(),
            std::io::ErrorKind::PermissionDenied
                | std::io::ErrorKind::ReadOnlyFilesystem
        ),
        UpdaterError::Extract { source, .. } => matches!(
            source.kind(),
            std::io::ErrorKind::PermissionDenied
                | std::io::ErrorKind::ReadOnlyFilesystem
        ),
        UpdaterError::Network(_) | UpdaterError::NotFound(_) => false,
    }
}

async fn handle_api_failure(
    ctx: &mut FlowContext<'_>,
    github: GitHubClient,
    original_err: UpdaterError,
) -> Result<FlowOutcome> {
    log::warn!("GitHub API failed: {original_err}; entering auto-retry loop");

    // Classify up front — a permanent error skips the retry ceremony.
    if is_permanent_error(&original_err) {
        set_state(
            &ctx.state,
            UiState::FatalError {
                message: format!("{original_err}"),
            },
        );
        return Err(original_err);
    }

    let mut iteration: u32 = 0;
    loop {
        iteration = iteration.saturating_add(1);

        // Refresh `local` per-iteration so late filesystem writes (user
        // drops a manifest from backup while we're parked) are picked
        // up on the next tick rather than frozen to the entry snapshot.
        let local = Manifest::read_local(&ctx.install_dir).ok().flatten();

        // Transition rule — spec 4.x opcja 2:
        //  - first `API_FAILURES_BEFORE_OFFLINE_PROMPT` (3) retries stay
        //    in `NoInternet` regardless of has_offline — UI keeps the
        //    user in "something's wrong, trying again" framing without
        //    prematurely offering the offline escape hatch.
        //  - after 3 retries + local exists → `OfflineAvailable` (we
        //    keep auto-retrying in the BACKGROUND so the flow resumes
        //    automatically if the API comes back; user can also just
        //    click Tryb offline to launch the local install directly).
        //  - after 3 retries + NO local → `FatalError` (no recovery
        //    path — first install on a broken network). Per Mateusz:
        //    "opcja 2: nie ma local install → wywala fatal error".
        //
        // Semantics note (per Mateusz "A" decision): the user lives
        // through `[Checking][NoInternet × 3][OfflineAvailable | FatalError]`
        // — 1 initial API call + 3 retry ticks = 4 network attempts
        // total before the UI switches to the terminal screen. The
        // threshold is the retry count, not the total-attempt count.
        if iteration > API_FAILURES_BEFORE_OFFLINE_PROMPT && local.is_none() {
            set_state(
                &ctx.state,
                UiState::FatalError {
                    message: format!("{original_err}"),
                },
            );
            return Err(original_err);
        }

        // Compute sleep FIRST so the UI's `next_tick_at` matches the
        // real deadline of the `select!` timer (includes jitter). UI
        // renders `next_tick_at - now` per frame for a live countdown.
        let sleep_duration = if ctx.cfg.retry_interval_secs == 0 {
            Duration::ZERO
        } else {
            backoff_floor_duration_with_jitter(iteration)
        };
        let next_tick_at = Instant::now() + sleep_duration;

        let ui_state =
            if iteration <= API_FAILURES_BEFORE_OFFLINE_PROMPT || local.is_none() {
                UiState::NoInternet { next_tick_at }
            } else {
                UiState::OfflineAvailable { next_tick_at }
            };
        set_state(&ctx.state, ui_state);

        // Race the cooldown sleep against a user click. `biased;` polls
        // the user arm first so a click that lands in the same runtime
        // tick as the timer expiry always wins — a user who explicitly
        // clicked Tryb offline wants to launch now, not sit through
        // another 30 s wait because the RNG happened to pick the timer.
        //
        // Both arms are cancel-safe per tokio docs (`mpsc::Receiver::recv`
        // explicitly, `time::sleep` trivially — clock unregister).
        tokio::select! {
            biased;
            user = ctx.user_rx.recv() => {
                return dispatch_user_action_with_local(
                    user,
                    local.as_ref(),
                    ctx,
                );
            }
            _ = tokio::time::sleep(sleep_duration) => {
                match github.latest_release(ctx.channel).await {
                    Ok(release) => {
                        return process_release_or_park(ctx, github, release).await;
                    }
                    Err(e) => {
                        if is_permanent_error(&e) {
                            set_state(
                                &ctx.state,
                                UiState::FatalError {
                                    message: format!("{e}"),
                                },
                            );
                            return Err(e);
                        }
                        log::warn!(
                            "auto-retry iteration {iteration} failed: {e}; scheduling next tick"
                        );
                        // fall through — next loop iteration
                    }
                }
            }
        }
    }
}

/// Interpret a `UserAction` (or a `None` from channel close) landing in
/// one of the parked retry loops. Both `handle_api_failure` and
/// `park_on_download_failure` arrive at the same fork after their
/// `select!` — Offline click needs a local install, channel close is
/// a hard stop. Pulled out to keep the retry-loop bodies narrow.
fn dispatch_user_action_with_local(
    action: Option<UserAction>,
    local: Option<&crate::manifest::Manifest>,
    ctx: &FlowContext<'_>,
) -> Result<FlowOutcome> {
    match action {
        Some(UserAction::Offline) => {
            if let Some(local) = local {
                Ok(FlowOutcome::UserRequestedOffline(
                    local.launcher_executable.clone(),
                ))
            } else {
                // Offline click arriving despite the UI hiding the
                // button when `has_offline == false` — bug or a racy
                // click landing between state transitions. Surface as
                // FatalError with the localized message.
                let msg = ctx.cfg.strings.no_offline_install.to_string();
                set_state(
                    &ctx.state,
                    UiState::FatalError {
                        message: msg.clone(),
                    },
                );
                Err(UpdaterError::NotFound(msg))
            }
        }
        None => Err(UpdaterError::NotFound(
            "UI action channel closed before user decision".to_string(),
        )),
    }
}

/// Wraps [`process_release`] so any failure funnels into the
/// `DownloadFailed` screen + background auto-retry, rather than
/// bubbling directly to `FatalError`. Transient errors (Network, Io,
/// Json, generic NotFound) cycle on the cooldown ladder until the
/// next attempt succeeds or the user dismisses. Permanent errors
/// (bad hash, corrupt manifest, permission denied, swap failure)
/// short-circuit to `FatalError` — retry can't fix those.
async fn process_release_or_park(
    ctx: &mut FlowContext<'_>,
    github: GitHubClient,
    release: Release,
) -> Result<FlowOutcome> {
    match process_release(
        ctx.install_dir.clone(),
        github,
        release,
        ctx.os,
        Arc::clone(&ctx.state),
        ctx.cfg.strings,
    )
    .await
    {
        Ok(launcher_rel) => Ok(FlowOutcome::Updated(launcher_rel)),
        Err(e) => park_on_download_failure(ctx, e).await,
    }
}

async fn park_on_download_failure(
    ctx: &mut FlowContext<'_>,
    original_err: UpdaterError,
) -> Result<FlowOutcome> {
    log::warn!("release processing failed: {original_err}; entering download auto-retry");

    // Permanent errors (HashMismatch, Manifest parse, Permission,
    // SwapFailed, InvalidConfig, SelfUpdate* failures) are NOT fixable
    // by retrying the same artifact / filesystem state. Short-circuit
    // to FatalError so the user sees a stable error screen instead of
    // the app hammering a broken manifest forever. Transient errors
    // (Network, Io, generic NotFound) fall through to the loop.
    if is_permanent_error(&original_err) {
        set_state(
            &ctx.state,
            UiState::FatalError {
                message: format!("{original_err}"),
            },
        );
        return Err(original_err);
    }

    // Build the GitHubClient ONCE before the loop and clone it into
    // each tick: reqwest::Client is `Arc<Inner>` internally, so `clone`
    // preserves the TCP/TLS connection pool and DNS cache across
    // retries. Previously every tick rebuilt the client and paid a
    // full-handshake cost per 8-30 s — wasted work with no payoff.
    let github =
        GitHubClient::with_base_url(&ctx.cfg.github_base_url, REPO_OWNER, REPO_NAME)?;

    let mut iteration: u32 = 0;
    loop {
        iteration = iteration.saturating_add(1);

        // Refresh local snapshot per-iteration — a user copy-pasting a
        // manifest from backup while we're parked should flip
        // has_offline: false → true on the next UI paint.
        let local = Manifest::read_local(&ctx.install_dir).ok().flatten();
        let has_offline = local.is_some();

        // Compute sleep FIRST so the UI's `next_tick_at` matches the
        // real deadline of the `select!` timer (includes jitter).
        let sleep_duration = if ctx.cfg.retry_interval_secs == 0 {
            Duration::ZERO
        } else {
            backoff_floor_duration_with_jitter(iteration)
        };
        let next_tick_at = Instant::now() + sleep_duration;

        set_state(
            &ctx.state,
            UiState::DownloadFailed {
                next_tick_at,
                has_offline,
            },
        );

        tokio::select! {
            biased;
            user = ctx.user_rx.recv() => {
                return dispatch_user_action_with_local(
                    user,
                    local.as_ref(),
                    ctx,
                );
            }
            _ = tokio::time::sleep(sleep_duration) => {
                // Tick — re-fetch release (API might have a new version)
                // + re-run process_release from scratch. Transient CDN
                // hiccups, AV scanner locks, flaky disks can recover.
                // Permanent errors short-circuit to FatalError via the
                // is_permanent_error check below (same rule as the
                // entry-point check above).
                match github.latest_release(ctx.channel).await {
                    Ok(release) => {
                        match process_release(
                            ctx.install_dir.clone(),
                            github.clone(),
                            release,
                            ctx.os,
                            Arc::clone(&ctx.state),
                            ctx.cfg.strings,
                        )
                        .await
                        {
                            Ok(launcher_rel) => {
                                return Ok(FlowOutcome::Updated(launcher_rel));
                            }
                            Err(e) => {
                                if is_permanent_error(&e) {
                                    set_state(
                                        &ctx.state,
                                        UiState::FatalError {
                                            message: format!("{e}"),
                                        },
                                    );
                                    return Err(e);
                                }
                                log::warn!(
                                    "download auto-retry iteration {iteration} failed: {e}"
                                );
                                // next loop iteration
                            }
                        }
                    }
                    Err(e) => {
                        if is_permanent_error(&e) {
                            set_state(
                                &ctx.state,
                                UiState::FatalError {
                                    message: format!("{e}"),
                                },
                            );
                            return Err(e);
                        }
                        log::warn!(
                            "download auto-retry iteration {iteration} API fetch failed: {e}"
                        );
                        // next loop iteration
                    }
                }
            }
        }
    }
}

async fn process_release(
    install_dir: PathBuf,
    github: GitHubClient,
    release: Release,
    os: OsTarget,
    state: Arc<Mutex<UiState>>,
    strings: &'static crate::Strings,
) -> Result<ManifestPath> {
    let remote = github.fetch_manifest(&release, os).await?;

    // Compatibility gate: remote manifest może wymagać nowszego
    // auto-update niż this binary (spec §4.6 future-proof format
    // changes). Kiedy bumpniemy manifest schema w przyszłym release
    // — np. dodajemy signed manifests, nowy hash algo, nowy field z
    // semantyką której stary parser nie rozumie — generate-manifest
    // script będzie emitował `minAutoUpdateVersion` ≥ tej nowej
    // wersji. Stary auto-update zobaczy manifest, wykryje że BUILD_VERSION
    // jest poniżej min, natychmiast poda FatalError zamiast próbować
    // install który może zrobić damage (silent partial failure).
    //
    // Self-update-from-remote jako recovery ścieżka jest follow-up
    // task: wymaga SHA-pinned auto-update binary URL w manifest, stable
    // CDN convention, extra test coverage. Na teraz user pobiera świeży
    // installer ręcznie (localized message wskazuje Discord).
    let current_version = Version::parse(crate::BUILD_VERSION).map_err(|e| {
        UpdaterError::InvalidConfig(format!(
            "invalid BUILD_VERSION '{}' embedded at build time: {e}",
            crate::BUILD_VERSION
        ))
    })?;
    if current_version.is_older_than(&remote.min_auto_update_version) {
        log::error!(
            "auto-update {current_version} is too old for manifest (requires >= {}); \
             returning InvalidConfig with localized message — user needs a fresh installer",
            remote.min_auto_update_version
        );
        // Message == localized `auto_update_too_old` bo
        // `park_on_download_failure` przy permanent-error path robi
        // `set_state(FatalError { message: format!("{e}") })` — Display
        // output tej variant staje się user-facing message. Gdybyśmy
        // użyli technical English (`"auto-update 1.0.0 < 99.0.0"`),
        // user widziałby surowy debug string zamiast instrukcji gdzie
        // pobrać nowy installer. Technical diff jest w logu powyżej.
        let _ = &state; // kept dla signature consistency; state ustawi park helper
        return Err(UpdaterError::InvalidConfig(strings.auto_update_too_old.to_string()));
    }


    let local = Manifest::read_local(&install_dir).ok().flatten();
    let old_version = local.as_ref().map(|m| &m.version);
    let decision = updater::decide_update(&remote, local.as_ref());

    if !decision.any() {
        log::info!("already up-to-date at {}", remote.version);
        return Ok(remote.launcher_executable);
    }

    log::info!(
        "updating {} -> {} (launcher={} jre={} auto_update={})",
        old_version_display(old_version),
        remote.version,
        decision.launcher_needed,
        decision.jre_needed,
        decision.auto_update_needed,
    );

    // --- Download phase ---
    // tmp/ lives pod install_dir (tar extract używa parent install_dir,
    // więc wrapping dir w install_dir/ upraszcza cleanup na wykonanie).
    let temp_dir = install_dir.join(crate::updater::TMP_DIR);
    let _ = std::fs::remove_dir_all(&temp_dir);
    let _temp_guard = TempDirGuard::new(temp_dir.clone());
    let downloader = Downloader::new(temp_dir.clone())?;

    // Build download targets per decision. launcher.tar.gz + jre-<os>.tar.gz
    // + auto-update-<os>.exe (gdy potrzebne). file_name = stąd asset name
    // wyekstraktowany z URL, nie pełna ścieżka — wszystko idzie do tmp/.
    let mut targets: Vec<DownloadTarget> = Vec::with_capacity(3);
    if decision.launcher_needed {
        targets.push(DownloadTarget {
            path: ManifestPath::parse("launcher.tar.gz")?,
            url: remote.launcher.url.clone(),
            size: remote.launcher.size,
            sha256: remote.launcher.sha256.clone(),
        });
    }
    if decision.jre_needed {
        let jre_name = match remote.os {
            OsTarget::Windows => "jre-windows.tar.gz",
            OsTarget::Linux => "jre-linux.tar.gz",
        };
        targets.push(DownloadTarget {
            path: ManifestPath::parse(jre_name)?,
            url: remote.jre.url.clone(),
            size: remote.jre.size,
            sha256: remote.jre.sha256.clone(),
        });
    }
    if decision.auto_update_needed {
        let au_name = match remote.os {
            OsTarget::Windows => "auto-update-windows.exe",
            OsTarget::Linux => "auto-update-linux",
        };
        targets.push(DownloadTarget {
            path: ManifestPath::parse(au_name)?,
            url: remote.auto_update.url.clone(),
            size: remote.auto_update.size,
            sha256: remote.auto_update.sha256.clone(),
        });
    }

    let total_bytes: u64 = targets.iter().map(|t| t.size).sum();
    let downloaded_so_far = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let mut downloaded_paths: Vec<(String, PathBuf)> = Vec::with_capacity(targets.len());

    for target in &targets {
        let ds = Arc::clone(&downloaded_so_far);
        let s = Arc::clone(&state);
        let total_bytes_c = total_bytes;
        let progress = move |curr_file_bytes: u64, _file_total: u64| {
            let base = ds.load(std::sync::atomic::Ordering::Relaxed);
            let total_done = base + curr_file_bytes;
            let pct = progress_percent(total_done, total_bytes_c);
            let mut guard = s.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            *guard = UiState::Downloading {
                percent: pct,
                downloaded_bytes: total_done,
                total_bytes: total_bytes_c,
            };
        };
        let path = downloader.download_verified(target, progress).await?;
        downloaded_so_far.fetch_add(target.size, std::sync::atomic::Ordering::Relaxed);
        downloaded_paths.push((target.path.as_str().to_string(), path));
    }

    // --- Verify phase (sha256 już sprawdzone w download_verified,
    // ale brief UI transition dla UX) ---
    set_state(&state, UiState::Verifying);
    tokio::time::sleep(Duration::from_millis(300)).await;

    // --- Install phase ---
    set_state(&state, UiState::Installing);

    // Backup aktualnych instalacji (rename do .old/) przed extract, żeby
    // crash counter mógł rollback. Tylko dla paczek które faktycznie
    // pobieramy — jeśli jre nie zmieniło się, zostawiamy runtime/ w miejscu.
    if decision.launcher_needed {
        updater::backup_launcher(&install_dir)?;
    }
    if decision.jre_needed {
        updater::backup_runtime(&install_dir)?;
    }

    // Extract tar.gz do install_dir/launcher/ + install_dir/runtime/.
    // Dla auto-update (single exe, nie tar): copy z tmp/ do install_dir/.
    for (name, path) in &downloaded_paths {
        match name.as_str() {
            "launcher.tar.gz" => updater::extract_launcher_bundle(path, &install_dir)?,
            n if n.starts_with("jre-") => updater::extract_jre_bundle(path, &install_dir)?,
            n if n.starts_with("auto-update") => {
                // Stage new auto-update binary dla self-replace na next boot
                // (self_update.rs handles actual swap). Zapisujemy pod
                // install_dir/{filename}.new — `apply_pending` przy następnym
                // starcie auto-update.exe zamienia w miejscu.
                let target = install_dir.join(format!("{}.new", n));
                std::fs::copy(path, &target).map_err(UpdaterError::Io)?;
                log::info!("staged new auto-update binary at {}", target.display());
            }
            _ => log::warn!("unexpected download target {name}, skipping install"),
        }
    }

    // Zaktualizuj local-manifest.json — snapshot aktualnego stanu
    // instalacji (co user ma zainstalowane).
    remote.write_local(&install_dir)?;

    // `_temp_guard` drops here → usuwa tmp/.
    Ok(remote.launcher_executable)
}

/// RAII guard: `remove_dir_all` on drop. Holds `.tmp-update/` for the
/// lifetime of `process_release` and wipes it whether the function
/// returns `Ok`, `Err`, or unwinds.
///
/// Errors from `remove_dir_all` are swallowed (log-warn only) — the
/// only realistic cause is an AV scanner still holding a partial file,
/// and the next run re-creates + re-truncates the directory anyway.
struct TempDirGuard {
    path: PathBuf,
}

impl TempDirGuard {
    fn new(path: PathBuf) -> Self {
        Self { path }
    }
}

impl Drop for TempDirGuard {
    fn drop(&mut self) {
        if let Err(e) = std::fs::remove_dir_all(&self.path) {
            // `NotFound` is the common case when `process_release`
            // returned early before the directory was ever created
            // (or it was already cleaned). Log other errors because
            // they point at a real disk / permission / AV issue worth
            // investigating on a user's crash report.
            if e.kind() != std::io::ErrorKind::NotFound {
                log::warn!(
                    "temp dir cleanup failed at {}: {e}",
                    self.path.display()
                );
            }
        }
    }
}

/// Auto-retry cooldown ladder in seconds, indexed by the 1-based retry
/// count. Formula: `min(8 + 2*(retry_index - 1), 30)`. Ladder:
/// 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 30, 30, ...
///
/// The ladder is shared by every auto-retry path in the state machine
/// (NoInternet API retries, DownloadFailed background retries) — one
/// rule set so UX is consistent regardless of where the failure hit.
/// Values grow gently from 8 s so the user gets a short-enough first
/// wait to perceive progress, and cap at 30 s (matches the inner
/// NoInternet retry cadence and the perceptual "anything longer
/// feels stuck" threshold).
///
/// Uses `saturating_*` arithmetic so monumentally large retry indices
/// (u32::MAX) cap cleanly at 30 s instead of wrapping.
#[must_use]
pub(crate) const fn backoff_floor_secs(retry_index: u32) -> u64 {
    const BASE_SECS: u64 = 8;
    const STEP_SECS: u64 = 2;
    const CAP_SECS: u64 = 30;
    if retry_index == 0 {
        return 0;
    }
    let offset = (retry_index as u64).saturating_sub(1);
    let raw = BASE_SECS.saturating_add(STEP_SECS.saturating_mul(offset));
    if raw > CAP_SECS { CAP_SECS } else { raw }
}

/// Jitter ceiling applied on top of [`backoff_floor_secs`]. Uniform
/// `0..JITTER_MAX_MS` spread keeps retry storms from synchronizing
/// when many clients retry against a flaky GitHub edge at once.
pub(crate) const JITTER_MAX_MS: u64 = 500;

/// Combine [`backoff_floor_secs`] with a jitter sample drawn from the
/// caller's RNG. DI-flavoured signature so unit tests can pin the exact
/// value (deterministic `SmallRng`) instead of sampling many times and
/// hoping the distribution hit the asserted bounds.
pub(crate) fn backoff_floor_duration_with_rng<R: rand::Rng + ?Sized>(
    retry_index: u32,
    rng: &mut R,
) -> Duration {
    let base_secs = backoff_floor_secs(retry_index);
    let jitter_ms: u64 = rng.random_range(0..JITTER_MAX_MS);
    Duration::from_secs(base_secs) + Duration::from_millis(jitter_ms)
}

/// Production wrapper around [`backoff_floor_duration_with_rng`] that
/// uses `rand::rng()` (thread-local ChaCha12) for the jitter source.
/// Matches the downloader's existing jitter pattern so both retry
/// paths stay consistent.
fn backoff_floor_duration_with_jitter(retry_index: u32) -> Duration {
    backoff_floor_duration_with_rng(retry_index, &mut rand::rng())
}

/// Clamped percent computation for progress updates. Extracted for tests;
/// caller doesn't need to remember the division-by-zero guard.
/// Returns [`Percent`] so the clamp-at-100 invariant survives into the
/// UI state without every call site having to remember `.min(100)`.
#[must_use]
pub fn progress_percent(done_bytes: u64, total_bytes: u64) -> crate::Percent {
    if total_bytes == 0 {
        return crate::Percent::new(100);
    }
    #[allow(
        clippy::cast_precision_loss,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss
    )]
    let pct = ((done_bytes as f64 / total_bytes as f64) * 100.0) as u32;
    // `Percent::new` clamps at 100 — passes through u8 cast because any
    // u32 value >255 is >100 and collapses to Percent(100) anyway, but
    // use min() here for an explicit u32→u8 path without surprises.
    crate::Percent::new(u8::try_from(pct.min(100)).unwrap_or(100))
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

    // --- backoff_floor_secs ---

    #[test]
    fn backoff_floor_secs_ladder_matches_spec() {
        // Spec ladder: start at 8 s, +2 s per retry, cap at 30 s.
        // Any mutation flipping a step trips this assert loudly.
        assert_eq!(backoff_floor_secs(0), 0, "retry 0 = no wait");
        assert_eq!(backoff_floor_secs(1), 8);
        assert_eq!(backoff_floor_secs(2), 10);
        assert_eq!(backoff_floor_secs(3), 12);
        assert_eq!(backoff_floor_secs(4), 14);
        assert_eq!(backoff_floor_secs(5), 16);
        assert_eq!(backoff_floor_secs(6), 18);
        assert_eq!(backoff_floor_secs(10), 26);
        assert_eq!(backoff_floor_secs(11), 28);
        assert_eq!(backoff_floor_secs(12), 30, "cap reached at retry 12");
    }

    #[test]
    fn backoff_floor_secs_saturates_at_cap() {
        // Past the cap point, every retry index saturates at 30 s — no
        // panic on extreme inputs, no wrap-around. `u32::MAX` pins the
        // saturating_* arithmetic contract (naive `8 + 2*(u32::MAX-1)`
        // would overflow to u64 but saturation keeps us at 30).
        assert_eq!(backoff_floor_secs(13), 30);
        assert_eq!(backoff_floor_secs(100), 30);
        assert_eq!(backoff_floor_secs(1_000_000), 30);
        assert_eq!(backoff_floor_secs(u32::MAX), 30);
    }

    #[test]
    fn backoff_floor_duration_with_rng_deterministic_under_seeded_smallrng() {
        // DI-friendly form means we can pin the exact duration without
        // relying on distribution luck. Use a seeded `SmallRng` — the
        // value is reproducible across runs (same seed → same draw) and
        // the test pins the exact total duration for a known seed. If
        // `rand` ever changes SmallRng's algorithm the expected jitter
        // will shift; the assertion format (base + specific jitter)
        // makes that visible in a single diff.
        //
        // Chose seeded SmallRng over `rand::rngs::mock::StepRng`
        // because StepRng is `#[deprecated]` since rand 0.9.2 with no
        // stated replacement — flagged in review.
        use rand::SeedableRng;
        use rand::rngs::SmallRng;
        let mut rng = SmallRng::seed_from_u64(42);
        let d = backoff_floor_duration_with_rng(1, &mut rng);
        // Base (retry=1) = 8 s. Jitter drawn from `random_range(0..500)`
        // on this seed is deterministic and bounded to `0..500 ms`.
        let base = Duration::from_secs(8);
        let upper = base + Duration::from_millis(JITTER_MAX_MS);
        assert!(
            d >= base && d < upper,
            "seeded duration must be in [{base:?}, {upper:?}), got {d:?}"
        );
        // Pin the EXACT value so a mutation changing `base` or the
        // formula surfaces in a single-line assertion failure.
        let d2 = backoff_floor_duration_with_rng(1, &mut SmallRng::seed_from_u64(42));
        assert_eq!(
            d, d2,
            "same seed must produce identical durations"
        );
    }

    #[test]
    fn backoff_floor_duration_with_rng_exercises_jitter_distribution() {
        // The previous `d < upper` assertion was structurally
        // unreachable (jitter range is exclusive at 500), so it
        // tolerated a `jitter_ms = 0` mutation. Sample 200 times and
        // assert at least one draw exceeds base — if the jitter path
        // is deleted, every sample equals `base` and this fails.
        let base = Duration::from_secs(backoff_floor_secs(1));
        let upper = base + Duration::from_millis(JITTER_MAX_MS);
        let mut saw_jitter = false;
        for _ in 0..200 {
            let d = backoff_floor_duration_with_rng(1, &mut rand::rng());
            assert!(d >= base, "jitter must not subtract from base: {d:?}");
            assert!(d < upper, "jitter must stay under cap: {d:?} vs {upper:?}");
            if d > base {
                saw_jitter = true;
            }
        }
        assert!(
            saw_jitter,
            "across 200 samples at least one must have nonzero jitter \
             (probability of 200 consecutive zeros is 1/500^200, ~0)"
        );
    }

    // --- old_version_display ---

    #[test]
    fn old_version_display_none_is_fresh_install_marker() {
        // Replaces `fresh_install_version()` test: the sentinel is gone,
        // but the display string is still part of the log contract.
        assert_eq!(old_version_display(None), "(fresh install)");
    }

    #[test]
    fn old_version_display_some_returns_version_string() {
        let v = Version::parse("1.2.3").unwrap();
        assert_eq!(old_version_display(Some(&v)), "1.2.3");
    }

    // --- progress_percent ---

    #[test]
    fn progress_percent_zero_total_returns_100() {
        // Edge case: empty download batch (all files cache-verified as
        // already present). Percent is meaningless; returning 100 reads
        // as "done" rather than crashing on divide-by-zero.
        assert_eq!(progress_percent(0, 0).as_u8(), 100);
    }

    #[test]
    fn progress_percent_half_done() {
        assert_eq!(progress_percent(500, 1000).as_u8(), 50);
    }

    #[test]
    fn progress_percent_clamps_at_100() {
        // Server lied about Content-Length — downloaded > total. UI
        // should show 100%, not 150%.
        assert_eq!(progress_percent(1500, 1000).as_u8(), 100);
    }

    #[test]
    fn progress_percent_large_u64_values() {
        // 10 GB scale — f64 cast precision is lossy but the result
        // still rounds to a sane percent.
        const TEN_GB: u64 = 10 * 1024 * 1024 * 1024;
        assert_eq!(progress_percent(TEN_GB / 2, TEN_GB).as_u8(), 50);
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
    fn user_action_carries_offline_variant() {
        // Sanity: the `Offline` variant stays constructible + Copy +
        // Debug so the mpsc plumbing compiles. The previous
        // `variants_are_distinct` test lost its point when `Retry` was
        // removed in T2.11f6 — only one variant remains.
        let a = UserAction::Offline;
        let _copy: UserAction = a;
        let _ = format!("{a:?}");
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
        // silent drift of spec 4.x values.
        let cfg = RunUpdateFlowConfig::default();
        assert_eq!(cfg.github_base_url, GITHUB_API_BASE_URL_DEFAULT);
        assert_eq!(cfg.retry_interval_secs, RETRY_INTERVAL_SECS);
        assert_eq!(cfg.checking_min_ms, CHECKING_MIN_MS);
        // `max_api_retries` config knob was removed in T2.11f7 —
        // the 3-fail threshold is now a crate-private const
        // (`API_FAILURES_BEFORE_OFFLINE_PROMPT`) so no runtime knob
        // can diverge from the spec.
        assert_eq!(API_FAILURES_BEFORE_OFFLINE_PROMPT, 3);
    }
}
