#!/usr/bin/env python3
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Run only the checks that the changed files can affect.

WHY THIS EXISTS. The full platform builds compile every module, run the whole test
suite and package an installer. That is the right gate before a pull request and the
wrong tool for the edit loop, where a one-line change pays minutes of work it cannot
affect. This script maps the changed paths to the modules that own them and runs only
those modules' tests and static analysis.

WHAT IT IS NOT. It is not a substitute for the platform build. It cannot see a break
in a module that did not change, and it never proves that an installer packages. Run
the platform build before a branch is proposed for review.

USAGE
    python3 build-tools/scripts/check-changed.py
    python3 build-tools/scripts/check-changed.py --base dev
    python3 build-tools/scripts/check-changed.py --files app/src/main/kotlin/Foo.kt

Exit codes: 0 checks passed, 1 a check failed, 2 nothing to check or bad input.
"""

import argparse
import subprocess
import sys
from pathlib import Path

# Test and static-analysis tasks per Gradle module. A module appears here only when
# its tests actually run on the JVM: an Android library's unit tests use the Debug
# variant, and the Compose multiplatform module runs its desktop test set.
MODULES = {
    "app": (":app:testDebugUnitTest", [":app:detekt"]),
    "desktop": (":desktop:test", [":desktop:detekt"]),
    "core:shared": (":core:shared:test", [":core:shared:detekt"]),
    "core:domain": (":core:domain:test", [":core:domain:detekt"]),
    "core:scoring": (":core:scoring:test", [":core:scoring:detekt"]),
    "core:presentation": (":core:presentation:test", [":core:presentation:detekt"]),
    "core:strings": (":core:strings:test", [":core:strings:detekt"]),
    "core:backup": (":core:backup:test", [":core:backup:detekt"]),
    "core:database-jvm": (":core:database-jvm:test", [":core:database-jvm:detekt"]),
    "core:database-model": (":core:database-model:test", [":core:database-model:detekt"]),
    "core:common": (":core:common:testDebugUnitTest", [":core:common:detekt"]),
    "core:database": (":core:database:testDebugUnitTest", [":core:database:detekt"]),
    "core:ui": (
        ":core:ui:desktopTest",
        [":core:ui:detektAndroidMain", ":core:ui:detektDesktopMain", ":core:ui:detektMetadataCommonMain"],
    ),
}

# A change here can alter how every module compiles or what it renders, so the whole
# set is selected rather than one module.
SHARED_PATHS = (
    "gradle/",
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
)


def changed_files(base):
    if base:
        command = ["git", "diff", "--name-only", f"{base}...HEAD"]
    else:
        command = ["git", "diff", "--name-only", "HEAD"]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    files = [line for line in result.stdout.splitlines() if line.strip()]
    if not files:
        # Untracked files are not in the diff but are still a change under test.
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            capture_output=True,
            text=True,
            check=True,
        )
        files = [line for line in untracked.stdout.splitlines() if line.strip()]
    return files


def modules_for(files):
    selected = set()
    for path in files:
        if any(path.startswith(prefix) for prefix in SHARED_PATHS):
            return set(MODULES), True
        if path.startswith("app/"):
            selected.add("app")
        elif path.startswith("desktop/"):
            selected.add("desktop")
        elif path.startswith("core/"):
            parts = path.split("/")
            if len(parts) > 1:
                candidate = f"core:{parts[1]}"
                if candidate in MODULES:
                    selected.add(candidate)
    return selected, False


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run only the checks affected by the changed files.")
    parser.add_argument("--base", help="diff against this ref instead of the working tree")
    parser.add_argument("--files", nargs="+", help="explicit paths, skipping git diff")
    args = parser.parse_args(argv)

    root = Path(__file__).resolve().parents[2]
    files = args.files if args.files else changed_files(args.base)
    if not files:
        print("check-changed: nothing changed")
        return 0

    modules, shared = modules_for(files)
    if not modules:
        print(f"check-changed: {len(files)} file(s) changed, none map to a module (docs, metadata, tooling)")
        return 0

    tasks = []
    for module in sorted(modules):
        test_task, detekt_tasks = MODULES[module]
        tasks.append(test_task)
        tasks.extend(detekt_tasks)

    reason = "shared build surface changed" if shared else f"{len(modules)} affected module(s)"
    print(f"check-changed: {len(files)} file(s) changed -> {reason}")
    for module in sorted(modules):
        print(f"  {module}")

    print("\nRunning:", " ".join(tasks))
    result = subprocess.run(["./gradlew", *tasks, "--console=plain"], cwd=root)
    return 0 if result.returncode == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
