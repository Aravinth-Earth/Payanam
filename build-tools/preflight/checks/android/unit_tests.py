#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The Android gate's unit-test pass, with the module list ratcheted so it cannot fall behind.

The task list is explicit rather than derived: a derived list hides what runs, and :core:presentation
once ran in no gate at all because it was simply never named. The ratchet below fails when a module has
a src/test and the list does not name it.
"""
import subprocess
from pathlib import Path

NAME = "Android unit tests"
TASKS = (
    ":app:testDebugUnitTest",
    ":core:backup:test",
    ":core:common:testDebugUnitTest",
    ":core:database:testDebugUnitTest",
    ":core:database-model:test",
    ":core:database-jvm:test",
    ":core:domain:test",
    ":core:presentation:test",
    ":core:scoring:test",
    ":core:shared:test",
    ":core:strings:test",
)
MODULES = (
    "app", "core/backup", "core/common", "core/database", "core/database-jvm", "core/domain",
    "core/presentation", "core/scoring", "core/shared", "core/strings",
)


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    def module_of(task: str) -> str:
        parts = task.strip(":").split(":")
        return "/".join(parts[:-1]) if len(parts) > 1 else parts[0]

    named = {module_of(task) for task in TASKS}
    missing = [
        module for module in MODULES
        if module not in named and (root / module / "src/test").is_dir()
    ]
    if missing:
        return False, "modules with src/test that the task list does not name: " + ", ".join(missing)
    result = subprocess.run(["./gradlew", *TASKS], cwd=root, env=ctx.env, capture_output=True, text=True)
    if result.returncode != 0:
        tail = (result.stdout + result.stderr).strip().splitlines()
        return False, tail[-1] if tail else f"gradle exited {result.returncode}"
    return True, f"{len(TASKS)} test task(s) passed"
