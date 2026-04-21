// Copyright (c) 2026 Echelon Team. All rights reserved.

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
pub const LAUNCHER_OLD_DIR: &str = "launcher.old";

/// Runtime nested INSIDE `launcher/` — jpackage launcher hardcodes sibling-
/// relative runtime lookup (Windows: `<exe_dir>/runtime/`, Linux: `<exe_dir>/
/// ../lib/runtime/`). Fighting tego layoutu kosztuje więcej niż daje — patch
/// do .cfg nie jest udokumentowany, junction na Windows wymaga user mode OR
/// admin zależnie od setupu. Adoptujemy native jpackage layout.
///
/// **Konsekwencja dla updatów:** runtime fizycznie siedzi w `launcher/runtime/`
/// (Win) lub `launcher/lib/runtime/` (Lin), ale logicznie jest OSOBNĄ paczką
/// (sha256 tracking, selective download). Gdy launcher update bez JRE update —
/// app.rs preserve'uje `launcher/{lib/,}runtime/` przed backup_launcher i
/// restore'uje po extract (patrz `preserve_runtime` / `restore_preserved_runtime`).
#[cfg(target_os = "windows")]
pub const RUNTIME_DIR: &str = "launcher/runtime";
#[cfg(target_os = "linux")]
pub const RUNTIME_DIR: &str = "launcher/lib/runtime";

#[cfg(target_os = "windows")]
pub const RUNTIME_OLD_DIR: &str = "launcher/runtime.old";
#[cfg(target_os = "linux")]
pub const RUNTIME_OLD_DIR: &str = "launcher/lib/runtime.old";

/// Tmp dir dla in-progress download'ów. Cleanupowany na success.
/// Uninstaller (.iss) usuwa go jeśli został po crash.
pub const TMP_DIR: &str = "tmp";

/// Względna ścieżka (pod `tmp/`) do preserved runtime podczas launcher-only
/// update. `preserve_runtime` przenosi `RUNTIME_DIR` tutaj zanim `backup_launcher`
/// move'nie całe `launcher/` do `launcher.old/`, a `restore_preserved_runtime`
/// przywraca po extract nowego launchera.
pub const RUNTIME_PRESERVE_REL: &str = "tmp/runtime-preserve";

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
/// - `local = None` (fresh install, brak `local-manifest.json`) →
///   launcher + jre always needed (installer nie dostarcza ich — pobiera auto-
///   update który ściąga resztę). Dla auto-update porównujemy `BUILD_VERSION`
///   (currently running binary) vs `remote.auto_update.version` — jeśli
///   matching, NIE pobieramy (installer already dostarczył bieżącą wersję).
///   Bez tego filtering fresh install zawsze pobierał .new file identyczny
///   bajt-po-bajcie z running binary, triggering `apply_pending` loop przy
///   następnym boot (self_replace kopiuje source → target, source zostaje).
/// - `local = Some(m)` → per-package compare:
///   - launcher / jre: sha256 `!=` (deterministic tar = same sha means same
///     content; downgrade sha też triggeruje download, co jest pożądane —
///     user manualnie rollback'ował release tag = akceptuje nowe bundle'e)
///   - auto-update: version `is_older_than` (monotonic: downgrade NIE
///     triggeruje download). Inaczej remote force-downgrade cofnąłby
///     security fixy w auto-update binarce. Hash fix dla WARN z code-
///     quality-v1 review — wcześniej `!=` pozwalał downgrade silently.
#[must_use]
pub fn decide_update(remote: &Manifest, local: Option<&Manifest>) -> UpdateDecision {
    decide_update_with_running(remote, local, &crate::Version::current())
}

