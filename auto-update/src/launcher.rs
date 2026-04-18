//! Launcher spawner + crash-counter persistence.
//!
//! **Spawn:** [`spawn_launcher`] resolves the launcher executable via a
//! [`ManifestPath`] under `install_dir`, then `CreateProcessW` / `fork+exec`
//! it with stdio detached and (on Windows) `DETACHED_PROCESS` +
//! `CREATE_NEW_PROCESS_GROUP` + `CREATE_BREAKAWAY_FROM_JOB` flags so the
//! launcher survives this auto-update process exiting — including when
//! the parent happens to live inside a Windows Job Object with
//! `KILL_ON_JOB_CLOSE`.
//!
//! **Crash counters (two):** `auto-update-state.json` under `install_dir`
//! tracks TWO distinct counters per spec:
//!   - `self_update_crash_counter` — crashes of the auto-update binary
//!     itself (startup failures after a self-update swap). Triggers
//!     rollback of `auto-update.exe` from `.bak`.
//!   - `launcher_crash_counter` — crashes of the spawned launcher after
//!     an update. Triggers restore from `File-Backups/pre-update-v*/`.
//! Separate counters prevent write contention once Task 2.11 wires the
//! launcher-side IPC ack path (launcher writes its own counter; auto-
//! update writes its own).
//!
//! **Threshold semantics:** rollback triggers when counter ≥
//! [`LAUNCHER_CRASH_THRESHOLD`] (2 — per Mateusz's explicit decision
//! to trigger rollback on the 3rd launch after 2 consecutive crashes).
//! Earlier spec draft said ≥3; shortened in 2026-04-18 follow-up so user
//! recovery happens faster at the cost of tolerating one less transient
//! crash (benign crashes are already rare and the alive-flag heuristic
//! accepts them — post-rollback the launcher is known-good from before
//! the update, so recovery is safer than persistence).
//!
//! [`increment_crash_counter`] uses `saturating_add` so a pathological
//! infinite loop never wraps to 0 and spoofs "all clear" — the counter
//! parks at `u32::MAX` but the state machine treats anything ≥ threshold
//! as a crash loop anyway.
//!
//! **Alive-flag handshake (crash detection):** launcher writes an empty
//! `launcher-alive-flag` file into `install_dir` after its first
//! composable stabilizes (~2 s after startup, post-window-ready). Auto-
//! update at next boot:
//!
//!   1. Flag present → previous launcher reached a stable UI state.
//!      Reset `launcher_crash_counter`, delete flag.
//!   2. Flag absent → previous launcher either crashed before the 2 s
//!      alive tick OR was never run. Increment the counter.
//!
//! Counter reaching [`LAUNCHER_CRASH_THRESHOLD`] drives a restore from
//! the newest `File-Backups/pre-update-*` snapshot. Flag path resolved
//! via [`launcher_alive_flag_path`].
//!
//! **Corrupt-state recovery:** matches [`crate::manifest::load_local`] —
//! a corrupt state file is renamed to
//! `auto-update-state.json.corrupt-{unix_ts}` for forensics and defaults
//! are returned. Next [`save_state`] writes a fresh valid file.

use crate::{util, ManifestPath, Result, UpdaterError};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};

/// Which crash counter to operate on. Per spec the two counters have
/// different owners (auto-update vs launcher) and different rollback
/// targets, so they're stored as independent fields rather than
/// conflated into one `u32`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CrashCounterKind {
    /// Counts failures of the auto-update binary itself (e.g. the self-
    /// replace swap installed a broken binary that crashes on startup).
    /// Triggers rollback from `auto-update.exe.bak`.
    SelfUpdate,
    /// Counts failures of the spawned launcher after a file-swap update.
    /// Triggers restore from `File-Backups/pre-update-v*/`.
    Launcher,
}

/// Persisted auto-update state. `#[serde(default)]` on every field so
/// older files predating a new field parse without error; unknown fields
/// from newer files are silently ignored by serde default behavior.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AutoUpdateState {
    /// Crash count for the auto-update binary itself.
    #[serde(default)]
    pub self_update_crash_counter: u32,
    /// Crash count for the spawned launcher.
    #[serde(default)]
    pub launcher_crash_counter: u32,
}

impl AutoUpdateState {
    /// Read-only view of a counter by kind — symmetric with
    /// [`Self::bump`] / [`Self::clear`] accessors used by the module fns.
    #[must_use]
    pub fn counter(&self, kind: CrashCounterKind) -> u32 {
        match kind {
            CrashCounterKind::SelfUpdate => self.self_update_crash_counter,
            CrashCounterKind::Launcher => self.launcher_crash_counter,
        }
    }

