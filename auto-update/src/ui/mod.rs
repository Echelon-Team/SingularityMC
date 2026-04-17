//! egui UI for the auto-update binary — 9 mutually-exclusive states
//! (per spec 4.x) displayed in a single center-aligned panel.
//!
//! State changes are driven externally (Task 2.11 state machine holds an
//! `Arc<Mutex<UiState>>` shared with this app and mutates it as downloads
//! progress / errors occur). The egui app re-reads the current state
//! every frame and renders the matching view.
//!
//! **Why Arc<Mutex> not channel:** egui is immediate-mode — each frame
//! reads state, draws UI, done. A channel would buffer state changes and
//! require polling loop discipline. Mutex lock is ~nanoseconds and held
//! for one read → negligible contention with the single writer (state
//! machine).
//!
//! **Repaint cadence:** `ctx.request_repaint_after(16ms)` keeps spinners
//! and progress bars smooth even when the user isn't interacting. egui
//! only repaints on input or explicit request, so without this the UI
//! would freeze mid-download from the user's perspective.
//!
//! **No unit tests here** — egui rendering requires a full `egui::Context`
//! + frame simulation which is integration-level. The [`UiState`] enum
//! itself is exercised via `ui::states` tests; the app wiring
//! (callbacks, Arc/Mutex sharing) is smoke-tested where construction is
//! cheap.

pub mod states;

use crate::i18n::{self, strings, Lang};
use eframe::egui;
use states::UiState;
use std::sync::{Arc, Mutex};

type Callback = Box<dyn Fn() + Send + Sync>;

/// Main egui app. Owns shared state handle + optional user-action
/// callbacks. State-machine code in Task 2.11 constructs this, calls
/// [`state_handle`](AutoUpdateApp::state_handle) to keep a writer end,
/// then hands the app off to `eframe::run_native`.
pub struct AutoUpdateApp {
    state: Arc<Mutex<UiState>>,
    lang: Lang,
    on_offline_mode: Option<Callback>,
    on_retry: Option<Callback>,
}

impl AutoUpdateApp {
    #[must_use]
    pub fn new(lang: Lang, initial_state: UiState) -> Self {
        Self {
            state: Arc::new(Mutex::new(initial_state)),
            lang,
            on_offline_mode: None,
            on_retry: None,
        }
    }

    /// Clone the state handle so the state machine can mutate the view.
    /// Cheap — `Arc::clone` is a single atomic refcount bump.
    #[must_use]
    pub fn state_handle(&self) -> Arc<Mutex<UiState>> {
        Arc::clone(&self.state)
    }

    /// Install the callback invoked when the user clicks "offline mode".
    /// Typically transitions the state machine into an offline-fallback
    /// path (spawn launcher with `--offline`).
    pub fn set_offline_mode_callback<F>(&mut self, f: F)
    where
        F: Fn() + Send + Sync + 'static,
    {
        self.on_offline_mode = Some(Box::new(f));
    }

    /// Install the callback invoked when the user clicks "retry".
    /// Typically re-triggers the update flow from the top.
    pub fn set_retry_callback<F>(&mut self, f: F)
    where
        F: Fn() + Send + Sync + 'static,
    {
        self.on_retry = Some(Box::new(f));
    }
}