/// Test-friendly core decyzji z jawnym `running_au` zamiast `BUILD_VERSION`.
/// Production wrapper [`decide_update`] przekazuje `Version::current()`; testy
/// dostają swobodę sterowania fresh-install edge case'ów bez hacka na
/// `BUILD_VERSION` (który jest compile-time const z Cargo.toml).
#[must_use]
pub fn decide_update_with_running(
    remote: &Manifest,
    local: Option<&Manifest>,
    running_au: &crate::Version,
) -> UpdateDecision {
    match local {
        None => {
            // Fresh install: porównaj running auto-update z
            // `remote.auto_update.version`. Jeśli running >= remote, NIE
            // stage'uj niepotrzebnego `.new`.
            //
            // Bez tego filtru fresh install zawsze pobierał .new identyczny
            // bajt-po-bajcie z running binary (installer właśnie dostarczył
            // tę samą wersję), triggering `apply_pending` loop przy
            // następnym boot (self_replace copies source→target, source file
            // zostaje, kolejne boot widzi .new → loop).
            //
            // Edge case: non-semver BUILD_VERSION → `is_older_than` fail-open
            // (false). Safe default "nie pobieraj, running binary jest OK".
            let auto_update_needed = running_au.is_older_than(&remote.auto_update.version);
            UpdateDecision {
                launcher_needed: true,
                jre_needed: true,
                auto_update_needed,
            }
        }
        Some(l) => UpdateDecision {
            launcher_needed: l.launcher.sha256 != remote.launcher.sha256,
            jre_needed: l.jre.sha256 != remote.jre.sha256,
            auto_update_needed: l
                .auto_update
                .version
                .is_older_than(&remote.auto_update.version),
        },
    }
}

/// Sprawdza czy sha256 lokalnego pliku matches oczekiwanego. Streaming —
/// nie ładuje całego pliku do pamięci (paczki bywają >30 MB).
///
/// **Error semantics:**
/// - Missing file / I/O read fail → `UpdaterError::Io`
/// - Hash mismatch → `UpdaterError::HashMismatch { path, expected, actual }`
///   z file name (ManifestPath-wrapped) — structured dla retry logic
///   (classifier: nie permanent, worth re-download).
pub fn verify_sha256(path: &Path, expected: &Sha256) -> Result<()> {
    use sha2::{Digest, Sha256 as Hasher};
    let file = fs::File::open(path).map_err(UpdaterError::Io)?;
    let mut reader = std::io::BufReader::new(file);
    let mut hasher = Hasher::new();
    std::io::copy(&mut reader, &mut hasher).map_err(UpdaterError::Io)?;
    let actual_hex = hex::encode(hasher.finalize());

    if actual_hex == expected.as_str() {
        return Ok(());
    }

    // Budujemy structured HashMismatch. ManifestPath wymaga relative +
    // no traversal; path argument jest full fs path (typowo tmp file),
    // więc używamy tylko file_name component — "launcher.tar.gz" etc.
    // przechodzi ManifestPath walidację (no slash, no traversal).
    let filename = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "<unknown>".to_string());
    let mpath = crate::ManifestPath::parse(&filename)
        .unwrap_or_else(|_| crate::ManifestPath::parse("unknown").expect("literal valid"));
    let actual = Sha256::parse(&actual_hex).ok();

    Err(UpdaterError::HashMismatch {
        path: mpath,
        expected: expected.clone(),
        actual,
    })
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
        // Parent of backup_path może być nested (RUNTIME_OLD_DIR = "launcher/
        // runtime.old" → parent "launcher/"). Create_dir_all idempotent.
        if let Some(parent) = backup_path.parent() {
            fs::create_dir_all(parent).map_err(UpdaterError::Io)?;
        }
        fs::rename(&current_path, &backup_path).map_err(UpdaterError::Io)?;
    }
    Ok(())
}