    fn bump(&mut self, kind: CrashCounterKind) {
        match kind {
            CrashCounterKind::SelfUpdate => {
                self.self_update_crash_counter =
                    self.self_update_crash_counter.saturating_add(1);
            }
            CrashCounterKind::Launcher => {
                self.launcher_crash_counter = self.launcher_crash_counter.saturating_add(1);
            }
        }
    }

    fn clear(&mut self, kind: CrashCounterKind) {
        match kind {
            CrashCounterKind::SelfUpdate => self.self_update_crash_counter = 0,
            CrashCounterKind::Launcher => self.launcher_crash_counter = 0,
        }
    }
}

/// Where `auto-update-state.json` lives.
///
/// Co-located with `File-Backups/` and `auto-update.exe.bak` under
/// `install_dir` — this file is installer-self-repair state, not user
/// data. `%APPDATA%\SingularityMC\` is reserved for user content per spec
/// and is NOT touched by the updater flow.
#[must_use]
pub fn state_path(install_dir: &Path) -> PathBuf {
    install_dir.join("auto-update-state.json")
}

/// Rollback triggers when [`AutoUpdateState::launcher_crash_counter`]
/// reaches this value. After 2 consecutive missing alive-flags the 3rd
/// auto-update boot performs restore from `File-Backups/pre-update-*/`
/// instead of spawning the (apparently broken) launcher again. See the
/// module-level "Threshold semantics" note.
pub const LAUNCHER_CRASH_THRESHOLD: u32 = 2;

/// Path of the "launcher-alive" sentinel in `install_dir`.
///
/// The launcher writes this file (via `LauncherAliveFlag.kt` on the
/// Kotlin side, ~2 s after first composition) to signal "I started
/// successfully." Auto-update at next boot checks existence; a missing
/// file is treated as "previous launcher crashed before stabilising"
/// and bumps the crash counter.
#[must_use]
pub fn launcher_alive_flag_path(install_dir: &Path) -> PathBuf {
    install_dir.join("launcher-alive-flag")
}

/// Check the alive flag and delete it atomically.
///
/// Returns `true` if the flag existed (i.e. previous launcher confirmed
/// alive), `false` if it was absent. Always leaves the file deleted so
/// the next run starts from a clean slate — a missing delete after a
/// `true` return would mis-identify the _next_ run's startup as stable.
///
/// I/O errors on deletion are logged and swallowed: the file's mere
/// existence answered the question, and if the subsequent delete fails
/// the worst case is the next run starts with an "already alive" reading
/// despite no launcher run yet — which would skip an increment, which is
/// strictly safer than spuriously incrementing (cannot cause a false-
/// positive rollback).
#[must_use]
pub fn consume_launcher_alive_flag(install_dir: &Path) -> bool {
    let path = launcher_alive_flag_path(install_dir);
    let existed = path.exists();
    if existed {
        if let Err(e) = std::fs::remove_file(&path) {
            log::warn!(
                "failed to consume launcher-alive-flag at {}: {e} \
                 (benign — next run will treat it as stable anyway)",
                path.display()
            );
        }
    }
    existed
}

/// Spawn the launcher executable under `install_dir/<launcher_rel_path>`.
///
/// Detaches stdio (`Stdio::null()` on all three streams). On Windows also
/// sets `DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP |
/// CREATE_BREAKAWAY_FROM_JOB` so the launcher does not inherit the
/// parent's console, is isolated from parent Ctrl-C group signals, and
/// breaks away from any Windows Job Object the parent may be part of
/// (harmless no-op when parent isn't in a Job).
///
/// `offline = true` forwards `--offline` as the sole argument, matching
/// the Phase 1 launcher CLI contract
/// (`singularity-launcher/.../OfflineMode.kt`).
pub fn spawn_launcher(
    install_dir: &Path,
    launcher_rel_path: &ManifestPath,
    offline: bool,
) -> Result<()> {
    let exe = launcher_rel_path.to_install_path(install_dir);
    if !exe.exists() {
        return Err(UpdaterError::NotFound(format!(
            "launcher executable not found at {}",
            exe.display()
        )));
    }

    let mut cmd = Command::new(&exe);
    if offline {
        cmd.arg("--offline");
    }
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    // Expose install_dir to the launcher so its `LauncherAliveFlag` can
    // touch `launcher-alive-flag` in the same directory auto-update will
    // check on next boot. Env-var instead of a CLI arg because current
    // arg parsing is fixed-form (`--offline` only) and adding a path arg
    // would need sync between both sides of the contract.
    cmd.env("SINGULARITY_INSTALL_DIR", install_dir);

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        // CREATE_BREAKAWAY_FROM_JOB: safety net for the case where our
        // own process lives inside a Windows Job with KILL_ON_JOB_CLOSE.
        // No-op when parent isn't in a Job (per MS docs).
        const CREATE_BREAKAWAY_FROM_JOB: u32 = 0x0100_0000;
        cmd.creation_flags(
            DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB,
        );
    }

    let child = cmd.spawn().map_err(UpdaterError::Io)?;
    log::info!(
        "spawned launcher pid={} from {}",
        child.id(),
        exe.display()
    );
    // Intentionally drop `child` without .wait() — detached spawn, parent
    // should exit and let OS reparent the launcher.
    drop(child);
    Ok(())
}

