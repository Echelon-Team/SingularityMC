#!/usr/bin/env bash
# Copyright (c) 2026 Echelon Team. All rights reserved.

#
# Buduje `SingularityMC-Installer.AppImage` — universal Linux bootstrap
# (Task 11). Bez wersji w nazwie — IMMUTABLE hash analogia do Windows
# installer (Task 10), choć Linux nie ma SmartScreen reputation.
#
# AppImage zawiera TYLKO:
# - AppRun (bootstrap shell script — pobiera auto-update-linux przy pierwszym run)
# - singularitymc.desktop (FreeDesktop metadata)
# - singularitymc.png (256x256 RGBA icon)
#
# NIE zawiera: auto-update binary (AppRun pobiera), launcher/, runtime/
# (auto-update pobiera przez manifest przy pierwszym uruchomieniu).
#
# Wymaga: `appimagetool` na PATH (CI instaluje via wget w release.yml).
#
# Usage: bash scripts/build-appimage.sh
#   (bez arg — installer jest universal, nie ma wersji)

set -euo pipefail

# Tempdir dla AppDir structure. trap sprząta nawet gdy appimagetool
# się wysypie. Single-quoted body żeby `$APPDIR` rozwiazywało się DOPIERO
# przy odpalaniu trapu (lazy expansion) — SC2064 best practice. Dla
# tego konkretnego skryptu różnica jest akademicka (APPDIR stały), ale
# single-quote = zero ryzyka że reassignment by shell logic expandował
# na pusto i `rm -rf ''` by no-op wywalił.
APPDIR=$(mktemp -d)
trap 'rm -rf "$APPDIR"' EXIT

# AppRun = entry point. Bootstrap pobiera auto-update przy pierwszym
# uruchomieniu (nie bundled — patrz installer/AppRun).
cp installer/AppRun "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"

# .desktop metadata dla FreeDesktop-compatible launchers (GNOME, KDE menu).
cp installer/singularitymc.desktop "$APPDIR/singularitymc.desktop"

# Icon: 256x256 RGBA PNG pre-built przez scripts/build-icons.py.
cp installer/singularitymc.png "$APPDIR/singularitymc.png"

mkdir -p installer
OUTPUT="installer/SingularityMC-Installer.AppImage"
# ARCH=x86_64 jawnie — appimagetool standardowo sniffuje architekturę
# z ELF binarek w AppDir, ale nasz AppDir zawiera TYLKO shell script +
# .desktop + PNG (no binary — AppRun pobiera auto-update przy pierwszym
# uruchomieniu). Bez ARCH env var tool wali "Unable to guess architecture".
# Hardcoded x86_64 bo release flow CI jest ubuntu-latest (amd64 runner);
# arm64 AppImage-y to future work z osobnym matrix entry.
ARCH=x86_64 appimagetool --no-appstream "$APPDIR" "$OUTPUT"

echo "AppImage created: $OUTPUT"
ls -lh "$OUTPUT"
