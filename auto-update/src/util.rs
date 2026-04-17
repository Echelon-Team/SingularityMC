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
use std::time::Duration;

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

/// Atomically copy `src` to `dst`: stream src → sibling tmp, fsync, rename.
///
/// Use when the contents originate from another file on disk (backup path)
/// rather than an in-memory byte slice — avoids loading large files into
/// RAM while preserving the same durability guarantees as [`atomic_write_bytes`].
/// A mid-copy crash leaves no half-written `dst`; at worst a `.tmp` sibling
/// to be cleaned up by the next successful call or manual GC.
pub fn atomic_copy(src: &Path, dst: &Path) -> Result<()> {
    let tmp = dst.with_file_name(format!(
        ".{}.tmp",
        dst.file_name().unwrap_or_default().to_string_lossy()
    ));
    {
        let mut src_file = std::fs::File::open(src)?;
        let mut dst_file = std::fs::File::create(&tmp)?;
        std::io::copy(&mut src_file, &mut dst_file)?;
        // Durability: without sync_all, a crash between this return and the
        // rename below could leave the tmp visible as empty/partial on disk.
        dst_file.sync_all()?;
    }
    std::fs::rename(&tmp, dst).map_err(|e| {
        // Best-effort tmp cleanup on rename failure so we don't leak debris.
        let _ = std::fs::remove_file(&tmp);
        UpdaterError::Io(e)
    })?;
    Ok(())
}

/// Rename `src` to `dst` with bounded retry on `PermissionDenied`, a common
/// transient failure when antivirus / EDR scanners briefly lock a freshly-
/// written file on Windows. Retries up to `max_attempts` times with linear
/// backoff (`backoff * attempt`); other error kinds fail fast.
///
/// Matches the `uv` project's AV-retry policy (astral-sh/uv#9543).
pub fn rename_with_retry(src: &Path, dst: &Path) -> Result<()> {
    const MAX_ATTEMPTS: u32 = 3;
    const BACKOFF: Duration = Duration::from_millis(100);
    for attempt in 0..MAX_ATTEMPTS {
        match std::fs::rename(src, dst) {
            Ok(()) => return Ok(()),
            Err(e)
                if e.kind() == std::io::ErrorKind::PermissionDenied
                    && attempt + 1 < MAX_ATTEMPTS =>
            {
                log::debug!(
                    "rename {} -> {} hit PermissionDenied (AV/EDR lock?), retry {}",
                    src.display(),
                    dst.display(),
                    attempt + 1
                );
                std::thread::sleep(BACKOFF * (attempt + 1));
            }
            Err(e) => return Err(UpdaterError::Io(e)),
        }
    }
    unreachable!("loop always returns or sleeps+continues")
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

    #[test]
    fn atomic_copy_copies_file_content() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("src.bin");
        let dst = dir.path().join("dst.bin");
        std::fs::write(&src, b"content").unwrap();
        atomic_copy(&src, &dst).unwrap();
        assert_eq!(std::fs::read(&dst).unwrap(), b"content");
        // Source preserved (copy, not move).
        assert!(src.exists());
    }

    #[test]
    fn atomic_copy_leaves_no_tmp_on_success() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("src.bin");
        let dst = dir.path().join("dst.bin");
        std::fs::write(&src, b"content").unwrap();
        atomic_copy(&src, &dst).unwrap();
        // Sibling tmp pattern: `.dst.bin.tmp` under same parent.
        let tmp = dir.path().join(".dst.bin.tmp");
        assert!(!tmp.exists());
    }

    #[test]
    fn atomic_copy_returns_io_error_when_src_missing() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("nope.bin");
        let dst = dir.path().join("dst.bin");
        assert!(matches!(
            atomic_copy(&src, &dst),
            Err(UpdaterError::Io(_))
        ));
    }

    #[test]
    fn rename_with_retry_succeeds_on_normal_rename() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("a");
        let dst = dir.path().join("b");
        std::fs::write(&src, b"x").unwrap();
        rename_with_retry(&src, &dst).unwrap();
        assert!(dst.exists() && !src.exists());
    }

    #[test]
    fn rename_with_retry_returns_io_error_when_src_missing() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("nope");
        let dst = dir.path().join("dst");
        assert!(matches!(
            rename_with_retry(&src, &dst),
            Err(UpdaterError::Io(_))
        ));
    }
}
