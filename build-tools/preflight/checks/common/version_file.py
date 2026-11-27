#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""`gradle/version.properties` is the only version source, and it keeps the literal keys tools grep.

F-Droid greps `versionCode=<digits>` and `version=<x.y.z>` as text, so this checks the file the way
those consumers read it rather than parsing it loosely.
"""
import re
from pathlib import Path

NAME = "Declared version"
VERSION = re.compile(r"^version=\d+(\.\d+){0,2}$", re.MULTILINE)
VERSION_CODE = re.compile(r"^versionCode=\d+$", re.MULTILINE)


def run(ctx) -> tuple[bool, str]:
    path = Path(ctx.root) / "gradle/version.properties"
    if not path.is_file():
        return False, f"missing {path}"
    text = path.read_text(encoding="utf-8")
    version = re.search(r"^version=(.+)$", text, re.MULTILINE)
    code = re.search(r"^versionCode=(.+)$", text, re.MULTILINE)
    if not version or not VERSION.search(text):
        return False, "version= is missing or not <major>[.<minor>[.<patch>]]"
    if not code or not VERSION_CODE.search(text):
        return False, "versionCode= is missing or not digits (F-Droid greps it as text)"
    ctx.env["PAYANAM_VERSION"] = version.group(1).strip()
    return True, f"version {version.group(1).strip()} (code {code.group(1).strip()})"
