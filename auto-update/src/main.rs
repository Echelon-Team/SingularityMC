// GUI subsystem on Windows: no attached conhost on double-click / shortcut
// launch. Without this the binary runs as `console` subsystem — Explorer
// spawns a fresh conhost that briefly flashes and, on some driver configs,
// the GUI window never becomes visible to the OS compositor (create-window
// races the console attach). With GUI subsystem winit creates the window
// directly, no console flashes, double-click / shortcut launch works.
//
// Trade-off: `env_logger` output goes to /dev/null when launched from
// Explorer (GUI apps have no default stdout). During dev run via
// `cargo run` from a terminal — the parent cmd picks up stdout via
// inherited handles. Production launcher pipe logs to a file if needed.
#![windows_subsystem = "windows"]

//! SingularityMC auto-update binary entry point.
//!
//! Boot sequence:
//!   1. `--version` short-circuit (CI smoke test, no GUI boot).
//!   2. env_logger init.
//!   3. Locate install_dir (parent of this exe).
//!   4. Apply pending self-update (`auto-update.exe.new`) if any →
//!      respawn + exit.
//!   5. Clean stale self-replace artifacts from prior cycles.
//!   6. Load config + resolve language + detect OS target.
//!   7. Build tokio multi-thread runtime. Create the `UserAction` mpsc
//!      channel that bridges UI button clicks into the state machine,
//!      and wire both ends: tx → UI callbacks, rx → background task.
//!   8. Spawn state machine ([`app::run_update_flow`]) as background
//!      task.
//!   9. Block on eframe UI loop (main thread — eframe requires it).
//!  10. Background task outcome:
//!        - `FlowOutcome::Updated`   → spawn launcher normally + exit 0.
//!        - `UserRequestedOffline`   → spawn launcher with `--offline`
//!                                     + exit 0.
//!        - `Err`                    → UI gets `FatalError`; user closes
//!                                     via the "Zamknij" button.

use singularitymc_auto_update::app::{
    current_os_target, run_update_flow_with_config, FlowOutcome, RunUpdateFlowConfig, UserAction,
};
use singularitymc_auto_update::i18n::{resolve_lang, strings};
use singularitymc_auto_update::launcher::{CrashCounterKind, LAUNCHER_CRASH_THRESHOLD};
use singularitymc_auto_update::ui::{states::UiState, AutoUpdateApp};
use singularitymc_auto_update::{config, launcher, manifest, self_update, updater};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::mpsc;

/// Bound on the pending UI actions channel. The state machine only parks
/// on a single decision point (`OfflineAvailable`), so a capacity of 8 is
/// ample headroom for double-click / spam-click jitter while keeping
/// back-pressure meaningful: a runaway callback that tries to push
/// hundreds of events will see `try_send` fail and the extras are dropped
/// (intended — one decision suffices).
const USER_ACTION_CHANNEL_CAP: usize = 8;

