#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The Gradle wrapper is present, and executable where the filesystem carries that bit."""
import os
import stat
from pathlib import Path

NAME = "Gradle wrapper present"


def run(ctx) -> tuple[bool, str]:
    wrapper = Path(ctx.root) / "gradlew"
    if not wrapper.is_file():
        return False, f"missing {wrapper}"
    mode = wrapper.stat().st_mode
    if os.name == "posix" and not mode & stat.S_IXUSR:
        wrapper.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        return True, "present (made executable)"
    return True, "present"
