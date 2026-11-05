#!/usr/bin/env python3
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""Store-listing asset guard for Payanam.

WHY THIS EXISTS. A tracked file with git mode 120000 is a symlink. With `core.symlinks=false`
(the Windows default) that entry checks out as a small TEXT file holding the target path, so a
store listing built from that checkout ships the path text where a PNG or JPG was expected.
The committed git mode is the root cause, and `git ls-files -s` reports it identically on every
OS, so the check is the same everywhere.

Every tracked file under fastlane/ must be mode 100644, and every tracked .png / .jpg / .jpeg
must carry its format signature on disk - a working tree that already received path text is
caught by the signature, not only by the mode.

The tracked-mode check REQUIRES a git checkout: a source archive has no index to read. When
`git rev-parse --is-inside-work-tree` fails, or git itself is unavailable, the script prints one
skip line and exits 0 instead of failing the build. The image signature check still applies
whenever a checkout exists, and its violations are still reported.

USAGE
    python3 build-tools/scripts/check-store-listing-assets.py [--repo-root PATH]
    python3 build-tools/scripts/check-store-listing-assets.py --self-test

Prints one line per violation and nothing on success.
Exit codes: 0 clean or skipped (no git checkout), 1 violations, 2 git ls-files failed / repo
root unusable. Paths are read NUL-separated (`git ls-files -s -z`), so a path holding spaces or
non-ASCII bytes stays one record.

Pure Python 3 standard library. Output is ASCII only: this runs on a Windows console that
raises UnicodeEncodeError on other characters.
"""

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

EXPECTED_MODE = "100644"
LISTING_DIR = "fastlane"
SIGNATURES = {
    ".png": b"\x89PNG\r\n\x1a\n",
    ".jpg": b"\xff\xd8\xff",
    ".jpeg": b"\xff\xd8\xff",
}


def emit(message):
    """Print one ASCII line; anything outside ASCII becomes a backslash escape."""
    print(message.encode("ascii", "backslashreplace").decode("ascii"))


def parse_entries(stdout):
    """Parse `git ls-files -s -z` output into (mode, path) tuples, keeping the listed order.

    Records are NUL-separated; a record is `<mode> <sha> <stage>\\t<path>`. Anything else means
    git printed something the guard does not understand, which is reported as an error rather
    than read as a pass.
    """
    entries = []
    for record in stdout.split("\0"):
        if not record.strip():
            continue
        meta, separator, path = record.partition("\t")
        fields = meta.split()
        if not separator or len(fields) != 3:
            raise ValueError("unexpected git ls-files record: {}".format(record))
        entries.append((fields[0], path))
    return entries


def find_problems(entries, repo_root):
    """Return one problem line per violation in the given (mode, path) entries.

    A mode violation is reported on its own: the content read would go through the symlink and
    say nothing about what a Windows checkout receives.
    """
    problems = []
    root = Path(repo_root)
    for mode, path in entries:
        if mode != EXPECTED_MODE:
            problems.append(
                "{}: tracked with mode {} (expected {})".format(path, mode, EXPECTED_MODE)
            )
            continue
        signature = SIGNATURES.get(Path(path).suffix.lower())
        if signature is None:
            continue
        resolved = root / path
        try:
            with resolved.open("rb") as handle:
                head = handle.read(8)
        except OSError as exc:
            problems.append(
                "{}: cannot read image data ({})".format(path, exc.strerror or exc.__class__.__name__)
            )
            continue
        if not head.startswith(signature):
            problems.append(
                "{}: content does not start with the {} signature (found {})".format(
                    path, Path(path).suffix.lower().lstrip(".").upper(), head.hex() or "empty"
                )
            )
    return problems


def self_test():
    """Prove both branches with fixtures in a temp directory; the real repo is never touched."""
    failures = []
    with tempfile.TemporaryDirectory() as temp_dir:
        root = Path(temp_dir)
        (root / "icon.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00" * 8)
        (root / "path-text.png").write_bytes(b"fastlane/metadata/android/en-US/images/icon.png\n")
        cases = (
            ("100644 png with real bytes", find_problems([("100644", "icon.png")], root), 0),
            ("120000 symlink entry", find_problems([("120000", "icon.png")], root), 1),
            ("100644 png holding plain text", find_problems([("100644", "path-text.png")], root), 1),
            ("100755 executable entry", find_problems([("100755", "icon.png")], root), 1),
        )
        for label, problems, expected in cases:
            if len(problems) != expected:
                failures.append(
                    "{}: expected {} problem(s), got {}".format(label, expected, len(problems))
                )
        # Fifth assertion: a NUL-separated record whose path is non-ASCII must survive parsing and
        # reach the signature check.
        (root / "caf\u00e9.png").write_bytes(b"plain text, not a png\n")
        nul_record = "100644 {} 0\tcaf\u00e9.png\0".format("0" * 40)
        try:
            nul_problems = find_problems(parse_entries(nul_record), root)
        except ValueError as exc:
            nul_problems = None
            failures.append("NUL-separated non-ASCII path: parse failed ({})".format(exc))
        if nul_problems is not None and len(nul_problems) != 1:
            failures.append(
                "NUL-separated non-ASCII path: expected 1 problem(s), got {}".format(
                    len(nul_problems)
                )
            )
    if failures:
        for failure in failures:
            emit("SELF-TEST FAILED: {}".format(failure))
        return 1
    emit("self-test passed: 5 assertions")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Fail when a tracked fastlane/ asset is not a regular 100644 image file."
    )
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parents[2]),
        help="repository root (default: derived from this script's location)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run the fixture assertions and exit without touching the real repo",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    root = Path(args.repo_root)
    if not root.is_dir():
        emit("store-listing guard: repo root is unusable: {}".format(root))
        return 2
    # No work tree means no index, so there are no tracked modes to check: skip, never fail.
    try:
        probe = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--is-inside-work-tree"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except OSError:
        emit("store-listing guard: not a git checkout - skipped")
        return 0
    if probe.returncode != 0:
        emit("store-listing guard: not a git checkout - skipped")
        return 0
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-s", "-z", "--", LISTING_DIR],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except OSError:
        emit("store-listing guard: git is not available on PATH")
        return 2
    if result.returncode != 0:
        emit(
            "store-listing guard: git ls-files failed: {}".format(
                result.stderr.decode("utf-8", "replace").strip()
            )
        )
        return 2
    try:
        entries = parse_entries(result.stdout.decode("utf-8", "replace"))
    except ValueError as exc:
        emit("store-listing guard: {}".format(exc))
        return 2

    problems = find_problems(entries, root)
    for problem in problems:
        emit("store-listing guard: {}".format(problem))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