fn main() -> anyhow::Result<()> {
    // `--version` short-circuit: prints the embedded BUILD_VERSION on a single
    // line and exits before any GUI/runtime bootstrap. Satisfies the smoke-test
    // contract from Task 2.1 (version embedding via build.rs stays verifiable
    // in CI without needing a display) and matches standard CLI convention.
    if std::env::args().any(|a| a == "--version" || a == "-V") {
        println!(
            "SingularityMC auto-update v{}",
            singularitymc_auto_update::BUILD_VERSION
        );
        return Ok(());
    }

    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"))
        .format_timestamp_millis()
        .init();
    log::info!(
        "SingularityMC auto-update v{} starting",
        singularitymc_auto_update::BUILD_VERSION
    );

    let current_exe = std::env::current_exe()?;
    let install_dir: PathBuf = current_exe
        .parent()
        .ok_or_else(|| anyhow::anyhow!("cannot determine install_dir from current_exe"))?
        .to_path_buf();

    // Pending self-update swap must happen before ANY other state reads —
    // the new binary may have different schema expectations.
    if self_update::apply_pending()? {
        // Swap committed + fresh process spawned; exit promptly so the
        // replaced image can be released by the OS.
        return Ok(());
    }

    self_update::cleanup_stale_files(&install_dir);

    // Launcher crash-loop detector + self-healing rollback. Runs BEFORE
    // any update flow work so a broken previous update gets reverted
    // instead of re-attempted.
    //
    // Handshake:
    //   1. Last launcher run wrote `launcher-alive-flag` (via Kotlin
    //      `LauncherAliveFlag.write()` 2 s after first composition)
    //      → reset counter, consume flag.
    //   2. Flag absent → either previous run crashed before the 2 s
    //      alive tick, or the binary never ran. Increment counter.
    //   3. Counter reached `LAUNCHER_CRASH_THRESHOLD` (2 per Mateusz's
    //      explicit decision — 3rd boot triggers rollback after 2
    //      consecutive crashes) → copy files from the newest
    //      `File-Backups/pre-update-*/` back over `install_dir/launcher`,
    //      reset counter, set `did_rollback` so the bg task skips the
    //      normal update flow (the rolled-back state is the intended
    //      "known good" — re-entering update would just re-pull the
    //      broken version).
    let did_rollback = handle_launcher_crash_counter(&install_dir);

    let cfg = config::load(&install_dir);
    let lang = resolve_lang(cfg.language);
    let os = current_os_target();
    let channel = cfg.channel;

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;

    // UI action channel: state machine parks on rx, callbacks push on tx.
    // `try_send` in the callbacks guarantees the UI thread never blocks —
    // if the worker isn't parked (e.g. a click arrives mid-download), the
    // send is dropped. That's intended: the parking points are the only
    // state-machine moments where a user action is meaningful.
    let (user_tx, mut user_rx) = mpsc::channel::<UserAction>(USER_ACTION_CHANNEL_CAP);

    let mut app = AutoUpdateApp::new(lang, UiState::Checking);
    let state_handle = app.state_handle();

    // Wire both callbacks BEFORE handing `app` to eframe. Cloning the tx
    // is cheap (Arc bump) and each callback gets its own owned clone so
    // the closures are `Send + Sync + 'static`.
    let offline_tx = user_tx.clone();
    app.set_offline_mode_callback(move || {
        // `try_send` → if the channel is full (8 queued decisions already)
        // or closed (worker already terminated), drop the click silently.
        // The worker is the sole consumer and every consume is followed
        // by a terminal transition, so "full" in practice means "user is
        // spamming double-clicks after already deciding" — also fine.
        if let Err(e) = offline_tx.try_send(UserAction::Offline) {
            log::warn!("offline-mode click ignored: {e}");
        }
    });

    // Retry is no longer a UI action — the state machine auto-retries
    // on the cooldown ladder; `UserAction::Retry` was removed. We drop
    // the original sender so the channel closes as soon as the single
    // remaining `offline_tx` clone is dropped (when the eframe loop
    // exits). Without this, the last `user_tx` handle would linger in
    // main and the worker's `recv().await` could block past window
    // close.
    drop(user_tx);

    let install_dir_bg = install_dir.clone();
    let state_bg = Arc::clone(&state_handle);

    // Resolve the i18n bundle now so the state machine can surface
    // localized FatalError messages (e.g. "no offline install")
    // without having to plumb `Lang` through every helper.
    let cfg = RunUpdateFlowConfig::default().with_strings(strings(lang));

    rt.spawn(async move {
        // Post-rollback short-circuit: skip the whole update flow and go
        // straight to `spawn_and_exit` using the local manifest's launcher
        // path. Re-entering `run_update_flow_with_config` would re-fetch
        // the remote manifest and re-download the very files we just
        // reverted, re-breaking the install. If local manifest is somehow
        // missing (rollback succeeded but manifest file was also lost),
        // surface FatalError — user needs to reinstall.
        if did_rollback {
            match manifest::load_local(&install_dir_bg) {
                Some(local) => {
                    log::warn!(
                        "rolled back from crash loop; launching rolled-back version {}",
                        local.version
                    );
                    spawn_and_exit(&state_bg, &install_dir_bg, &local.launcher_executable, false)
                        .await;
                }
                None => {
                    log::error!("post-rollback local manifest missing — cannot spawn launcher");
                    singularitymc_auto_update::app::set_state(
                        &state_bg,
                        UiState::FatalError {
                            message: strings(lang).no_offline_install.to_string(),
                        },
                    );
                }
            }
            return;
        }

        let outcome = run_update_flow_with_config(
            install_dir_bg.clone(),
            channel,
            os,
            Arc::clone(&state_bg),
            &mut user_rx,
            cfg,
        )
        .await;

        match outcome {
            Ok(FlowOutcome::Updated(launcher_rel)) => {
                spawn_and_exit(&state_bg, &install_dir_bg, &launcher_rel, false).await;
            }
            Ok(FlowOutcome::UserRequestedOffline(launcher_rel)) => {
                spawn_and_exit(&state_bg, &install_dir_bg, &launcher_rel, true).await;
            }
            // `FlowOutcome` is `#[non_exhaustive]` — a future variant
            // added upstream would require either wiring here or
            // falling into this catch-all. The USER-facing message is
            // pulled from the localized `Strings` bundle (so a PL
            // install doesn't see an English Debug format). The Debug
            // render goes only to the log, which is for us.
            Ok(other) => {
                log::error!("unhandled FlowOutcome variant: {other:?}");
                singularitymc_auto_update::app::set_state(
                    &state_bg,
                    UiState::FatalError {
                        message: strings(lang).unhandled_flow_outcome.to_string(),
                    },
                );
            }
            Err(e) => {
                log::error!("update flow failed: {e}");
                singularitymc_auto_update::app::set_state(
                    &state_bg,
                    UiState::FatalError {
                        message: format!("{e}"),
                    },
                );
                // Leave eframe running; user dismisses via the "Zamknij"
                // button wired into the FatalError UI. No process::exit
                // here — that would terminate eframe mid-paint and lose
                // the error screen the user needs to read.
            }
        }
    });

    // eframe blocks until the user closes the window. Required to run on
    // the main thread (platform-specific — winit/AppKit/Wayland).
    // Borderless, non-resizable window per spec 4.x — no OS titlebar /
    // min-max-close buttons. Draggable via the custom titlebar strip
    // rendered in `ui::mod` (top 30 px emits `ViewportCommand::StartDrag`
    // on drag_started).
    //
    // NOTE: no `with_transparent(true)` — wgpu `CompositeAlphaMode` is
    // unreliable across GPU drivers (NVIDIA + Overwolf Vulkan loader
    // hooks report no alpha support in practice). Requesting transparency
    // when the surface can't honour it leaves the painted rounded-corner
    // cut-outs rendering as opaque clear-color instead. Proper rounded
    // corners on Win11 need a native DwmSetWindowAttribute call — future
    // polish task.
    let native_options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_inner_size([400.0, 220.0])
            .with_resizable(false)
            .with_decorations(false),
        ..Default::default()
    };
    eframe::run_native(
        "SingularityMC Auto-Update",
        native_options,
        Box::new(move |cc| {
            apply_win11_rounded_corners(cc);
            Ok(Box::new(app) as Box<dyn eframe::App>)
        }),
    )
    .map_err(|e| anyhow::anyhow!("eframe error: {e}"))?;

    // eframe returned — the window is gone and `app` (which owns the
    // callbacks' `user_tx` clones) is dropped by now, so the channel is
    // closed. Give the background task up to 2 s to observe that,
    // propagate a final state if it was mid-`recv`, and finish any
    // in-flight disk/HTTP work cleanly. Without this, `rt.drop()` would
    // cancel the task at its next `.await`, potentially mid-write —
    // which on Windows can leave an orphaned `.tmp-update/` directory or
    // an AV-locked half-swapped file. 2 s is a conservative upper bound
    // for our async operations (each already has its own internal
    // timeout); a hung task past that deadline is a real bug, and hard-
    // cancelling is the right remedy.
    rt.shutdown_timeout(std::time::Duration::from_secs(2));

    Ok(())
}

