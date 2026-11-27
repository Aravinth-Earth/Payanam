#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Every module with a test source set is named by some gate.

A module can gain `src/test` and still run in no gate at all - that is how :core:presentation's suite
once slept. The list below is what this layer runs; the ratchet fails when the tree grows a module it
does not name, so the list cannot silently fall behind.

Only modules this layer actually gates are counted. Modules whose tests need the Android plugin
(`core/common`, `core/database`) belong to the android layer and are excluded here by name, not by
guesswork.
"""
from pathlib import Path

NAME = "Unclaimed-test module ratchet"
NAMED_MODULES = (
    "desktop",
    "core/backup",
    "core/database-jvm",
    "core/database-model",
    "core/domain",
    "core/presentation",
    "core/scoring",
    "core/shared",
    "core/strings",
)
ANDROID_ONLY_MODULES = ("core/common", "core/database")


def modules_with_tests(root: Path) -> list[str]:
    found: list[str] = []
    for parent in (root / "core", root / "desktop"):
        for candidate in sorted(parent.iterdir()) if parent.is_dir() else []:
            if (candidate / "src/test").is_dir():
                found.append(candidate.relative_to(root).as_posix())
    return found


def run(ctx) -> tuple[bool, str]:
    named = set(NAMED_MODULES)
    missing = [
        module
        for module in modules_with_tests(Path(ctx.root))
        if module not in ANDROID_ONLY_MODULES and module not in named
    ]
    if missing:
        return False, (
            "these modules have a src/test but are named by no step: "
            + ", ".join(missing)
            + " - add each to module_ratchet.NAMED_MODULES or its tests run in no gate"
        )
    return True, f"{len(named)} module(s) named, every module with src/test covered"