/// Load the persisted state, falling back to defaults on any failure.
///
/// - Missing file → default, silently (normal first-launch state).
/// - Corrupt JSON → warn + rename file to `.corrupt-{unix_ts}` for
///   post-mortem forensics + return default. Preserves the broken bytes
///   for debugging AND stops the next launch from re-warning on the
///   same file. Matches [`crate::manifest::load_local`] semantics.
/// - I/O error → warn + return default. No rename (nothing coherent to
///   preserve).
#[must_use]
pub fn load_state(install_dir: &Path) -> AutoUpdateState {
    let path = state_path(install_dir);
    match std::fs::read_to_string(&path) {
        Ok(content) => match serde_json::from_str::<AutoUpdateState>(&content) {
            Ok(state) => state,
            Err(e) => {
                let ts = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                let corrupt = path.with_extension(format!("json.corrupt-{ts}"));
                log::warn!(
                    "auto-update-state.json at {} is corrupt: {e}; renaming to {} and resetting",
                    path.display(),
                    corrupt.display()
                );
                if let Err(rename_err) = std::fs::rename(&path, &corrupt) {
                    log::warn!(
                        "failed to preserve corrupt state file as {}: {rename_err}",
                        corrupt.display()
                    );
                }
                AutoUpdateState::default()
            }
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => AutoUpdateState::default(),
        Err(e) => {
            log::warn!(
                "failed to read auto-update-state.json at {}: {e}; resetting",
                path.display()
            );
            AutoUpdateState::default()
        }
    }
}

fn save_state(install_dir: &Path, state: &AutoUpdateState) -> Result<()> {
    let path = state_path(install_dir);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let content = serde_json::to_string_pretty(state)?;
    util::atomic_write_bytes(&path, content.as_bytes())
}

/// Increment the crash counter for `kind`, persist atomically, and return
/// the new value.
///
/// Callers MUST propagate a returned `Err` rather than ignoring it —
/// swallowing the error means a real crash goes uncounted, defeating
/// crash-loop detection for that cycle.
pub fn increment_crash_counter(install_dir: &Path, kind: CrashCounterKind) -> Result<u32> {
    let mut state = load_state(install_dir);
    state.bump(kind);
    let new_value = state.counter(kind);
    save_state(install_dir, &state)?;
    log::info!("crash counter {kind:?} incremented to {new_value}");
    Ok(new_value)
}

/// Reset the crash counter for `kind` to 0. Load-then-mutate-then-save
/// (rather than constructing a fresh `AutoUpdateState`) so a clear of one
/// counter does not wipe other fields — critical once future fields like
/// `lastSuccessfulVersion` are added.
pub fn reset_crash_counter(install_dir: &Path, kind: CrashCounterKind) -> Result<()> {
    let mut state = load_state(install_dir);
    state.clear(kind);
    save_state(install_dir, &state)?;
    log::info!("crash counter {kind:?} reset to 0");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    // --- spawn_launcher ---

    #[test]
    fn spawn_launcher_returns_notfound_when_exe_missing() {
        let dir = TempDir::new().unwrap();
        let path = ManifestPath::parse("launcher/does-not-exist.exe").unwrap();
        let result = spawn_launcher(dir.path(), &path, false);
        assert!(matches!(result, Err(UpdaterError::NotFound(_))));
    }

    // --- launcher-alive-flag handshake ---

    #[test]
    fn launcher_alive_flag_path_is_colocated_with_state() {
        // Flag must live in install_dir same as auto-update-state.json —
        // that's what the Kotlin side reads via `SINGULARITY_INSTALL_DIR`
        // env var (spawn_launcher sets it to install_dir).
        let dir = TempDir::new().unwrap();
        let flag = launcher_alive_flag_path(dir.path());
        assert_eq!(flag.parent(), Some(dir.path()));
        assert_eq!(flag.file_name().unwrap(), "launcher-alive-flag");
    }

    #[test]
    fn consume_launcher_alive_flag_returns_false_when_absent() {
        let dir = TempDir::new().unwrap();
        assert!(!consume_launcher_alive_flag(dir.path()));
    }

    #[test]
    fn consume_launcher_alive_flag_returns_true_and_deletes_when_present() {
        let dir = TempDir::new().unwrap();
        let flag = launcher_alive_flag_path(dir.path());
        std::fs::write(&flag, b"").unwrap();
        assert!(flag.exists(), "pre-condition: flag written");
        assert!(consume_launcher_alive_flag(dir.path()));
        assert!(
            !flag.exists(),
            "consume must delete flag so next boot starts clean"
        );
        // Calling again must be idempotent — flag already gone → false.
        assert!(!consume_launcher_alive_flag(dir.path()));
    }

    #[test]
    fn launcher_crash_threshold_matches_spec_decision() {
        // Threshold 2 — Mateusz 2026-04-18: "po 2 crashach z rzędu system
        // się uaktywnia a trzecie włączenie launchera skutkuje automatycznym
        // skopiowaniem plików". Pinned so any future tuning is an explicit
        // review change, not silent drift.
        assert_eq!(LAUNCHER_CRASH_THRESHOLD, 2);
    }

    // --- CrashCounterKind + state accessor methods ---

    #[test]
    fn state_counter_accessor_reads_correct_field() {
        let state = AutoUpdateState {
            self_update_crash_counter: 3,
            launcher_crash_counter: 7,
        };
        assert_eq!(state.counter(CrashCounterKind::SelfUpdate), 3);
        assert_eq!(state.counter(CrashCounterKind::Launcher), 7);
    }

    #[test]
    fn state_bump_saturates_per_counter() {
        let mut state = AutoUpdateState {
            self_update_crash_counter: u32::MAX,
            launcher_crash_counter: 5,
        };
        state.bump(CrashCounterKind::SelfUpdate);
        assert_eq!(state.self_update_crash_counter, u32::MAX);
        // Launcher counter untouched.
        assert_eq!(state.launcher_crash_counter, 5);
    }

    #[test]
    fn state_clear_only_affects_requested_counter() {
        let mut state = AutoUpdateState {
            self_update_crash_counter: 3,
            launcher_crash_counter: 7,
        };
        state.clear(CrashCounterKind::SelfUpdate);
        assert_eq!(state.self_update_crash_counter, 0);
        assert_eq!(state.launcher_crash_counter, 7);
    }

    // --- increment / reset (self-update counter path) ---

    #[test]
    fn increment_self_update_starts_at_one_when_state_absent() {
        let dir = TempDir::new().unwrap();
        let n = increment_crash_counter(dir.path(), CrashCounterKind::SelfUpdate).unwrap();
        assert_eq!(n, 1);
        assert_eq!(load_state(dir.path()).launcher_crash_counter, 0);
    }

    #[test]
    fn increment_launcher_starts_at_one_without_affecting_self_update() {
        let dir = TempDir::new().unwrap();
        increment_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap();
        let s = load_state(dir.path());
        assert_eq!(s.launcher_crash_counter, 1);
        assert_eq!(s.self_update_crash_counter, 0);
    }

    #[test]
    fn increment_accumulates_per_counter_independently() {
        let dir = TempDir::new().unwrap();
        increment_crash_counter(dir.path(), CrashCounterKind::SelfUpdate).unwrap();
        increment_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap();
        increment_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap();
        let s = load_state(dir.path());
        assert_eq!(s.self_update_crash_counter, 1);
        assert_eq!(s.launcher_crash_counter, 2);
    }

    #[test]
    fn increment_from_explicit_zero_lands_at_one() {
        // Pin the "file exists with value 0" path — distinct from
        // file-absent default, distinct from saturation. Covers a silent
        // regression in Default integration.
        let dir = TempDir::new().unwrap();
        save_state(
            dir.path(),
            &AutoUpdateState {
                self_update_crash_counter: 0,
                launcher_crash_counter: 0,
            },
        )
        .unwrap();
        let n = increment_crash_counter(dir.path(), CrashCounterKind::SelfUpdate).unwrap();
        assert_eq!(n, 1);
    }

    #[test]
    fn reset_zeros_requested_counter_without_wiping_other() {
        let dir = TempDir::new().unwrap();
        increment_crash_counter(dir.path(), CrashCounterKind::SelfUpdate).unwrap();
        increment_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap();
        reset_crash_counter(dir.path(), CrashCounterKind::SelfUpdate).unwrap();
        let s = load_state(dir.path());
        assert_eq!(s.self_update_crash_counter, 0);
        // Critical: launcher counter preserved. Guards regression where
        // reset constructs a fresh AutoUpdateState and wipes everything.
        assert_eq!(s.launcher_crash_counter, 1);
    }

    #[test]
    fn lifecycle_increment_reset_increment_restarts_at_one() {
        // Full lifecycle the counter is designed for: crash → recover →
        // later crash. Third-call result of 1 (not 3) proves reset
        // actually cleared the disk state, not just an in-memory value.
        let dir = TempDir::new().unwrap();
        assert_eq!(
            increment_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap(),
            1
        );
        assert_eq!(
            increment_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap(),
            2
        );
        reset_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap();
        assert_eq!(
            increment_crash_counter(dir.path(), CrashCounterKind::Launcher).unwrap(),
            1
        );
    }

    #[test]
    fn increment_saturates_at_u32_max() {
        let dir = TempDir::new().unwrap();
        save_state(
            dir.path(),
            &AutoUpdateState {
                self_update_crash_counter: u32::MAX,
                launcher_crash_counter: 0,
            },
        )
        .unwrap();
        let n = increment_crash_counter(dir.path(), CrashCounterKind::SelfUpdate).unwrap();
        assert_eq!(n, u32::MAX);
    }

    // --- load_state ---

    #[test]
    fn load_state_returns_default_when_file_missing() {
        let dir = TempDir::new().unwrap();
        assert_eq!(load_state(dir.path()), AutoUpdateState::default());
    }

    #[test]
    fn load_state_renames_corrupt_file_and_returns_default() {
        let dir = TempDir::new().unwrap();
        let path = state_path(dir.path());
        std::fs::write(&path, "{ not valid").unwrap();
        assert_eq!(load_state(dir.path()), AutoUpdateState::default());
        // Original must be renamed away (forensic preservation).
        assert!(!path.exists());
        let has_corrupt_sibling = std::fs::read_dir(dir.path())
            .unwrap()
            .any(|e| {
                e.unwrap()
                    .file_name()
                    .to_string_lossy()
                    .contains(".corrupt-")
            });
        assert!(has_corrupt_sibling, "expected a .corrupt-* file");
    }

    #[test]
    fn load_state_tolerates_io_error_as_default() {
        let dir = TempDir::new().unwrap();
        std::fs::create_dir(state_path(dir.path())).unwrap();
        assert_eq!(load_state(dir.path()), AutoUpdateState::default());
    }

    // --- state_path + wire format ---

    #[test]
    fn state_path_filename_is_pinned() {
        // Phase 1 launcher side (Sub 5 integration, Task 2.11) will read
        // this file by exact name — guard against accidental rename.
        let p = state_path(Path::new("/install"));
        assert_eq!(p.file_name().unwrap(), "auto-update-state.json");
    }

    #[test]
    fn state_serializes_camel_case_on_wire() {
        let s = AutoUpdateState {
            self_update_crash_counter: 2,
            launcher_crash_counter: 5,
        };
        let json = serde_json::to_string(&s).unwrap();
        assert!(json.contains("\"selfUpdateCrashCounter\":2"));
        assert!(json.contains("\"launcherCrashCounter\":5"));
        assert!(!json.contains("self_update_crash_counter"));
        assert!(!json.contains("launcher_crash_counter"));
    }

    #[test]
    fn state_deserialize_tolerates_unknown_fields_forward_compat() {
        let dir = TempDir::new().unwrap();
        std::fs::write(
            state_path(dir.path()),
            r#"{"selfUpdateCrashCounter":4,"launcherCrashCounter":2,"futureField":"ignored"}"#,
        )
        .unwrap();
        let s = load_state(dir.path());
        assert_eq!(s.self_update_crash_counter, 4);
        assert_eq!(s.launcher_crash_counter, 2);
    }

    #[test]
    fn state_deserialize_applies_defaults_for_missing_fields_backward_compat() {
        // Older pre-split state file only had `crashCounter`. A manifest
        // with that field wouldn't deserialize cleanly — but one with
        // neither of the new fields lands both at 0 via #[serde(default)].
        let dir = TempDir::new().unwrap();
        std::fs::write(state_path(dir.path()), "{}").unwrap();
        assert_eq!(load_state(dir.path()), AutoUpdateState::default());
    }
}
