//! Small shared primitives reused across the auto-update crate.
//!
//! Currently: atomic-file-write, used by `config::save` and
//! `manifest::save_local`. Extracted here so the 6-line `AtomicFile` +
//! `Error::{Internal,User}` collapse lives in exactly one place — any future
//! tuning (retry counts, write-through flags, telemetry hooks) lands here
//! rather than being copy-pasted into every module that persists state.

use crate::{Result, UpdaterError};
use atomicwrites::{AtomicFile, OverwriteBehavior};
use std::io::Write;
use std::path::Path;

/// Atomically write `bytes` to `path`.
///
/// On Windows: delegates to `MoveFileExW` with `MOVEFILE_WRITE_THROUGH`
/// + `MOVEFILE_REPLACE_EXISTING` (via `atomicwrites`). Retries transient
/// `ERROR_SHARING_VIOLATION` from AV scanners briefly locking the tmp file.
///
/// On Unix: standard `write(tmp) → fsync(tmp) → rename(tmp, target)`.
///
/// Caller is responsible for ensuring `path.parent()` exists — this fn does
/// not `create_dir_all` so callers can make their own mkdir error visible.
pub fn atomic_write_bytes(path: &Path, bytes: &[u8]) -> Result<()> {
    AtomicFile::new(path, OverwriteBehavior::AllowOverwrite)
        .write(|f| f.write_all(bytes))
        .map_err(|e| match e {
            atomicwrites::Error::Internal(err) | atomicwrites::Error::User(err) => {
                UpdaterError::Io(err)
            }
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn writes_bytes_to_path() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("out.txt");
        atomic_write_bytes(&path, b"hello").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "hello");
    }

    #[test]
    fn overwrites_existing() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("out.txt");
        std::fs::write(&path, "old").unwrap();
        atomic_write_bytes(&path, b"new").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "new");
    }

    #[test]
    fn returns_io_error_when_parent_missing() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("no_such_dir").join("out.txt");
        let result = atomic_write_bytes(&path, b"x");
        assert!(matches!(result, Err(UpdaterError::Io(_))));
    }
}
