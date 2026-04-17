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
    /// Network unreachable / GitHub timed out; state machine retries
    /// after `retry_in_seconds` countdown.
    NoInternet { retry_in_seconds: u32 },
    /// Network failed AND a usable prior install exists — user can pick
    /// "offline mode" to bypass the update and launch what's on disk.
    OfflineAvailable,
    /// Download or verify failed irrecoverably (exhausted retries).
    /// User can retry manually or pick offline mode.
    DownloadFailed,
    /// Non-recoverable error (malformed manifest, write permission
    /// denied, corrupt install state). Message is pre-localized by the
    /// state machine before being set here.
    FatalError { message: String },
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
    fn no_internet_carries_retry_countdown() {
        let s = UiState::NoInternet {
            retry_in_seconds: 7,
        };
        match s {
            UiState::NoInternet { retry_in_seconds } => {
                assert_eq!(retry_in_seconds, 7);
            }
            _ => panic!("expected NoInternet variant"),
        }
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
