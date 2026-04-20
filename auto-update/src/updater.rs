//! Bundle-based updater: sha256 compare, download coordination, backup,
//! extract, rollback. Zastępuje per-file flow z v1.0.x (`swap_files` +
//! `cleanup_orphans` + `copy_tree_recursive`) — patrz commit 23f2960 oraz
//! spec §4-§6.
//!
//! **Flow per update (orchestrated przez `app.rs::process_release`):**
//! 1. Fetch remote manifest (`Manifest::parse`).
//! 2. Load local manifest (`Manifest::read_local` → `Option<Manifest>`).
//! 3. [`decide_update`] compare per-package:
//!    - launcher: sha256 remote vs local → download if different
//!    - jre: sha256 remote vs local → download if different
//!    - auto-update: version remote vs local (Cargo.toml bump controlled)
//! 4. Download required paczki do `install_dir/tmp/` (downloader.rs).
//! 5. [`verify_sha256`] każdej pobranej paczki przed extract.
//! 6. Backup: [`backup_launcher`] / [`backup_runtime`] — rename
//!    `install_dir/launcher/` → `launcher.old/`, analogicznie `runtime/`.
//! 7. [`extract_launcher_bundle`] / [`extract_jre_bundle`] — tar.gz unpack.
//! 8. Update `install_dir/local-manifest.json` (`Manifest::write_local`).
//! 9. Spawn launcher (launcher.rs alive flag handshake).
//! 10. Po pomyślnym launch → [`cleanup_backups`] (usuń `.old/`).
//!     Na crash → [`rollback_launcher`] / [`rollback_runtime`] (rename back).
//!
//! **Partial extract contract:** `extract_tar_gz` może fail mid-way
//! zostawiając częściowo wypakowane pliki w target. W naszym flow target
//! jest fresh (po rename do `.old/`), więc residue = partial new state.
//! Na fail: jawny cleanup target dir PRZED rollback (inaczej rollback
//! rename z `.old/` → target dostanie dir-already-exists error).

use crate::extract::extract_tar_gz;
use crate::manifest::Manifest;
use crate::{Result, Sha256, UpdaterError};
use std::fs;
use std::path::Path;

/// Subdirectory names w install_dir. Stałe żeby literały nie rozjeżdżały
/// się między updater.rs / launcher.rs / main.rs / installer .iss.
pub const LAUNCHER_DIR: &str = "launcher";
pub const RUNTIME_DIR: &str = "runtime";
pub const LAUNCHER_OLD_DIR: &str = "launcher.old";
pub const RUNTIME_OLD_DIR: &str = "runtime.old";
/// Tmp dir dla in-progress download'ów. Cleanupowany na success.
/// Uninstaller (.iss) usuwa go jeśli został po crash.
pub const TMP_DIR: &str = "tmp";

/// Decyzja update per paczka. Pola `*_needed: bool` — skipować download
/// gdy lokalna wersja identyczna z remote (sha256 dla launcher/jre,
/// version dla auto-update).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UpdateDecision {
    pub launcher_needed: bool,
    pub jre_needed: bool,
    pub auto_update_needed: bool,
}

impl UpdateDecision {
    /// `true` gdy cokolwiek wymaga pobrania. `false` → skip całego update
    /// (lokal identyczny z remote, spawn launcher od razu).
    #[must_use]
    pub fn any(&self) -> bool {
        self.launcher_needed || self.jre_needed || self.auto_update_needed
    }
}

/// Decyduje co pobrać na podstawie remote manifest vs local snapshot.
///
/// - `local = None` (fresh install, brak `local-manifest.json`) → all three needed.
/// - `local = Some(m)` → per-package compare: sha256 dla launcher/jre
///   (deterministic tar = same sha means same content), version dla
///   auto-update (sha nie jest deterministic bo Rust rebuild timestamps).
#[must_use]
pub fn decide_update(remote: &Manifest, local: Option<&Manifest>) -> UpdateDecision {
    match local {
        None => UpdateDecision {
            launcher_needed: true,
            jre_needed: true,
            auto_update_needed: true,
        },
        Some(l) => UpdateDecision {
            launcher_needed: l.launcher.sha256 != remote.launcher.sha256,
            jre_needed: l.jre.sha256 != remote.jre.sha256,
            auto_update_needed: l.auto_update.version != remote.auto_update.version,
        },
    }
}

