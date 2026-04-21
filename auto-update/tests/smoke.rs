// Copyright (c) 2026 Echelon Team. All rights reserved.

//! Integration smoke test — pins the build.rs ↔ main.rs contract so a future
//! refactor cannot silently break version embedding (e.g. deleting the
//! `println!("cargo:rustc-env=BUILD_VERSION=...")` line would still let the
//! binary compile against the `CARGO_PKG_VERSION` fallback, but the embedded
//! version would go stale without any signal). Running `cargo test` now fails
//! loudly if the output format drifts.
//!
//! Uses Cargo's built-in `CARGO_BIN_EXE_<name>` env var (always set for
//! integration tests), so no extra test crates are required.

use std::process::Command;

const BIN: &str = env!("CARGO_BIN_EXE_singularitymc-auto-update");

#[test]
fn prints_version_line() {
    // `--version` short-circuits before any GUI/runtime bootstrap (see
    // main.rs); without this flag the binary would open its eframe window
    // and block until the user closes it — fine for real use, fatal for
    // `cargo test` in CI or on a headless dev box.
    let output = Command::new(BIN)
        .arg("--version")
        .output()
        .expect("failed to execute auto-update binary");

    assert!(
        output.status.success(),
        "binary exited with status {:?}; stderr=<{}>",
        output.status,
        String::from_utf8_lossy(&output.stderr)
    );

    let stdout = String::from_utf8(output.stdout).expect("stdout not UTF-8");
    let prefix = "SingularityMC auto-update v";

    assert!(
        stdout.starts_with(prefix),
        "stdout does not start with {prefix:?}; got <{stdout}>"
    );
    // The portion after the prefix is the embedded BUILD_VERSION — must be
    // non-empty (git tag or CARGO_PKG_VERSION fallback, never blank).
    let version_part = stdout[prefix.len()..].trim();
    assert!(
        !version_part.is_empty(),
        "embedded BUILD_VERSION must not be empty"
    );
}