/// Launcher-only update scenario: `launcher/` zawiera runtime zagnieżdżone
/// (RUNTIME_DIR = "launcher/runtime" lub "launcher/lib/runtime"), ale
/// launcher.tar.gz NIE zawiera runtime/ (excluded w copy-release-files.sh).
/// Jeśli po prostu backup_launcher → extract, nowy `launcher/` nie będzie
/// miał JRE → launcher nie wystartuje.
///
/// Flow preserve/restore:
/// 1. `preserve_runtime`: mv `install_dir/<RUNTIME_DIR>` → `install_dir/
///    tmp/runtime-preserve/` (wywołane PRZED backup_launcher)
/// 2. `backup_launcher`: mv launcher/ → launcher.old/ (runtime już przeniesiony)
/// 3. extract launcher.tar.gz → launcher/
/// 4. `restore_preserved_runtime`: mv tmp/runtime-preserve/ → `install_dir/
///    <RUNTIME_DIR>` (parent z extract już istnieje)
///
/// Idempotent: no-op gdy źródło nie istnieje (fresh install, JRE update
/// path gdzie nie preserve'owaliśmy).
pub fn preserve_runtime(install_dir: &Path, preserve_path: &Path) -> Result<()> {
    let runtime = install_dir.join(RUNTIME_DIR);
    if !runtime.exists() {
        return Ok(());
    }
    if let Some(parent) = preserve_path.parent() {
        fs::create_dir_all(parent).map_err(UpdaterError::Io)?;
    }
    if preserve_path.exists() {
        fs::remove_dir_all(preserve_path).map_err(UpdaterError::Io)?;
    }
    fs::rename(&runtime, preserve_path).map_err(UpdaterError::Io)?;
    Ok(())
}

