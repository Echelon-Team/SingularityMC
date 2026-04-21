#!/usr/bin/env bash
# Copyright (c) 2026 Echelon Team. All rights reserved.

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
#   <launcherDistRoot> — Compose Desktop jpackage output root. Zawiera layout
#                        zależny od OS:
#                        Windows: <root>/SingularityMC.exe, <root>/app/,
#                                 <root>/runtime/ (runtime SIBLING exe)
#                        Linux:   <root>/bin/singularitymc, <root>/lib/app/,
#                                 <root>/lib/runtime/ (runtime NESTED w lib/)
#   <outputDir>        — gdzie zapisać tarbally (np. release/)
#   <osSuffix>         — "windows" lub "linux"
#
# Output:
#   <outputDir>/launcher-<osSuffix>.tar.gz  (wszystko z <launcherDistRoot>
#                                            POZA runtime folderem — path
#                                            runtime różni się per OS)
#   <outputDir>/jre-<osSuffix>.tar.gz        (FLAT content runtime/ folderu:
#                                            bin/, conf/, lib/, release —
#                                            bez "runtime/" prefix)
#
# Dlaczego flat content w JRE tarball: Rust extract_jre_bundle extract'uje
# do `install_dir/<RUNTIME_DIR>` gdzie RUNTIME_DIR to zagnieżdżona ścieżka
# per OS ("launcher/runtime" lub "launcher/lib/runtime"). Flat archive =
# `bin/java.exe` w tarze → po extract `install_dir/launcher/runtime/bin/
# java.exe`. Gdybyśmy pack'owali z prefiksem `runtime/`, rozpakowanie dało
# by `launcher/runtime/runtime/bin/java.exe` (double nesting).
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
    # Diagnostic: co faktycznie createDistributable wygenerowało pod
    # `compose/binaries/main/app/`? Typowy mismatch to Compose Desktop
    # `nativeDistributions.packageName` override (Linux = "singularitymc"
    # lowercase, Win = "SingularityMC") niezsynchronizowany z release.yml
    # matrix `app_dir_name`.
    PARENT_DIR=$(dirname "$LAUNCHER_ROOT")
    if [ -d "$PARENT_DIR" ]; then
        echo "Faktyczna zawartość $PARENT_DIR:" >&2
        ls -la "$PARENT_DIR" >&2
    else
        echo "Parent $PARENT_DIR także nie istnieje — createDistributable zapewne failował." >&2
    fi
    exit 1
fi

case "$OS_SUFFIX" in
    windows|linux) ;;
    *)
        echo "osSuffix musi być 'windows' lub 'linux', got: $OS_SUFFIX" >&2
        exit 1
        ;;
esac

# Auto-detect runtime location. jpackage `--type app-image` różni się per OS:
#   Windows: runtime/ SIBLING exe (root-level z app/, launcher.exe)
#   Linux:   lib/runtime/ NESTED w lib/ (razem z lib/app/, lib/launcher.cfg)
# Ale Compose Desktop może customize layout w przyszłych wersjach, a CI
# runnery mogą mieć inne defaults — auto-detect + diagnostic na miss zamiast
# hardcode.
if [ -d "$LAUNCHER_ROOT/runtime" ]; then
    RUNTIME_SRC="$LAUNCHER_ROOT/runtime"
    # --anchored matchuje pattern od archiwum root (path relative do `-C` dir).
    LAUNCHER_EXCLUDE='./runtime'
elif [ -d "$LAUNCHER_ROOT/lib/runtime" ]; then
    RUNTIME_SRC="$LAUNCHER_ROOT/lib/runtime"
    LAUNCHER_EXCLUDE='./lib/runtime'
else
    echo "brak runtime folder w $LAUNCHER_ROOT/{runtime,lib/runtime} —" >&2
    echo "jpackage createDistributable się nie wykonał LUB Compose Desktop" >&2
    echo "zmienił layout output'u. Diagnostic dump struktury (3 levels):" >&2
    # Native `find` dla diagnostyki — pokaż co faktycznie jest w output dir.
    # max-depth=3 powinien złapać runtime/ w obu znanych locations i dodatkowe.
    find "$LAUNCHER_ROOT" -maxdepth 3 -type d | head -40 >&2
    exit 1
fi
echo "Detected runtime at: $RUNTIME_SRC"

mkdir -p "$OUTPUT_DIR"
LAUNCHER_OUT="$OUTPUT_DIR/launcher-$OS_SUFFIX.tar.gz"
JRE_OUT="$OUTPUT_DIR/jre-$OS_SUFFIX.tar.gz"

# Launcher tarball: wszystko z $LAUNCHER_ROOT POZA runtime folderem (path
# różni się per OS — patrz case wyżej). `-C $LAUNCHER_ROOT .` zmienia cwd
# tar żeby w archiwum były relatywne ścieżki od root launcher dist
# (SingularityMC.exe + app/ na Windows, bin/ + lib/... na Linux).
# --anchored: wzorzec --exclude matchowany od początku path (inaczej
# "lib/runtime" mogłoby match'ować deeper podstringi).
echo "Packing $LAUNCHER_OUT (excluding $LAUNCHER_EXCLUDE)..."
tar \
    --anchored \
    --exclude="$LAUNCHER_EXCLUDE" \
    --sort=name \
    --mtime='@0' \
    --owner=0 --group=0 \
    --numeric-owner \
    -czf "$LAUNCHER_OUT" \
    -C "$LAUNCHER_ROOT" \
    .
ls -lh "$LAUNCHER_OUT"

# JRE tarball: FLAT content runtime folderu (bin/, conf/, lib/, release bez
# "runtime/" prefix). `-C $RUNTIME_SRC .` pack'uje jego wnętrze. Rust
# extract_jre_bundle rozpakowuje do `install_dir/<RUNTIME_DIR>` gdzie
# RUNTIME_DIR to "launcher/runtime" (Win) lub "launcher/lib/runtime" (Lin) —
# finalne `install_dir/launcher/runtime/bin/java.exe` matches jpackage
# sibling-of-exe layout.
echo "Packing $JRE_OUT (flat content from $RUNTIME_SRC)..."
tar \
    --sort=name \
    --mtime='@0' \
    --owner=0 --group=0 \
    --numeric-owner \
    -czf "$JRE_OUT" \
    -C "$RUNTIME_SRC" \
    .
ls -lh "$JRE_OUT"

echo "Done. Output: $OUTPUT_DIR/"
