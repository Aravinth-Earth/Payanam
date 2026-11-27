#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The static-analysis gate: the `staticAnalysisCheck` aggregate the build already defines.

Fatal on purpose. detekt carries the KDoc baseline, so a green build is also the promise that the
documented contract still holds - the same aggregate build-android.ps1 runs.
"""
import subprocess
from pathlib import Path

NAME = "Static analysis (lint + detekt)"


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    result = subprocess.run(
        ["./gradlew", "staticAnalysisCheck"],
        cwd=root,
        env=ctx.env,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        tail = (result.stdout + result.stderr).strip().splitlines()
        return False, tail[-1] if tail else f"gradle exited {result.returncode}"
    return True, "passed"
