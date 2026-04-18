#!/usr/bin/env bash
#
# Kopiuje pliki launchera do katalogu release/ z prefiksem per OS
# dla OS-specific plików, bez prefiksu dla cross-platform JARów.
# Używane w release job (.github/workflows/release.yml) do
# przygotowania per-plik assetów ktore auto-update pobiera osobno
# przez manifest.
#
# Classification heuristic MUSI BYĆ spójna z
# `scripts/generate-manifest.main.kts::isOsSpecific` — rozbieżność
# → 404 on update attempt (manifest wskazuje $os-$file, release ma
# tylko $file lub odwrotnie).
#
# Usage: bash scripts/copy-release-files.sh <src_launcher_dir> <dest_dir> <os_suffix>
#   src_launcher_dir — np. artifacts/build-windows/singularity-launcher
#   dest_dir         — np. release/
#   os_suffix        — "win" lub "linux" (krotsze niz "windows" dla
#                      asset names, w sync z manifest URL prefix)

set -euo pipefail

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <src_launcher_dir> <dest_dir> <os_suffix>" >&2
    exit 1
fi

SRC=$1
DEST=$2
OS=$3

find "$SRC" -type f | while read -r file; do
    rel="${file#$SRC/}"
    basename=$(basename "$file")
    # Klasyfikacja MUSI matchować `generate-manifest.main.kts::isOsSpecific`
    # byte-for-byte. Kotlin:
    #   endsWith(".exe") || contains("/runtime/")
    #       || (!endsWith(".jar") && !endsWith(".cfg") && !contains("/app/"))
    # → shared iff NOT exe AND NOT /runtime/ AND (jar ANYWHERE OR cfg
    #   ANYWHERE OR pod /app/). Poniższa logika bash to dokładnie
    #   to samo: poprzednia wersja gated shared tylko na `*/app/*.cfg`
    #   co dawało drift — root-level `SingularityMC.cfg` i plik typu
    #   `launcher/app/subfolder/data.bin` Kotlin traktował jako
    #   shared, bash jako OS-specific → manifest URL wskazywał na
    #   asset którego nie ma w Release → 404 każdego auto-update.
    if [[ "$rel" == *.exe ]] || [[ "$rel" == */runtime/* ]] || \
       { [[ "$rel" != *.jar ]] && [[ "$rel" != *.cfg ]] && [[ "$rel" != */app/* ]]; }; then
        cp "$file" "$DEST/$OS-$basename"
    else
        # Shared JAR / cfg / plik pod /app/ — jedna kopia wystarczy.
        # Pierwszy OS wygrywa, drugi skip. Explicit existence check
        # zamiast `cp -n || true` który zagryzał real errors
        # (permission denied, no space, destination is a directory).
        if [[ ! -f "$DEST/$basename" ]]; then
            cp "$file" "$DEST/$basename"
        fi
    fi
done
