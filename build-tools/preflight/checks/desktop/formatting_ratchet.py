#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Changed Kotlin files are formatted (spotless, ratcheted to this change).

The ratchet already exists as `build-tools/scripts/spotless-ratchet.ps1` and is delegated to here
rather than restated: one implementation of the rule while the scripts migrate. Porting the ratchet
itself to Python is its own step (see #3402's follow-ups), so this module records the delegation
instead of pretending the check is native.
"""
import shutil
import subprocess
from pathlib import Path

NAME = "Formatting ratchet"
DELEGATED = "spotless-ratchet.ps1"


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    script = root / "build-tools/scripts" / DELEGATED
    if not script.is_file():
        return False, f"missing {script}"
    pwsh = shutil.which("pwsh") or shutil.which("powershell")
    if not pwsh:
        return False, "pwsh is required to run the formatting ratchet"
    result = subprocess.run([pwsh, "-NoProfile", "-File", str(script)], cwd=root, env=ctx.env, capture_output=True, text=True)
    if result.returncode != 0:
        tail = (result.stdout + result.stderr).strip().splitlines()
        return False, tail[-1] if tail else f"exited {result.returncode}"
    return True, f"delegated to {DELEGATED}"
