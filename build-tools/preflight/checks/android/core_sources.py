#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The four sources whose absence means this is not the tree the gate thinks it is.

A truncated checkout or a wrong working directory otherwise fails much later, inside Gradle, with an
error that points at a build script instead of at the missing file.
"""
from pathlib import Path

NAME = "Core sources present"
REQUIRED = (
    "app/src/main/kotlin/io/payanam/PayanamApp.kt",
    "app/src/main/kotlin/io/payanam/MainActivity.kt",
    "core/domain/src/main/kotlin/io/payanam/domain/model/Task.kt",
    "core/database/src/main/kotlin/io/payanam/database/PayanamDatabase.kt",
)


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    missing = [path for path in REQUIRED if not (root / path).exists()]
    if missing:
        return False, "missing: " + ", ".join(missing)
    return True, f"{len(REQUIRED)} required source(s) present"
