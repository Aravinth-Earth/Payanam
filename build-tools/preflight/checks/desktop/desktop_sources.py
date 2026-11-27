#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The desktop entry points and build script exist."""
from pathlib import Path

NAME = "Desktop sources present"
REQUIRED = (
    "desktop/build.gradle.kts",
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopMain.kt",
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt",
)


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    missing = [path for path in REQUIRED if not (root / path).is_file()]
    if missing:
        return False, "missing: " + ", ".join(missing)
    return True, f"{len(REQUIRED)} required file(s) present"
