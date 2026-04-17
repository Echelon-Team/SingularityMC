//! Self-replacement of the running auto-update binary.
//!
//! When the launcher update requires a newer auto-update binary itself
//! (spec 4.x: `min_auto_update_version` in the manifest), the state
//! machine downloads the new auto-update executable to a sibling path
//! (`auto-update.exe.new`) and then triggers [`apply_pending`]. This module
//! delegates the swap to the `self-replace` crate, spawns a fresh copy
//! with the same CLI args, and signals the caller to exit so the
//! replaced process can terminate.
//!
//! **Swap mechanics (self-replace 1.5):** the crate uses `std::fs::rename`
//! plus `FILE_FLAG_DELETE_ON_CLOSE` to atomically replace the running
//! executable on Windows. Works even with the binary currently executing
//! because the OS keeps the old image mapped for the running process;
//! newly-spawned processes load the new image.
//!
//! **Error taxonomy:** swap failure vs respawn failure are distinct
//! [`UpdaterError`] variants — the state machine needs to distinguish
//! "swap didn't happen, safe to retry" from "swap committed, manual
//! restart needed".
//!
//! **Leftover artifact cleanup:** aborted self-replace cycles can leave
//! hidden sibling files named `.__selfdelete__.exe`, `.__relocated__.exe`,
//! and `.__temp__.exe` (all dot-prefixed, each with a 32-char random
//! suffix). [`cleanup_stale_files`] sweeps them on startup. Best-effort —
//! logs but doesn't fail the update on cleanup errors.

use crate::{Result, UpdaterError};
use std::path::{Path, PathBuf};

/// Conventional suffix for the pending self-update binary placed next to
/// the running exe (e.g. `auto-update.exe` -> `auto-update.exe.new`).
const PENDING_SUFFIX: &str = "new";

/// Pattern fragments identifying `self-replace` leftover artifacts. These
/// appear as `.__selfdelete__*`, `.__relocated__*`, `.__temp__*` sibling
/// files (dot-prefixed, with a random 32-char suffix on Windows). Source:
/// self-replace crate src/windows.rs.
const STALE_MARKERS: &[&str] = &["__selfdelete__", "__relocated__", "__temp__"];

/// Apply a pending self-update sitting next to the running binary, if any.
///
/// Returns `Ok(true)` when a swap was applied AND a fresh process was
/// spawned — **caller MUST exit promptly** so the replaced process
/// terminates and any open handles on the old image are released.
///
/// Returns `Ok(false)` when no pending update was found (normal steady-
/// state path; caller continues with the launcher-update flow).
///
/// Errors are split so the state machine can pick the correct recovery
/// path: [`UpdaterError::SelfUpdateSwapFailed`] means the swap didn't
/// commit (binary untouched, retry-safe);
/// [`UpdaterError::SelfUpdateRespawnFailed`] means the swap committed but
/// the new process couldn't start (user must manually restart).
#[must_use = "caller must exit the process when this returns Ok(true)"]
pub fn apply_pending() -> Result<bool> {
    let current = std::env::current_exe().map_err(UpdaterError::Io)?;
    let pending = pending_path(&current);
    if !pending.exists() {
        return Ok(false);
    }

    log::info!("applying pending self-update: {}", pending.display());
    self_replace::self_replace(&pending).map_err(|e| {
        UpdaterError::SelfUpdateSwapFailed(std::io::Error::other(format!(
            "self_replace failed: {e}"
        )))
    })?;
    log::info!(
        "self_replace committed; spawning new process from {}",
        current.display()
    );

    // Respawn with identical CLI args. Detach stdio + Windows console so
    // the replaced parent exiting doesn't tear down the child's handles.
    let mut cmd = std::process::Command::new(&current);
    cmd.args(std::env::args().skip(1))
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // DETACHED_PROCESS (0x00000008): new process has no console.
        // CREATE_NEW_PROCESS_GROUP (0x00000200): isolates from Ctrl-C sent
        // to the parent's console group while parent is still exiting.
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        cmd.creation_flags(DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP);
    }

    cmd.spawn().map_err(UpdaterError::SelfUpdateRespawnFailed)?;
    Ok(true)
}