/// Success terminal: flip to `Starting`, pause briefly so the screen is
/// perceivable, then spawn the launcher and `process::exit(0)`. The brief
/// sleeps are UX, not correctness — users perceive <200ms transitions as
/// a flash/glitch rather than a state change. Uses `tokio::time::sleep`
/// (not `std::thread::sleep`) because the caller is a tokio task; a
/// blocking sleep would stall a multi-thread runtime worker for 300ms.
async fn spawn_and_exit(
    state_bg: &Arc<std::sync::Mutex<UiState>>,
    install_dir: &std::path::Path,
    launcher_rel: &singularitymc_auto_update::ManifestPath,
    offline: bool,
) {
    singularitymc_auto_update::app::set_state(state_bg, UiState::Starting);
    tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    if let Err(e) = launcher::spawn_launcher(install_dir, launcher_rel, offline) {
        log::error!("failed to spawn launcher: {e}");
        singularitymc_auto_update::app::set_state(
            state_bg,
            UiState::FatalError {
                message: format!("{e}"),
            },
        );
        // Same rationale as the main error branch — let the user read
        // the error and close the window manually.
        return;
    }
    // Let OS release any lingering handles before we vanish.
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    std::process::exit(0);
}

/// Ask the Win11 DWM to draw rounded corners on our borderless window.
///
/// Normal route to rounded corners — `with_transparent(true)` + an
/// alpha-cut custom frame — fails silently on this project's dev
/// machine: NVIDIA + Overwolf's Vulkan implicit layer combine to
/// produce a wgpu surface that only advertises `CompositeAlphaMode::Opaque`,
/// so the OS never composites our transparent pixels away. This path
/// sidesteps wgpu entirely: DWM draws the rounding outside the swap
/// chain, same system Win11 uses for native apps. Fails silently on
/// Win10 (`DWMWA_WINDOW_CORNER_PREFERENCE` unsupported on that OS — the
/// attribute is just ignored, which is the correct graceful fallback).
#[cfg(windows)]
fn apply_win11_rounded_corners(cc: &eframe::CreationContext<'_>) {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Dwm::{
        DwmSetWindowAttribute, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND,
    };

    let handle = match cc.window_handle() {
        Ok(h) => h,
        Err(e) => {
            log::warn!("window_handle() unavailable — skip rounded corners: {e}");
            return;
        }
    };
    let RawWindowHandle::Win32(win32) = handle.as_raw() else {
        log::warn!("non-Win32 window handle — skip rounded corners");
        return;
    };
    // SAFETY: `hwnd` is a live Win32 window handle owned by the eframe
    // runtime at this point (we're inside `run_native`'s creator before
    // the event loop starts consuming events). `DwmSetWindowAttribute`
    // with `DWMWA_WINDOW_CORNER_PREFERENCE` takes a 4-byte enum and
    // doesn't retain the pointer after return.
    let hwnd = HWND(win32.hwnd.get() as *mut _);
    let pref: i32 = DWMWCP_ROUND.0;
    unsafe {
        if let Err(e) = DwmSetWindowAttribute(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            std::ptr::from_ref(&pref).cast(),
            std::mem::size_of::<i32>() as u32,
        ) {
            log::warn!(
                "DwmSetWindowAttribute(DWMWCP_ROUND) failed: {e} \
                 (non-fatal — window just stays square)"
            );
        }
    }
}

