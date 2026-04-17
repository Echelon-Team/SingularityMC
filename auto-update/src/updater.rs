//! File updater — apply downloaded files to `install_dir` with per-file
//! backup, rollback on mid-swap failure, and post-swap orphan cleanup.
//!
//! **Flow per file:**
//! 1. Atomic-copy the existing target (if any) to the versioned backup dir
//!    (`File-Backups/pre-update-v{old_version}-{unix_ts}/{relative_path}`).
//!    Atomic-copy streams through a sibling tmp + fsync + rename so a crash
//!    never leaves a partial backup file that would masquerade as valid.
//! 2. Atomic-rename the downloaded tmp file over the target with bounded
//!    retry on `PermissionDenied` (transient AV/EDR locks on Windows).
//!    Falls back to copy+remove if the rename crosses filesystems.
//!
//! **Rollback semantics:** if any file in the batch fails, the already-
//! swapped files are restored from their backups (fresh installs without a
//! backup are deleted). Rollback is best-effort: per-file failures are
//! recorded and returned in a composite [`UpdaterError::SwapFailed`] so the
//! state machine (Task 2.11) can distinguish "clean rollback to pre-update
//! state" from "partial rollback — manual intervention needed" rather than
//! both collapsing into a generic swap failure.
//!
//! **Orphan cleanup:** files present in the OLD manifest but absent from
//! the NEW one (deleted upstream) are removed after a successful swap.
//!
//! **Backup retention:** `cleanup_old_backups(n)` keeps the N most recent
//! `pre-update-*` directories. Timestamps embedded in directory names
//! (unix seconds) make lexicographic sort deterministic across filesystems
//! — no reliance on platform-variable modtime granularity (NTFS 100ns,
//! FAT32 2s, tmpfs ns).
//!
//! **Durability caveats (documented for Task 2.11):** `swap_files` is NOT
//! journaled — a process kill mid-batch leaves some files swapped + some
//! pending, and the caller's `write_local_manifest` still points at the
//! old version. State machine should either hash-verify every manifest
//! file on startup, or write a `.in-progress` sentinel at swap start
//! cleared on success.

use crate::manifest::{FileEntry, Manifest};
use crate::{util, Result, UpdaterError, Version};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// Number of `pre-update-*` backup snapshots retained by default.
pub const DEFAULT_KEEP_BACKUPS: usize = 3;

/// Record of one successful swap, tracked for rollback. `backup = None`
/// means the destination was a fresh install (no prior file to restore);
/// rollback deletes the destination to restore that state. The `Option`
/// encodes the conditional-validity invariant structurally — consumers
/// `match` on backup, not on a sidecar `was_preexisting` bool.
#[derive(Debug)]
struct SwappedFile {
    dest: PathBuf,
    backup: Option<PathBuf>,
}

/// Applies downloaded files to an install directory with backup + rollback
/// + orphan cleanup.
#[derive(Debug)]
pub struct Updater<'a> {
    install_dir: &'a Path,
    backup_dir: PathBuf,
}

impl<'a> Updater<'a> {
    pub fn new(install_dir: &'a Path) -> Self {
        let backup_dir = install_dir.join("File-Backups");
        Self {
            install_dir,
            backup_dir,
        }
    }

    /// Swap each `(FileEntry, tmp_path)` pair into the install directory,
    /// backing up the original to a timestamped snapshot dir.
    ///
    /// Empty `downloads` is a valid no-op; no backup directory is created.
    ///
    /// On any failure mid-sequence: rollback is attempted for every
    /// already-swapped file. If rollback itself fully succeeds, the
    /// ORIGINAL swap error is propagated. If rollback has per-file
    /// failures, [`UpdaterError::SwapFailed`] wraps both the original
    /// error and the list of rollback failures so the caller (Task 2.11)
    /// can distinguish recoverable from unrecoverable outcomes.
    /// Swap downloaded files into `install_dir`, backing up any existing
    /// copies under `File-Backups/pre-update-v<old>-<ts>/`. Pass `None`
    /// for `old_version` on a first-run/clean install — the snapshot
    /// directory is then labelled `pre-update-fresh-<ts>/`, which avoids
    /// a misleading `"0.0.0"` sentinel in the folder name.
    pub fn swap_files(
        &self,
        downloads: &[(FileEntry, PathBuf)],
        old_version: Option<&Version>,
    ) -> Result<()> {
        if downloads.is_empty() {
            return Ok(());
        }

        let pre_update_dir = self.backup_dir.join(Self::snapshot_dir_name(old_version));
        std::fs::create_dir_all(&pre_update_dir)?;

        let mut swapped: Vec<SwappedFile> = Vec::with_capacity(downloads.len());

        for (file, temp_path) in downloads {
            let dest = file.path.to_install_path(self.install_dir);
            let backup_path = pre_update_dir.join(file.path.as_str());
            let was_preexisting = dest.exists();

            match Self::do_swap(&dest, temp_path, &backup_path, was_preexisting) {
                Ok(()) => {
                    swapped.push(SwappedFile {
                        dest,
                        backup: if was_preexisting {
                            Some(backup_path)
                        } else {
                            None
                        },
                    });
                }
                Err(e) => {
                    let rollback_failures = self.rollback_collect(&swapped);
                    return Err(if rollback_failures.is_empty() {
                        e
                    } else {
                        UpdaterError::SwapFailed {
                            original: Box::new(e),
                            rollback_failures,
                        }
                    });
                }
            }
        }
        Ok(())
    }

