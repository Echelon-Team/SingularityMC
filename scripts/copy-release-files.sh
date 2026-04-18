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
    # OS-specific jeśli: .exe (Win binary), /runtime/ (bundled JRE),
    # albo NIE jest .jar / .cfg / w /app/ (shared bucket).
    if [[ "$rel" == *.exe ]] || [[ "$rel" == */runtime/* ]] || \
       { [[ "$rel" != *.jar ]] && [[ "$rel" != */app/*.cfg ]]; }; then
        cp "$file" "$DEST/$OS-$basename"
    else
        # Shared JAR / cfg — jedna kopia wystarczy. Jeśli drugi OS
        # build już skopiował, `cp -n` bezpiecznie no-op.
        cp -n "$file" "$DEST/$basename" 2>/dev/null || true
    fi
done
