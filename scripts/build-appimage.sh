#!/usr/bin/env bash
#
# Buduje `SingularityMC-<VERSION>.AppImage` z pre-built
# auto-update binarki + AppRun + .desktop.
#
# Launcher NIE JEST packowany — auto-update pobierze go przy
# pierwszym uruchomieniu (tak samo jak Windows installer — patrz
# installer/singularitymc.iss).
#
# Wymaga: `appimagetool` na PATH (CI instaluje via wget w release.yml).
#
# Usage: bash scripts/build-appimage.sh <VERSION>

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <VERSION>" >&2
    exit 1
fi

VERSION=$1

# Tempdir dla AppDir structure. trap sprząta nawet gdy appimagetool
# się wysypie.
APPDIR=$(mktemp -d)
trap "rm -rf '$APPDIR'" EXIT

# Auto-update binary (zbudowana przez `cargo build --release` przed
# wywołaniem tego skryptu).
cp auto-update/target/release/singularitymc-auto-update "$APPDIR/auto-update"
chmod +x "$APPDIR/auto-update"

# AppRun = AppImage entrypoint (uruchamia `auto-update` binarkę).
cp installer/AppRun "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"

# .desktop — metadata dla FreeDesktop-compatible launchers.
cp installer/singularitymc.desktop "$APPDIR/singularitymc.desktop"

# Icon: pre-built przez `scripts/build-icons.py` z launcher Logo
# (biały background → alpha). 256x256 PNG RGBA. AppImage + .desktop
# konsumują `Icon=singularitymc` → GNOME/KDE menu widzi właściwą
# ikonę zamiast default broken-image placeholder.
cp installer/singularitymc.png "$APPDIR/singularitymc.png"

# Default config bundled into AppImage. User nadpisuje przy
# pierwszym uruchomieniu via auto-update config dialog.
cat > "$APPDIR/auto-update-config.json" <<EOF
{
  "channel": "stable"
}
EOF

mkdir -p installer
appimagetool --no-appstream "$APPDIR" "installer/SingularityMC-$VERSION.AppImage"

echo "AppImage created: installer/SingularityMC-$VERSION.AppImage"
