#!/usr/bin/env python3
# Copyright (c) 2026 Echelon Team. All rights reserved.
"""
One-shot script to prepend copyright notice to every source file in the repo.

Per project_license.md (memory rule): SingularityMC is Source Available,
not OSS. Each source file must have a visible copyright notice to
strengthen our position in case of infringement.

Idempotent — re-running will not duplicate notices (checks for "Echelon
Team" + "All rights reserved" in the first 10 lines of the file).

Run ONCE from repo root:
    python scripts/add-copyright-notice.py

Skips: build/, target/, .gradle/, .git/, node_modules/, README.md,
third-party files, and any file that already has the notice.
"""

from __future__ import annotations

import sys
from pathlib import Path

NOTICE_TEXT = "Copyright (c) 2026 Echelon Team. All rights reserved."

# Extension → line prefix. Shebang detection is now DYNAMIC (patrz
# prepend_notice poniżej) — jeśli plik zaczyna się od `#!` to shebang
# preserved line 1 BEZ WZGLĘDU na extension. Dostaliśmy burned przez
# `scripts/generate-manifest.main.kts` która ma `.kts` ale też shebang
# `#!/usr/bin/env kotlin` — hard-coded `.kts: shebang-aware=False` zamienił
# shebang z linii 1 na linię 3, breaking script execution.
COMMENT_STYLES: dict[str, str] = {
    ".rs": "// ",
    ".kt": "// ",
    ".kts": "// ",
    ".java": "// ",
    ".sh": "# ",
    ".iss": "; ",
    ".py": "# ",
    ".yml": "# ",
    ".yaml": "# ",
    ".toml": "# ",
}

# Directory parts that cause skip — anywhere in path.
SKIP_DIRS = {
    "build", "target", ".gradle", ".git", "node_modules", ".idea",
    "out", "bin", ".playwright-mcp",
}

# Specific file names to skip even if extension matches.
# README/CHANGELOG are docs, not source. Cargo.lock is generated.
SKIP_FILENAMES = {
    "Cargo.lock", "gradle-wrapper.properties",
}


def should_skip_path(path: Path) -> bool:
    if any(part in SKIP_DIRS for part in path.parts):
        return True
    if path.name in SKIP_FILENAMES:
        return True
    return False


def already_has_notice(content: str) -> bool:
    """True if first 10 lines contain both Echelon Team AND All rights reserved."""
    head = "\n".join(content.splitlines()[:10])
    return "Echelon Team" in head and "All rights reserved" in head


def prepend_notice(file_path: Path, prefix: str) -> bool:
    """Return True if notice added, False if skipped (already has or empty).

    Shebang handling: if FIRST line starts with `#!`, preserve it as line 1
    and put notice on line 2. Works for any extension — `.sh`, `.py`, but
    also `.kts` (Kotlin CLI scripts like `scripts/generate-manifest.main.kts`
    use `#!/usr/bin/env kotlin` shebang).
    """
    try:
        content = file_path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, PermissionError) as e:
        print(f"  skip (read error): {file_path}: {e}", file=sys.stderr)
        return False

    if not content.strip():
        return False

    if already_has_notice(content):
        return False

    notice_line = f"{prefix}{NOTICE_TEXT}"
    lines = content.split("\n")

    # Dynamic shebang detection — preserve `#!/...` as line 1 regardless of
    # extension. Bez tego .kts scripts z shebang (kotlin CLI) by zostały
    # broken (shebang MUSI być line 1 żeby system execve je uznał).
    insert_at = 1 if lines and lines[0].startswith("#!") else 0

    # Insert notice + blank separator line after shebang (or at top).
    new_lines = lines[:insert_at] + [notice_line, ""] + lines[insert_at:]
    new_content = "\n".join(new_lines)

    try:
        file_path.write_text(new_content, encoding="utf-8")
        return True
    except PermissionError as e:
        print(f"  skip (write error): {file_path}: {e}", file=sys.stderr)
        return False


def handle_special_files(repo_root: Path) -> tuple[int, int]:
    """Files without standard extension that still need notice.

    - installer/AppRun is a bash shell script (no extension; has shebang)
    - installer/singularitymc.desktop is a FreeDesktop entry (# comments)

    Shebang handling delegated do prepend_notice — dynamic detection.
    """
    added = 0
    skipped = 0
    for relpath, prefix in [
        ("installer/AppRun", "# "),
        ("installer/singularitymc.desktop", "# "),
    ]:
        p = repo_root / relpath
        if not p.exists():
            continue
        if prepend_notice(p, prefix):
            added += 1
            print(f"  + {relpath}")
        else:
            skipped += 1
    return added, skipped


def main() -> int:
    repo_root = Path.cwd()
    added = 0
    skipped = 0
    errors = 0

    for ext, prefix in COMMENT_STYLES.items():
        for file_path in repo_root.rglob(f"*{ext}"):
            if not file_path.is_file():
                continue
            if should_skip_path(file_path):
                continue
            try:
                if prepend_notice(file_path, prefix):
                    added += 1
                else:
                    skipped += 1
            except (OSError, ValueError) as e:
                errors += 1
                print(f"  ! error on {file_path}: {e}", file=sys.stderr)

    # Special filename-only entries (AppRun, .desktop).
    sp_added, sp_skipped = handle_special_files(repo_root)
    added += sp_added
    skipped += sp_skipped

    print()
    print(f"Added notice to:  {added}")
    print(f"Skipped (has it): {skipped}")
    print(f"Errors:           {errors}")
    return 0 if errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
