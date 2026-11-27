#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""A JDK the Android plugin accepts: JAVA_HOME (or gradle.properties) if set, else PATH, and 17+."""
import os
import re
import subprocess
from pathlib import Path

NAME = "Java toolchain"
MIN_MAJOR = 17


def _java_home(root: Path) -> Path | None:
    configured = os.environ.get("JAVA_HOME")
    if configured and (Path(configured) / "bin/java").exists():
        return Path(configured)
    properties = root / "gradle.properties"
    if properties.is_file():
        match = re.search(r"^org\.gradle\.java\.home=(.+)$", properties.read_text(encoding="utf-8"), re.MULTILINE)
        if match:
            candidate = Path(match.group(1).strip())
            if (candidate / "bin/java").exists():
                return candidate
    return None


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    home = _java_home(root)
    java = str(home / "bin/java") if home else "java"
    try:
        result = subprocess.run([java, "-version"], capture_output=True, text=True)
    except FileNotFoundError:
        return False, "no Java found: set JAVA_HOME (or org.gradle.java.home in gradle.properties)"
    output = (result.stdout + result.stderr).strip().splitlines()
    first = output[0] if output else ""
    match = re.search(r'version "(\d+)', first)
    if not match:
        return False, f"could not read the version from: {first}"
    major = int(match.group(1))
    if major < MIN_MAJOR:
        return False, f"Java {major} found at {java}; the Android plugin needs {MIN_MAJOR}+"
    where = f"JAVA_HOME={home}" if home else "system default"
    ctx.env["JAVA_HOME"] = str(home) if home else ctx.env.get("JAVA_HOME", "")
    return True, f"Java {major} ({where})"
