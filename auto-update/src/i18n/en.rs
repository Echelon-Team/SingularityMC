//! English string bundle.

use super::Strings;

pub const STRINGS: &Strings = &Strings {
    checking: "Checking for updates...",
    verifying: "Verifying files...",
    installing: "Installing update...",
    starting: "Starting SingularityMC...",
    no_internet: "Check your internet connection",
    download_failed: "Update failed",
    help: "Help",
    offline_mode: "OFFLINE MODE",
    retry: "Retry",
    close: "Exit",
    no_offline_install:
        "Download failed and no local install is available for offline mode.",
    unhandled_flow_outcome:
        "Unexpected update state. Please report this on Discord.",
    auto_update_too_old:
        "This installer is too old to safely install the new version. Download a fresh installer from Discord.",
};
