#!/usr/bin/env bash
#
# Packs launcher + JRE z Compose Desktop jpackage output do 2 tarballi
# per OS, z deterministic flags — identyczne source = identyczny sha256
# (żeby user NIE pobierał bez potrzeby gdy CI rebuild z tego samego
# source daje te same bajty).
#
# Używane w release job (.github/workflows/release.yml) PRZED generate-manifest.
# Zastępuje legacy per-file copy z v0.1.x — nowy 3-package model (Task 3
# refactor) pakuje launcher + runtime w dwa tar.gz + auto-update.exe osobno.
#
# Usage:
#   copy-release-files.sh <launcherDistRoot> <outputDir> <osSuffix>
#
#   <launcherDistRoot> — np. singularity-launcher/build/compose/binaries/main/app/SingularityMC
#                        (root unpacked Compose Desktop output, zawiera runtime/ subdir)
#   <outputDir>        — gdzie zapisać tarbally (np. release/)
#   <osSuffix>         — "windows" lub "linux"
#
# Output:
#   <outputDir>/launcher-<osSuffix>.tar.gz  (wszystko z <launcherDistRoot>
#                                            POZA runtime/ folderem)
#   <outputDir>/jre-<osSuffix>.tar.gz        (content folder runtime/ z jpackage)
#
# Asset naming — IMMUTABLE per memory rule project_release_asset_naming_immutable.
# Hardcoded w installer Pascal i AppRun shell — zmiana nazwy = dead URL.
#
# Determinism: --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner.
# GNU tar compatible. Windows GitHub runner używa Git Bash / MSYS2 tar
# (GNU fork) — flags są supported. bsdtar native Windows fork (np. w
# Windows 10+ build 17063+) akceptuje także te flags jako aliasy.

set -euo pipefail

if [ "$#" -lt 3 ]; then
    echo "Usage: $0 <launcherDistRoot> <outputDir> <osSuffix>" >&2
    exit 1
fi

LAUNCHER_ROOT="$1"
OUTPUT_DIR="$2"
OS_SUFFIX="$3"

if [ ! -d "$LAUNCHER_ROOT" ]; then
    echo "launcherDistRoot nie jest katalogiem: $LAUNCHER_ROOT" >&2
    exit 1
fi
if [ ! -d "$LAUNCHER_ROOT/runtime" ]; then
    echo "brak runtime/ folder w $LAUNCHER_ROOT — czy jpackage createDistributable się wykonał?" >&2
    exit 1
fi
case "$OS_SUFFIX" in
    windows|linux) ;;
    *) echo "osSuffix musi być 'windows' lub 'linux', got: $OS_SUFFIX" >&2; exit 1 ;;
esac

mkdir -p "$OUTPUT_DIR"
LAUNCHER_OUT="$OUTPUT_DIR/launcher-$OS_SUFFIX.tar.gz"
JRE_OUT="$OUTPUT_DIR/jre-$OS_SUFFIX.tar.gz"

# Launcher tarball: wszystko POZA runtime/. Pattern --exclude=runtime.
# `-C $LAUNCHER_ROOT .` zmienia cwd tar żeby w archiwum były relatywne
# ścieżki od root launcher dist (SingularityMC.exe, app/, SingularityMC.cfg,
# itp.) — bez tego extract na user site dostałby `SingularityMC/...`
# jako prefix co rozjedzie się z spec §4.3 ("wszystko z <dist>/SingularityMC/
# poza runtime/").
echo "Packing $LAUNCHER_OUT..."
tar \
    --exclude='runtime' \
    --sort=name \
    --mtime='@0' \
    --owner=0 --group=0 \
    --numeric-owner \
    -czf "$LAUNCHER_OUT" \
    -C "$LAUNCHER_ROOT" \
    .
ls -lh "$LAUNCHER_OUT"

# JRE tarball: TYLKO runtime/ folder (bin/, lib/, conf/, lib/modules, release).
# `-C $LAUNCHER_ROOT runtime` daje archiwum z `runtime/bin/java.exe`, ...
# jako relative paths — extract na user site idzie do `install_dir/runtime/`
# (per Task 4 extract_jre_bundle).
echo "Packing $JRE_OUT..."
tar \
    --sort=name \
    --mtime='@0' \
    --owner=0 --group=0 \
    --numeric-owner \
    -czf "$JRE_OUT" \
    -C "$LAUNCHER_ROOT" \
    runtime
ls -lh "$JRE_OUT"

echo "Done. Output: $OUTPUT_DIR/"
