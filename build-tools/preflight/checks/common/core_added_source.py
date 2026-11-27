#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Every file added under a core module's main sources ships with a test or a stated reason.

The ratchet used to cover app/src/main and desktop/src/main only, so a new class in :core:domain or
:core:presentation could ship with no test and no gate would notice - the modules both platforms run on
were the least covered. Each module is paired against its OWN test roots, which is what makes the rule
mean something: a test in another module does not exercise this one.

Two modules are special and are modelled as such rather than skipped:

- `core/database` has both `src/test` and `src/androidTest`; a test in either one covers the file.
- `core/ui` is Kotlin Multiplatform: main sources live in `commonMain` / `androidMain` / `desktopMain`
  and tests in `commonTest` / `desktopTest` (there is no `src/test` at all).

Baseline: `PAYANAM_CORE_TEST_BASELINE` (any ref), else the commit that first added the marker to THIS
file, else a logged no-op - the same self-baselining the app and desktop ratchets use.
"""
from pathlib import Path

from checks.common._added_source import run as ratchet

NAME = "Added-source test ratchet (core modules)"
ENV_VAR = "PAYANAM_CORE_TEST_BASELINE"
MARKER_FILE = "build-tools/preflight/checks/common/core_added_source.py"

TARGETS: tuple[tuple[str, tuple[str, ...], tuple[str, ...]], ...] = (
    ("core/domain", ("core/domain/src/main",), ("core/domain/src/test",)),
    ("core/presentation", ("core/presentation/src/main",), ("core/presentation/src/test",)),
    ("core/scoring", ("core/scoring/src/main",), ("core/scoring/src/test",)),
    ("core/shared", ("core/shared/src/main",), ("core/shared/src/test",)),
    ("core/common", ("core/common/src/main",), ("core/common/src/test",)),
    ("core/backup", ("core/backup/src/main",), ("core/backup/src/test",)),
    ("core/strings", ("core/strings/src/main",), ("core/strings/src/test",)),
    (
        "core/database",
        ("core/database/src/main",),
        ("core/database/src/test", "core/database/src/androidTest"),
    ),
    ("core/database-jvm", ("core/database-jvm/src/main",), ("core/database-jvm/src/test",)),
    (
        "core/ui",
        ("core/ui/src/commonMain", "core/ui/src/androidMain", "core/ui/src/desktopMain"),
        ("core/ui/src/commonTest", "core/ui/src/desktopTest"),
    ),
)


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    checked = 0
    problems: list[str] = []
    for label, main_roots, test_roots in TARGETS:
        if not any((root / scope).is_dir() for scope in main_roots):
            continue
        checked += 1
        ok, detail = ratchet(root, ctx.env, ENV_VAR, main_roots, test_roots, MARKER_FILE)
        if not ok:
            problems.append(f"{label}: {detail}")
    if problems:
        return False, f"{len(problems)} module(s) failed: " + " | ".join(problems[:4])
    return True, f"{checked} module(s) checked"
