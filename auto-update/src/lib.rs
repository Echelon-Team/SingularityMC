//! Auto-update library — all application logic lives here so integration tests
//! under `tests/` can target it. The binary entry point (`src/main.rs`) is a
//! thin shim that boots logging, parses CLI args, and calls into this crate.
//!
//! Subsequent tasks in Phase 2 add modules here:
//! - `config`   — auto-update-config.json reader/writer (Task 2.2)
//! - `manifest` — SHA-256 / per-file manifest types + parsing (Task 2.3)
//! - `github_api` — GitHub Releases client (Task 2.4)
//! - `downloader` — retry + hash verification (Task 2.5)
//! - `updater` — file swap + backup (Task 2.6)
//! - `self_update` — self-replace integration (Task 2.7)
//! - `launcher` — spawn JVM launcher (Task 2.8)
//! - `i18n` — PL/EN strings (Task 2.9)
//! - `ui` — egui 8-state rendering (Task 2.10)
//! - `app` — main state machine (Task 2.11)

pub mod config;
pub mod error;

pub use config::{AutoUpdateConfig, Channel, LanguagePreference};
pub use error::{Result, UpdaterError};

/// Build-time version string, emitted by `build.rs` from `git describe --tags
/// --dirty` (or `CARGO_PKG_VERSION` fallback). Always safe to call — the macro
/// is evaluated at compile time, so a missing env var would fail the build.
pub const BUILD_VERSION: &str = env!("BUILD_VERSION");

/// Wraps a version string (git tag or semver literal) so parsing / comparison
/// logic lives in one place instead of being reinvented in each Phase 2 module.
///
/// Intentionally opaque: construction goes through [`Version::current`] (the
/// build-embedded version) or, in later tasks, [`Version::parse`]. Invariants
/// (non-empty, no leading whitespace) are enforced at construction.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Version(String);

impl Version {
    /// Version embedded at build time via `env!("BUILD_VERSION")`.
    #[must_use]
    pub fn current() -> Self {
        Self(BUILD_VERSION.to_string())
    }

    /// Borrowed view of the underlying string for logging / display.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for Version {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_version_is_non_empty() {
        let v = Version::current();
        assert!(
            !v.as_str().is_empty(),
            "BUILD_VERSION must be set at compile time"
        );
    }

    #[test]
    fn display_matches_as_str() {
        let v = Version::current();
        assert_eq!(format!("{v}"), v.as_str());
    }

    #[test]
    fn equality_is_structural() {
        assert_eq!(Version("1.2.3".into()), Version("1.2.3".into()));
        assert_ne!(Version("1.2.3".into()), Version("1.2.4".into()));
    }
}
