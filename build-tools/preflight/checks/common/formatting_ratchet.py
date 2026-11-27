#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Kotlin files CHANGED in this tree are formatted; pre-existing drift elsewhere is ignored.

Why a ratchet rather than plain spotlessCheck: the gate never ran spotless, so ~216 files are
unformatted today, and the only auto-fix (spotlessApply) deletes comments. Block NEW drift now and
leave the backlog to its own reviewed task.

Which files: the working tree vs HEAD (plus staged and untracked), so this is an edit-loop guard, not
a commit-time one. Spotless itself is still the judge - it is run read-only repo-wide, and its verdict
is intersected with the files this change touches, because a per-file invocation cannot see the module
backlog.
"""
import re
import subprocess
from pathlib import Path

NAME = "Formatting ratchet (changed files)"
BASE = "HEAD"


def changed_kotlin(root: Path) -> list[str]:
    commands = (
        ["git", "diff", "--name-only", BASE],
        ["git", "diff", "--name-only"],
        ["git", "diff", "--name-only", "--cached"],
        ["git", "ls-files", "--others", "--exclude-standard"],
    )
    found: set[str] = set()
    for command in commands:
        result = subprocess.run(command, cwd=root, capture_output=True, text=True)
        for line in result.stdout.splitlines():
            path = line.strip().replace("\\", "/")
            if path.endswith(".kt"):
                found.add(path)
    return sorted(found)


def violating_files(root: Path, env: dict[str, str]) -> set[str]:
    result = subprocess.run(
        ["./gradlew", "spotlessCheck", "--continue", "--console=plain"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
    )
    violating: set[str] = set()
    module: str | None = None
    for line in (result.stdout + result.stderr).splitlines():
        task = re.match(r"^> Task :(.+?):spotless", line)
        if task:
            module = task.group(1).replace(":", "/")
            continue
        file_line = re.match(r"^\s+(src/.*\.kt)\s*$", line)
        if module and file_line:
            violating.add(f"{module}/{file_line.group(1)}")
    return violating


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    changed = changed_kotlin(root)
    if not changed:
        return True, f"no .kt changed vs {BASE}"
    violating = violating_files(root, ctx.env)
    offenders = [path for path in changed if path in violating]
    if offenders:
        return False, (
            f"{len(offenders)} changed file(s) are unformatted: "
            + ", ".join(offenders[:10])
            + " - run './gradlew spotlessApply' per module and review the diff (apply can delete comments)"
        )
    suffix = f"; {len(violating)} pre-existing unformatted file(s) ignored" if violating else ""
    return True, f"{len(changed)} changed file(s) formatted{suffix}"