/// Sprawdza czy sha256 lokalnego pliku matches oczekiwanego. Streaming —
/// nie ładuje całego pliku do pamięci (paczki bywają >30 MB).
/// Zwraca Err dla missing file, I/O fail, lub hash mismatch.
pub fn verify_sha256(path: &Path, expected: &Sha256) -> Result<()> {
    let file = fs::File::open(path).map_err(UpdaterError::Io)?;
    let mut reader = std::io::BufReader::new(file);
    let ok = expected.verify_reader(&mut reader)?;
    if !ok {
        return Err(UpdaterError::Manifest(format!(
            "sha256 mismatch for {}: expected {}",
            path.display(),
            expected.as_str()
        )));
    }
    Ok(())
}

/// Backup current `launcher/` → `launcher.old/`. Jeśli `launcher.old/` już
/// istnieje (poprzedni nieczyszczony backup np. po crash) → usunięty.
/// Gdy `launcher/` nie istnieje (fresh install) → no-op Ok.
pub fn backup_launcher(install_dir: &Path) -> Result<()> {
    backup_dir(install_dir, LAUNCHER_DIR, LAUNCHER_OLD_DIR)
}

/// Backup current `runtime/` → `runtime.old/`. Semantyka jak
/// `backup_launcher`.
pub fn backup_runtime(install_dir: &Path) -> Result<()> {
    backup_dir(install_dir, RUNTIME_DIR, RUNTIME_OLD_DIR)
}

fn backup_dir(install_dir: &Path, current: &str, backup: &str) -> Result<()> {
    let current_path = install_dir.join(current);
    let backup_path = install_dir.join(backup);
    if backup_path.exists() {
        fs::remove_dir_all(&backup_path).map_err(UpdaterError::Io)?;
    }
    if current_path.exists() {
        fs::rename(&current_path, &backup_path).map_err(UpdaterError::Io)?;
    }
    Ok(())
}

/// Rollback: usuń current `launcher/` (half-extracted residue), rename
/// `launcher.old/` → `launcher/`. Err gdy brak `launcher.old/` (nie
/// ma czego rollbackować — caller musi zdecydować FatalError lub fresh install).
pub fn rollback_launcher(install_dir: &Path) -> Result<()> {
    rollback_dir(install_dir, LAUNCHER_DIR, LAUNCHER_OLD_DIR)
}

/// Rollback `runtime.old/` → `runtime/`. Semantyka jak `rollback_launcher`.
pub fn rollback_runtime(install_dir: &Path) -> Result<()> {
    rollback_dir(install_dir, RUNTIME_DIR, RUNTIME_OLD_DIR)
}

fn rollback_dir(install_dir: &Path, current: &str, backup: &str) -> Result<()> {
    let current_path = install_dir.join(current);
    let backup_path = install_dir.join(backup);
    if !backup_path.exists() {
        return Err(UpdaterError::Manifest(format!(
            "cannot rollback: no {} backup at {}",
            backup,
            backup_path.display()
        )));
    }
    if current_path.exists() {
        fs::remove_dir_all(&current_path).map_err(UpdaterError::Io)?;
    }
    fs::rename(&backup_path, &current_path).map_err(UpdaterError::Io)?;
    Ok(())
}

/// Cleanup `.old/` backupy po potwierdzeniu że nowa wersja działa
/// (alive flag detected w launcher.rs). Idempotent — no-op gdy dir brak.
pub fn cleanup_backups(install_dir: &Path) -> Result<()> {
    for dir in [LAUNCHER_OLD_DIR, RUNTIME_OLD_DIR] {
        let path = install_dir.join(dir);
        if path.exists() {
            fs::remove_dir_all(&path).map_err(UpdaterError::Io)?;
        }
    }
    Ok(())
}

/// Extract launcher bundle tar.gz z `tmp/launcher.tar.gz` do
/// `install_dir/launcher/`. Caller odpowiedzialny za `backup_launcher`
/// przed wywołaniem (target jest fresh/nieistniejący po rename do `.old/`).
pub fn extract_launcher_bundle(tmp_archive: &Path, install_dir: &Path) -> Result<()> {
    let target = install_dir.join(LAUNCHER_DIR);
    extract_tar_gz(tmp_archive, &target)
}

/// Extract jre bundle tar.gz z `tmp/jre-<os>.tar.gz` do
/// `install_dir/runtime/`. Analogiczne do `extract_launcher_bundle`.
pub fn extract_jre_bundle(tmp_archive: &Path, install_dir: &Path) -> Result<()> {
    let target = install_dir.join(RUNTIME_DIR);
    extract_tar_gz(tmp_archive, &target)
}

