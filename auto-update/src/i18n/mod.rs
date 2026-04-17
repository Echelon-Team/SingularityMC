//! UI strings for PL + EN. Selection flow:
//!
//! 1. Config holds [`LanguagePreference`](crate::LanguagePreference)
//!    (Auto / Pl / En), stored in `auto-update-config.json`.
//! 2. [`resolve_lang`] turns that into a concrete [`Lang`] (Pl / En) —
//!    on `Auto` it falls back to `sys_locale` detection.
//! 3. [`strings`] returns the `&'static Strings` bundle for static labels;
//!    formatted messages with runtime values go through dedicated helpers
//!    like [`downloading_percent`] / [`retry_in_seconds_str`] that inline
//!    the `format!` literal.
//!
//! **Why helpers for formatted strings:** `format!` requires a string
//! LITERAL as its first argument — `format!(some_str, ...)` is a compile
//! error. Keeping placeholders in a `Strings` field would force every
//! call site to either (a) use brittle `.replace("{}", ...)` substitution
//! or (b) duplicate the `match lang { ... }` dispatch. Helpers localize
//! the translation + formatting pair in one place and return `String`,
//! which is the natural egui label input.
//!
//! **Why no Polish plural handling:** the spec phrases formatted strings
//! so the numeric value sits before an abbreviation (`"{}s"` /`"{}%"`)
//! which is grammatically neutral in Polish — sidesteps the 3-form
//! plural (one / few / other) that would otherwise require CLDR rules.
//! If a long-form variant like `"{} sekund"` is ever added, helper
//! functions are the right place to implement the plural logic.
//!
//! **No runtime loading** — static bundles live in `.rodata`; only the
//! initial `sys_locale::get_locale()` call allocates a `String` which
//! we consume and drop before returning `Lang`.

pub mod en;
pub mod pl;

use crate::LanguagePreference;
use sys_locale::get_locale;

/// Discord invite URL (spec 4.x "Pomoc"/"Help" button). Single source of
/// truth across languages — NOT localized content, treated as module
/// constant rather than a `Strings` field to prevent drift between PL/EN
/// bundles and make the "this is config, not translation" intent explicit.
pub const DISCORD_URL: &str = "https://discord.gg/RBkJhH4xuE";

/// Concrete display language after auto-detection resolves. Exhaustive —
/// adding a variant MUST force every `match lang { ... }` across the
/// crate to add a new arm (and, by design, a new bundle file), which is
/// the correct behavior: shipping an untranslated UI is a bug, not a
/// fallback.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lang {
    Pl,
    En,
}

/// Static UI string slots — labels / short messages with no runtime
/// interpolation. Messages that take runtime values (percentage, seconds
/// remaining) live in helper functions below because `format!`'s first
/// argument must be a literal.
#[derive(Debug, Clone, Copy)]
pub struct Strings {
    // --- status screens (static labels) ---
    pub checking: &'static str,
    pub verifying: &'static str,
    pub installing: &'static str,
    pub starting: &'static str,
    // --- error screens (static labels) ---
    pub no_internet: &'static str,
    pub download_failed: &'static str,
    // --- buttons / chrome ---
    pub help: &'static str,
    pub offline_mode: &'static str,
    pub retry: &'static str,
    /// Dismiss button shown on the `FatalError` screen. Without this,
    /// terminal errors leave the user with only the window's X as a way
    /// out — which reads as "the app froze" rather than "I understand,
    /// I've read the error, I'm closing it."
    pub close: &'static str,
}

/// Resolve a stored [`LanguagePreference`] to a concrete [`Lang`].
#[must_use]
pub fn resolve_lang(preference: LanguagePreference) -> Lang {
    match preference {
        LanguagePreference::Pl => Lang::Pl,
        LanguagePreference::En => Lang::En,
        LanguagePreference::Auto => detect_system_lang(),
    }
}

/// Inspect OS locale and pick the best-match bundle. Separated for
/// testability (see [`is_polish_locale`]).
fn detect_system_lang() -> Lang {
    match get_locale().as_deref() {
        Some(loc) if is_polish_locale(loc) => Lang::Pl,
        _ => Lang::En,
    }
}

/// Prefix-match covers Windows BCP-47 (`pl-PL`), Linux LANG
/// (`pl_PL.UTF-8`), short code (`pl`), and CLDR legacy (`plk`).
fn is_polish_locale(loc: &str) -> bool {
    loc.starts_with("pl")
}

/// Borrow the static string bundle for a resolved language. Callers
/// should cache this once at UI startup, not re-call per frame —
/// `resolve_lang(Auto)` triggers a `get_locale()` syscall each time.
#[must_use]
pub fn strings(lang: Lang) -> &'static Strings {
    match lang {
        Lang::Pl => pl::STRINGS,
        Lang::En => en::STRINGS,
    }
}