impl eframe::App for AutoUpdateApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // eframe 0.34's default `update` wraps this in a CentralPanel,
        // so we just paint directly into the given `ui`.
        let current_state = match self.state.lock() {
            Ok(guard) => guard.clone(),
            // Poisoned mutex = a panic happened mid-mutation. Recover
            // the value anyway — a single poisoning must not deadlock
            // the UI forever.
            Err(poisoned) => poisoned.into_inner().clone(),
        };
        let s = strings(self.lang);

        ui.vertical_centered(|ui| {
            ui.add_space(20.0);
            ui.heading("SingularityMC");
            ui.add_space(20.0);

            match &current_state {
                UiState::Checking => {
                    ui.label(s.checking);
                    ui.spinner();
                }
                UiState::Downloading {
                    percent,
                    downloaded_bytes,
                    total_bytes,
                } => {
                    ui.label(i18n::downloading_percent(self.lang, *percent));
                    ui.add(
                        egui::ProgressBar::new(f32::from(*percent) / 100.0)
                            .desired_width(300.0),
                    );
                    // Format bytes → MB at render time; truth stays u64.
                    const MB: f64 = 1_048_576.0;
                    let d_mb = *downloaded_bytes as f64 / MB;
                    let t_mb = *total_bytes as f64 / MB;
                    ui.label(format!("{d_mb:.1} MB / {t_mb:.1} MB"));
                }
                UiState::Verifying => {
                    ui.label(s.verifying);
                    ui.spinner();
                }
                UiState::Installing => {
                    ui.label(s.installing);
                    ui.spinner();
                }
                UiState::Starting => {
                    ui.label(s.starting);
                }
                UiState::NoInternet { retry_in_seconds } => {
                    ui.label(s.no_internet);
                    ui.label(i18n::retry_in_seconds_str(self.lang, *retry_in_seconds));
                    if ui.button(s.help).clicked() {
                        let _ = open::that(i18n::DISCORD_URL);
                    }
                }
                UiState::OfflineAvailable => {
                    ui.label(s.no_internet);
                    ui.horizontal(|ui| {
                        if ui.button(s.help).clicked() {
                            let _ = open::that(i18n::DISCORD_URL);
                        }
                        if ui.button(s.offline_mode).clicked() {
                            if let Some(cb) = &self.on_offline_mode {
                                cb();
                            }
                        }
                    });
                }
                UiState::DownloadFailed => {
                    ui.label(s.download_failed);
                    ui.horizontal(|ui| {
                        if ui.button(s.retry).clicked() {
                            if let Some(cb) = &self.on_retry {
                                cb();
                            }
                        }
                        if ui.button(s.offline_mode).clicked() {
                            if let Some(cb) = &self.on_offline_mode {
                                cb();
                            }
                        }
                    });
                }
                UiState::FatalError { message } => {
                    ui.label(s.download_failed);
                    ui.add_space(8.0);
                    ui.label(message);
                }
            }
        });

        // Immediate-mode redraw tick — egui only repaints on input
        // without this, so mid-download spinners / progress bars would
        // freeze. 33ms ≈ 30fps — egui docs' recommended balance between
        // perceptual smoothness for spinners and CPU/battery cost on
        // idle-ish updater screens.
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_millis(33));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_new_stores_initial_state_and_lang() {
        let app = AutoUpdateApp::new(Lang::Pl, UiState::Checking);
        assert_eq!(app.lang, Lang::Pl);
        // Default callbacks unset.
        assert!(app.on_offline_mode.is_none());
        assert!(app.on_retry.is_none());
    }

    #[test]
    fn state_handle_is_shared_with_writer() {
        // The pattern Task 2.11 will use: hold the handle, mutate state,
        // UI thread sees the change on the next frame.
        let app = AutoUpdateApp::new(Lang::En, UiState::Checking);
        let writer = app.state_handle();
        *writer.lock().unwrap() = UiState::Verifying;
        // App's own state handle observes the same mutation.
        assert!(matches!(
            *app.state.lock().unwrap(),
            UiState::Verifying
        ));
    }

    #[test]
    fn callbacks_can_be_installed() {
        let mut app = AutoUpdateApp::new(Lang::En, UiState::Checking);
        app.set_retry_callback(|| {});
        app.set_offline_mode_callback(|| {});
        assert!(app.on_retry.is_some());
        assert!(app.on_offline_mode.is_some());
    }

    #[test]
    fn poisoned_state_mutex_still_yields_state_for_ui() {
        // Pins the fn ui recovery path at mod.rs: if the writer panics
        // while holding the state Mutex, the UI must still be able to
        // read the last-written value and render it — not deadlock.
        use std::panic;
        use std::sync::Arc;

        let app = AutoUpdateApp::new(Lang::En, UiState::Checking);
        let writer_handle = app.state_handle();

        // Poison the mutex by panicking while holding the guard. We do
        // this in a spawned thread so the panic doesn't abort the test.
        let poisoner = Arc::clone(&writer_handle);
        let _ = std::thread::spawn(move || {
            let _guard = poisoner.lock().unwrap();
            panic::panic_any("intentional poison");
        })
        .join();

        // Mutex is now poisoned. The recovery pattern used in fn ui:
        let state = match writer_handle.lock() {
            Ok(g) => g.clone(),
            Err(poisoned) => poisoned.into_inner().clone(),
        };
        // State value survived — we can still render it.
        assert!(matches!(state, UiState::Checking));
    }
}
