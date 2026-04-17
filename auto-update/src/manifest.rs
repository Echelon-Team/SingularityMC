//! Release manifest types matching the `manifest.json` schema emitted by the
//! Phase 3 release workflow (one manifest per release × OS).
//!
//! The remote manifest is fetched from GitHub Releases assets in Task 2.4.
//! A copy of the currently-installed manifest is persisted as
//! `local-manifest.json` under the launcher install dir so we can diff
//! subsequent remote manifests and only download changed files.

use crate::{util, ManifestPath, Result, Sha256, UpdaterError, Version};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::io;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// Target OS identifier on the wire. Matches the per-OS asset naming
/// convention in GitHub releases (`manifest-windows.json`, `manifest-linux.json`).
/// `#[non_exhaustive]` future-proofs adding macOS or ARM variants without
/// breaking downstream matchers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum OsTarget {
    Windows,
    Linux,
}

/// Top-level manifest for a single release × OS.
///
/// Wire field names use camelCase via `#[serde(rename_all = "camelCase")]` —
/// keeps the JSON schema consistent without per-field renames. Rust field
/// names stay snake_case per language convention.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub version: Version,
    pub os: OsTarget,
    /// ISO-8601 UTC timestamp. Opaque string at this layer — UI display only.
    /// Introducing a `chrono`/`time` dep for one display-only field would be
    /// disproportionate.
    pub released_at: String,
    /// Lowest auto-update binary version that can safely install this
    /// manifest. If our `BUILD_VERSION < min_auto_update_version`, Task 2.7
    /// must self-update before proceeding.
    pub min_auto_update_version: Version,
    /// Path inside `install_dir` to the launcher executable, validated
    /// (no traversal, relative, forward-slash-separated) via [`ManifestPath`].
    /// Used by the launcher-spawner in Task 2.8.
    pub launcher_executable: ManifestPath,
    /// Markdown changelog rendered in the auto-update UI and surfaced as the
    /// launcher's Aktualności entry for this release (spec 4.12).
    #[serde(default)]
    pub changelog: String,
    pub files: Vec<FileEntry>,
}

/// Individual tracked file: validated install path, download URL, declared
/// size, and SHA-256 integrity hash.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileEntry {
    /// Path relative to `install_dir` — validated at construction time.
    /// See [`ManifestPath`] for the security rationale (zip-slip protection).
    pub path: ManifestPath,
    /// Download URL. Kept as `String` for Task 2.3 — reqwest accepts `&str`
    /// and does its own parse in Task 2.5. Promote to `url::Url` if/when
    /// host allowlisting (e.g. pinning to github.com) becomes a requirement.
    pub url: String,
    pub size: u64,
    pub sha256: Sha256,
}

impl Manifest {
    /// Parse a JSON manifest string. Fails fast on schema / type / validation
    /// mismatch (invalid SHA-256, unknown OS enum, missing required field,
    /// empty version, traversal in a path) AND on semantic validation
    /// (duplicate paths inside `files`).
    pub fn parse(json: &str) -> Result<Self> {
        let manifest: Manifest = serde_json::from_str(json)?;
        manifest.validate()?;
        Ok(manifest)
    }

    pub fn to_json(&self) -> Result<String> {
        serde_json::to_string_pretty(self).map_err(UpdaterError::Json)
    }

    /// Post-deserialization semantic check. Today: no duplicate paths inside
    /// `files`. Future additions (e.g. total-size sanity bounds) land here.
    fn validate(&self) -> Result<()> {
        let mut seen: HashSet<&str> = HashSet::with_capacity(self.files.len());
        for entry in &self.files {
            if !seen.insert(entry.path.as_str()) {
                return Err(UpdaterError::Manifest(format!(
                    "duplicate file path in manifest: {}",
                    entry.path
                )));
            }
        }
        Ok(())
    }
}

/// Where `local-manifest.json` lives under `install_dir`.
#[must_use]
pub fn local_manifest_path(install_dir: &Path) -> PathBuf {
    install_dir.join("local-manifest.json")
}

