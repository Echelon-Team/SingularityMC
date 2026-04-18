//! Embed the build-time version string into the binary as `BUILD_VERSION`.
//!
//! Priority order:
//! 1. `git describe --tags --dirty` (definitive in a proper checkout).
//! 2. `CARGO_PKG_VERSION` fallback (tarball / no-git environments).
//!
//! Unexpected git failures (git IS present, repo IS valid, but the command
//! fails — e.g. permissions, corrupted refs) emit `cargo:warning=` so stale
//! version strings never ship silently from a broken CI checkout.

use std::io::ErrorKind;
use std::process::Command;

fn main() {
    // Rerun when the version-affecting inputs change. Directories only watch
    // direct entries, so packed-refs (shallow clones, CI) needs its own entry.
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=../.git/HEAD");
    println!("cargo:rerun-if-changed=../.git/refs/tags");
    println!("cargo:rerun-if-changed=../.git/packed-refs");

    let version = version_from_git().unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_string());
    println!("cargo:rustc-env=BUILD_VERSION={version}");

    // Embed launcher Logo jako Win32 icon resource w auto-update.exe.
    // Bez tego dwuklik na exe w Explorer / taskbar / Alt+Tab pokazują
    // generic Rust binary icon. Icon.ico jest multi-res 16-256 px
    // produkowany przez `scripts/build-icons.py` z launcher logo
    // (singularity-launcher/src/main/resources/icons/Logo.png).
    #[cfg(windows)]
    {
        println!("cargo:rerun-if-changed=../installer/icon.ico");
        let mut res = winres::WindowsResource::new();
        res.set_icon("../installer/icon.ico");
        if let Err(e) = res.compile() {
            // Nie fail build jeśli ikona nie zbuduje — zbudowana binarka
            // bez icon to tylko cosmetic regression, nie broken flow.
            // Inno Setup ma osobny SetupIconFile jako fallback.
            println!("cargo:warning=winres icon embed failed: {e}");
        }
    }
}

/// Returns Some(version) on clean success, None when git is absent or produced
/// no usable output. Emits cargo:warning for "git present but failed" — those
/// are the silent-failure cases we want to surface.
fn version_from_git() -> Option<String> {
    match Command::new("git")
        .args(["describe", "--tags", "--dirty"])
        .output()
    {
        Ok(out) if out.status.success() => {
            let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if v.is_empty() {
                println!(
                    "cargo:warning=git describe succeeded but returned empty output; \
                     falling back to CARGO_PKG_VERSION"
                );
                None
            } else {
                Some(v)
            }
        }
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr);
            println!(
                "cargo:warning=git describe failed (exit {}): {}",
                out.status,
                stderr.trim()
            );
            None
        }
        // "No git installed" is a clean, silent fallback (e.g. tarball builds).
        Err(e) if e.kind() == ErrorKind::NotFound => None,
        Err(e) => {
            println!("cargo:warning=git spawn failed ({}): {}", e.kind(), e);
            None
        }
    }
}
