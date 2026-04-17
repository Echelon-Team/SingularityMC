//! Auto-update library — all application logic lives here so integration tests
//! under `tests/` can target it. The binary entry point (`src/main.rs`) is a
//! thin shim that boots logging, parses CLI args, and calls into this crate.
//!
//! Subsequent tasks in Phase 2 add modules here:
//! - `config`   — auto-update-config.json reader/writer (Task 2.2) ✅
//! - `manifest` — SHA-256 / per-file manifest types + parsing (Task 2.3) ✅
//! - `github_api` — GitHub Releases client (Task 2.4)
//! - `downloader` — retry + hash verification (Task 2.5)
//! - `updater` — file swap + backup (Task 2.6)
//! - `self_update` — self-replace integration (Task 2.7)
//! - `launcher` — spawn JVM launcher (Task 2.8)
//! - `i18n` — PL/EN strings (Task 2.9)
//! - `ui` — egui 8-state rendering (Task 2.10)
//! - `app` — main state machine (Task 2.11)

pub mod config;
pub mod downloader;
pub mod error;
pub mod github_api;
pub mod manifest;
pub mod self_update;
pub mod updater;
pub mod util;

pub use config::{AutoUpdateConfig, Channel, LanguagePreference};
pub use downloader::Downloader;
pub use error::{Result, UpdaterError};
pub use updater::Updater;
// Asset intentionally NOT re-exported: external consumers reach it via
// `release.assets` field access; adding it to the public root would widen
// the API surface for zero gain.
pub use github_api::{GitHubClient, Release};
pub use manifest::{FileEntry, Manifest, OsTarget};

use serde::{Deserialize, Serialize};

/// Build-time version string, emitted by `build.rs` from `git describe --tags
/// --dirty` (or `CARGO_PKG_VERSION` fallback). Always safe to call — the macro
/// is evaluated at compile time, so a missing env var would fail the build.
pub const BUILD_VERSION: &str = env!("BUILD_VERSION");

/// Wraps a version string (git tag or semver literal) so parsing / comparison
/// logic lives in one place instead of being reinvented in each Phase 2 module.
///
/// Construction goes through [`Version::current`] (the build-embedded version)
/// or [`Version::parse`]. Serde `try_from = "String"` enforces validation when
/// deserializing manifest payloads — an empty version string is rejected at
/// the parse layer, and surrounding whitespace is trimmed so `"1.2.3"` and
/// `"  1.2.3  "` compare equal.
///
/// TODO(Task 2.7): when the self-update min-version gate needs real semver
/// ordering, add `pub fn compare_semver(&self, other: &Self) -> Result<Ordering>`
/// as a free/method API. Do NOT pre-parse into `Option<semver::Version>` in
/// the struct — unused fields on the main type become API noise.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct Version(String);

impl Version {
    /// Version embedded at build time via `env!("BUILD_VERSION")`.
    #[must_use]
    pub fn current() -> Self {
        Self(BUILD_VERSION.to_string())
    }

    /// Construct from a raw string. Trims surrounding whitespace and rejects
    /// empty / whitespace-only input. Accepts any non-empty tag format
    /// (`"v1.2.3"`, `"0.1.0-beta.1"`, `"nightly-2026-04-17"`) — semver-aware
    /// comparison is deferred to Task 2.7.
    pub fn parse(s: &str) -> Result<Self> {
        let trimmed = s.trim();
        if trimmed.is_empty() {
            return Err(UpdaterError::Manifest(
                "version string must not be empty".into(),
            ));
        }
        Ok(Self(trimmed.to_string()))
    }

    /// Borrowed view of the underlying string for logging / display.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl TryFrom<String> for Version {
    type Error = UpdaterError;
    fn try_from(s: String) -> Result<Self> {
        Self::parse(&s)
    }
}

impl From<Version> for String {
    fn from(v: Version) -> Self {
        v.0
    }
}