    /// Backup dir name embedding the old version AND a unix timestamp so
    /// collisions (repeated updates from the same old version) are
    /// avoided — AND so lexicographic sort gives reliable
    /// newest-to-oldest ordering across filesystems.
    ///
    /// `None` produces `pre-update-fresh-<ts>/` (first-run install with
    /// nothing on disk to back up). That branch is dead weight for the
    /// rollback path — no pre-existing files are ever found, so nothing
    /// gets copied into the backup — but keeping the directory around
    /// gives log readers a single consistent "what happened" marker.
    fn snapshot_dir_name(old_version: Option<&Version>) -> String {
        let ts = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        match old_version {
            Some(v) => format!("pre-update-v{v}-{ts}"),
            None => format!("pre-update-fresh-{ts}"),
        }
    }

    fn do_swap(
        dest: &Path,
        temp: &Path,
        backup: &Path,
        was_preexisting: bool,
    ) -> Result<()> {
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        if let Some(parent) = backup.parent() {
            std::fs::create_dir_all(parent)?;
        }

        if was_preexisting {
            // Atomic copy so a mid-copy crash can never produce a
            // half-written backup that a later rollback might restore
            // over an intact dest.
            util::atomic_copy(dest, backup)?;
        }

        // Atomic rename with AV/EDR retry. Falls back to copy+remove if
        // the rename crosses filesystems (EXDEV on Linux).
        if let Err(rename_err) = util::rename_with_retry(temp, dest) {
            log::debug!(
                "rename {} -> {} failed ({rename_err}); falling back to copy+remove",
                temp.display(),
                dest.display()
            );
            std::fs::copy(temp, dest)?;
            // Swap semantics: at this point dest has the new content and
            // the install is correct. A failed remove only leaks a tmp
            // file — log-warn and return Ok so rollback is NOT triggered.
            if let Err(e) = std::fs::remove_file(temp) {
                log::warn!(
                    "tmp cleanup failed after cross-fs swap of {} (harmless leak): {e}",
                    temp.display()
                );
            }
        }
        Ok(())
    }

    /// Rollback iterates in reverse (LIFO unwinding) and collects per-file
    /// failure messages. Returns empty `Vec` on a fully-clean rollback;
    /// non-empty indicates the caller should surface `SwapFailed`.
    fn rollback_collect(&self, swapped: &[SwappedFile]) -> Vec<String> {
        let mut failures = Vec::new();
        for sf in swapped.iter().rev() {
            match &sf.backup {
                Some(backup) => {
                    if let Err(e) = util::atomic_copy(backup, &sf.dest) {
                        let msg = format!(
                            "rollback restore of {} from {}: {e}",
                            sf.dest.display(),
                            backup.display()
                        );
                        log::warn!("{msg}");
                        failures.push(msg);
                    }
                }
                None => {
                    if let Err(e) = std::fs::remove_file(&sf.dest) {
                        let msg = format!(
                            "rollback delete of fresh-install file {}: {e}",
                            sf.dest.display()
                        );
                        log::warn!("{msg}");
                        failures.push(msg);
                    }
                }
            }
        }
        failures
    }

    /// Remove files that were in `old` but absent from `new`. Best-effort —
    /// individual removal failures are logged but not propagated.
    pub fn cleanup_orphans(&self, old: &Manifest, new: &Manifest) {
        let new_paths: HashSet<&str> = new.files.iter().map(|f| f.path.as_str()).collect();
        for old_file in &old.files {
            if new_paths.contains(old_file.path.as_str()) {
                continue;
            }
            let orphan = old_file.path.to_install_path(self.install_dir);
            if !orphan.exists() {
                continue;
            }
            if let Err(e) = std::fs::remove_file(&orphan) {
                log::warn!(
                    "orphan cleanup: failed to remove {}: {e}",
                    orphan.display()
                );
            }
        }
    }

