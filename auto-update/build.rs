// Copyright (c) 2026 Echelon Team. All rights reserved.

//! Embed the auto-update version string into the binary as `BUILD_VERSION`.
//!
//! Source: `CARGO_PKG_VERSION` = `[package].version` w `Cargo.toml`.
//! Mateusz 2026-04-18 explicit: auto-update version NIEZALEŻNA od
//! launcher git-tag version (spec §4.1.3 example shows
//! `minAutoUpdateVersion: "1.0.0"` vs launcher `version: "1.2.3"`).
//!
//! Poprzednia wersja build.rs używała `git describe --tags --dirty`
//! jako primary source co wstrzykiwało LAUNCHER git tag do
//! auto-update BUILD_VERSION — bug: app.rs potem porównywał "launcher
//! 0.1.0" vs "manifest.minAutoUpdateVersion 1.0.0" co zawsze failuje.
//! Poprawka: build.rs czyta CARGO_PKG_VERSION, launcher version jest
//! propagowana osobnym torem (CI's `GITHUB_REF_NAME` → argv dla
//! `generate-manifest.main.kts` → `manifest.version`).

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=Cargo.toml");

    // `env!("CARGO_PKG_VERSION")` w build.rs jest compile-time stałą
    // którą Cargo sets od `[package].version`. Bump wersji = edycja
    // Cargo.toml + rebuild → nowa BUILD_VERSION osadzona.
    let version = env!("CARGO_PKG_VERSION");
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

        // Windows FileVersion string metadata — widoczne w Properties →
        // Szczegóły. Bez tego user widzi puste pola / Rust default co
        // utrudnia diagnozowanie który build jest zainstalowany u usera.
        //
        // `CARGO_PKG_VERSION` = ten sam field z [package]::version
        // z Cargo.toml (np. "1.3.1").
        //
        // VS_FIXEDFILEINFO (binary 4×u16 FileMajorPart/MinorPart/BuildPart/
        // PrivatePart) ZAWSZE musi mieć 4 części — Windows API wymaga.
        // winres auto-setuje z CARGO_PKG_VERSION_MAJOR/MINOR/PATCH + 0 jako
        // revision. Ale DISPLAYED string w Explorer → Właściwości → Szczegóły
        // bierze się z StringFileInfo "FileVersion" entry który jest wolnym
        // stringiem. Ustawiamy go na czysty SemVer "x.y.z" bez trailing ".0"
        // żeby user widział tożsamą wartość z Cargo.toml (Mateusz feedback
        // 2026-04-21: "1.3.1.0 to nie jest wersja SemVer").
        //
        // SemVer pre-release/build-metadata suffixes (`-rc.1`, `+build.123`)
        // MUSZĄ być obcięte bo StringFileInfo parser Windows je odrzuca
        // jako invalid. Debug-time assertion pilnuje 3-part numeric shape.
        let raw_version = env!("CARGO_PKG_VERSION");
        let base_version = raw_version.split(['-', '+']).next().unwrap_or(raw_version);
        let parts: Vec<&str> = base_version.split('.').collect();
        assert!(
            parts.len() == 3 && parts.iter().all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())),
            "CARGO_PKG_VERSION base '{base_version}' (from '{raw_version}') must be numeric 3-part 'x.y.z'"
        );
        // StringFileInfo display — 3-part SemVer (Explorer pokazuje to).
        // FIXEDFILEINFO numeric — pozostawiamy winres auto (4-part 'x.y.z.0').
        res.set("FileVersion", base_version);
        res.set("ProductVersion", base_version);
        res.set("ProductName", "SingularityMC Auto-Update");
        res.set("FileDescription", "SingularityMC auto-update daemon");
        res.set("CompanyName", "Echelon Team");
        res.set("LegalCopyright", "(c) 2026 Echelon Team");

        if let Err(e) = res.compile() {
            // W release profile PANIC — FileVersion jest celem tego całego
            // mechanizmu (spec §4.2: "user widzi w Windows Properties
            // właściwy numer"). Silently broken artifact w CI = Task 1
            // nie spełnia contract. Panic zatrzymuje CI żeby error nie
            // przeszedł niezauważony.
            //
            // W debug/dev iteration warning wystarczy — developer widzi
            // go lokalnie, icon embed fail to realnie cosmetic.
            if std::env::var("PROFILE").as_deref() == Ok("release") {
                panic!("winres resource embed failed in release profile: {e}");
            }
            println!(
                "cargo:warning=winres resource embed failed (icon/version metadata skipped): {e}"
            );
        }
    }
}
