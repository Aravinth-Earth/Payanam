#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The added-source-without-test ratchet, shared by both platforms.

No Gradle task can enforce "a new file ships with a test or a stated reason": detekt and the test tasks
see the tree as a whole, and coverage has a floor rather than a per-change rule. So it is enforced with
git, in milliseconds, at the gate.

Baseline resolution, in order:
  1. the platform's env var (`PAYANAM_APP_TEST_BASELINE`, `PAYANAM_DESKTOP_TEST_BASELINE`) - any ref
  2. the commit that first added the marker string to this file - self-describing and immune to a
     hardcoded ref that later moves or is not fetched
Anything else (unset with no marker commit, a ref that no longer resolves, no git history) is a logged
no-op: it must never fail a build for a reason unrelated to the code being built.

A file is covered when <Basename>Test.kt exists anywhere under the platform's test root, or when the
file itself carries a `// no-test: <reason>` line.
"""
import re
import subprocess
from pathlib import Path

MARKER = re.compile(r"^\s*//\s*no-test:\s*\S", re.MULTILINE)


def _git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else ""


def run(root: Path, env: dict[str, str], env_var: str, main_root: str, test_root: str, marker_file: str) -> tuple[bool, str]:
    baseline = env.get(env_var, "").strip()
    source = "env"
    if not baseline:
        baseline = _git(root, "log", f"-S{env_var}", "--reverse", "--format=%H", "--", marker_file).splitlines()[:1]
        baseline = baseline[0] if baseline else ""
        source = "this check's first commit"
    if not baseline:
        return True, f"no baseline (no {env_var}, no marker commit yet) - skipped"
    if not _git(root, "rev-parse", "--verify", "-q", f"{baseline}^{{commit}}"):
        return True, f"baseline '{baseline}' does not resolve - skipped"

    added = set()
    for args in (
        ("diff", "--diff-filter=A", "--name-only", f"{baseline}..HEAD", "--", main_root),
        ("diff", "--cached", "--diff-filter=A", "--name-only", "--", main_root),
        ("ls-files", "--others", "--exclude-standard", "--", main_root),
    ):
        for line in _git(root, *args).splitlines():
            if line.strip().endswith(".kt"):
                added.add(line.strip())
    if not added:
        return True, f"no .kt added under {main_root} vs {baseline} - nothing to check"

    tests = {path.name for path in (root / test_root).rglob("*Test.kt")} if (root / test_root).is_dir() else set()
    uncovered = []
    for relative in sorted(added):
        stem = Path(relative).stem
        if f"{stem}Test.kt" in tests:
            continue
        path = root / relative
        if path.is_file() and MARKER.search(path.read_text(encoding="utf-8", errors="replace")):
            continue
        uncovered.append(relative)
    if uncovered:
        return False, (
            f"{len(uncovered)} added source(s) with no matching *Test.kt under {test_root}: "
            + ", ".join(uncovered[:8])
            + f" - add a test, or a '// no-test: <reason>' line; widen the range with {env_var}=<ref>"
        )
    return True, f"{len(added)} added file(s) vs {baseline} covered"
