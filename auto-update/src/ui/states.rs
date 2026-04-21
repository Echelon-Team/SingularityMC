// Copyright (c) 2026 Echelon Team. All rights reserved.

//! UI state enum driving [`AutoUpdateApp`](super::AutoUpdateApp) rendering.
//!
//! One variant per mutually-exclusive user-visible screen (per spec 4.x).
//! State transitions are owned by the Task 2.11 state machine — this
//! module just defines the shape.

/// All states the auto-update UI can display.
///
/// Construction is data-shaped (positional / named fields) rather than
/// a builder — states are transient display snapshots, not long-lived
/// objects, and the state machine creates many of them per update cycle.
///
/// Parked-retry variants carry `next_tick_at: Instant` rather than a
/// pre-computed seconds count — UI layer subtracts `Instant::now()` per
/// frame so the countdown updates in real time (33ms repaint tick) and
/// doesn't freeze between state-machine transitions.
#[derive(Debug, Clone)]
pub enum UiState {
    /// Initial state while the GitHub API call is in flight.
    Checking,
    /// Active download with progress tracking. Bytes are the source of
    /// truth (integer, no precision drift); MB conversion happens at
    /// render time in the UI layer. `percent` is the clamped `Percent`
    /// newtype — the struct can't be constructed with an out-of-range
    /// value, so the UI layer renders it verbatim.
    Downloading {
        percent: crate::Percent,
        downloaded_bytes: u64,
        total_bytes: u64,
    },
    /// Post-download hash verification.
    Verifying,
    /// File-swap (Task 2.6 updater) in progress.
    Installing,
    /// Spawning the launcher (final state before process exit).
    Starting,
    /// Network unreachable / GitHub timed out; state machine auto-retries
    /// on the cooldown ladder. `next_tick_at` is the absolute instant the
    /// next auto-retry fires — UI renders `(next_tick_at - now)` per frame
    /// for a live countdown.
    NoInternet { next_tick_at: std::time::Instant },
    /// Network failed three times AND a usable prior install exists —
    /// user can pick "offline mode" to bypass the update and launch
    /// what's on disk, OR keep waiting while the state machine auto-
    /// retries in the background. `next_tick_at` is the absolute instant
    /// of the next retry — UI renders the live countdown same as
    /// NoInternet.
    OfflineAvailable { next_tick_at: std::time::Instant },
    /// Download or verify failed; state machine auto-retries on the
    /// cooldown ladder. `next_tick_at` is the absolute instant of the
    /// next retry.
    ///
    /// `has_offline` drives whether the UI renders the "Offline mode"
    /// button: true only when a local `local-manifest.json` was found
    /// on disk, so clicking Offline will actually have a valid install
    /// to launch. On a fresh install the flag is false — Offline button
    /// is hidden so the user doesn't click what would otherwise drop
    /// to FatalError with "no local install available".
    DownloadFailed {
        next_tick_at: std::time::Instant,
        has_offline: bool,
    },
    /// Non-recoverable error (malformed manifest, write permission
    /// denied, corrupt install state). Message is pre-localized by the
    /// state machine before being set here.
    FatalError { message: String },
}

impl UiState {
    /// Seconds until the next scheduled auto-retry tick, computed against
    /// wall clock. Returns 0 once the deadline has passed (saturating
    /// subtraction). Returns 0 for states that don't carry a tick
    /// deadline — callers should only invoke on parked variants.
    #[must_use]
    pub fn remaining_retry_seconds(&self) -> u32 {
        let deadline = match self {
            Self::NoInternet { next_tick_at }
            | Self::OfflineAvailable { next_tick_at }
            | Self::DownloadFailed { next_tick_at, .. } => *next_tick_at,
            _ => return 0,
        };
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        u32::try_from(remaining.as_secs()).unwrap_or(u32::MAX)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn states_are_clone() {
        // Design contract: UiState must be Clone so the UI can snapshot
        // under a Mutex lock and release before rendering. Regression
        // guard — adding a !Clone field would break the render loop.
        let s = UiState::Downloading {
            percent: crate::Percent::new(42),
            downloaded_bytes: 10 * 1_048_576,
            total_bytes: 25 * 1_048_576,
        };
        let _copy = s.clone();
    }

    #[test]
    fn downloading_carries_progress_data() {
        let s = UiState::Downloading {
            percent: crate::Percent::new(75),
            downloaded_bytes: 7_500_000,
            total_bytes: 10_000_000,
        };
        match s {
            UiState::Downloading {
                percent,
                downloaded_bytes,
                total_bytes,
            } => {
                assert_eq!(percent.as_u8(), 75);
                assert_eq!(downloaded_bytes, 7_500_000);
                assert_eq!(total_bytes, 10_000_000);
            }
            _ => panic!("expected Downloading variant"),
        }
    }

    #[test]
    fn no_internet_carries_tick_deadline() {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(7);
        let s = UiState::NoInternet {
            next_tick_at: deadline,
        };
        match s {
            UiState::NoInternet { next_tick_at } => {
                assert_eq!(next_tick_at, deadline);
            }
            _ => panic!("expected NoInternet variant"),
        }
    }

    #[test]
    fn remaining_retry_seconds_reflects_deadline() {
        // 5 s in the future should read back as 4 or 5 depending on
        // sub-second drift in the as_secs() truncation. Assert the
        // reasonable range rather than an exact value.
        let s = UiState::NoInternet {
            next_tick_at: std::time::Instant::now() + std::time::Duration::from_secs(5),
        };
        let r = s.remaining_retry_seconds();
        assert!((4..=5).contains(&r), "expected 4..=5 s, got {r}");
    }

    #[test]
    fn remaining_retry_seconds_saturates_at_zero_for_past_deadline() {
        // Deadline already elapsed — saturating_duration_since returns
        // ZERO, not a panic or negative wrap.
        let s = UiState::DownloadFailed {
            next_tick_at: std::time::Instant::now()
                - std::time::Duration::from_secs(5),
            has_offline: false,
        };
        assert_eq!(s.remaining_retry_seconds(), 0);
    }

    #[test]
    fn remaining_retry_seconds_is_zero_for_non_parked_states() {
        // Checking / Installing / Verifying / etc. don't carry a tick
        // deadline — caller shouldn't rely on 0 meaning "ready now",
        // but this guards against accidental non-panic behaviour.
        assert_eq!(UiState::Checking.remaining_retry_seconds(), 0);
        assert_eq!(UiState::Verifying.remaining_retry_seconds(), 0);
    }

    #[test]
    fn fatal_error_carries_localized_message() {
        // The state machine pre-localizes message text before placing
        // it in FatalError, so the UI never has to format here.
        let s = UiState::FatalError {
            message: "Manifest podpisany nieprawidłowo".to_string(),
        };
        match s {
            UiState::FatalError { message } => {
                assert_eq!(message, "Manifest podpisany nieprawidłowo");
            }
            _ => panic!("expected FatalError variant"),
        }
    }
}
