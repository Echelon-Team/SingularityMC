; SingularityMC Universal Installer (Inno Setup script)
;
; 🔴 KRYTYCZNE — ten skrypt po pierwszym stable release MUSI być
; NIEZMIENNY. Zmiana dowolnego bajta → nowy installer hash → reset
; SmartScreen reputation. Patrz:
;   docs/superpowers/specs/2026-04-20-universal-installer-auto-update-refactor-design.md §8
;   memory rules: project_installer_hash_stability, project_release_asset_naming_immutable
;
; Bootstrap flow (Task 10, spec correction per web-research 2026-04-20):
;   1. Inno Setup standardowy wizard (Next → Install).
;   2. [Files]: kopiuje icon.ico + auto-update-config.template.json.
;   3. [Icons]: tworzy skróty wskazujące na {app}\auto-update.exe (plik
;      jeszcze nie istnieje — Windows akceptuje shortcut do przyszłego targetu).
;   4. `[Code]::CurStepChanged(ssPostInstall)`: Pascal `DownloadTemporaryFile`
;      pobiera auto-update-windows.exe z GitHub Releases latest, kopiuje
;      pod {app}\auto-update.exe.
;   5. Pascal `Exec()` spawn'uje auto-update.exe jako final step (zamiast
;      [Run] sekcji — web-research-v1 finding że [Run] odpala się PRZED
;      ssPostInstall, więc nie może uruchomić jeszcze-nie-pobranego pliku).
;
; Auto-update sam potem pobiera launcher.tar.gz + jre-windows.tar.gz +
; rozpakowuje (Task 5 process_release flow).

#define MyAppName "SingularityMC"
#define MyAppPublisher "Echelon Team"
#define MyAppURL "https://github.com/Echelon-Team/SingularityMC"
#define MyAppExeName "auto-update.exe"

; AppVersion STAŁY — part hash stability. Wartość umowna "1.0.0" oznaczająca
; "universal installer generation 1". Bump tylko gdy świadomie godzimy się
; na reset SmartScreen reputation (bug krytyczny w Pascal etc.).
#define InstallerAppVersion "1.0.0"

