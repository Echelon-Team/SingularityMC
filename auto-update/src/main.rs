//! SingularityMC auto-update binary entry point.
//!
//! Intentionally thin: boots the logging backend, reports the embedded build
//! version, and (in later tasks) hands off to the state machine in
//! [`singularitymc_auto_update::app`]. All real logic lives in the library
//! crate (`src/lib.rs`) so `tests/*.rs` integration tests can drive it.

use singularitymc_auto_update::Version;

fn main() {
    // Install env_logger NOW, before anything else touches `log`. Without this,
    // every `log::warn!` / `log::error!` in subsequent modules silently no-ops
    // and bugs become invisible. Default filter is `info` so operational
    // messages surface without the user setting `RUST_LOG`.
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"))
        .format_timestamp_millis()
        .init();

    let version = Version::current();
    log::info!("SingularityMC auto-update v{version}");
    println!("SingularityMC auto-update v{version}");
}
