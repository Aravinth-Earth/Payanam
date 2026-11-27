#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Every file added under desktop/src/main ships with a test or a stated reason."""
from pathlib import Path

from checks.common._added_source import run as ratchet

NAME = "Added-source test ratchet (desktop)"
ENV_VAR = "PAYANAM_DESKTOP_TEST_BASELINE"


def run(ctx) -> tuple[bool, str]:
    return ratchet(Path(ctx.root), ctx.env, ENV_VAR, "desktop/src/main", "desktop/src/test", "build-tools/preflight/checks/desktop/added_source.py")
