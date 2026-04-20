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
        // z Cargo.toml (np. "1.1.0"). Windows oczekuje 4-part format
        // (major.minor.patch.build) — dopełniamy zerem jeśli brak build.
        //
        // Te wartości idą do StringFileInfo StringTable (czytane przez
        // Windows Explorer GUI). Binary VS_FIXEDFILEINFO (numeric) jest
        // ustawiany osobno przez `set_version_info()` — winres robi to
        // automatycznie z CARGO_PKG_VERSION gdy go nie nadpiszemy explicite.
        let file_version = format!("{}.0", env!("CARGO_PKG_VERSION"));
        res.set("FileVersion", &file_version);
        res.set("ProductVersion", &file_version);
        res.set("ProductName", "SingularityMC Auto-Update");
        res.set("FileDescription", "SingularityMC auto-update daemon");
        res.set("CompanyName", "Echelon Team");
        res.set("LegalCopyright", "(c) 2026 Echelon Team");

        if let Err(e) = res.compile() {
            // Nie fail build jeśli resource nie zbuduje — zbudowana binarka
            // bez icon/version info to tylko cosmetic regression, nie broken flow.
            // Inno Setup ma osobny SetupIconFile jako fallback.
            println!("cargo:warning=winres embed failed: {e}");
        }
    }
}
