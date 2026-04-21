// Copyright (c) 2026 Echelon Team. All rights reserved.

//! Release manifest types matching the `manifest-<os>.json` schema emitted
//! przez Phase 3 release workflow (jeden manifest per release × OS).
//!
//! **Format evolution (od v1.1.x):** struktura przeszła z per-file
//! (`files: Vec<FileEntry>`) na 3-package model — `launcher`, `jre`,
//! `autoUpdate` jako named fields. Motywacja:
//! - Eliminuje GitHub secondary rate limit na upload (250 assetów → ~5)
//! - Eliminuje dotfile 404 bug (dotfiles wewnątrz tar nie są filtrowane
//!   przez GitHub release API, tylko gołe asset names)
//! - Upraszcza update flow (1 download + 1 extract per bundle vs 250
//!   osobnych HTTP + fs operations)
//!
//! **Update decision strategy:**
//! - `launcher` + `jre` — sha256 compare (deterministic tar packing =
//!   identical sha256 gdy source nie zmienił się)
//! - `autoUpdate` — `version` compare (Cargo.toml bump controlled przez
//!   developera, `feedback_auto_update_version_bump` rule)
//!
//! **Local state:** `install_dir/local-manifest.json` zachowuje snapshot
//! aktualnego stanu (what's installed). Diff vs remote decyduje download.

use crate::{util, ManifestPath, Result, Sha256, UpdaterError, Version};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// Target OS identifier na wire. Matches per-OS asset naming
/// (`manifest-windows.json`, `manifest-linux.json`, `launcher-windows.tar.gz`
/// etc.). `#[non_exhaustive]` future-proofs dodanie macOS / ARM wariantów
/// bez breaking downstream matcherów w zewnętrznych crates (wewnątrz
/// naszego crate'a match pozostaje exhaustive, bez wildcard — zweryfikowane
/// przez web-research, non_exhaustive ignored within defining crate).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum OsTarget {
    Windows,
    Linux,
}

/// Top-level manifest dla jednego release × OS.
///
/// Wire field names = camelCase (via `#[serde(rename_all = "camelCase")]`);
/// Rust field names snake_case per language convention. Nested structs
/// (`PackageEntry`, `AutoUpdatePackage`) powtarzają `rename_all` bo serde
/// `rename_all` NIE propaguje rekursywnie — trzeba deklarować per struct.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    /// Release tag (bez prefiksu `v`), np. "0.4.7". Identyfikuje całą
    /// dystrybucję — gdy lokalna `version` != remote, trigger update flow.
    pub version: Version,
    pub os: OsTarget,
    /// ISO-8601 UTC timestamp. Opaque string na tym poziomie — używany
    /// tylko dla UI display. Unikamy zależności od `chrono`/`time` dla
    /// jednego display-only field.
    pub released_at: String,
    /// Najstarsza akceptowana wersja auto-update która może zainstalować
    /// ten manifest. Jeśli local `BUILD_VERSION < min_auto_update_version`,
    /// `app.rs::process_release` emituje FatalError z komunikatem
    /// "pobierz nowy installer" — schema major bumps via tego gate.
    pub min_auto_update_version: Version,
    /// Path w install_dir do launcher executable (walidowany ManifestPath —
    /// no traversal, relative, forward-slash). Typowo: "launcher/SingularityMC.exe"
    /// (Windows), "launcher/bin/SingularityMC" (Linux — Compose Desktop
    /// `createDistributable` używa GLOBAL packageName "SingularityMC" na obu
    /// OS; per-OS linux.packageName override dotyczy tylko .deb/.rpm).
    pub launcher_executable: ManifestPath,
    /// Markdown changelog — wyświetlany w UI auto-update + news feed launcher-a
    /// (spec §4.12). Domyślnie pusty string dla release'ów bez notes.
    #[serde(default)]
    pub changelog: String,
    /// Launcher bundle (tar.gz): JARs, native Compose libs, SingularityMC.exe,
    /// cfg, dotfiles z app/ folder. Wszystko poza `runtime/` z jpackage output.
    pub launcher: PackageEntry,
    /// JRE bundle (tar.gz): content folder `runtime/` z jpackage output
    /// (bin/java.exe, lib/jvm.dll, modules, conf, ...).
    pub jre: PackageEntry,
    /// Auto-update binary (raw .exe / Linux ELF, NIE w tar — pojedynczy plik
    /// żeby self_replace crate mógł swap-in-place).
    pub auto_update: AutoUpdatePackage,
}

/// Generic tar.gz package entry: download URL + integrity + size.
/// Identyfikowany przez sha256 — deterministic tar packing gwarantuje
/// że remote sha == local sha gdy source nie zmienił się (skip download).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackageEntry {
    /// Download URL — full `https://github.com/Echelon-Team/SingularityMC/
    /// releases/download/v{ver}/{asset-name}`. Asset names są immutable
    /// per memory rule `project_release_asset_naming_immutable`.
    pub url: String,
    pub sha256: Sha256,
    pub size: u64,
}

