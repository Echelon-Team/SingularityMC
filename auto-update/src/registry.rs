//! Windows Registry updates dla post-auto-update Control Panel metadata.
//!
//! **Problem:** Inno Setup installer zapisuje `DisplayVersion` w HKCU
//! Uninstall key przy initial install z `AppVersion` w `.iss` (= "1.0.0"
//! frozen universal installer). User widzi w Panel Sterowania → Programy
//! i Funkcje → "1.0.0" zamiast realnej wersji launchera pobranego przez
//! auto-update (np. "0.2.0", "0.3.0").
//!
//! **Rozwiązanie:** po udanym update w `app.rs::process_release`, auto-update
//! nadpisuje `DisplayVersion` w rejestrze na wersję z manifestu. Klucz:
//!   `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\{AppId}_is1`
//!
//! AppId to UUID stały z `installer/singularitymc.iss` (linia 35). `_is1`
//! suffix dodaje Inno Setup automatycznie. Konwencja stabilna od Inno 1.3
//! (pre-2000) — potwierdzone w oficjalnej dokumentacji jrsoftware i bibliotece
//! UninsIS.
//!
//! **Industry-standard practice:** Chrome (Omaha), Firefox, VSCode User
//! installer, Steam bootstrapper — wszystkie nadpisują Uninstall key po
//! self-update. Squirrel.Windows (Discord/Slack/Teams) mają buga (issue
//! #1187) gdzie tego NIE robią — nasz approach jest po prostu "jak powinno
//! być".
//!
//! **Failure mode:** jeśli registry write fail (brak klucza = stara
//! instalacja bez Inno, brak permissions na HKCU co jest edge case), log
//! warning i continue — update tarball extract już się powiódł, user
//! ma działający launcher, metadata mismatch jest cosmetic only. NIE
//! propagujemy error do `Result` bo nie chcemy rollbackować udanego
//! install tylko z powodu Panel Sterowania display version.
//!
//! **Linux:** module exposes no-op fn (unconditional `Ok(())`) bo Linux
//! nie ma Windows Registry. AppImage `.desktop` file pokazuje metadata
//! inaczej (Name=SingularityMC, brak pola "version").

/// Inno Setup AppId z `installer/singularitymc.iss` linia 35. MUSI match
/// dokładnie — Inno używa tej UUID jako klucza rejestru przy install,
/// auto-update tego samego klucza szuka przy update.
///
/// UWAGA o zmianie: jeśli kiedyś zmienisz AppId w `.iss`, stary installer
/// zostawia orphan uninstall entry — user widzi DWA wpisy SingularityMC
/// w Panel Sterowania. Generalnie NIE zmieniać po first stable release.
#[cfg(target_os = "windows")]
const INNO_APP_ID: &str = "{18159995-d967-4cd2-8885-77bfa97cfa9f}_is1";

/// Relative path pod HKCU gdzie Inno zapisał per-user install metadata.
/// `PrivilegesRequired=lowest` w .iss → Inno zawsze używa HKCU (nie HKLM).
#[cfg(target_os = "windows")]
const UNINSTALL_KEY_PATH: &str =
    r"Software\Microsoft\Windows\CurrentVersion\Uninstall";

/// Zapisuje `DisplayVersion` w HKCU Uninstall key dla SingularityMC.
///
/// Return semantics:
/// - `Ok(())` — registry write succeeded LUB no-op na non-Windows platformie
/// - `Err(..)` — Windows registry error (key missing, permission denied,
///   registry hive corrupted). Caller zwyczajowo logs warning i ignoruje —
///   to nie-krytyczna failure która nie powinna rollbackować update.
///
/// `launcher_version` typowo pochodzi z `remote.version` po successful
/// `process_release` — np. "0.2.0", "0.3.1".
#[cfg(target_os = "windows")]
pub fn update_uninstall_display_version(launcher_version: &str) -> std::io::Result<()> {
    use winreg::RegKey;
    use winreg::enums::{HKEY_CURRENT_USER, KEY_SET_VALUE};

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let full_subkey = format!(r"{UNINSTALL_KEY_PATH}\{INNO_APP_ID}");

    // `open_subkey_with_flags` z KEY_SET_VALUE tylko — nie potrzebujemy
    // KEY_QUERY_VALUE bo tylko piszemy. Minimalne permissions reduce risk
    // że antywirus / heuristic detection flagging operation jako suspicious.
    let key = hkcu.open_subkey_with_flags(&full_subkey, KEY_SET_VALUE)?;
    key.set_value("DisplayVersion", &launcher_version.to_string())?;
    log::info!(
        "registry: updated HKCU\\{full_subkey}\\DisplayVersion = {launcher_version}"
    );
    Ok(())
}