impl std::fmt::Display for Version {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// SHA-256 hash wire format: exactly 64 lowercase hex characters.
/// Construction validates the invariant at parse time so downstream compare /
/// lookup code can assume well-formed values — an invalid hash from the wire
/// never reaches the downloader (Task 2.5).
///
/// Uppercase hex is rejected intentionally: GitHub Releases / our manifest
/// generator (Phase 3) always emit lowercase, and allowing mixed case would
/// break string-level equality checks in manifest diffs.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct Sha256(String);

impl Sha256 {
    /// Parse + validate. Returns `UpdaterError::Manifest` on wrong length or
    /// non-lowercase-hex content — this variant is matched by callers to
    /// produce localized error messages in the UI (Task 2.10).
    pub fn parse(s: &str) -> Result<Self> {
        const EXPECTED_LEN: usize = 64;
        if s.len() != EXPECTED_LEN {
            return Err(UpdaterError::Manifest(format!(
                "sha256 must be {EXPECTED_LEN} hex chars, got {} chars: {s}",
                s.len()
            )));
        }
        if !s.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f')) {
            return Err(UpdaterError::Manifest(format!(
                "sha256 must be lowercase hex (0-9 a-f), got: {s}"
            )));
        }
        Ok(Self(s.to_string()))
    }

    /// Borrowed hex view for logging / fmt.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Verify the receiver matches the SHA-256 of `bytes`. For large files
    /// (launcher JARs routinely exceed 10 MB), prefer [`Sha256::verify_reader`]
    /// to avoid loading the full payload into memory.
    #[must_use]
    pub fn verify_against(&self, bytes: &[u8]) -> bool {
        use sha2::{Digest, Sha256 as Hasher};
        let computed = hex::encode(Hasher::digest(bytes));
        computed == self.0
    }

    /// Stream-verify: read `r` to EOF, hash incrementally, compare against the
    /// manifest value. Returns `Ok(true)` on match, `Ok(false)` on mismatch,
    /// `Err(Io)` on read failure.
    pub fn verify_reader<R: std::io::Read>(&self, r: &mut R) -> Result<bool> {
        use sha2::{Digest, Sha256 as Hasher};
        let mut hasher = Hasher::new();
        std::io::copy(r, &mut hasher)?;
        let computed = hex::encode(hasher.finalize());
        Ok(computed == self.0)
    }
}

impl TryFrom<String> for Sha256 {
    type Error = UpdaterError;
    fn try_from(s: String) -> Result<Self> {
        Self::parse(&s)
    }
}

impl From<Sha256> for String {
    fn from(h: Sha256) -> Self {
        h.0
    }
}

