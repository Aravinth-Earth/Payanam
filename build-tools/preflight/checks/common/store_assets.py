#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The store-listing assets are real files, not path text from a symlink checked out on Windows.

The check itself already exists as `build-tools/scripts/check-store-listing-assets.py`; this step runs
that implementation rather than restating it, so the two scripts keep one source of truth during the
migration.
"""
import subprocess
import sys
from pathlib import Path

NAME = "Store-listing assets"


def run(ctx) -> tuple[bool, str]:
    script = Path(ctx.root) / "build-tools/scripts/check-store-listing-assets.py"
    if not script.is_file():
        return False, f"missing {script}"
    result = subprocess.run(
        [sys.executable, str(script), "--repo-root", str(ctx.root)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = (result.stdout + result.stderr).strip().splitlines()
        return False, detail[-1] if detail else f"exit {result.returncode}"
    return True, "ok"