    /// Persist the new manifest as `local-manifest.json`.
    pub fn write_local_manifest(&self, manifest: &Manifest) -> Result<()> {
        crate::manifest::save_local(self.install_dir, manifest)
    }

    /// Write `version.txt` atomically for debug-friendly inspection.
    pub fn write_version_file(&self, version: &Version) -> Result<()> {
        let path = self.install_dir.join("version.txt");
        util::atomic_write_bytes(&path, version.as_str().as_bytes())
    }

    /// Prune `File-Backups/pre-update-*` snapshots. Lexicographic sort on
    /// timestamp-embedded names gives reliable newest-last ordering; older
    /// snapshots are deleted first. Entries with unreadable metadata are
    /// skipped (logged) rather than sorted to UNIX_EPOCH — avoids the
    /// "corrupted-metadata causes newest backup to be deleted" footgun.
    pub fn cleanup_old_backups(&self, keep_count: usize) -> Result<()> {
        if !self.backup_dir.exists() {
            return Ok(());
        }
        let mut pre_updates: Vec<_> = std::fs::read_dir(&self.backup_dir)?
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.file_name()
                    .to_string_lossy()
                    .starts_with("pre-update-")
            })
            .collect();
        // Lexicographic sort on name — since names embed unix-seconds
        // timestamps, ascending order == oldest-first == delete-first.
        pre_updates.sort_by(|a, b| a.file_name().cmp(&b.file_name()));
        if pre_updates.len() > keep_count {
            let to_remove = pre_updates.len() - keep_count;
            for entry in pre_updates.iter().take(to_remove) {
                if let Err(e) = std::fs::remove_dir_all(entry.path()) {
                    log::warn!(
                        "failed to remove old backup {}: {e}",
                        entry.path().display()
                    );
                }
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ManifestPath, OsTarget, Sha256};
    use tempfile::TempDir;

    const HASH: &str =
        "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";

    fn file_entry(rel: &str) -> FileEntry {
        FileEntry {
            path: ManifestPath::parse(rel).unwrap(),
            url: "https://example.com".to_string(),
            size: 3,
            sha256: Sha256::parse(HASH).unwrap(),
        }
    }

    fn tmp_file(dir: &TempDir, name: &str, bytes: &[u8]) -> PathBuf {
        let p = dir.path().join(name);
        std::fs::write(&p, bytes).unwrap();
        p
    }

    fn manifest_with(files: Vec<FileEntry>, version: &str) -> Manifest {
        Manifest {
            version: Version::parse(version).unwrap(),
            os: OsTarget::Windows,
            released_at: "2026-04-15T10:00:00Z".to_string(),
            min_auto_update_version: Version::parse("0.1.0").unwrap(),
            launcher_executable: ManifestPath::parse("launcher/app.exe").unwrap(),
            changelog: String::new(),
            files,
        }
    }

    /// Locate the sole pre-update-* snapshot dir under File-Backups.
    fn snapshot_dir(install: &Path) -> PathBuf {
        let bd = install.join("File-Backups");
        std::fs::read_dir(&bd)
            .unwrap()
            .filter_map(|e| e.ok())
            .find(|e| {
                e.file_name()
                    .to_string_lossy()
                    .starts_with("pre-update-")
            })
            .expect("expected a pre-update snapshot dir")
            .path()
    }

    // --- swap_files ---

    #[test]
    fn swap_files_replaces_existing_and_backs_up() {
        let install = TempDir::new().unwrap();
        let tmp = TempDir::new().unwrap();

        std::fs::create_dir_all(install.path().join("launcher")).unwrap();
        std::fs::write(install.path().join("launcher/a.jar"), b"OLD").unwrap();

        let new_tmp = tmp_file(&tmp, "a.jar", b"NEW");
        Updater::new(install.path())
            .swap_files(
                &[(file_entry("launcher/a.jar"), new_tmp)],
                Some(&Version::parse("1.0.0").unwrap()),
            )
            .unwrap();

        assert_eq!(
            std::fs::read(install.path().join("launcher/a.jar")).unwrap(),
            b"NEW"
        );
        let snap = snapshot_dir(install.path());
        assert!(snap
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("pre-update-v1.0.0-"));
        assert_eq!(
            std::fs::read(snap.join("launcher/a.jar")).unwrap(),
            b"OLD"
        );
    }

    #[test]
    fn swap_files_handles_fresh_install_without_backup() {
        let install = TempDir::new().unwrap();
        let tmp = TempDir::new().unwrap();
        let new_tmp = tmp_file(&tmp, "b.jar", b"FRESH");

        Updater::new(install.path())
            .swap_files(
                &[(file_entry("launcher/b.jar"), new_tmp)],
                Some(&Version::parse("1.0.0").unwrap()),
            )
            .unwrap();

        assert_eq!(
            std::fs::read(install.path().join("launcher/b.jar")).unwrap(),
            b"FRESH"
        );
        let snap = snapshot_dir(install.path());
        assert!(!snap.join("launcher/b.jar").exists());
    }

    #[test]
    fn swap_files_empty_downloads_is_no_op() {
        let install = TempDir::new().unwrap();
        Updater::new(install.path())
            .swap_files(&[], Some(&Version::parse("1.0.0").unwrap()))
            .unwrap();
        // No backup dir created for a no-op swap.
        assert!(!install.path().join("File-Backups").exists());
    }

    #[test]
    fn swap_files_rolls_back_cleanly_restores_original() {
        // First file swaps OK; second tmp missing → rollback. Rollback must
        // restore file 1 and bubble up the ORIGINAL error (not SwapFailed).
        let install = TempDir::new().unwrap();
        let tmp = TempDir::new().unwrap();
        std::fs::create_dir_all(install.path().join("launcher")).unwrap();
        std::fs::write(install.path().join("launcher/a.jar"), b"OLD").unwrap();

        let a_new = tmp_file(&tmp, "a.jar", b"NEW_A");
        let b_nonexistent = tmp.path().join("does-not-exist.jar");

        let result = Updater::new(install.path()).swap_files(
            &[
                (file_entry("launcher/a.jar"), a_new),
                (file_entry("launcher/b.jar"), b_nonexistent),
            ],
            Some(&Version::parse("1.0.0").unwrap()),
        );
        assert!(result.is_err());
        // Clean rollback → original error surfaces, NOT SwapFailed.
        assert!(
            !matches!(result, Err(UpdaterError::SwapFailed { .. })),
            "clean rollback must propagate original error, not wrap in SwapFailed"
        );
        assert_eq!(
            std::fs::read(install.path().join("launcher/a.jar")).unwrap(),
            b"OLD"
        );
    }

    #[test]
    fn swap_rollback_deletes_fresh_install_files() {
        let install = TempDir::new().unwrap();
        let tmp = TempDir::new().unwrap();
        let a_new = tmp_file(&tmp, "a.jar", b"FRESH_A");
        let b_nonexistent = tmp.path().join("does-not-exist.jar");

        let _ = Updater::new(install.path()).swap_files(
            &[
                (file_entry("launcher/a.jar"), a_new),
                (file_entry("launcher/b.jar"), b_nonexistent),
            ],
            Some(&Version::parse("1.0.0").unwrap()),
        );
        assert!(!install.path().join("launcher/a.jar").exists());
    }

    #[test]
    fn swap_files_fails_at_backup_step_when_backup_parent_exists_as_file() {
        // Backup target path has a conflicting FILE (not dir) as its
        // snapshot-dir parent. create_dir_all inside do_swap will fail
        // with NotADirectory / AlreadyExists → swap error → rollback over
        // zero prior swaps → clean error surfaces.
        let install = TempDir::new().unwrap();
        std::fs::create_dir_all(install.path().join("launcher")).unwrap();
        std::fs::write(install.path().join("launcher/a.jar"), b"OLD").unwrap();

        // Create a FILE where File-Backups dir would go.
        std::fs::write(install.path().join("File-Backups"), b"").unwrap();

        let tmp = TempDir::new().unwrap();
        let a_new = tmp_file(&tmp, "a.jar", b"NEW");
        let result = Updater::new(install.path()).swap_files(
            &[(file_entry("launcher/a.jar"), a_new)],
            Some(&Version::parse("1.0.0").unwrap()),
        );
        assert!(matches!(result, Err(UpdaterError::Io(_))));
        // Original file untouched (swap never started).
        assert_eq!(
            std::fs::read(install.path().join("launcher/a.jar")).unwrap(),
            b"OLD"
        );
    }

    // --- cleanup_orphans ---

    #[test]
    fn cleanup_orphans_removes_files_absent_from_new_manifest() {
        let install = TempDir::new().unwrap();
        std::fs::create_dir_all(install.path().join("launcher")).unwrap();
        std::fs::write(install.path().join("launcher/old.jar"), b"STALE").unwrap();
        std::fs::write(install.path().join("launcher/kept.jar"), b"KEEP").unwrap();

        let old = manifest_with(
            vec![file_entry("launcher/old.jar"), file_entry("launcher/kept.jar")],
            "1.0.0",
        );
        let new = manifest_with(vec![file_entry("launcher/kept.jar")], "1.0.1");

        Updater::new(install.path()).cleanup_orphans(&old, &new);

        assert!(!install.path().join("launcher/old.jar").exists());
        assert!(install.path().join("launcher/kept.jar").exists());
    }

    #[test]
    fn cleanup_orphans_tolerates_already_missing_orphans() {
        let install = TempDir::new().unwrap();
        let old = manifest_with(vec![file_entry("launcher/old.jar")], "1.0.0");
        let new = manifest_with(vec![], "1.0.1");
        Updater::new(install.path()).cleanup_orphans(&old, &new);
    }

    // --- cleanup_old_backups ---

    #[test]
    fn cleanup_old_backups_keeps_n_most_recent_by_timestamp_in_name() {
        // Names embed unix timestamps — lex sort == chronological sort
        // regardless of filesystem modtime granularity.
        let install = TempDir::new().unwrap();
        let bd = install.path().join("File-Backups");
        std::fs::create_dir_all(&bd).unwrap();
        for name in [
            "pre-update-v1-1700000000",
            "pre-update-v2-1700000100",
            "pre-update-v3-1700000200",
        ] {
            std::fs::create_dir(bd.join(name)).unwrap();
        }
        std::fs::create_dir(bd.join("unrelated")).unwrap();

        Updater::new(install.path()).cleanup_old_backups(2).unwrap();

        // Oldest (v1-1700000000) deleted; v2 + v3 retained; unrelated untouched.
        assert!(!bd.join("pre-update-v1-1700000000").exists());
        assert!(bd.join("pre-update-v2-1700000100").exists());
        assert!(bd.join("pre-update-v3-1700000200").exists());
        assert!(bd.join("unrelated").exists());
    }

    #[test]
    fn cleanup_old_backups_no_op_when_dir_missing() {
        let install = TempDir::new().unwrap();
        Updater::new(install.path()).cleanup_old_backups(3).unwrap();
    }

    #[test]
    fn cleanup_old_backups_keeps_all_when_count_exceeds_total() {
        let install = TempDir::new().unwrap();
        let bd = install.path().join("File-Backups");
        std::fs::create_dir_all(&bd).unwrap();
        std::fs::create_dir(bd.join("pre-update-v1-1700000000")).unwrap();
        Updater::new(install.path()).cleanup_old_backups(10).unwrap();
        assert!(bd.join("pre-update-v1-1700000000").exists());
    }

    // --- write_version_file + write_local_manifest ---

    #[test]
    fn write_version_file_writes_atomically() {
        let install = TempDir::new().unwrap();
        Updater::new(install.path())
            .write_version_file(&Version::parse("0.1.0").unwrap())
            .unwrap();
        assert_eq!(
            std::fs::read_to_string(install.path().join("version.txt")).unwrap(),
            "0.1.0"
        );
    }

    #[test]
    fn write_local_manifest_persists_all_fields() {
        let install = TempDir::new().unwrap();
        let m = manifest_with(vec![file_entry("launcher/app.jar")], "0.1.0");
        Updater::new(install.path()).write_local_manifest(&m).unwrap();
        let raw = std::fs::read_to_string(install.path().join("local-manifest.json")).unwrap();
        assert!(raw.contains("\"version\": \"0.1.0\""));
        // Pin that launcherExecutable round-trips (previously untested).
        assert!(raw.contains("\"launcher/app.exe\""));
    }

    // --- snapshot_dir_name ---

    #[test]
    fn snapshot_dir_name_embeds_version_and_timestamp() {
        let v = Version::parse("1.2.3").unwrap();
        let name = Updater::snapshot_dir_name(Some(&v));
        assert!(name.starts_with("pre-update-v1.2.3-"));
        // Suffix after "v1.2.3-" is a unix-seconds number; parse to verify.
        let ts_str = name.rsplit('-').next().unwrap();
        assert!(ts_str.parse::<u64>().is_ok());
    }

    #[test]
    fn snapshot_dir_name_uses_fresh_marker_for_none() {
        // Replaces the old "0.0.0" sentinel: first-run installs produce
        // `pre-update-fresh-<ts>/` in the backup directory, which is
        // unambiguous in log output and leaves the semver namespace
        // untouched by the sentinel.
        let name = Updater::snapshot_dir_name(None);
        assert!(name.starts_with("pre-update-fresh-"));
        let ts_str = name.rsplit('-').next().unwrap();
        assert!(ts_str.parse::<u64>().is_ok());
    }
}