/// Top-level rollback convenience — wywołuje `rollback_launcher` +
/// `rollback_runtime` (ten drugi tolerowany gdy brak `.old/` backup
/// bo ostatni update mógł być launcher-only). Używane przez main.rs
/// w crash-loop recovery path gdzie caller nie chce rozróżniać
/// launcher/runtime. Zastępuje legacy `Updater::restore_from_latest_backup`.
pub fn perform_launcher_rollback(install_dir: &Path) -> Result<()> {
    rollback_launcher(install_dir)?;
    // runtime.old może nie istnieć jeśli ostatni update tylko launcher
    // wymagał pobrania (jre sha unchanged). Tolerate missing runtime backup.
    let runtime_backup = install_dir.join(RUNTIME_OLD_DIR);
    if runtime_backup.exists() {
        rollback_runtime(install_dir)?;
    }
    Ok(())
}

/// Cleanup `tmp/` po update. Idempotent — no-op gdy dir brak.
/// Używany po successful extract + install (żeby nie zajmować miejsca
/// na dysku z pobranymi paczkami).
pub fn cleanup_tmp(install_dir: &Path) -> Result<()> {
    let path = install_dir.join(TMP_DIR);
    if path.exists() {
        fs::remove_dir_all(&path).map_err(UpdaterError::Io)?;
    }
    Ok(())
}