/// Load the installed-version manifest. Returns `None` for a fresh install
/// (file absent) AND for a corrupt/unreadable local file — with a warn log
/// in the corrupt case, and the corrupt file renamed to
/// `local-manifest.json.corrupt-{unix_ts}` so the next launch doesn't repeat
/// the warn and forensic copies accumulate rather than overwrite.
///
/// A `None` return causes the differ to treat all remote files as needing
/// download — a safe (if bandwidth-costly) recovery from local-state rot.
#[must_use]
pub fn load_local(install_dir: &Path) -> Option<Manifest> {
    let path = local_manifest_path(install_dir);
    match std::fs::read_to_string(&path) {
        Ok(content) => match Manifest::parse(&content) {
            Ok(m) => Some(m),
            Err(e) => {
                let ts = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                let corrupt = path.with_extension(format!("json.corrupt-{ts}"));
                log::warn!(
                    "local-manifest.json at {} is corrupt: {e}; renaming to {} and treating as absent — next update will redownload all files",
                    path.display(),
                    corrupt.display()
                );
                if let Err(rename_err) = std::fs::rename(&path, &corrupt) {
                    log::warn!(
                        "Failed to preserve corrupt local-manifest.json as {}: {rename_err}",
                        corrupt.display()
                    );
                }
                None
            }
        },
        Err(e) if e.kind() == io::ErrorKind::NotFound => None,
        Err(e) => {
            log::warn!(
                "Failed to read local-manifest.json at {}: {e}; treating as absent — next update will redownload all files",
                path.display()
            );
            None
        }
    }
}

/// Persist the post-update manifest as the new "installed" state.
pub fn save_local(install_dir: &Path, manifest: &Manifest) -> Result<()> {
    let path = local_manifest_path(install_dir);
    let parent = path
        .parent()
        .expect("local_manifest_path always has a parent dir by construction");
    std::fs::create_dir_all(parent)?;
    let content = manifest.to_json()?;
    util::atomic_write_bytes(&path, content.as_bytes())
}

