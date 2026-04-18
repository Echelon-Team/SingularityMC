; SingularityMC Inno Setup installer script
;
; Buduje `SingularityMC-Setup-<VERSION>.exe` który bierze skompilowany
; auto-update.exe + config template i instaluje do
; `%LocalAppData%\Programs\SingularityMC\`. Launcher NIE JEST packowany
; do installera — auto-update pobiera go przy pierwszym uruchomieniu
; przez `manifest-windows.json` z GitHub Release.
;
; CI uruchamia: iscc installer/singularitymc.iss /DVERSION=x.y.z
;
; Lokalny test (Windows z Inno Setup 6):
;   cd installer && iscc singularitymc.iss /DVERSION=0.1.0

#define MyAppName "SingularityMC"
#define MyAppPublisher "Echelon Team"
#define MyAppURL "https://github.com/Echelon-Team/SingularityMC"
#define MyAppExeName "auto-update.exe"

; VERSION wstrzykiwane z CI via /DVERSION=x.y.z. Fallback dla
; lokalnego developu gdy nikt nie poda.
#ifndef VERSION
#define VERSION "0.1.0"
#endif

[Setup]
; AppId — UUID stały dla upgrade detection. Inno Setup porównuje AppId
; żeby wykryć "ten sam program zainstalowany wcześniej" → auto-upgrade
; in place zamiast equal-install. NIGDY nie zmieniaj UUID — zniszczy
; upgrade flow dla wszystkich obecnych userów.
AppId={{18159995-d967-4cd2-8885-77bfa97cfa9f}
AppName={#MyAppName}
AppVersion={#VERSION}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
; %LocalAppData%\Programs — nie wymaga admin, user-scoped install.
; `PrivilegesRequired=lowest` = no UAC prompt. User może wybrać dialog
; elevation jeśli chce Program Files install.
DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=.
OutputBaseFilename=SingularityMC-Setup-{#VERSION}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
AllowNoIcons=yes

[Languages]
Name: "polish"; MessagesFile: "compiler:Languages\Polish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce
Name: "startmenuicon"; Description: "Dodaj do menu Start"; GroupDescription: "Skróty:"; Flags: checkedonce
Name: "autostart"; Description: "Uruchamiaj przy starcie Windows"; GroupDescription: "Dodatkowe:"; Flags: unchecked

[Files]
; auto-update binary (zbudowany przez `cargo build --release` w CI przed iscc).
; `DestName: auto-update.exe` — przemianowanie z `singularitymc-auto-update.exe`
; żeby user nie widział długiej nazwy w Task Manager / Start Menu.
Source: "..\auto-update\target\release\singularitymc-auto-update.exe"; DestDir: "{app}"; DestName: "auto-update.exe"; Flags: ignoreversion
; Config template — kopiowane TYLKO gdy plik nie istnieje
; (onlyifdoesntexist) żeby upgrade nie nadpisał user settings.
Source: "auto-update-config.template.json"; DestDir: "{app}"; DestName: "auto-update-config.json"; Flags: onlyifdoesntexist

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: startmenuicon
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
; Register `singularitymc://` URL protocol — przyszłe deep linki
; (np. z Modrinth "Install in SingularityMC" button). Handler to
; auto-update.exe z argumentem `--url=<raw>`.
Root: HKCU; Subkey: "Software\Classes\singularitymc"; ValueType: string; ValueData: "URL:SingularityMC Protocol"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\singularitymc"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\singularitymc\shell\open\command"; ValueType: string; ValueData: """{app}\{#MyAppExeName}"" ""--url=%1"""; Flags: uninsdeletekey

; Auto-start (gdy task `autostart` wybrany).
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "{#MyAppName}"; ValueData: """{app}\{#MyAppExeName}"""; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Pliki auto-update-managed które mają iść razem z deinstall —
; launcher/ (downloaded przez auto-update), File-Backups/ (pre-update
; backups), local-manifest.json/version.txt (current install state),
; auto-update-config.json/log.
Type: filesandordirs; Name: "{app}\launcher"
Type: filesandordirs; Name: "{app}\File-Backups"
Type: files; Name: "{app}\local-manifest.json"
Type: files; Name: "{app}\version.txt"
Type: files; Name: "{app}\auto-update-config.json"
Type: files; Name: "{app}\auto-update.log"

[Code]
// User data ( instancje, konta, ustawienia launcher-a ) żyje w
// %APPDATA%\SingularityMC\ — NIE usuwane domyślnie. Dialog pyta czy
// skasować ; default "Nie" żeby reinstall zachował user state.
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
      DelTree(UserDataDir, True, True, True);
    end;
  end;
end;