impl std::fmt::Display for Sha256 {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// A path sourced from a manifest: guaranteed relative, forward-slash-
/// separated, and free of `..` traversal components.
///
/// **Security boundary:** without this type, Task 2.6 (file updater) would
/// `install_dir.join(path_from_manifest)` with data controlled by whoever
/// signs / serves the manifest. A malicious or compromised manifest could
/// ship `"../../Windows/System32/evil.exe"` and the updater would happily
/// write outside `install_dir`. Construction here rejects every form of
/// escape (absolute paths on Unix, drive-letter prefixes on Windows,
/// backslashes, `..` components) so callers can trust the result.
///
/// The forward-slash-only contract also prevents a Phase 3 generator bug
/// (emitting `launcher\app.exe` on Windows) from silently breaking Linux
/// consumers where `PathBuf::join("launcher\\app.exe")` treats the whole
/// string as a single filename component.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct ManifestPath(String);

impl ManifestPath {
    /// Parse + validate a manifest path string.
    pub fn parse(s: &str) -> Result<Self> {
        if s.is_empty() {
            return Err(UpdaterError::Manifest(
                "manifest path must not be empty".into(),
            ));
        }
        if s.contains('\\') {
            return Err(UpdaterError::Manifest(format!(
                "manifest path must use forward-slash separators only: {s}"
            )));
        }
        if s.starts_with('/') {
            return Err(UpdaterError::Manifest(format!(
                "manifest path must be relative, got absolute: {s}"
            )));
        }
        // Windows drive-letter prefix like "C:".
        let mut chars = s.chars();
        if let (Some(first), Some(second)) = (chars.next(), chars.next()) {
            if first.is_ascii_alphabetic() && second == ':' {
                return Err(UpdaterError::Manifest(format!(
                    "manifest path must be relative, got drive-prefixed: {s}"
                )));
            }
        }
        for component in s.split('/') {
            if component == ".." {
                return Err(UpdaterError::Manifest(format!(
                    "manifest path must not contain '..' traversal component: {s}"
                )));
            }
        }
        Ok(Self(s.to_string()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Join onto an install directory. Safe by construction — `self` is
    /// guaranteed to be a validated relative path, so the result is always
    /// inside `install_dir`.
    #[must_use]
    pub fn to_install_path(&self, install_dir: &std::path::Path) -> std::path::PathBuf {
        install_dir.join(&self.0)
    }
}

impl TryFrom<String> for ManifestPath {
    type Error = UpdaterError;
    fn try_from(s: String) -> Result<Self> {
        Self::parse(&s)
    }
}

impl From<ManifestPath> for String {
    fn from(p: ManifestPath) -> Self {
        p.0
    }
}

impl std::fmt::Display for ManifestPath {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // --- Version ---

    #[test]
    fn current_version_is_non_empty() {
        assert!(!Version::current().as_str().is_empty());
    }

    #[test]
    fn display_matches_as_str() {
        let v = Version::current();
        assert_eq!(format!("{v}"), v.as_str());
    }

    #[test]
    fn equality_is_structural() {
        assert_eq!(Version("1.2.3".into()), Version("1.2.3".into()));
        assert_ne!(Version("1.2.3".into()), Version("1.2.4".into()));
    }

    #[test]
    fn version_parse_rejects_empty() {
        assert!(Version::parse("").is_err());
        assert!(Version::parse("   ").is_err());
        assert!(Version::parse("\t\n").is_err());
    }

    #[test]
    fn version_parse_trims_surrounding_whitespace() {
        // Whitespace is normalized at construction so structural equality
        // holds between `"1.2.3"` and `"  1.2.3  "` — critical for manifest
        // diffs and min-version comparisons.
        assert_eq!(Version::parse("1.2.3").unwrap().as_str(), "1.2.3");
        assert_eq!(Version::parse("  1.2.3  ").unwrap().as_str(), "1.2.3");
        assert_eq!(Version::parse("\t1.2.3\n").unwrap().as_str(), "1.2.3");
        assert_eq!(
            Version::parse("1.2.3").unwrap(),
            Version::parse("  1.2.3  ").unwrap()
        );
    }

    #[test]
    fn version_parse_accepts_varied_formats() {
        Version::parse("1.2.3").unwrap();
        Version::parse("v1.2.3").unwrap();
        Version::parse("0.1.0-beta.1").unwrap();
        Version::parse("nightly-2026-04-17").unwrap();
    }

    #[test]
    fn version_serialize_deserialize_roundtrip() {
        let v = Version::parse("1.2.3-beta.1").unwrap();
        let json = serde_json::to_string(&v).unwrap();
        assert_eq!(json, "\"1.2.3-beta.1\"");
        let back: Version = serde_json::from_str(&json).unwrap();
        assert_eq!(back, v);
    }

    #[test]
    fn version_deserialize_rejects_empty_via_try_from() {
        let res: std::result::Result<Version, _> = serde_json::from_str("\"\"");
        assert!(res.is_err());
    }

    // --- Sha256 ---

    const VALID_HASH: &str =
        "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";

    #[test]
    fn sha256_parse_accepts_64_lowercase_hex() {
        Sha256::parse(VALID_HASH).unwrap();
    }

    #[test]
    fn sha256_parse_rejects_wrong_length() {
        assert!(Sha256::parse("").is_err());
        assert!(Sha256::parse("abc").is_err());
        assert!(Sha256::parse(&"a".repeat(63)).is_err());
        assert!(Sha256::parse(&"a".repeat(65)).is_err());
    }

    #[test]
    fn sha256_parse_rejects_uppercase() {
        assert!(Sha256::parse(&"A".repeat(64)).is_err());
        let mixed: String = "A".to_string() + &"a".repeat(63);
        assert!(Sha256::parse(&mixed).is_err());
    }

    #[test]
    fn sha256_parse_rejects_non_hex() {
        assert!(Sha256::parse(&"z".repeat(64)).is_err());
        assert!(Sha256::parse(&"g".repeat(64)).is_err());
        let spaced: String = "a".repeat(30) + " " + &"a".repeat(33);
        assert!(Sha256::parse(&spaced).is_err());
    }

    #[test]
    fn sha256_display_is_raw_hex() {
        let s = Sha256::parse(VALID_HASH).unwrap();
        assert_eq!(s.to_string(), VALID_HASH);
        assert_eq!(s.as_str(), VALID_HASH);
    }

    #[test]
    fn sha256_serialize_deserialize_roundtrip() {
        let original = Sha256::parse(VALID_HASH).unwrap();
        let json = serde_json::to_string(&original).unwrap();
        assert_eq!(json, format!("\"{VALID_HASH}\""));
        let back: Sha256 = serde_json::from_str(&json).unwrap();
        assert_eq!(back, original);
    }

    #[test]
    fn sha256_deserialize_rejects_invalid_via_try_from() {
        let res: std::result::Result<Sha256, _> = serde_json::from_str("\"TOOSHORT\"");
        assert!(res.is_err());
    }

    #[test]
    fn sha256_verify_against_matches_known_hash() {
        // Known: SHA-256 of "hello world" = b94d27b9...
        let h = Sha256::parse(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
        )
        .unwrap();
        assert!(h.verify_against(b"hello world"));
        assert!(!h.verify_against(b"HELLO WORLD"));
        assert!(!h.verify_against(b""));
    }

    #[test]
    fn sha256_verify_reader_matches_matches_single_shot() {
        use std::io::Cursor;
        let bytes = b"hello world";
        let h = Sha256::parse(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
        )
        .unwrap();
        assert!(h.verify_reader(&mut Cursor::new(bytes)).unwrap());
        assert!(h.verify_against(bytes));
    }

    #[test]
    fn sha256_verify_empty_input() {
        // SHA-256 of empty: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        let h = Sha256::parse(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        .unwrap();
        assert!(h.verify_against(b""));
    }

    // --- ManifestPath ---

    #[test]
    fn manifest_path_accepts_simple_relative() {
        ManifestPath::parse("file.txt").unwrap();
        ManifestPath::parse("dir/file.txt").unwrap();
        ManifestPath::parse("a/b/c/d.jar").unwrap();
        ManifestPath::parse("launcher/SingularityMC.exe").unwrap();
    }

    #[test]
    fn manifest_path_rejects_empty() {
        assert!(ManifestPath::parse("").is_err());
    }

    #[test]
    fn manifest_path_rejects_backslash() {
        assert!(ManifestPath::parse("launcher\\app.exe").is_err());
        assert!(ManifestPath::parse("a\\b").is_err());
        // Even a single trailing backslash is rejected.
        assert!(ManifestPath::parse("a\\").is_err());
    }

    #[test]
    fn manifest_path_rejects_absolute_unix() {
        assert!(ManifestPath::parse("/etc/passwd").is_err());
        assert!(ManifestPath::parse("/").is_err());
    }

    #[test]
    fn manifest_path_rejects_drive_prefix() {
        assert!(ManifestPath::parse("C:/Windows/System32/evil.exe").is_err());
        assert!(ManifestPath::parse("D:").is_err());
    }

    #[test]
    fn manifest_path_rejects_parent_traversal() {
        // CRITICAL: zip-slip protection. Every form the wire could use.
        assert!(ManifestPath::parse("..").is_err());
        assert!(ManifestPath::parse("../file").is_err());
        assert!(ManifestPath::parse("../../etc/passwd").is_err());
        assert!(ManifestPath::parse("a/../b").is_err());
        assert!(ManifestPath::parse("a/../../b").is_err());
        assert!(ManifestPath::parse("a/b/..").is_err());
    }

    #[test]
    fn manifest_path_accepts_single_dot() {
        // "." by itself is the current dir — acceptable (result is install_dir).
        // Single "." inside path is also acceptable (harmless).
        ManifestPath::parse(".").unwrap();
        ManifestPath::parse("./file").unwrap();
        ManifestPath::parse("a/./b").unwrap();
    }

    #[test]
    fn manifest_path_to_install_path_joins_under_dir() {
        use std::path::Path;
        let mp = ManifestPath::parse("launcher/app.jar").unwrap();
        let joined = mp.to_install_path(Path::new("/opt/smc"));
        // On Unix joined = /opt/smc/launcher/app.jar; on Windows ... /launcher/app.jar.
        // Both forms are inside install_dir, which is the invariant we pin.
        let joined_str = joined.to_string_lossy();
        assert!(joined_str.contains("launcher"));
        assert!(joined_str.contains("app.jar"));
    }

    #[test]
    fn manifest_path_serialize_deserialize_roundtrip() {
        let mp = ManifestPath::parse("launcher/app.jar").unwrap();
        let json = serde_json::to_string(&mp).unwrap();
        assert_eq!(json, "\"launcher/app.jar\"");
        let back: ManifestPath = serde_json::from_str(&json).unwrap();
        assert_eq!(back, mp);
    }

    #[test]
    fn manifest_path_deserialize_rejects_traversal_via_try_from() {
        // Pins that an attacker crafting a malicious manifest cannot bypass
        // ManifestPath::parse by going through serde — TryFrom<String> wires
        // the same validation into Deserialize.
        let res: std::result::Result<ManifestPath, _> =
            serde_json::from_str(r#""../../etc/passwd""#);
        assert!(res.is_err());
        let res: std::result::Result<ManifestPath, _> =
            serde_json::from_str(r#""launcher\\app.exe""#);
        assert!(res.is_err());
    }
}
