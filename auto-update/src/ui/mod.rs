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
}

impl AutoUpdateApp {
    #[must_use]
    pub fn new(lang: Lang, initial_state: UiState) -> Self {
        Self {
            state: Arc::new(Mutex::new(initial_state)),
            lang,
            on_offline_mode: None,
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
}

/// Rounded-corner radius (px) for the borderless window frame. Matches
/// Win11 native rounding (`DWMWCP_ROUND`, ~8 px) so the updater doesn't
/// look foreign next to other apps. Applied in the custom `CentralPanel`
/// frame rendered in `update()`.
const WINDOW_CORNER_RADIUS: u8 = 12;

/// Fixed height of the top drag-titlebar strip.
const TITLEBAR_HEIGHT: f32 = 30.0;

/// Fixed height reserved at the bottom for the controls row (buttons
/// plus breathing room). Constant so content centering math is
/// deterministic and buttons land pinned at the same spot across every
/// state.
const BUTTON_ROW_HEIGHT: f32 = 44.0;

/// End Dimension theme base colour (`--base` from launcher's
/// `SingularityTheme.kt`). Hex `#14121D`. Keeps the updater visually
/// tied to the launcher it's about to hand off to.
const BG_COLOR: egui::Color32 = egui::Color32::from_rgb(0x14, 0x12, 0x1D);

impl eframe::App for AutoUpdateApp {
    /// Solid End Dimension clear — avoids the transparency dance that
    /// wgpu's `CompositeAlphaMode` can't honour on some GPU stacks
    /// (NVIDIA + Overwolf Vulkan hooks, etc.). Matches the painted
    /// `CentralPanel` fill so the window reads as one flat surface.
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] {
        const fn srgb(b: u8) -> f32 {
            b as f32 / 255.0
        }
        [srgb(0x14), srgb(0x12), srgb(0x1D), 1.0]
    }

    /// Override `update` instead of just implementing `ui` so we can
    /// install a custom `CentralPanel` frame — corner rounding + End
    /// Dimension fill. The default `update` wraps `ui` in a plain
    /// CentralPanel with no rounding and the standard visuals fill.
    /// Rounded corners stay in the Frame even without a transparent
    /// window — they're invisible against a matching `clear_color` but
    /// remain "free" for a follow-up that wires the Win11 native
    /// rounding API (DwmSetWindowAttribute) or restores transparency
    /// once a cross-driver surface config is known.
    fn update(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        let panel_frame = egui::Frame::default()
            .fill(BG_COLOR)
            .corner_radius(egui::CornerRadius::same(WINDOW_CORNER_RADIUS));
        egui::CentralPanel::default()
            .frame(panel_frame)
            .show(ctx, |ui| self.ui(ui, frame));
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let current_state = match self.state.lock() {
            Ok(guard) => guard.clone(),
            // Poisoned mutex = a panic happened mid-mutation. Recover
            // the value anyway — a single poisoning must not deadlock
            // the UI forever.
            Err(poisoned) => poisoned.into_inner().clone(),
        };
        let s = strings(self.lang);
        let lang = self.lang;

        // Layout skeleton shared by every state:
        //   1. Top strip   — OS-native drag region + centered app name
        //                    (replaces the OS titlebar we removed).
        //   2. Middle area — main content, vertically + horizontally
        //                    centered in the remaining space.
        //   3. Bottom row  — Wyjdź / Pomoc / optional Tryb offline,
        //                    horizontally centered, pinned to the
        //                    window bottom (fixed-height reservation
        //                    regardless of state so the layout doesn't
        //                    jump when transitioning).
        draw_titlebar(ui);

        let content_height =
            (ui.available_height() - BUTTON_ROW_HEIGHT).max(0.0);
        ui.allocate_ui_with_layout(
            egui::vec2(ui.available_width(), content_height),
            egui::Layout::centered_and_justified(egui::Direction::TopDown),
            |ui| {
                ui.vertical_centered(|ui| {
                    render_content(ui, &current_state, s, lang);
                });
            },
        );

        ui.allocate_ui_with_layout(
            egui::vec2(ui.available_width(), BUTTON_ROW_HEIGHT),
            egui::Layout::top_down(egui::Align::Center),
            |ui| {
                render_buttons(ui, &current_state, s, &self.on_offline_mode);
            },
        );

        // Immediate-mode redraw tick — egui only repaints on input
        // without this, so mid-download spinners / progress bars would
        // freeze and `remaining_retry_seconds` wouldn't tick. 33ms ≈
        // 30fps — balance between perceptual smoothness and CPU cost
        // on idle-ish updater screens.
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_millis(33));
    }
}