/// Compute the conventional pending-update path for `current_exe`:
/// appends `.new` to whatever extension the current binary has (or plain
/// `.new` if extensionless, as on Linux). Public for tests and so the
/// state machine can reference the same filename convention.
#[must_use]
pub fn pending_path(current: &Path) -> PathBuf {
    let new_ext = match current.extension() {
        Some(ext) => format!("{}.{PENDING_SUFFIX}", ext.to_string_lossy()),
        None => PENDING_SUFFIX.to_string(),
    };
    current.with_extension(new_ext)
}

/// Sweep `self-replace` leftover artifacts from the install dir.
///
/// Targets: filenames containing `__selfdelete__`, `__relocated__`, or
/// `__temp__` — the three markers self-replace embeds in its transient
/// sibling files (see module doc for actual naming pattern). Best-effort:
/// per-entry failures log `warn`, never propagate. Subdirectories
/// matching the patterns are NOT recursively deleted — `remove_file`
/// rejects directories, keeping user data safe from accidental recursion.
pub fn cleanup_stale_files(install_dir: &Path) {
    if !install_dir.exists() {
        return;
    }
    let read_dir = match std::fs::read_dir(install_dir) {
        Ok(r) => r,
        Err(e) => {
            log::warn!(
                "cleanup_stale_files: read_dir {} failed: {e}",
                install_dir.display()
            );
            return;
        }
    };
    for entry in read_dir {
        let entry = match entry {
            Ok(e) => e,
            Err(e) => {
                log::warn!("cleanup_stale_files: entry read failed: {e}");
                continue;
            }
        };
        let name = entry.file_name().to_string_lossy().into_owned();
        let is_stale = STALE_MARKERS.iter().any(|m| name.contains(m));
        if !is_stale {
            continue;
        }
        log::info!("removing stale self-replace artifact: {name}");
        if let Err(e) = std::fs::remove_file(entry.path()) {
            log::warn!("failed to remove stale file {name}: {e}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    // --- pending_path ---

    #[test]
    fn pending_path_appends_new_to_windows_exe() {
        let p = pending_path(Path::new("C:/install/auto-update.exe"));
        assert_eq!(p, Path::new("C:/install/auto-update.exe.new"));
    }

    #[test]
    fn pending_path_appends_new_to_extensionless_unix_binary() {
        let p = pending_path(Path::new("/usr/local/bin/auto-update"));
        assert_eq!(p, Path::new("/usr/local/bin/auto-update.new"));
    }

    #[test]
    fn pending_path_preserves_multi_component_extension_semantics() {
        // Path::extension returns only LAST component; with_extension
        // replaces it. "auto-update.v2.exe" → ext "exe" → ".exe.new" →
        // "auto-update.v2.exe.new". Pin this.
        let p = pending_path(Path::new("auto-update.v2.exe"));
        assert_eq!(p, Path::new("auto-update.v2.exe.new"));
    }

    // --- cleanup_stale_files ---

    #[test]
    fn cleanup_removes_self_replace_markers() {
        let dir = TempDir::new().unwrap();
        // Self-replace actual artifacts (dot-prefixed, 32-char random).
        std::fs::write(dir.path().join(".auto-update.__selfdelete__abc123.exe"), b"").unwrap();
        std::fs::write(dir.path().join(".auto-update.__relocated__xyz789.exe"), b"").unwrap();
        std::fs::write(dir.path().join(".auto-update.__temp__aaaaaa.exe"), b"").unwrap();
        // Real files must NOT be touched.
        std::fs::write(dir.path().join("auto-update.exe"), b"running").unwrap();
        std::fs::write(dir.path().join("launcher.jar"), b"ok").unwrap();

        cleanup_stale_files(dir.path());

        assert!(!dir.path().join(".auto-update.__selfdelete__abc123.exe").exists());
        assert!(!dir.path().join(".auto-update.__relocated__xyz789.exe").exists());
        assert!(!dir.path().join(".auto-update.__temp__aaaaaa.exe").exists());
        assert!(dir.path().join("auto-update.exe").exists());
        assert!(dir.path().join("launcher.jar").exists());
    }

    #[test]
    fn cleanup_preserves_user_files_with_marker_substring_only_in_middle() {
        // Data-loss regression guard: a user/launcher file whose name
        // happens to contain letters similar to markers but NOT the exact
        // sentinel substring must NOT be removed. The patterns are
        // `__selfdelete__` etc. (double underscore both sides), which
        // reduces collision odds to essentially zero.
        let dir = TempDir::new().unwrap();
        std::fs::write(dir.path().join("world-selfdelete.dat"), b"user-data").unwrap();
        std::fs::write(dir.path().join("relocated-maps.json"), b"user-data").unwrap();
        std::fs::write(dir.path().join("temp-cache.bin"), b"user-data").unwrap();
        // Even a file that legitimately contains `.bak` (was swept by old
        // buggy pattern) must survive under the corrected contract.
        std::fs::write(dir.path().join("world.bak.tar"), b"user-backup").unwrap();

        cleanup_stale_files(dir.path());

        assert!(dir.path().join("world-selfdelete.dat").exists());
        assert!(dir.path().join("relocated-maps.json").exists());
        assert!(dir.path().join("temp-cache.bin").exists());
        assert!(dir.path().join("world.bak.tar").exists());
    }

    #[test]
    fn cleanup_is_no_op_when_install_dir_missing() {
        let missing = TempDir::new().unwrap().path().join("does-not-exist");
        cleanup_stale_files(&missing);
    }

    #[test]
    fn cleanup_tolerates_empty_dir() {
        let dir = TempDir::new().unwrap();
        cleanup_stale_files(dir.path());
    }

    #[test]
    fn cleanup_ignores_subdirectories() {
        // A directory whose NAME contains a stale marker must NOT be
        // recursively deleted. `remove_file` rejects dirs — we log-warn
        // and continue. Blocks whole-tree-nuking bugs.
        let dir = TempDir::new().unwrap();
        let stale_dir = dir.path().join(".auto-update.__temp__deadbeef.exe");
        std::fs::create_dir(&stale_dir).unwrap();
        std::fs::write(stale_dir.join("important"), b"keep").unwrap();

        cleanup_stale_files(dir.path());

        assert!(stale_dir.exists());
        assert!(stale_dir.join("important").exists());
    }

    // Unix-only: confirm that cleanup follows symlink-safe semantics —
    // removes the link, not the target. Matters because someone could
    // symlink a stale-named file to critical user data.
    #[cfg(unix)]
    #[test]
    fn cleanup_on_unix_removes_symlink_but_preserves_target() {
        let dir = TempDir::new().unwrap();
        let target = dir.path().join("important_user_data.txt");
        let symlink = dir.path().join(".__selfdelete__poison.exe");
        std::fs::write(&target, b"critical").unwrap();
        std::os::unix::fs::symlink(&target, &symlink).unwrap();

        cleanup_stale_files(dir.path());

        assert!(!symlink.exists(), "stale-named symlink should be removed");
        assert!(target.exists(), "symlink target must be preserved");
        assert_eq!(std::fs::read(&target).unwrap(), b"critical");
    }

    // --- apply_pending (unit-level bounded by cross-process constraints) ---

    #[test]
    fn apply_pending_returns_false_when_no_pending_next_to_current_exe() {
        let result = apply_pending();
        assert!(
            matches!(result, Ok(false)),
            "apply_pending must be a safe no-op when no pending file exists, got {result:?}"
        );
    }
}