/// Auto-update binary entry: dodatkowe `version` field (z Cargo.toml
/// `[package].version`) dla decyzji download. Porównujemy `version`,
/// nie sha256 — rebuild w CI daje różne sha nawet bez zmian kodu
/// (timestamps, build metadata). `version` bump jest controlled przez
/// developera (feedback rule).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AutoUpdatePackage {
    pub url: String,
    pub sha256: Sha256,
    pub size: u64,
    /// SemVer z `auto-update/Cargo.toml [package].version`, np. "1.1.4".
    pub version: Version,
}

impl Manifest {
    /// Parse JSON manifest string. Fail-fast na schema / type / validation
    /// mismatch (invalid SHA-256, unknown OS, missing field, traversal path).
    /// Newtypes (Version, Sha256, ManifestPath) samodzielnie walidują przez
    /// `#[serde(try_from = "String")]`.
    pub fn parse(json: &str) -> Result<Self> {
        serde_json::from_str(json).map_err(UpdaterError::Json)
    }

    /// Pretty-print JSON dla local-manifest.json (human-readable diffs
    /// w razie manualnego sprawdzenia stanu instalki).
    pub fn to_json(&self) -> Result<String> {
        serde_json::to_string_pretty(self).map_err(UpdaterError::Json)
    }

    /// Read local manifest z `install_dir/local-manifest.json`.
    /// Missing file → Ok(None) (fresh install, nothing to compare).
    /// Corrupt JSON → Err — caller decyduje (zwykle: treat as fresh).
    pub fn read_local(install_dir: &Path) -> Result<Option<Self>> {
        let path = local_manifest_path(install_dir);
        if !path.exists() {
            return Ok(None);
        }
        let json = std::fs::read_to_string(&path).map_err(UpdaterError::Io)?;
        Self::parse(&json).map(Some)
    }

    /// Persist self jako `install_dir/local-manifest.json`. Atomic write
    /// via `util::atomic_write_bytes` (temp + rename + fsync) — unikamy
    /// half-written plik gdy crash między pisaniem a fsync.
    pub fn write_local(&self, install_dir: &Path) -> Result<()> {
        let path = local_manifest_path(install_dir);
        let json = self.to_json()?;
        util::atomic_write_bytes(&path, json.as_bytes())
    }
}