/// Cleanup half-extracted target po failed extract. Wywoływane przed
/// rollback, żeby rename `launcher.old/` → `launcher/` nie fail z
/// AlreadyExists. Idempotent.
pub fn cleanup_partial_extract(install_dir: &Path, which: &str) -> Result<()> {
    let path = install_dir.join(which);
    if path.exists() {
        fs::remove_dir_all(&path).map_err(UpdaterError::Io)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::manifest::{AutoUpdatePackage, Manifest, OsTarget, PackageEntry};
    use crate::{ManifestPath, Version};
    use tempfile::tempdir;

    fn dummy_manifest(launcher_sha_hex: &str, jre_sha_hex: &str, au_version: &str) -> Manifest {
        // Padding do 64 hex chars.
        let pad = |hex: &str| -> String { format!("{hex:0>64}") };
        Manifest {
            version: Version::parse("0.4.7").unwrap(),
            os: OsTarget::Windows,
            released_at: "2026-04-20T12:00:00Z".into(),
            min_auto_update_version: Version::parse("1.0.0").unwrap(),
            launcher_executable: ManifestPath::parse("launcher/SingularityMC.exe").unwrap(),
            changelog: String::new(),
            launcher: PackageEntry {
                url: "https://example.com/launcher.tar.gz".into(),
                sha256: Sha256::parse(&pad(launcher_sha_hex)).unwrap(),
                size: 100,
            },
            jre: PackageEntry {
                url: "https://example.com/jre.tar.gz".into(),
                sha256: Sha256::parse(&pad(jre_sha_hex)).unwrap(),
                size: 200,
            },
            auto_update: AutoUpdatePackage {
                url: "https://example.com/au.exe".into(),
                sha256: Sha256::parse(&pad("ff")).unwrap(),
                size: 50,
                version: Version::parse(au_version).unwrap(),
            },
        }
    }

    // --- decide_update ---

    #[test]
    fn decide_update_fresh_install_all_needed() {
        let remote = dummy_manifest("1", "2", "1.1.0");
        let decision = decide_update(&remote, None);
        assert!(decision.launcher_needed);
        assert!(decision.jre_needed);
        assert!(decision.auto_update_needed);
        assert!(decision.any());
    }

    #[test]
    fn decide_update_identical_no_change() {
        let m = dummy_manifest("1", "2", "1.1.0");
        let decision = decide_update(&m, Some(&m));
        assert!(!decision.launcher_needed);
        assert!(!decision.jre_needed);
        assert!(!decision.auto_update_needed);
        assert!(!decision.any());
    }

    #[test]
    fn decide_update_only_launcher_changed() {
        let remote = dummy_manifest("3", "2", "1.1.0");
        let local = dummy_manifest("1", "2", "1.1.0");
        let decision = decide_update(&remote, Some(&local));
        assert!(decision.launcher_needed);
        assert!(!decision.jre_needed);
        assert!(!decision.auto_update_needed);
    }

    #[test]
    fn decide_update_only_jre_changed() {
        let remote = dummy_manifest("1", "5", "1.1.0");
        let local = dummy_manifest("1", "2", "1.1.0");
        let decision = decide_update(&remote, Some(&local));
        assert!(!decision.launcher_needed);
        assert!(decision.jre_needed);
        assert!(!decision.auto_update_needed);
    }

    #[test]
    fn decide_update_only_auto_update_version_bump() {
        let remote = dummy_manifest("1", "2", "1.2.0");
        let local = dummy_manifest("1", "2", "1.1.0");
        let decision = decide_update(&remote, Some(&local));
        assert!(!decision.launcher_needed);
        assert!(!decision.jre_needed);
        assert!(decision.auto_update_needed);
    }

    // --- verify_sha256 ---

    #[test]
    fn verify_sha256_passes_for_matching_hash() {
        use sha2::{Digest, Sha256 as Hasher};
        let tmp = tempdir().unwrap();
        let path = tmp.path().join("file.bin");
        let content = b"test content 12345";
        fs::write(&path, content).unwrap();

        let expected_hex = hex::encode(Hasher::digest(content));
        let expected = Sha256::parse(&expected_hex).unwrap();
        verify_sha256(&path, &expected).unwrap();
    }

    #[test]
    fn verify_sha256_fails_for_mismatch() {
        let tmp = tempdir().unwrap();
        let path = tmp.path().join("file.bin");
        fs::write(&path, b"actual content").unwrap();

        let wrong = Sha256::parse(&"a".repeat(64)).unwrap();
        let err = verify_sha256(&path, &wrong).unwrap_err();
        assert!(matches!(err, UpdaterError::Manifest(_)));
    }

    #[test]
    fn verify_sha256_fails_for_missing_file() {
        let tmp = tempdir().unwrap();
        let expected = Sha256::parse(&"a".repeat(64)).unwrap();
        let err = verify_sha256(&tmp.path().join("missing.bin"), &expected).unwrap_err();
        assert!(matches!(err, UpdaterError::Io(_)));
    }

    // --- backup + rollback cycles ---

    #[test]
    fn backup_then_rollback_launcher_restores_original() {
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        let launcher = install.join(LAUNCHER_DIR);
        fs::create_dir(&launcher).unwrap();
        fs::write(launcher.join("marker.txt"), b"original").unwrap();

        backup_launcher(install).unwrap();
        assert!(!launcher.exists());
        assert!(install.join(LAUNCHER_OLD_DIR).join("marker.txt").exists());

        // Symuluj nowy launcher zainstalowany (half-extracted residue)
        fs::create_dir(&launcher).unwrap();
        fs::write(launcher.join("marker.txt"), b"new").unwrap();

        rollback_launcher(install).unwrap();
        let restored = fs::read_to_string(launcher.join("marker.txt")).unwrap();
        assert_eq!(restored, "original");
        assert!(!install.join(LAUNCHER_OLD_DIR).exists());
    }

    #[test]
    fn backup_runtime_and_rollback_cycle() {
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        let runtime = install.join(RUNTIME_DIR);
        fs::create_dir(&runtime).unwrap();
        fs::write(runtime.join("jvm.dll"), b"original-jvm").unwrap();

        backup_runtime(install).unwrap();
        assert!(install.join(RUNTIME_OLD_DIR).join("jvm.dll").exists());

        rollback_runtime(install).unwrap();
        assert_eq!(
            fs::read(runtime.join("jvm.dll")).unwrap(),
            b"original-jvm"
        );
    }

    #[test]
    fn backup_launcher_no_op_when_current_missing() {
        // Fresh install: launcher/ nie istnieje — backup_launcher nie
        // powinien fail. Idempotent dla fresh install scenariusza.
        let tmp = tempdir().unwrap();
        backup_launcher(tmp.path()).unwrap();
        assert!(!tmp.path().join(LAUNCHER_OLD_DIR).exists());
    }

    #[test]
    fn backup_launcher_removes_stale_backup_before_rename() {
        // Jeśli .old/ został po crash (cleanup_backups nie zadziałał),
        // backup_launcher powinien go usunąć przed rename — żeby rename
        // nie fail z AlreadyExists.
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        fs::create_dir(install.join(LAUNCHER_DIR)).unwrap();
        fs::write(install.join(LAUNCHER_DIR).join("new.txt"), b"new").unwrap();
        fs::create_dir(install.join(LAUNCHER_OLD_DIR)).unwrap();
        fs::write(install.join(LAUNCHER_OLD_DIR).join("stale.txt"), b"stale").unwrap();

        backup_launcher(install).unwrap();

        // .old/ zawiera "new" content (stale usunięty), launcher/ brak
        assert!(install.join(LAUNCHER_OLD_DIR).join("new.txt").exists());
        assert!(!install.join(LAUNCHER_OLD_DIR).join("stale.txt").exists());
        assert!(!install.join(LAUNCHER_DIR).exists());
    }

    #[test]
    fn rollback_launcher_errors_when_no_backup() {
        let tmp = tempdir().unwrap();
        let err = rollback_launcher(tmp.path()).unwrap_err();
        assert!(matches!(err, UpdaterError::Manifest(_)));
    }

    // --- cleanup_backups / cleanup_tmp ---

    #[test]
    fn cleanup_backups_removes_both_old_dirs() {
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        fs::create_dir(install.join(LAUNCHER_OLD_DIR)).unwrap();
        fs::create_dir(install.join(RUNTIME_OLD_DIR)).unwrap();

        cleanup_backups(install).unwrap();
        assert!(!install.join(LAUNCHER_OLD_DIR).exists());
        assert!(!install.join(RUNTIME_OLD_DIR).exists());
    }

    #[test]
    fn cleanup_backups_idempotent_when_nothing_to_cleanup() {
        let tmp = tempdir().unwrap();
        cleanup_backups(tmp.path()).unwrap(); // no-op OK
    }

    #[test]
    fn cleanup_backups_removes_only_launcher_when_runtime_missing() {
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        fs::create_dir(install.join(LAUNCHER_OLD_DIR)).unwrap();

        cleanup_backups(install).unwrap();
        assert!(!install.join(LAUNCHER_OLD_DIR).exists());
    }

    #[test]
    fn cleanup_tmp_removes_and_is_idempotent() {
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        fs::create_dir(install.join(TMP_DIR)).unwrap();
        cleanup_tmp(install).unwrap();
        assert!(!install.join(TMP_DIR).exists());
        // Second call OK — idempotent.
        cleanup_tmp(install).unwrap();
    }

    // --- extract_launcher_bundle / extract_jre_bundle ---

    #[test]
    fn extract_launcher_bundle_unpacks_to_correct_subdir() {
        use flate2::write::GzEncoder;
        use flate2::Compression;

        let tmp = tempdir().unwrap();
        let archive = tmp.path().join("launcher.tar.gz");

        // Build a small tar.gz
        let file = fs::File::create(&archive).unwrap();
        let gz = GzEncoder::new(file, Compression::default());
        let mut builder = tar::Builder::new(gz);
        let data = b"mock launcher executable";
        let mut header = tar::Header::new_gnu();
        header.set_path("SingularityMC.exe").unwrap();
        header.set_size(data.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        builder.append(&header, &data[..]).unwrap();
        builder.finish().unwrap();
        drop(builder); // flush gz

        extract_launcher_bundle(&archive, tmp.path()).unwrap();
        let target = tmp.path().join(LAUNCHER_DIR).join("SingularityMC.exe");
        assert!(target.exists());
        assert_eq!(fs::read(&target).unwrap(), data);
    }

    // --- perform_launcher_rollback ---

    #[test]
    fn perform_launcher_rollback_rolls_back_both_when_present() {
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        // Setup: .old dirs present (simulate backup taken)
        fs::create_dir(install.join(LAUNCHER_OLD_DIR)).unwrap();
        fs::write(install.join(LAUNCHER_OLD_DIR).join("a.txt"), b"launcher-old").unwrap();
        fs::create_dir(install.join(RUNTIME_OLD_DIR)).unwrap();
        fs::write(install.join(RUNTIME_OLD_DIR).join("b.txt"), b"runtime-old").unwrap();

        perform_launcher_rollback(install).unwrap();

        assert_eq!(
            fs::read(install.join(LAUNCHER_DIR).join("a.txt")).unwrap(),
            b"launcher-old"
        );
        assert_eq!(
            fs::read(install.join(RUNTIME_DIR).join("b.txt")).unwrap(),
            b"runtime-old"
        );
    }

    #[test]
    fn perform_launcher_rollback_tolerates_missing_runtime_old() {
        // Scenariusz: ostatni update pobierał tylko launcher (jre sha
        // unchanged), więc runtime.old/ nie był tworzony. Crash counter
        // rollback powinien jednak rollbackować launcher bez fail
        // na brakujące runtime backup.
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        fs::create_dir(install.join(LAUNCHER_OLD_DIR)).unwrap();
        fs::write(install.join(LAUNCHER_OLD_DIR).join("a.txt"), b"launcher-old").unwrap();

        perform_launcher_rollback(install).unwrap();
        assert!(install.join(LAUNCHER_DIR).join("a.txt").exists());
        assert!(!install.join(RUNTIME_DIR).exists());
    }

    #[test]
    fn perform_launcher_rollback_errors_when_no_launcher_backup() {
        // Bez żadnego backupu — nie ma czego rollbackować.
        let tmp = tempdir().unwrap();
        let err = perform_launcher_rollback(tmp.path()).unwrap_err();
        assert!(matches!(err, UpdaterError::Manifest(_)));
    }
}
