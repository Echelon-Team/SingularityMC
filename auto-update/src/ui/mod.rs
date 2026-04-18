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

/// End Dimension theme colours used for the window background
/// gradient. Gradient goes top → bottom from `--surface-1` (a vivid
/// deep purple) down to `--base` (near-black purple). Values pulled
/// from launcher's `SingularityTheme.kt` so the updater visually
/// matches the launcher it's about to hand off to.
const GRADIENT_TOP: egui::Color32 = egui::Color32::from_rgb(0x2F, 0x19, 0x5F);
const GRADIENT_BOTTOM: egui::Color32 = egui::Color32::from_rgb(0x14, 0x12, 0x1D);

impl eframe::App for AutoUpdateApp {
    /// Clear to the gradient's bottom colour so any area outside the
    /// painted `CentralPanel` frame (shouldn't happen in practice, but
    /// safety) matches the visible bottom of the gradient instead of
    /// flashing black.
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] {
        const fn srgb(b: u8) -> f32 {
            b as f32 / 255.0
        }
        [srgb(0x14), srgb(0x12), srgb(0x1D), 1.0]
    }

    /// Override `update` to install a custom `CentralPanel` frame
    /// (corner rounding + End Dimension base fill), paint the vertical
    /// gradient mesh on top, and theme the buttons to accent-primary
    /// purple so they don't render as default-neutral grey against the
    /// purple background. Default `update` wraps `ui` in a plain
    /// CentralPanel with no rounding, no gradient, no custom button
    /// visuals.
    fn update(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        let panel_frame = egui::Frame::default()
            .fill(GRADIENT_BOTTOM)
            .corner_radius(egui::CornerRadius::same(WINDOW_CORNER_RADIUS));
        egui::CentralPanel::default()
            .frame(panel_frame)
            .show(ctx, |ui| {
                // Paint gradient BEFORE any widget draws — mesh becomes
                // the lowest layer; everything the inner `ui()` renders
                // lands on top.
                paint_vertical_gradient(ui, GRADIENT_TOP, GRADIENT_BOTTOM);
                self.ui(ui, frame);
            });
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
        //
        // Carved via explicit rects + `scope_builder` because
        // `allocate_ui_with_layout` lays children top-down from the
        // current cursor — which doesn't pin the button row to the
        // bottom. Rect-based lets us reserve the bottom strip first,
        // then the middle fills everything above it with a
        // `centered_and_justified` layout that really does center on
        // both axes.
        draw_titlebar(ui);

        let remaining = ui.available_rect_before_wrap();
        let content_rect = egui::Rect::from_min_max(
            remaining.min,
            egui::pos2(remaining.max.x, remaining.max.y - BUTTON_ROW_HEIGHT),
        );
        // Centre the buttons by making the scope rect itself only as
        // wide as the button row needs, positioned at the horizontal
        // centre of the window. `Layout::left_to_right(Align::Center)`
        // on its own anchors items to the left of whatever Ui rect it
        // gets — so the centering has to come from the rect, not the
        // layout.
        let buttons_width = estimate_buttons_width(&current_state);
        let buttons_rect = egui::Rect::from_min_size(
            egui::pos2(
                remaining.center().x - buttons_width / 2.0,
                remaining.max.y - BUTTON_ROW_HEIGHT,
            ),
            egui::vec2(buttons_width, BUTTON_ROW_HEIGHT),
        );

        // Middle content. egui has no built-in "centre multiple stacked
        // items vertically" primitive — `centered_and_justified` only
        // centres a single child and then expands it to fill, which
        // defeats the purpose for a stack of labels. Compute an
        // estimated content height from the current state and add
        // symmetric top padding to nudge the stack into the visual
        // middle; naturally-stacked items take care of the rest.
        ui.scope_builder(
            egui::UiBuilder::new()
                .max_rect(content_rect)
                .layout(egui::Layout::top_down(egui::Align::Center)),
            |ui| {
                let est_h = estimate_content_height(&current_state);
                let pad_top = ((content_rect.height() - est_h) / 2.0).max(0.0);
                ui.add_space(pad_top);
                render_content(ui, &current_state, s, lang);
            },
        );

        // Bottom button row — rect itself is centred horizontally, the
        // `left_to_right` layout then packs buttons inside it with
        // normal spacing. `apply_button_theme` MUST run inside this
        // inner scope: `UiBuilder::new()` starts a fresh child Ui and
        // the visuals mutation we'd do on the outer `ui` doesn't
        // propagate — that's why the previous version kept rendering
        // default-grey buttons even though `apply_button_theme` was
        // called.
        ui.scope_builder(
            egui::UiBuilder::new()
                .max_rect(buttons_rect)
                .layout(egui::Layout::left_to_right(egui::Align::Center)),
            |ui| {
                apply_button_theme(ui);
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

/// Paint a vertical gradient spanning the ui's full available rect.
/// Implemented as a 4-vertex `Mesh` with per-vertex colour — egui's
/// `Shape` layer interpolates between top and bottom in the fragment
/// rasteriser, so we get a smooth gradient without per-pixel work.
fn paint_vertical_gradient(
    ui: &egui::Ui,
    top: egui::Color32,
    bottom: egui::Color32,
) {
    let rect = ui.available_rect_before_wrap();
    let mut mesh = egui::epaint::Mesh::default();
    let uv = egui::epaint::WHITE_UV;
    mesh.vertices.push(egui::epaint::Vertex {
        pos: rect.left_top(),
        uv,
        color: top,
    });
    mesh.vertices.push(egui::epaint::Vertex {
        pos: rect.right_top(),
        uv,
        color: top,
    });
    mesh.vertices.push(egui::epaint::Vertex {
        pos: rect.right_bottom(),
        uv,
        color: bottom,
    });
    mesh.vertices.push(egui::epaint::Vertex {
        pos: rect.left_bottom(),
        uv,
        color: bottom,
    });
    mesh.indices.extend_from_slice(&[0, 1, 2, 0, 2, 3]);
    ui.painter().add(egui::Shape::mesh(mesh));
}

/// Theme buttons to End-Dimension accent-primary so they read as
/// clickable against the purple gradient instead of blending into
/// default neutral grey. Mutates the current `Ui`'s visuals — scope
/// is the closure it was called from (`update`'s `CentralPanel::show`
/// closure), which covers every widget painted afterwards including
/// the `render_buttons` children.
fn apply_button_theme(ui: &mut egui::Ui) {
    let accent = egui::Color32::from_rgb(0x7F, 0x3F, 0xB2); // --accent-primary
    let accent_hover = egui::Color32::from_rgb(0xA2, 0x33, 0xEB); // --accent-tertiary
    let accent_active = egui::Color32::from_rgb(0x5F, 0x2F, 0x92); // darker primary
    let visuals = ui.visuals_mut();
    // Button fill lands on `weak_bg_fill` for flat widgets,
    // `bg_fill` for emphasised ones — set both so the chosen colour
    // sticks regardless of which path the widget takes.
    visuals.widgets.inactive.bg_fill = accent;
    visuals.widgets.inactive.weak_bg_fill = accent;
    visuals.widgets.hovered.bg_fill = accent_hover;
    visuals.widgets.hovered.weak_bg_fill = accent_hover;
    visuals.widgets.active.bg_fill = accent_active;
    visuals.widgets.active.weak_bg_fill = accent_active;
    // White text on purple — readable + matches launcher's onPrimary.
    visuals.widgets.inactive.fg_stroke.color = egui::Color32::WHITE;
    visuals.widgets.hovered.fg_stroke.color = egui::Color32::WHITE;
    visuals.widgets.active.fg_stroke.color = egui::Color32::WHITE;
    // Round button corners a little — matches the window corner vibe.
    let br = egui::CornerRadius::same(4);
    visuals.widgets.inactive.corner_radius = br;
    visuals.widgets.hovered.corner_radius = br;
    visuals.widgets.active.corner_radius = br;
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

/// Approx height (px) of the content stack per state — used to
/// compute symmetric top/bottom padding for vertical centring.
/// Rough estimates: ≈ label line ≈ 20 px + spacing. Over-estimation
/// just nudges the stack higher; under-estimation nudges it lower.
/// Either is better than glued-to-the-top.
fn estimate_content_height(state: &UiState) -> f32 {
    match state {
        UiState::Starting => 20.0,
        UiState::Checking | UiState::Verifying | UiState::Installing => 42.0,
        UiState::Downloading { .. } => 68.0,
        UiState::NoInternet { .. }
        | UiState::OfflineAvailable { .. }
        | UiState::DownloadFailed { .. } => 46.0,
        // FatalError: 1 label + spacer + wrapped message — worst case.
        UiState::FatalError { .. } => 72.0,
    }
}

/// Approx width (px) of the button row per state — used to size the
/// scope rect so it can be positioned horizontally-centred. egui button
/// widths depend on text + padding; hand-eyed estimates from the PL/EN
/// labels with a cushion for text size variance.
fn estimate_buttons_width(state: &UiState) -> f32 {
    // Wyjdź ≈ 60, Pomoc ≈ 60, TRYB OFFLINE ≈ 115; 4 px spacing.
    match state {
        UiState::OfflineAvailable { .. } => 260.0,
        UiState::DownloadFailed { has_offline: true, .. } => 260.0,
        UiState::NoInternet { .. }
        | UiState::FatalError { .. }
        | UiState::DownloadFailed { has_offline: false, .. } => 140.0,
        // Work states — buttons row is empty but the estimate is used
        // to size the (invisible) rect. Any value works; keep it
        // modest so it doesn't affect nothing.
        _ => 80.0,
    }
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

/// Bottom button row per state. Called from an outer scope whose
/// layout is already `Layout::left_to_right(Align::Center)` and whose
/// rect is sized + positioned for horizontal centring, so buttons are
/// added directly to the parent Ui without an extra `ui.horizontal`
/// wrapper (that would nest another LTR layout and shift spacing).
/// Work states (Checking / Downloading / …) render nothing.
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
        _ => return,
    };
    if ui.button(s.close).clicked() {
        ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
    }
    if ui.button(s.help).clicked() {
        let _ = open::that(i18n::DISCORD_URL);
    }
    if matches!(offline_button, Some(true)) && ui.button(s.offline_mode).clicked() {
        if let Some(cb) = on_offline_mode {
            cb();
        }
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
