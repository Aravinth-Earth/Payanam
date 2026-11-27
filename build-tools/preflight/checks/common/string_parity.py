#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The EN and TA string tables hold the same keys.

There is one authored table per language (`core/strings/.../values` and `values-ta`) and it is flat:
every entry is a single <string>. A key held by one language only is a screen that ships untranslated,
and the count difference is its visible symptom.
"""
import xml.etree.ElementTree as ET
from pathlib import Path

NAME = "String parity (EN/TA)"
PAIRS = (
    ("core/strings", "core/strings/src/main/res/values/strings.xml", "core/strings/src/main/res/values-ta/strings.xml"),
)


def _keys(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {element.get("name") for element in root.findall("string") if element.get("name")}


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    problems: list[str] = []
    for name, english, tamil in PAIRS:
        en_path, ta_path = root / english, root / tamil
        if not en_path.is_file():
            problems.append(f"{name}: EN table not found ({english})")
            continue
        if not ta_path.is_file():
            problems.append(f"{name}: TA table not found ({tamil})")
            continue
        en_keys, ta_keys = _keys(en_path), _keys(ta_path)
        en_only, ta_only = sorted(en_keys - ta_keys), sorted(ta_keys - en_keys)
        if en_only:
            problems.append(f"{name}: {len(en_only)} key(s) only in EN: {', '.join(en_only[:10])}")
        if ta_only:
            problems.append(f"{name}: {len(ta_only)} key(s) only in TA: {', '.join(ta_only[:10])}")
    if problems:
        return False, " | ".join(problems)
    return True, f"{len(PAIRS)} pair(s) in step"