[Setup]
AppId={{18159995-d967-4cd2-8885-77bfa97cfa9f}
AppName={#MyAppName}
AppVersion={#InstallerAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
; %LocalAppData%\Programs — per-user, bez UAC.
DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=.
; NAZWA PLIKU BEZ WERSJI — immutable hash convention. Nowe release'y
; auto-update nie wymagają rebuild installera.
OutputBaseFilename=SingularityMC-Installer
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\icon.ico
AllowNoIcons=yes

[Languages]
Name: "polish"; MessagesFile: "compiler:Languages\Polish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce
Name: "startmenuicon"; Description: "Dodaj do menu Start"; GroupDescription: "Skróty:"; Flags: checkedonce
Name: "autostart"; Description: "Uruchamiaj przy starcie Windows"; GroupDescription: "Dodatkowe:"; Flags: unchecked

[Files]
; Installer zawiera TYLKO:
; - icon.ico (Add/Remove Programs + skróty)
; - auto-update-config.template.json (default config, onlyifdoesntexist
;   żeby upgrade nie nadpisał user settings)
;
; NIE zawiera: auto-update.exe (Pascal pobiera w [Code] ssPostInstall),
; launcher/, runtime/ (auto-update pobiera przez manifest przy pierwszym
; uruchomieniu).
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "auto-update-config.template.json"; DestDir: "{app}"; DestName: "auto-update-config.json"; Flags: onlyifdoesntexist

[Icons]
; Skróty wskazują na {app}\auto-update.exe który Pascal pobierze w ssPostInstall.
; Windows akceptuje tworzenie shortcut do nieistniejącego targetu — po pobraniu
; auto-update.exe skrót zacznie działać.
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"; Tasks: startmenuicon
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Registry]
; URL protocol handler `singularitymc://` — przyszłe deep links (np.
; Modrinth "Install in SingularityMC"). Handler to auto-update.exe z
; argumentem --url=<raw>.
Root: HKCU; Subkey: "Software\Classes\singularitymc"; ValueType: string; ValueData: "URL:SingularityMC Protocol"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\singularitymc"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\singularitymc\shell\open\command"; ValueType: string; ValueData: """{app}\{#MyAppExeName}"" ""--url=%1"""; Flags: uninsdeletekey
; Auto-start (gdy task `autostart` wybrany).
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "{#MyAppName}"; ValueData: """{app}\{#MyAppExeName}"""; Flags: uninsdeletevalue; Tasks: autostart

[Run]
; [Run] entries bez flagi execute PRZED ssPostInstall (gdy plik jeszcze
; nie pobrany) — tu używamy `postinstall` flag który przenosi execution
; na Finished wizard page (PO ssPostInstall download). User widzi checkbox
; "Uruchom SingularityMC" domyślnie zaznaczony. `nowait` = nie blokuje
; wizard close. `skipifsilent` = /SILENT install pomija checkbox entirely.
Filename: "{app}\{#MyAppExeName}"; Description: "Uruchom {#MyAppName}"; Flags: postinstall nowait skipifsilent

[UninstallDelete]
; Post-install state który auto-update i launcher produkują — usuwany
; przy uninstall. Paths zgodne z updater.rs const (LAUNCHER_DIR, RUNTIME_DIR,
; TMP_DIR, LAUNCHER_OLD_DIR, RUNTIME_OLD_DIR).
Type: filesandordirs; Name: "{app}\launcher"
Type: filesandordirs; Name: "{app}\launcher.old"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\runtime.old"
Type: filesandordirs; Name: "{app}\tmp"
Type: filesandordirs; Name: "{app}\File-Backups"
Type: files; Name: "{app}\auto-update.exe"
Type: files; Name: "{app}\auto-update.exe.new"
Type: files; Name: "{app}\auto-update.exe.bak"
Type: files; Name: "{app}\auto-update.exe.broken"
Type: files; Name: "{app}\local-manifest.json"
Type: files; Name: "{app}\auto-update-config.json"
Type: files; Name: "{app}\auto-update.log"
Type: files; Name: "{app}\launcher-alive-flag"
Type: files; Name: "{app}\launcher-crash-counter"
Type: files; Name: "{app}\self-update-alive-flag"
Type: files; Name: "{app}\self-update-crash-counter"

; URL hardcoded — immutable per project_release_asset_naming_immutable.
; Zmiana = dead installer hash. `releases/latest/download/<name>` to
; GitHub standard redirect API który zwraca 302 → actual asset URL dla
; najnowszego stable release.
[Code]
const
  AUTO_UPDATE_URL = 'https://github.com/Echelon-Team/SingularityMC/releases/latest/download/auto-update-windows.exe';

// Pascal code wywołane w ssPostInstall — PO [Files] (icon + config
// skopiowane, {app} istnieje), PRZED wizard Finished page. Pobiera
// auto-update.exe do {app}. Spawn robi [Run] postinstall (wyżej),
// który odpala się gdy user klika Finish na Finished page — checkbox
// dostępny do odznaczenia jeśli user NIE chce automatycznego uruchomienia.
procedure CurStepChanged(CurStep: TSetupStep);
var
  DestFile: String;
  TempFile: String;
begin
  if CurStep = ssPostInstall then begin
    DestFile := ExpandConstant('{app}\auto-update.exe');

    // DownloadTemporaryFile pobiera pod {tmp} — wbudowany do Inno.
    // Trzeci arg '' = skip SHA256 verification (HTTPS + GitHub CDN
    // integrity wystarcza dla universal installer; każda kolejna wersja
    // auto-update będzie weryfikowana przez samego auto-update-a przez
    // manifest sha256). Więcej w web-research-v1 raport punkt 6.
    try
      DownloadTemporaryFile(AUTO_UPDATE_URL, 'auto-update.exe', '', nil);
    except
      MsgBox(
        'Nie udało się pobrać auto-update.' + #13#10 + #13#10 +
        'Sprawdź połączenie z internetem i uruchom instalator ponownie.' + #13#10 + #13#10 +
        'Szczegóły: ' + GetExceptionMessage,
        mbError, MB_OK);
      Abort;
    end;

    // DownloadTemporaryFile zapisuje do {tmp}\<BaseName>. Przenosimy do
    // {app}. FileCopy z False = nie fail gdy target istnieje (replace).
    TempFile := ExpandConstant('{tmp}\auto-update.exe');
    if not FileCopy(TempFile, DestFile, False) then begin
      MsgBox(
        'Nie udało się skopiować auto-update.exe do katalogu instalacji.' + #13#10 +
        'Sprawdź uprawnienia dostępu do ' + ExpandConstant('{app}'),
        mbError, MB_OK);
      Abort;
    end;
  end;
end;

// Uninstall confirmation dla user data (zachowane z poprzedniej wersji).
function InitializeUninstall(): Boolean;
var
  Answer: Integer;
  UserDataDir: String;
begin
  Result := True;
  UserDataDir := ExpandConstant('{userappdata}\SingularityMC');
  if DirExists(UserDataDir) then begin
    Answer := MsgBox(
      'Czy usunąć również dane użytkownika (instancje, konta, ustawienia)?' + #13#10 + #13#10 +
      'Jeśli wybierzesz Nie, dane zostaną zachowane dla przyszłej ponownej instalacji.',
      mbConfirmation, MB_YESNO or MB_DEFBUTTON2);
    if Answer = IDYES then begin
      // DelTree zwraca False gdy którykolwiek plik nie da się usunąć
      // (np. launcher wciąż uruchomiony trzyma handle). Bez tego checka
      // uninstaller raportował success a dane zostawały na dysku mimo
      // explicit "Tak, usuń". Istotne dla GDPR "right to erasure".
      if not DelTree(UserDataDir, True, True, True) then begin
        MsgBox(
          'Nie udało się usunąć wszystkich danych w ' + UserDataDir + #13#10 + #13#10 +
          'Zamknij launcher (jeśli wciąż uruchomiony) i usuń katalog ręcznie.',
          mbError, MB_OK);
      end;
    end;
  end;
end;