/// Non-Windows stub — the rounded-corner path is Win11-specific.
#[cfg(not(windows))]
fn apply_win11_rounded_corners(_cc: &eframe::CreationContext<'_>) {}

/// Consume the `launcher-alive-flag`, update the Launcher crash counter
/// accordingly, and trigger a File-Backups rollback when the counter
/// reaches threshold. Returns `true` if a rollback was performed —
/// caller short-circuits the normal update flow in that case.
///
/// Failure modes are degraded gracefully:
/// - Counter read / write errors → logged, flow continues (missing one
///   crash detection is strictly better than blocking the whole boot).
/// - Rollback fails (no `File-Backups/`, IO error) → logged error,
///   `did_rollback = false`, normal update flow runs as a last-chance
///   recovery attempt.
fn handle_launcher_crash_counter(install_dir: &PathBuf) -> bool {
    let alive = launcher::consume_launcher_alive_flag(install_dir);
    if alive {
        if let Err(e) = launcher::reset_crash_counter(install_dir, CrashCounterKind::Launcher) {
            log::warn!("reset launcher crash counter failed: {e}");
        } else {
            log::info!("previous launcher run was stable (alive flag consumed)");
        }
        return false;
    }

    let count = match launcher::increment_crash_counter(install_dir, CrashCounterKind::Launcher)
    {
        Ok(c) => c,
        Err(e) => {
            log::warn!("increment launcher crash counter failed: {e}");
            return false;
        }
    };
    log::info!(
        "launcher alive flag absent; launcher_crash_counter now {count} \
         (threshold {LAUNCHER_CRASH_THRESHOLD})"
    );

    if count < LAUNCHER_CRASH_THRESHOLD {
        return false;
    }

    log::warn!(
        "launcher crash counter {count} >= threshold {LAUNCHER_CRASH_THRESHOLD}; \
         performing rollback from newest File-Backups snapshot"
    );
    match updater::perform_launcher_rollback(install_dir) {
        Ok(snapshot) => {
            log::warn!("rolled back launcher files from {}", snapshot.display());
            if let Err(e) = launcher::reset_crash_counter(install_dir, CrashCounterKind::Launcher)
            {
                log::warn!("reset launcher crash counter after rollback failed: {e}");
            }
            true
        }
        Err(e) => {
            log::error!(
                "launcher rollback failed: {e}; user may need to reinstall manually"
            );
            false
        }
    }
}
