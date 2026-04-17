//! Persistent runtime config for the auto-update binary. Lives at
//! `{install_dir}/auto-update-config.json`.
//!
//! **Read resilience:** a missing file returns defaults without logging
//! (normal first-launch state). A corrupt or unreadable file logs a warn and
//! also returns defaults — the auto-updater MUST keep running (it is the
//! only update path) even when its own config has rotted. The user's
//! customization IS lost in the corrupt case; the warn log gives post-mortem
//! context.
//!
//! **Write atomicity:** [`save`] delegates to the `atomicwrites` crate. On
//! Unix this is a standard `write(tmp) → fsync(tmp) → rename(tmp, target)`
//! flow. On Windows it additionally passes `MOVEFILE_WRITE_THROUGH` to
//! `MoveFileExW`, which `std::fs::rename` alone does not — without that flag
//! the directory-entry update itself is buffered and a crash between
//! rename-return and physical-commit can lose the swap. `atomicwrites` also
//! retries transient `ERROR_SHARING_VIOLATION` races caused by AV scanners
//! briefly locking a freshly-written temp file.
//!
//! **Filesystem caveat:** atomicity is a local-NTFS/ReFS property. Installing
//! the launcher onto an SMB/network share inherits the remote server's flush
//! semantics (the SMB redirector can ACK FlushFileBuffers before the server
//! has committed to platter). Network installs are an unsupported configuration.

use crate::{util, Result};
use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};

/// Release channel the launcher follows. Stable is the default; Beta is an
/// opt-in pre-release track. `#[non_exhaustive]` future-proofs a hypothetical
/// crate split where external matchers would otherwise become semver-breaking
/// when new channels (Nightly, Canary) land.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum Channel {
    #[default]
    Stable,
    Beta,
}

/// UI language selection.
///
/// Tri-state encoded as a single enum rather than `Option<Language>` so
/// "auto-detect" is a first-class, greppable variant — and so `Default`
/// derives directly instead of relying on `Option::None`'s incidental default.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum LanguagePreference {
    /// Follow the OS locale via `sys-locale` (Task 2.9 wiring).
    #[default]
    Auto,
    Pl,
    En,
}

/// Persisted configuration. Every field has a `#[serde(default)]` so we
/// tolerate older files missing a field the current schema has added
/// (forward-/backward-compat read path).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct AutoUpdateConfig {
    #[serde(default)]
    pub channel: Channel,
    #[serde(default)]
    pub language: LanguagePreference,
}

/// File location given the launcher install root.
#[must_use]
pub fn config_path(install_dir: &Path) -> PathBuf {
    install_dir.join("auto-update-config.json")
}

/// Load the persistent config, falling back to defaults on any failure.
///
/// See module-level doc for why this is infallible: the auto-update binary
/// must run in every scenario so it can at least ship a fresh launcher that
/// fixes the broken config. Corrupt/unreadable paths are logged via `log::warn`.
#[must_use]
pub fn load(install_dir: &Path) -> AutoUpdateConfig {
    let path = config_path(install_dir);
    match std::fs::read_to_string(&path) {
        Ok(content) => match serde_json::from_str::<AutoUpdateConfig>(&content) {
            Ok(cfg) => cfg,
            Err(e) => {
                log::warn!(
                    "auto-update-config.json at {} is corrupt: {e}; using defaults",
                    path.display()
                );
                AutoUpdateConfig::default()
            }
        },
        Err(e) if e.kind() == io::ErrorKind::NotFound => AutoUpdateConfig::default(),
        Err(e) => {
            log::warn!(
                "Failed to read auto-update-config.json at {}: {e}; using defaults",
                path.display()
            );
            AutoUpdateConfig::default()
        }
    }
}