/// Compute the set of files the downloader (Task 2.5) must fetch: every
/// remote entry whose SHA-256 differs from the corresponding local entry, or
/// that is missing locally entirely.
///
/// `local = None` returns every remote file — matching the fresh-install case.
/// Files present only in the local manifest (deleted upstream) are NOT
/// reported: cleanup of orphaned files is the updater's concern (Task 2.6).
#[must_use]
pub fn diff_manifests(local: Option<&Manifest>, remote: &Manifest) -> Vec<FileEntry> {
    let local_hashes: HashMap<&str, &Sha256> = local
        .map(|m| {
            m.files
                .iter()
                .map(|f| (f.path.as_str(), &f.sha256))
                .collect()
        })
        .unwrap_or_default();

    remote
        .files
        .iter()
        .filter(|remote_file| match local_hashes.get(remote_file.path.as_str()) {
            Some(local_hash) => *local_hash != &remote_file.sha256,
            None => true,
        })
        .cloned()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    // 64-char lowercase hex placeholders — valid Sha256 payloads, distinct
    // enough to pin which one survived through assertions.
    const HASH_A: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";
    const HASH_B: &str = "b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1";
    const HASH_N: &str = "c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2";

    fn file(path: &str, hash_hex: &str) -> FileEntry {
        FileEntry {
            path: ManifestPath::parse(path).unwrap(),
            url: format!("https://example.com/{path}"),
            size: 100,
            sha256: Sha256::parse(hash_hex).unwrap(),
        }
    }

    fn manifest(version: &str, files: Vec<FileEntry>) -> Manifest {
        Manifest {
            version: Version::parse(version).unwrap(),
            os: OsTarget::Windows,
            released_at: "2026-04-15T10:00:00Z".to_string(),
            min_auto_update_version: Version::parse("0.1.0").unwrap(),
            launcher_executable: ManifestPath::parse("launcher/SingularityMC.exe").unwrap(),
            changelog: "- fix".to_string(),
            files,
        }
    }

    // --- Parsing + validation ---

    #[test]
    fn parse_valid_manifest_round_trips() {
        let original = manifest("1.2.3", vec![file("a.jar", HASH_A), file("b.jar", HASH_B)]);
        let json = original.to_json().unwrap();
        let parsed = Manifest::parse(&json).unwrap();
        assert_eq!(parsed, original);
    }

    #[test]
    fn parse_uses_camelcase_wire_fields() {
        // Pin the camelCase rename_all — guards against a future refactor
        // switching the wire format without coordinating with the Phase 3
        // release workflow.
        let m = manifest("1.2.3", vec![]);
        let json = m.to_json().unwrap();
        assert!(json.contains("\"releasedAt\""));
        assert!(json.contains("\"minAutoUpdateVersion\""));
        assert!(json.contains("\"launcherExecutable\""));
        // snake_case on wire must NOT appear.
        assert!(!json.contains("released_at"));
        assert!(!json.contains("min_auto_update_version"));
        assert!(!json.contains("launcher_executable"));
    }

    #[test]
    fn parse_rejects_invalid_sha256() {
        let bad = r#"{
            "version":"1.0.0","os":"windows","releasedAt":"2026-04-15T10:00:00Z",
            "minAutoUpdateVersion":"0.1.0","launcherExecutable":"app.exe",
            "files":[{"path":"a.jar","url":"https://example.com/a.jar","size":100,"sha256":"TOOSHORT"}]
        }"#;
        assert!(matches!(Manifest::parse(bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_uppercase_sha256() {
        let uppercase_hash = "A".repeat(64);
        let bad = format!(
            r#"{{"version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0","launcherExecutable":"x","files":[{{"path":"a","url":"u","size":1,"sha256":"{uppercase_hash}"}}]}}"#
        );
        assert!(matches!(Manifest::parse(&bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_invalid_os() {
        let bad = r#"{
            "version":"1.0.0","os":"macos","releasedAt":"t","minAutoUpdateVersion":"0.1.0",
            "launcherExecutable":"app.exe","files":[]
        }"#;
        assert!(matches!(Manifest::parse(bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_capitalized_os_enforcing_lowercase_rename() {
        // Pins rename_all="lowercase" — a future regression switching to
        // case-insensitive matching would silently accept "Windows".
        let bad = r#"{
            "version":"1.0.0","os":"Windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0",
            "launcherExecutable":"app.exe","files":[]
        }"#;
        assert!(matches!(Manifest::parse(bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_empty_version() {
        let bad = r#"{
            "version":"","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0",
            "launcherExecutable":"app.exe","files":[]
        }"#;
        assert!(matches!(Manifest::parse(bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_missing_required_field() {
        let bad = r#"{
            "version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0",
            "launcherExecutable":"app.exe"
        }"#;
        assert!(matches!(Manifest::parse(bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_duplicate_paths_in_files() {
        // Schema invariant — malformed generator or malicious manifest must
        // not produce order-dependent diff behavior.
        let bad = format!(
            r#"{{"version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0","launcherExecutable":"app.exe","files":[{{"path":"dup.jar","url":"u","size":1,"sha256":"{HASH_A}"}},{{"path":"dup.jar","url":"u","size":1,"sha256":"{HASH_B}"}}]}}"#
        );
        let result = Manifest::parse(&bad);
        assert!(
            matches!(result, Err(UpdaterError::Manifest(ref msg)) if msg.contains("duplicate")),
            "expected UpdaterError::Manifest about duplicates, got {result:?}"
        );
    }

    #[test]
    fn parse_rejects_path_traversal_via_files() {
        // Zip-slip defense — via ManifestPath::try_from during deserialize.
        let bad = format!(
            r#"{{"version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0","launcherExecutable":"app.exe","files":[{{"path":"../../evil.exe","url":"u","size":1,"sha256":"{HASH_A}"}}]}}"#
        );
        assert!(matches!(Manifest::parse(&bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_backslash_path_via_files() {
        let bad = format!(
            r#"{{"version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0","launcherExecutable":"app.exe","files":[{{"path":"launcher\\app.jar","url":"u","size":1,"sha256":"{HASH_A}"}}]}}"#
        );
        assert!(matches!(Manifest::parse(&bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_rejects_traversal_launcher_executable() {
        let bad = r#"{
            "version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0",
            "launcherExecutable":"../../etc/malicious","files":[]
        }"#;
        assert!(matches!(Manifest::parse(bad), Err(UpdaterError::Json(_))));
    }

    #[test]
    fn parse_accepts_missing_changelog_via_serde_default() {
        let ok = r#"{
            "version":"1.0.0","os":"windows","releasedAt":"t","minAutoUpdateVersion":"0.1.0",
            "launcherExecutable":"app.exe","files":[]
        }"#;
        let m = Manifest::parse(ok).unwrap();
        assert_eq!(m.changelog, "");
    }

    // --- Diff ---

    #[test]
    fn diff_with_no_local_returns_all_remote_files() {
        let remote = manifest("1.2.3", vec![file("a.jar", HASH_A), file("b.jar", HASH_B)]);
        assert_eq!(diff_manifests(None, &remote).len(), 2);
    }

    #[test]
    fn diff_with_identical_hashes_returns_empty() {
        let remote = manifest("1.2.3", vec![file("a.jar", HASH_A)]);
        let local = manifest("1.2.3", vec![file("a.jar", HASH_A)]);
        assert!(diff_manifests(Some(&local), &remote).is_empty());
    }

    #[test]
    fn diff_detects_hash_mismatch() {
        let remote = manifest("1.2.3", vec![file("a.jar", HASH_B)]);
        let local = manifest("1.2.0", vec![file("a.jar", HASH_A)]);
        let diff = diff_manifests(Some(&local), &remote);
        assert_eq!(diff.len(), 1);
        assert_eq!(diff[0].sha256.as_str(), HASH_B);
    }

    #[test]
    fn diff_detects_file_missing_from_local() {
        let remote = manifest(
            "1.2.3",
            vec![file("a.jar", HASH_A), file("new.jar", HASH_N)],
        );
        let local = manifest("1.2.0", vec![file("a.jar", HASH_A)]);
        let diff = diff_manifests(Some(&local), &remote);
        assert_eq!(diff.len(), 1);
        assert_eq!(diff[0].path.as_str(), "new.jar");
    }

    #[test]
    fn diff_ignores_files_only_in_local() {
        let remote = manifest("1.2.3", vec![file("a.jar", HASH_A)]);
        let local = manifest(
            "1.2.0",
            vec![file("a.jar", HASH_A), file("old.jar", HASH_B)],
        );
        assert!(diff_manifests(Some(&local), &remote).is_empty());
    }

    // --- Local persistence ---

    #[test]
    fn load_local_missing_returns_none() {
        let dir = TempDir::new().unwrap();
        assert!(load_local(dir.path()).is_none());
    }

    #[test]
    fn load_local_corrupt_returns_none_and_renames_file() {
        let dir = TempDir::new().unwrap();
        let path = local_manifest_path(dir.path());
        std::fs::write(&path, "{ not json").unwrap();
        assert!(load_local(dir.path()).is_none());
        // Original corrupt path should be gone (renamed away for forensics).
        assert!(
            !path.exists(),
            "corrupt local-manifest.json must be renamed, not left in place"
        );
        // A `.corrupt-*` sibling should exist for post-mortem inspection.
        let corrupt_exists = std::fs::read_dir(dir.path())
            .unwrap()
            .any(|e| {
                e.unwrap()
                    .file_name()
                    .to_string_lossy()
                    .contains(".corrupt-")
            });
        assert!(corrupt_exists, "expected a .corrupt-* forensic file");
    }

    #[test]
    fn load_local_corrupt_twice_does_not_repeat_warn() {
        // Second launch sees NotFound (original renamed on first call) — no
        // repeat warn. Pinned by observing side effect: second call returns
        // None without any file remaining to parse.
        let dir = TempDir::new().unwrap();
        let path = local_manifest_path(dir.path());
        std::fs::write(&path, "{ not json").unwrap();
        assert!(load_local(dir.path()).is_none());
        assert!(load_local(dir.path()).is_none());
        assert!(!path.exists());
    }

    #[test]
    fn load_local_io_error_returns_none() {
        let dir = TempDir::new().unwrap();
        std::fs::create_dir(local_manifest_path(dir.path())).unwrap();
        assert!(load_local(dir.path()).is_none());
    }

    #[test]
    fn save_local_then_load_local_roundtrip() {
        let dir = TempDir::new().unwrap();
        let original = manifest("1.2.3", vec![file("a.jar", HASH_A)]);
        save_local(dir.path(), &original).unwrap();
        let loaded = load_local(dir.path()).unwrap();
        assert_eq!(loaded, original);
    }

    #[test]
    fn save_local_produces_pretty_json() {
        let dir = TempDir::new().unwrap();
        let m = manifest("1.2.3", vec![file("a.jar", HASH_A)]);
        save_local(dir.path(), &m).unwrap();
        let raw = std::fs::read_to_string(local_manifest_path(dir.path())).unwrap();
        assert!(raw.contains('\n'));
    }

    #[test]
    fn save_local_returns_io_error_when_parent_is_a_file() {
        let dir = TempDir::new().unwrap();
        let fake_parent = dir.path().join("im_a_file");
        std::fs::write(&fake_parent, "").unwrap();
        let m = manifest("1.2.3", vec![]);
        let result = save_local(&fake_parent, &m);
        assert!(matches!(result, Err(UpdaterError::Io(_))));
    }
}