/// Where `local-manifest.json` lives under `install_dir`. Public helper
/// dla modułów które potrzebują raw path (np. uninstall cleanup lub
/// diagnostyka) bez konstruowania Manifest instance.
#[must_use]
pub fn local_manifest_path(install_dir: &Path) -> PathBuf {
    install_dir.join("local-manifest.json")
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    const VALID_MANIFEST: &str = r#"{
        "version": "0.4.7",
        "os": "windows",
        "releasedAt": "2026-04-20T12:34:56Z",
        "minAutoUpdateVersion": "1.0.0",
        "launcherExecutable": "launcher/SingularityMC.exe",
        "changelog": "- Fixed X\n- Added Y",
        "launcher": {
            "url": "https://github.com/Echelon-Team/SingularityMC/releases/download/v0.4.7/launcher-windows.tar.gz",
            "sha256": "0000000000000000000000000000000000000000000000000000000000000001",
            "size": 26214400
        },
        "jre": {
            "url": "https://github.com/Echelon-Team/SingularityMC/releases/download/v0.4.7/jre-windows.tar.gz",
            "sha256": "0000000000000000000000000000000000000000000000000000000000000002",
            "size": 31457280
        },
        "autoUpdate": {
            "url": "https://github.com/Echelon-Team/SingularityMC/releases/download/v0.4.7/auto-update-windows.exe",
            "sha256": "0000000000000000000000000000000000000000000000000000000000000003",
            "size": 6291456,
            "version": "1.1.4"
        }
    }"#;

    #[test]
    fn parse_valid_manifest_fills_all_fields() {
        let m = Manifest::parse(VALID_MANIFEST).unwrap();
        assert_eq!(m.version.as_str(), "0.4.7");
        assert_eq!(m.os, OsTarget::Windows);
        assert_eq!(m.released_at, "2026-04-20T12:34:56Z");
        assert_eq!(m.min_auto_update_version.as_str(), "1.0.0");
        assert_eq!(m.launcher_executable.as_str(), "launcher/SingularityMC.exe");
        assert!(m.changelog.contains("Fixed X"));
        assert_eq!(m.launcher.size, 26214400);
        assert_eq!(m.jre.size, 31457280);
        assert_eq!(m.auto_update.size, 6291456);
        assert_eq!(m.auto_update.version.as_str(), "1.1.4");
    }

    #[test]
    fn roundtrip_to_json_and_back_preserves_all_fields() {
        // Struct-based serde zachowuje insertion order z deklaracji struct,
        // więc bit-exact roundtrip jest gwarantowany (żadnych HashMap).
        let m = Manifest::parse(VALID_MANIFEST).unwrap();
        let serialized = m.to_json().unwrap();
        let reparsed = Manifest::parse(&serialized).unwrap();
        assert_eq!(m, reparsed);
    }

    #[test]
    fn parse_missing_launcher_field_fails() {
        let invalid = VALID_MANIFEST.replace(r#""launcher":"#, r#""invalid":"#);
        let result = Manifest::parse(&invalid);
        assert!(matches!(result, Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_missing_jre_field_fails() {
        let invalid = VALID_MANIFEST.replace(r#""jre":"#, r#""invalid":"#);
        let result = Manifest::parse(&invalid);
        assert!(matches!(result, Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_missing_auto_update_field_fails() {
        let invalid = VALID_MANIFEST.replace(r#""autoUpdate":"#, r#""invalid":"#);
        let result = Manifest::parse(&invalid);
        assert!(matches!(result, Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_invalid_sha256_length_fails() {
        let invalid = VALID_MANIFEST.replace(
            "0000000000000000000000000000000000000000000000000000000000000001",
            "abc",
        );
        let result = Manifest::parse(&invalid);
        assert!(result.is_err());
    }

    #[test]
    fn parse_unknown_os_variant_fails() {
        let invalid = VALID_MANIFEST.replace(r#""os": "windows""#, r#""os": "freebsd""#);
        let result = Manifest::parse(&invalid);
        assert!(matches!(result, Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_absolute_launcher_executable_fails() {
        // ManifestPath walidator rejects absolute paths + ../ traversal.
        let invalid = VALID_MANIFEST.replace(
            r#""launcherExecutable": "launcher/SingularityMC.exe""#,
            r#""launcherExecutable": "/etc/passwd""#,
        );
        let result = Manifest::parse(&invalid);
        assert!(result.is_err());
    }

    #[test]
    fn parse_default_changelog_when_missing() {
        // `#[serde(default)]` dla changelog — manifest bez tego pola
        // deserializuje z pustym string.
        let minimal = VALID_MANIFEST.replace(r#""changelog": "- Fixed X\n- Added Y","#, "");
        let m = Manifest::parse(&minimal).unwrap();
        assert_eq!(m.changelog, "");
    }

    #[test]
    fn read_local_returns_none_when_missing() {
        let tmp = tempdir().unwrap();
        let result = Manifest::read_local(tmp.path()).unwrap();
        assert!(result.is_none());
    }

    #[test]
    fn read_local_parses_existing_file() {
        let tmp = tempdir().unwrap();
        std::fs::write(tmp.path().join("local-manifest.json"), VALID_MANIFEST).unwrap();
        let result = Manifest::read_local(tmp.path()).unwrap().unwrap();
        assert_eq!(result.version.as_str(), "0.4.7");
    }

    #[test]
    fn read_local_returns_err_on_corrupt_json() {
        let tmp = tempdir().unwrap();
        std::fs::write(tmp.path().join("local-manifest.json"), "{not valid").unwrap();
        let result = Manifest::read_local(tmp.path());
        assert!(matches!(result, Err(UpdaterError::Json(_))));
    }

    #[test]
    fn write_local_then_read_roundtrip() {
        let tmp = tempdir().unwrap();
        let original = Manifest::parse(VALID_MANIFEST).unwrap();
        original.write_local(tmp.path()).unwrap();

        let loaded = Manifest::read_local(tmp.path()).unwrap().unwrap();
        assert_eq!(original, loaded);
    }

    #[test]
    fn write_local_overwrites_existing() {
        let tmp = tempdir().unwrap();
        // Pierwszy write
        let original = Manifest::parse(VALID_MANIFEST).unwrap();
        original.write_local(tmp.path()).unwrap();

        // Drugi write z inną wersją — atomic rename nadpisuje
        let modified_json = VALID_MANIFEST.replace(r#""version": "0.4.7""#, r#""version": "0.4.8""#);
        let modified = Manifest::parse(&modified_json).unwrap();
        modified.write_local(tmp.path()).unwrap();

        let loaded = Manifest::read_local(tmp.path()).unwrap().unwrap();
        assert_eq!(loaded.version.as_str(), "0.4.8");
    }

    #[test]
    fn local_manifest_path_joins_install_dir() {
        let p = local_manifest_path(Path::new("/tmp/install"));
        assert_eq!(p, PathBuf::from("/tmp/install/local-manifest.json"));
    }
}
