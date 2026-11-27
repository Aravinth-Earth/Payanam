#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Every file added under app/src/main ships with a test or a stated reason."""
from pathlib import Path

from checks.common._added_source import run as ratchet

NAME = "Added-source test ratchet (app)"
ENV_VAR = "PAYANAM_APP_TEST_BASELINE"


def run(ctx) -> tuple[bool, str]:
    return ratchet(Path(ctx.root), ctx.env, ENV_VAR, "app/src/main", "app/src/test", "build-tools/preflight/checks/android/added_source.py")
