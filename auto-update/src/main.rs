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
    current_os_target, run_update_flow, FlowOutcome, UserAction,
};
use singularitymc_auto_update::i18n::resolve_lang;
use singularitymc_auto_update::ui::{states::UiState, AutoUpdateApp};
use singularitymc_auto_update::{config, launcher, self_update};
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

    let retry_tx = user_tx.clone();
    app.set_retry_callback(move || {
        if let Err(e) = retry_tx.try_send(UserAction::Retry) {
            log::warn!("retry click ignored: {e}");
        }
    });

    // Drop the original sender so the channel closes as soon as the two
    // UI callbacks' clones are dropped (they drop when the eframe loop
    // exits). Without this, the last `user_tx` handle would linger in
    // main and the worker's `recv().await` could block past window close.
    drop(user_tx);

    let install_dir_bg = install_dir.clone();
    let state_bg = Arc::clone(&state_handle);

    rt.spawn(async move {
        let outcome = run_update_flow(
            install_dir_bg.clone(),
            channel,
            os,
            Arc::clone(&state_bg),
            &mut user_rx,
        )
        .await;

        match outcome {
            Ok(FlowOutcome::Updated(launcher_rel)) => {
                spawn_and_exit(&state_bg, &install_dir_bg, &launcher_rel, false).await;
            }
            Ok(FlowOutcome::UserRequestedOffline(launcher_rel)) => {
                spawn_and_exit(&state_bg, &install_dir_bg, &launcher_rel, true).await;
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
    let native_options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_inner_size([400.0, 220.0])
            .with_resizable(false),
        ..Default::default()
    };
    eframe::run_native(
        "SingularityMC Auto-Update",
        native_options,
        Box::new(move |_cc| Ok(Box::new(app) as Box<dyn eframe::App>)),
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
