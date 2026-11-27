#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Desktop verification: detekt, the desktop suite, the shared UI suite and the coverage floor.

`:core:ui:desktopTest` is named here because no other task runs it: the module's shared-screen tests
would otherwise run in no gate, and a regression there ships to both platforms unverified.
"""
import subprocess
from pathlib import Path

NAME = "Desktop verification (detekt, tests, coverage)"
TASKS = (
    ":desktop:detekt",
    ":desktop:test",
    ":core:ui:desktopTest",
    ":desktop:jacocoTestReport",
    ":desktop:jacocoTestCoverageVerification",
)


def run(ctx) -> tuple[bool, str]:
    result = subprocess.run(["./gradlew", *TASKS], cwd=Path(ctx.root), env=ctx.env, capture_output=True, text=True)
    if result.returncode != 0:
        tail = (result.stdout + result.stderr).strip().splitlines()
        return False, tail[-1] if tail else f"gradle exited {result.returncode}"
    return True, f"{len(TASKS)} task(s) passed"