/// "Downloading update... {N}%" — formatted per language.
#[must_use]
pub fn downloading_percent(lang: Lang, percent: u8) -> String {
    match lang {
        Lang::Pl => format!("Pobieranie aktualizacji... {percent}%"),
        Lang::En => format!("Downloading update... {percent}%"),
    }
}

/// "Retry in {N}s" — formatted per language. Uses abbreviation `s` so
/// the Polish 3-form plural (sekunda / sekundy / sekund) does not apply.
#[must_use]
pub fn retry_in_seconds_str(lang: Lang, secs: u32) -> String {
    match lang {
        Lang::Pl => format!("Próba za {secs}s"),
        Lang::En => format!("Retry in {secs}s"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // --- resolve_lang + locale matching ---

    #[test]
    fn resolve_lang_direct_choices() {
        assert_eq!(resolve_lang(LanguagePreference::Pl), Lang::Pl);
        assert_eq!(resolve_lang(LanguagePreference::En), Lang::En);
    }

    #[test]
    fn resolve_lang_auto_falls_through_to_detect() {
        let lang = resolve_lang(LanguagePreference::Auto);
        assert!(matches!(lang, Lang::Pl | Lang::En));
    }

    #[test]
    fn is_polish_locale_matches_common_forms() {
        assert!(is_polish_locale("pl"));
        assert!(is_polish_locale("pl-PL"));
        assert!(is_polish_locale("pl_PL.UTF-8"));
        assert!(is_polish_locale("plk"));
    }

    #[test]
    fn is_polish_locale_rejects_others() {
        assert!(!is_polish_locale("en"));
        assert!(!is_polish_locale("en-US"));
        assert!(!is_polish_locale("de-DE"));
        assert!(!is_polish_locale(""));
        assert!(!is_polish_locale("spl-ish"));
    }

    // --- static bundles ---

    #[test]
    fn strings_returns_pl_bundle_for_pl() {
        let s = strings(Lang::Pl);
        assert_eq!(s.help, "Pomoc");
        assert_eq!(s.retry, "Ponów");
        assert_eq!(s.offline_mode, "TRYB OFFLINE");
    }

    #[test]
    fn strings_returns_en_bundle_for_en() {
        let s = strings(Lang::En);
        assert_eq!(s.help, "Help");
        assert_eq!(s.retry, "Retry");
        assert_eq!(s.offline_mode, "OFFLINE MODE");
    }

    #[test]
    fn all_static_slots_are_non_empty_in_both_bundles() {
        for lang in [Lang::Pl, Lang::En] {
            let s = strings(lang);
            for (name, v) in [
                ("checking", s.checking),
                ("verifying", s.verifying),
                ("installing", s.installing),
                ("starting", s.starting),
                ("no_internet", s.no_internet),
                ("download_failed", s.download_failed),
                ("help", s.help),
                ("offline_mode", s.offline_mode),
                ("retry", s.retry),
                ("close", s.close),
            ] {
                assert!(!v.is_empty(), "{lang:?}.{name} must not be empty");
            }
        }
    }

    // --- DISCORD_URL constant ---

    #[test]
    fn discord_url_constant_matches_spec() {
        // Spec-declared URL — pinning the literal so rotation is a
        // deliberate change that shows up in a single diff.
        assert_eq!(DISCORD_URL, "https://discord.gg/RBkJhH4xuE");
    }

    // --- formatted helpers ---

    #[test]
    fn downloading_percent_formats_pl() {
        let s = downloading_percent(Lang::Pl, 42);
        assert_eq!(s, "Pobieranie aktualizacji... 42%");
    }

    #[test]
    fn downloading_percent_formats_en() {
        let s = downloading_percent(Lang::En, 42);
        assert_eq!(s, "Downloading update... 42%");
    }

    #[test]
    fn downloading_percent_handles_bounds() {
        // 0 and 100 are legitimate UI states (download just started /
        // just finished). Neither should format weirdly.
        assert!(downloading_percent(Lang::Pl, 0).contains("0%"));
        assert!(downloading_percent(Lang::Pl, 100).contains("100%"));
        assert!(downloading_percent(Lang::En, 0).contains("0%"));
        assert!(downloading_percent(Lang::En, 100).contains("100%"));
    }

    #[test]
    fn retry_in_seconds_str_formats_pl() {
        let s = retry_in_seconds_str(Lang::Pl, 5);
        assert_eq!(s, "Próba za 5s");
    }

    #[test]
    fn retry_in_seconds_str_formats_en() {
        let s = retry_in_seconds_str(Lang::En, 5);
        assert_eq!(s, "Retry in 5s");
    }

    #[test]
    fn retry_in_seconds_str_zero_and_large_values() {
        assert!(retry_in_seconds_str(Lang::Pl, 0).contains("0s"));
        assert!(retry_in_seconds_str(Lang::En, 9999).contains("9999s"));
    }
}
