//! SingularityMC auto-update binary entry point.
//!
//! Boot sequence:
//!   1. env_logger init.
//!   2. Locate install_dir (parent of this exe).
//!   3. Apply pending self-update (`auto-update.exe.new`) if any →
//!      respawn + exit.
//!   4. Clean stale self-replace artifacts from prior cycles.
//!   5. Load config + resolve language + detect OS target.
//!   6. Build tokio multi-thread runtime, spawn state machine
//!      ([`app::run_update_flow`]) as background task.
//!   7. Block on eframe UI loop (main thread — eframe requires it).
//!   8. Background task, on success, transitions UI to Starting, waits
//!      briefly, spawns the launcher, and calls `process::exit`.

use singularitymc_auto_update::app::{current_os_target, run_update_flow};
use singularitymc_auto_update::i18n::resolve_lang;
use singularitymc_auto_update::ui::{states::UiState, AutoUpdateApp};
use singularitymc_auto_update::{config, launcher, self_update};
use std::path::PathBuf;
use std::sync::Arc;

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

    let app = AutoUpdateApp::new(lang, UiState::Checking);
    let state_handle = app.state_handle();

    let install_dir_bg = install_dir.clone();
    let state_bg = Arc::clone(&state_handle);

    rt.spawn(async move {
        match run_update_flow(install_dir_bg.clone(), channel, os, Arc::clone(&state_bg)).await {
            Ok(launcher_rel) => {
                // Brief "Starting..." screen so exit doesn't look like a
                // crash from the user's perspective.
                singularitymc_auto_update::app::set_state(&state_bg, UiState::Starting);
                tokio::time::sleep(std::time::Duration::from_millis(300)).await;
                if let Err(e) = launcher::spawn_launcher(&install_dir_bg, &launcher_rel, false) {
                    log::error!("failed to spawn launcher: {e}");
                    singularitymc_auto_update::app::set_state(
                        &state_bg,
                        UiState::FatalError {
                            message: format!("{e}"),
                        },
                    );
                    return;
                }
                // Let OS release any lingering handles before we vanish.
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                std::process::exit(0);
            }
            Err(e) => {
                log::error!("update flow failed: {e}");
                singularitymc_auto_update::app::set_state(
                    &state_bg,
                    UiState::FatalError {
                        message: format!("{e}"),
                    },
                );
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

    Ok(())
}