/// Persist the config atomically. Errors: `Io` for filesystem / permission
/// issues, `Json` for (extremely unlikely) serialization failure.
pub fn save(install_dir: &Path, config: &AutoUpdateConfig) -> Result<()> {
    let path = config_path(install_dir);
    let parent = path
        .parent()
        .expect("config_path always has a parent dir by construction");
    std::fs::create_dir_all(parent)?;
    let content = serde_json::to_string_pretty(config)?;
    util::atomic_write_bytes(&path, content.as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::UpdaterError;
    use tempfile::TempDir;

    #[test]
    fn load_missing_file_returns_default() {
        let dir = TempDir::new().unwrap();
        let config = load(dir.path());
        assert_eq!(config, AutoUpdateConfig::default());
        assert_eq!(config.channel, Channel::Stable);
        assert_eq!(config.language, LanguagePreference::Auto);
    }

    #[test]
    fn load_corrupt_json_returns_default_not_error() {
        let dir = TempDir::new().unwrap();
        std::fs::write(config_path(dir.path()), "{ not valid json").unwrap();
        // Hits the JSON-parse branch. Pins "always returns usable config" contract.
        assert_eq!(load(dir.path()), AutoUpdateConfig::default());
    }

    #[test]
    fn load_io_error_path_returns_default() {
        // Path IS a directory, not a file → read_to_string returns Err with
        // PermissionDenied / IsADirectory (platform-dependent). This pins the
        // IO-error branch of load() as independent from the JSON-parse branch
        // so a future refactor cannot collapse them without breaking a test.
        let dir = TempDir::new().unwrap();
        std::fs::create_dir(config_path(dir.path())).unwrap();
        assert_eq!(load(dir.path()), AutoUpdateConfig::default());
    }

    #[test]
    fn save_then_load_roundtrip_all_combinations() {
        // 2 channels × 3 language preferences = 6 configurations. One loop
        // catches per-variant serde mismatches without new deps.
        for channel in [Channel::Stable, Channel::Beta] {
            for language in [LanguagePreference::Auto, LanguagePreference::Pl, LanguagePreference::En] {
                let dir = TempDir::new().unwrap();
                let original = AutoUpdateConfig { channel, language };
                save(dir.path(), &original).unwrap();
                let loaded = load(dir.path());
                assert_eq!(loaded, original, "roundtrip failed for {channel:?} / {language:?}");
            }
        }
    }

    #[test]
    fn save_produces_pretty_json() {
        let dir = TempDir::new().unwrap();
        save(dir.path(), &AutoUpdateConfig::default()).unwrap();
        let raw = std::fs::read_to_string(config_path(dir.path())).unwrap();
        assert!(
            raw.contains('\n'),
            "expected pretty (multi-line) JSON, got: {raw}"
        );
    }

    #[test]
    fn save_creates_missing_parent_dir() {
        let dir = TempDir::new().unwrap();
        let nested = dir.path().join("deeply").join("nested");
        save(&nested, &AutoUpdateConfig::default()).unwrap();
        assert!(config_path(&nested).exists());
    }

    #[test]
    fn save_returns_io_error_when_parent_is_a_file() {
        // Parent of config_path is a pre-existing FILE, not a dir →
        // create_dir_all returns NotADirectory/AlreadyExists → UpdaterError::Io.
        // Pins the error-propagation path of save() cross-platform.
        let dir = TempDir::new().unwrap();
        let fake_parent = dir.path().join("im_a_file");
        std::fs::write(&fake_parent, "").unwrap();
        let result = save(&fake_parent, &AutoUpdateConfig::default());
        assert!(
            matches!(result, Err(UpdaterError::Io(_))),
            "expected Err(UpdaterError::Io), got {result:?}"
        );
    }

    #[test]
    fn channel_serializes_lowercase() {
        assert_eq!(serde_json::to_string(&Channel::Stable).unwrap(), "\"stable\"");
        assert_eq!(serde_json::to_string(&Channel::Beta).unwrap(), "\"beta\"");
    }

    #[test]
    fn language_serializes_lowercase() {
        assert_eq!(serde_json::to_string(&LanguagePreference::Auto).unwrap(), "\"auto\"");
        assert_eq!(serde_json::to_string(&LanguagePreference::Pl).unwrap(), "\"pl\"");
        assert_eq!(serde_json::to_string(&LanguagePreference::En).unwrap(), "\"en\"");
    }

    #[test]
    fn unknown_field_is_ignored_for_forward_compat() {
        // Newer launcher version writes a field this code doesn't know about —
        // must deserialize known fields and drop the unknown silently. Relies
        // on serde's default behavior (no `#[serde(deny_unknown_fields)]`).
        let dir = TempDir::new().unwrap();
        std::fs::write(
            config_path(dir.path()),
            r#"{"channel":"beta","future_field":123,"language":"en"}"#,
        )
        .unwrap();
        let config = load(dir.path());
        assert_eq!(config.channel, Channel::Beta);
        assert_eq!(config.language, LanguagePreference::En);
    }

    #[test]
    fn missing_field_gets_serde_default_for_backward_compat() {
        // Older launcher wrote a config without the `language` field.
        // `#[serde(default)]` on that field should fill in LanguagePreference::Auto.
        let dir = TempDir::new().unwrap();
        std::fs::write(config_path(dir.path()), r#"{"channel":"beta"}"#).unwrap();
        let config = load(dir.path());
        assert_eq!(config.channel, Channel::Beta);
        assert_eq!(config.language, LanguagePreference::Auto);

        // Empty object → full defaults.
        std::fs::write(config_path(dir.path()), r"{}").unwrap();
        assert_eq!(load(dir.path()), AutoUpdateConfig::default());
    }

    #[test]
    fn duplicate_keys_first_one_wins() {
        // Pin current serde_json semantics: JSON spec doesn't define behavior
        // for duplicate keys (RFC 8259 §4 says "implementations differ"), and
        // serde_json currently picks the first occurrence. If a future serde
        // upgrade flips to last-wins or deny-duplicates, this test flags it
        // so we can evaluate the semantic implication rather than silently
        // inherit the change.
        let dir = TempDir::new().unwrap();
        std::fs::write(
            config_path(dir.path()),
            r#"{"channel":"stable","channel":"beta"}"#,
        )
        .unwrap();
        assert_eq!(load(dir.path()).channel, Channel::Stable);
    }
}
