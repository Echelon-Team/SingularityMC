//! Library-level error taxonomy. Every Phase 2 module returns
//! [`Result<T, UpdaterError>`], so the top-level state machine and UI layer
//! get a single exhaustive `match` point for localized messages and retry
//! policy decisions.
//!
//! Split with `anyhow`: this module is the typed contract for library code;
//! `main.rs` may use `anyhow::Result` for one-off glue where typed variants
//! would be premature.
//!
//! [`non_exhaustive`] lets future tasks add variants (manifest, permissions,
//! signature verification, ...) without making each addition a semver-
//! breaking change — which matters if this crate is ever split into a
//! workspace or reused.

use thiserror::Error;

/// Convenient alias so call sites write `-> Result<Manifest>` not
/// `-> Result<Manifest, UpdaterError>`.
pub type Result<T> = std::result::Result<T, UpdaterError>;

#[derive(Debug, Error)]
#[non_exhaustive]
pub enum UpdaterError {
    /// HTTP/network failure (DNS, TLS, connection reset, non-success status
    /// surfaced by reqwest). Wraps [`reqwest::Error`] via `From` for use with
    /// the `?` operator.
    #[error("network error: {0}")]
    Network(#[from] reqwest::Error),

    /// Filesystem I/O failure (open, read, write, rename, permissions). Wraps
    /// [`std::io::Error`] via `From`.
    #[error("filesystem I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// JSON (de)serialization failure (manifest, config). Wraps
    /// [`serde_json::Error`] via `From`.
    #[error("JSON (de)serialization error: {0}")]
    Json(#[from] serde_json::Error),

    /// Downloaded artifact hash does not match the manifest-declared hash.
    /// This is a correctness / possible tampering signal; never silently swallowed.
    #[error("hash mismatch for {path}: expected {expected}, got {actual}")]
    HashMismatch {
        path: String,
        expected: String,
        actual: String,
    },

    /// Manifest is present but semantically invalid (missing required field,
    /// unknown version, contradictory entries).
    #[error("manifest validation failed: {0}")]
    Manifest(String),

    /// Insufficient filesystem permissions to perform the requested write
    /// (swap executable, write to install dir, rename backup). Typically
    /// signals the user started the launcher without write access to its
    /// install location.
    #[error("insufficient permissions: {0}")]
    Permission(String),
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;

    #[test]
    fn hash_mismatch_display_includes_all_fields() {
        let err = UpdaterError::HashMismatch {
            path: "launcher.jar".into(),
            expected: "abc".into(),
            actual: "xyz".into(),
        };
        let msg = format!("{err}");
        assert!(msg.contains("launcher.jar"));
        assert!(msg.contains("abc"));
        assert!(msg.contains("xyz"));
    }

    #[test]
    fn io_error_converts_via_from() {
        let io_err = io::Error::new(io::ErrorKind::NotFound, "boom");
        let wrapped: UpdaterError = io_err.into();
        assert!(matches!(wrapped, UpdaterError::Io(_)));
    }

    #[test]
    fn result_alias_works() {
        fn faulty() -> Result<()> {
            Err(UpdaterError::Manifest("bad".into()))
        }
        assert!(faulty().is_err());
    }
}