/// Linux no-op — podpis kompatybilny z Windows dla jednolitego call site
/// w `app.rs`. Zwraca `Ok(())` bezwarunkowo. `#[allow(unused)]` bo
/// parametr unused na Linux — inaczej clippy warn.
#[cfg(not(target_os = "windows"))]
#[allow(clippy::unnecessary_wraps)]
pub fn update_uninstall_display_version(_launcher_version: &str) -> std::io::Result<()> {
    // No Windows Registry na Linux/macOS. AppImage/.deb have own metadata
    // mechanisms (desktop files, dpkg database) które handle tam są.
    Ok(())
}

#[cfg(all(test, target_os = "windows"))]
mod tests {
    use super::*;
    use winreg::RegKey;
    use winreg::enums::{HKEY_CURRENT_USER, KEY_ALL_ACCESS, KEY_READ};

    /// Test-only throwaway subkey pod HKCU. Używamy odrębnej ścieżki dla
    /// tests żeby NIE dotykać realnego SingularityMC uninstall key gdyby
    /// developer właśnie testował installer na swoim komputerze.
    const TEST_SUBKEY: &str = r"Software\SingularityMC-Test\RegistryModuleTest";

    /// Helper: tworzy test subkey, zapewnia cleanup przez Drop.
    struct TestKeyGuard {
        path: String,
    }

    impl TestKeyGuard {
        fn new(path: &str) -> Self {
            let hkcu = RegKey::predef(HKEY_CURRENT_USER);
            hkcu.create_subkey(path).expect("create test subkey");
            Self { path: path.to_string() }
        }
    }

    impl Drop for TestKeyGuard {
        fn drop(&mut self) {
            let hkcu = RegKey::predef(HKEY_CURRENT_USER);
            // Best-effort cleanup — jeśli key nie istnieje (test już usunął)
            // albo process interrupted mid-test, just swallow. Test isolation
            // dzięki unique path per module.
            let _ = hkcu.delete_subkey_all(&self.path);
        }
    }

    /// Wariant write/read zamiast realnego Uninstall key — test dowodzi
    /// że winreg API zrozumienie jest poprawne (open + set_value + read
    /// back matching). Nie testujemy `update_uninstall_display_version`
    /// bezpośrednio bo wymaga hardcoded Inno AppId subkey który nie jest
    /// gwarantowanie present na CI runnerze bez uruchomienia installera.
    #[test]
    fn winreg_write_and_read_roundtrip() {
        let _guard = TestKeyGuard::new(TEST_SUBKEY);

        let hkcu = RegKey::predef(HKEY_CURRENT_USER);
        let key = hkcu
            .open_subkey_with_flags(TEST_SUBKEY, KEY_ALL_ACCESS)
            .expect("open test subkey");
        key.set_value("DisplayVersion", &String::from("0.2.0"))
            .expect("set value");

        // Read back — sanity check że write persisted + type preserved.
        let read_key = hkcu
            .open_subkey_with_flags(TEST_SUBKEY, KEY_READ)
            .expect("reopen read-only");
        let value: String = read_key
            .get_value("DisplayVersion")
            .expect("read value back");
        assert_eq!(value, "0.2.0");
    }

    /// Documents expected behavior: gdy subkey nie istnieje (fresh env bez
    /// installed SingularityMC), `update_uninstall_display_version` zwraca
    /// Err. Caller w app.rs decides: log + ignore (nie-blokujące).
    #[test]
    fn errors_when_subkey_missing() {
        // Testujemy protected path: unique nonexistent subkey pod HKCU.
        // Przez nasz stub-helper konstrukcję nie stub'ujemy realnego
        // INNO_APP_ID, weryfikujemy winreg fail pattern dla missing key.
        let hkcu = RegKey::predef(HKEY_CURRENT_USER);
        let nonexistent = r"Software\SingularityMC-Test\DefinitelyDoesNotExist12345";
        // First cleanup gdyby ktoś ręcznie zostawił po poprzednim rodzaju.
        let _ = hkcu.delete_subkey_all(nonexistent);

        let result = hkcu.open_subkey_with_flags(
            nonexistent,
            winreg::enums::KEY_SET_VALUE,
        );
        assert!(result.is_err(), "open_subkey na brak powinien fail");
    }
}