/// Paint the top drag region with centered app name.
fn draw_titlebar(ui: &mut egui::Ui) {
    let available = ui.available_rect_before_wrap();
    let titlebar_rect = egui::Rect::from_min_size(
        available.min,
        egui::vec2(available.width(), TITLEBAR_HEIGHT),
    );
    let drag = ui.interact(
        titlebar_rect,
        egui::Id::new("window_drag_region"),
        egui::Sense::drag(),
    );
    if drag.drag_started() {
        ui.ctx().send_viewport_cmd(egui::ViewportCommand::StartDrag);
    }
    ui.painter().text(
        titlebar_rect.center(),
        egui::Align2::CENTER_CENTER,
        "SingularityMC",
        egui::FontId::proportional(14.0),
        ui.style().visuals.text_color(),
    );
    ui.add_space(TITLEBAR_HEIGHT);
}

/// Main content per state. Labels + spinners / progress bars; no
/// buttons (those live in `render_buttons` in the pinned bottom row).
fn render_content(
    ui: &mut egui::Ui,
    state: &UiState,
    s: &i18n::Strings,
    lang: Lang,
) {
    match state {
        UiState::Checking => {
            ui.label(s.checking);
            ui.spinner();
        }
        UiState::Downloading {
            percent,
            downloaded_bytes,
            total_bytes,
        } => {
            ui.label(i18n::downloading_percent(lang, *percent));
            ui.add(
                egui::ProgressBar::new(f32::from(percent.as_u8()) / 100.0)
                    .desired_width(300.0),
            );
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
        UiState::NoInternet { .. } | UiState::OfflineAvailable { .. } => {
            ui.label(s.no_internet);
            ui.label(i18n::retry_in_seconds_str(
                lang,
                state.remaining_retry_seconds(),
            ));
        }
        UiState::DownloadFailed { .. } => {
            ui.label(s.download_failed);
            ui.label(i18n::retry_in_seconds_str(
                lang,
                state.remaining_retry_seconds(),
            ));
        }
        UiState::FatalError { message } => {
            ui.label(s.download_failed);
            ui.add_space(6.0);
            ui.label(message);
        }
    }
}

/// Bottom button row per state. Horizontally centered by the outer
/// `Layout::top_down(Align::Center)` wrapper in `ui()`; transient
/// work states (Checking / Downloading / …) render an empty row so
/// the layout doesn't reflow on transition into parked states.
fn render_buttons(
    ui: &mut egui::Ui,
    state: &UiState,
    s: &i18n::Strings,
    on_offline_mode: &Option<Callback>,
) {
    let offline_button = match state {
        UiState::OfflineAvailable { .. } => Some(true),
        UiState::DownloadFailed { has_offline, .. } => Some(*has_offline),
        UiState::NoInternet { .. } | UiState::FatalError { .. } => None,
        // Work states — no buttons, keep the row empty but reserved.
        _ => return,
    };
    ui.horizontal(|ui| {
        if ui.button(s.close).clicked() {
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
        }
        if ui.button(s.help).clicked() {
            let _ = open::that(i18n::DISCORD_URL);
        }
        if matches!(offline_button, Some(true))
            && ui.button(s.offline_mode).clicked()
        {
            if let Some(cb) = on_offline_mode {
                cb();
            }
        }
    });
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
    fn offline_callback_can_be_installed() {
        let mut app = AutoUpdateApp::new(Lang::En, UiState::Checking);
        app.set_offline_mode_callback(|| {});
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