/// Restore preserved runtime post-launcher-extract. Idempotent — no-op gdy
/// preserve_path nie istnieje (fresh install, brak preserve'a).
///
/// Gdy destination (`install_dir/<RUNTIME_DIR>`) już istnieje (np. regres
/// extract tarballu przypadkowo include'ował runtime/) — usuwa przed move,
/// żeby fs::rename nie fail z `AlreadyExists`.
pub fn restore_preserved_runtime(install_dir: &Path, preserve_path: &Path) -> Result<()> {
    if !preserve_path.exists() {
        return Ok(());
    }
    let runtime = install_dir.join(RUNTIME_DIR);
    if let Some(parent) = runtime.parent() {
        fs::create_dir_all(parent).map_err(UpdaterError::Io)?;
    }
    if runtime.exists() {
        fs::remove_dir_all(&runtime).map_err(UpdaterError::Io)?;
    }
    fs::rename(preserve_path, &runtime).map_err(UpdaterError::Io)?;
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

/// Extract jre bundle tar.gz z `tmp/jre-<os>.tar.gz` do nested `RUNTIME_DIR`
/// (Windows: `launcher/runtime/`, Linux: `launcher/lib/runtime/`).
///
/// Tarball layout: FLAT — `bin/java.exe`, `conf/`, `lib/`, `release` bez
/// `runtime/` prefix (copy-release-files.sh używa `tar -C $RUNTIME_SRC .`).
/// Extract do RUNTIME_DIR daje `install_dir/launcher/runtime/bin/java.exe`.
/// Parent RUNTIME_DIR (`launcher/` lub `launcher/lib/`) musi istnieć — caller
/// odpowiedzialny (typowo extract launcher tarball poprzedza ten call; fresh
/// install path tworzy launcher/ przez extract_launcher_bundle).
pub fn extract_jre_bundle(tmp_archive: &Path, install_dir: &Path) -> Result<()> {
    let target = install_dir.join(RUNTIME_DIR);
    // Parent RUNTIME_DIR może jeszcze nie istnieć jeśli JRE-only update na
    // świeżym install_dir (bez launcher/). Tworzymy chain.
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(UpdaterError::Io)?;
    }
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
    fn decide_update_fresh_install_all_needed_when_running_older() {
        // Fresh install z running auto-update STARSZY niż remote → wszystkie
        // trzy needed (installer dostarczył auto-update v1.0.0, manifest
        // wymaga v1.1.0 — trzeba pobrać nowszy).
        let remote = dummy_manifest("1", "2", "1.1.0");
        let running = Version::parse("1.0.0").unwrap();
        let decision = decide_update_with_running(&remote, None, &running);
        assert!(decision.launcher_needed);
        assert!(decision.jre_needed);
        assert!(decision.auto_update_needed);
        assert!(decision.any());
    }

    #[test]
    fn decide_update_fresh_install_skips_auto_update_when_running_matches() {
        // Regression guard 2026-04-21: installer dostarczył auto-update w tej
        // samej wersji co manifest.auto_update.version. Poprzednio
        // auto_update_needed = true ZAWSZE na fresh install → pobierał
        // identyczny binary jako .new → apply_pending loop (Bug A composed
        // z Bug B). Fix: compare running vs remote, skip download gdy match.
        let remote = dummy_manifest("1", "2", "1.3.3");
        let running = Version::parse("1.3.3").unwrap();
        let decision = decide_update_with_running(&remote, None, &running);
        assert!(decision.launcher_needed);
        assert!(decision.jre_needed);
        assert!(
            !decision.auto_update_needed,
            "running AU == remote AU powinno wyłączyć pobieranie (fresh install no-op dla AU)"
        );
    }

    #[test]
    fn decide_update_fresh_install_skips_auto_update_when_running_newer() {
        // Edge case: dev-installed running auto-update nowszy niż manifest
        // (np. manual install nightly nad stable release). Downgrade-skip
        // policy — monotonic version, nie pobieraj "starszego".
        let remote = dummy_manifest("1", "2", "1.2.0");
        let running = Version::parse("1.3.3").unwrap();
        let decision = decide_update_with_running(&remote, None, &running);
        assert!(
            !decision.auto_update_needed,
            "running newer than remote powinno NIE triggerować downgrade download"
        );
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

    #[test]
    fn decide_update_skips_auto_update_downgrade() {
        // Monotonic policy: remote < local auto-update version → NIE
        // triggeruje download. Chroni przed force-downgrade security fixów.
        let remote = dummy_manifest("1", "2", "1.0.5");
        let local = dummy_manifest("1", "2", "1.2.0");
        let decision = decide_update(&remote, Some(&local));
        assert!(!decision.auto_update_needed, "downgrade must not trigger download");
    }

    #[test]
    fn decide_update_equal_auto_update_no_download() {
        // Identyczna wersja (typowy case gdy bundle się zmienił ale
        // auto-update został) — skip download.
        let remote = dummy_manifest("9", "9", "1.1.0");
        let local = dummy_manifest("1", "1", "1.1.0");
        let decision = decide_update(&remote, Some(&local));
        assert!(decision.launcher_needed);
        assert!(decision.jre_needed);
        assert!(!decision.auto_update_needed);
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
    fn verify_sha256_fails_for_mismatch_with_structured_error() {
        let tmp = tempdir().unwrap();
        let path = tmp.path().join("file.bin");
        fs::write(&path, b"actual content").unwrap();

        let wrong = Sha256::parse(&"a".repeat(64)).unwrap();
        let err = verify_sha256(&path, &wrong).unwrap_err();

        match err {
            UpdaterError::HashMismatch { path, expected, actual } => {
                assert_eq!(path.as_str(), "file.bin");
                assert_eq!(expected.as_str(), &"a".repeat(64));
                assert!(actual.is_some(), "actual hash must be captured");
                assert_ne!(actual.unwrap().as_str(), expected.as_str());
            }
            other => panic!("expected HashMismatch, got {other:?}"),
        }
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
        // RUNTIME_DIR zagnieżdżone ("launcher/runtime" lub "launcher/lib/
        // runtime") — parent musi istnieć przed write.
        fs::create_dir_all(&runtime).unwrap();
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
        // RUNTIME_OLD_DIR jest zagnieżdżony (`launcher/runtime.old` lub
        // `launcher/lib/runtime.old`) — create_dir_all dla chained parents.
        fs::create_dir_all(install.join(RUNTIME_OLD_DIR)).unwrap();

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

    // --- preserve_runtime / restore_preserved_runtime ---

    #[test]
    fn preserve_and_restore_runtime_round_trip() {
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        let runtime = install.join(RUNTIME_DIR);
        fs::create_dir_all(&runtime).unwrap();
        fs::write(runtime.join("java.marker"), b"original-jre").unwrap();

        let preserve = install.join(RUNTIME_PRESERVE_REL);
        preserve_runtime(install, &preserve).unwrap();

        // Po preserve: runtime path pusty, preserve_path ma content.
        assert!(!runtime.exists(), "runtime path powinien być przeniesiony");
        assert!(
            preserve.join("java.marker").exists(),
            "content powinien być w preserve_path"
        );

        // Simulate launcher extract: parent RUNTIME_DIR pojawia się (launcher/
        // lub launcher/lib/). restore_preserved_runtime tworzy RUNTIME_DIR i
        // wrzuca content z preserve.
        restore_preserved_runtime(install, &preserve).unwrap();
        assert_eq!(
            fs::read(runtime.join("java.marker")).unwrap(),
            b"original-jre",
            "preserved runtime powinien być restored dokładnie"
        );
        assert!(!preserve.exists(), "preserve path powinien być zużyty");
    }

    #[test]
    fn preserve_runtime_no_op_when_runtime_missing() {
        // Fresh install path: nie ma runtime → preserve to no-op, preserve_path
        // nie powstaje.
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        let preserve = install.join(RUNTIME_PRESERVE_REL);

        preserve_runtime(install, &preserve).unwrap();
        assert!(!preserve.exists(), "preserve_path nie powinien powstać bez źródła");
    }

    #[test]
    fn restore_preserved_runtime_no_op_when_preserve_missing() {
        // Normal flow bez preserve'a (JRE update path) — restore powinien
        // być idempotent no-op.
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        let preserve = install.join(RUNTIME_PRESERVE_REL);

        restore_preserved_runtime(install, &preserve).unwrap();
        assert!(!install.join(RUNTIME_DIR).exists());
    }

    #[test]
    fn restore_preserved_runtime_overwrites_existing_destination() {
        // Edge case: new launcher tarball niespodziewanie zawiera runtime/
        // (regres build config). Restore powinien nadpisać a nie fail z
        // AlreadyExists (fs::rename wymagałoby clean target).
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        let runtime = install.join(RUNTIME_DIR);
        let preserve = install.join(RUNTIME_PRESERVE_REL);

        fs::create_dir_all(&preserve).unwrap();
        fs::write(preserve.join("preserved.bin"), b"preserved").unwrap();
        fs::create_dir_all(&runtime).unwrap();
        fs::write(runtime.join("unexpected.bin"), b"from-extract").unwrap();

        restore_preserved_runtime(install, &preserve).unwrap();
        assert!(runtime.join("preserved.bin").exists());
        assert!(!runtime.join("unexpected.bin").exists());
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
        // Simulate realistic post-backup state gdzie backup_runtime był
        // wywołany PRZED backup_launcher:
        //   1. mv launcher/runtime → launcher/runtime.old (backup_runtime)
        //   2. mv launcher → launcher.old (backup_launcher, bierze runtime.old w sobie)
        // Result: launcher.old/ + launcher.old/runtime.old/ (zagnieżdżone).
        let tmp = tempdir().unwrap();
        let install = tmp.path();
        fs::create_dir(install.join(LAUNCHER_OLD_DIR)).unwrap();
        fs::write(install.join(LAUNCHER_OLD_DIR).join("a.txt"), b"launcher-old").unwrap();
        // runtime.old zagnieżdżony W launcher.old/ — matches post-backup layout.
        // Używamy raw path bo RUNTIME_OLD_DIR = "launcher/runtime.old" odnosi
        // się do POST-rollback state, nie POST-backup.
        let runtime_old_in_launcher_old = install.join(LAUNCHER_OLD_DIR).join("runtime.old");
        fs::create_dir(&runtime_old_in_launcher_old).unwrap();
        fs::write(runtime_old_in_launcher_old.join("b.txt"), b"runtime-old").unwrap();

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
        // Scenariusz: ostatni update pobierał tylko launcher + preserved
        // runtime został przywrócony bezpośrednio do RUNTIME_DIR (bez
        // tworzenia runtime.old), więc rollback path widzi samo launcher.old/.
        // perform_launcher_rollback powinien rollbackować launcher bez fail.
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
