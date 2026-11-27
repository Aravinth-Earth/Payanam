#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Both platform entry points install the real logger.

`core/domain` and `core/scoring` log through AppLogger, which defaults to a SILENT no-op. A missing
install cannot be caught by a test - a no-op logger is exactly what a test expects - so every
diagnostic from those modules would disappear with no error anywhere.
"""
import re
from pathlib import Path

NAME = "AppLogger install contract"
ENTRY_POINTS = (
    "app/src/main/kotlin/io/payanam/PayanamApp.kt",
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopMain.kt",
)
INSTALL = re.compile(r"^(?!\s*//).*AppLogger\.install\(", re.MULTILINE)


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    missing: list[str] = []
    for relative in ENTRY_POINTS:
        path = root / relative
        if not path.is_file():
            return False, f"entry point missing: {relative}"
        if not INSTALL.search(path.read_text(encoding="utf-8")):
            missing.append(relative)
    if missing:
        return False, (
            "no AppLogger.install(...) call in: " + ", ".join(missing)
            + " - without it AppLogger.current stays the no-op and every core/domain and core/scoring log entry is discarded"
        )
    return True, f"{len(ENTRY_POINTS)} entry point(s) install the logger"
