#!/usr/bin/env python3
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The build's preflight, one layer at a time.

Each check is its own module under `checks/<layer>/`, exposing NAME and run(ctx) -> (ok, detail).
This runner executes a layer's checks in a fixed, declared order and stops at the first failure, so a
log reads as a sequence of steps rather than one opaque verdict.

    python3 build-tools/preflight/run.py --layer common
    python3 build-tools/preflight/run.py --layer common --list
    python3 build-tools/preflight/run.py --layer common --only temp_capacity

Layers are `common`, `android` and `desktop`. The platform layers are added by the later slices of
#3402; a layer with no checks yet reports that rather than pretending to have verified something.
"""
from __future__ import annotations

import argparse
import importlib
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

# Declared order per layer: the sequence the gate runs in, never discovery order. A check that must
# happen before any Gradle invocation (the temp probe) is deliberately first.
LAYER_ORDER: dict[str, list[str]] = {
    "common": [
        "gradle_wrapper",
        "temp_capacity",
        "version_file",
        "store_assets",
        "module_ratchet",
        "string_parity",
        "formatting_ratchet",
        "db_auth_contract",
        "app_logger_contract",
        "logging_coverage",
    ],
    "android": [
        "jdk",
        "core_sources",
        "static_analysis",
        "added_source",
        "unit_tests",
        "apk_footprint",
    ],
    "desktop": [
        "desktop_sources",
        "added_source",
        "desktop_tests",
        "packaging",
    ],
}


# Each check may declare PHASE; the runner only executes the requested phase, so a post-build scanner
# (an APK's central directory, a package's launcher entry) never runs before there is a package.
DEFAULT_PHASE = "pre"


@dataclass
class Context:
    root: Path
    env: dict[str, str] = field(default_factory=lambda: dict(os.environ))


def load(layer: str, name: str):
    return importlib.import_module(f"checks.{layer}.{name}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Run one preflight layer.")
    parser.add_argument("--layer", required=False, choices=sorted(LAYER_ORDER), default=None)
    parser.add_argument("--root", default=".", help="repository root (default: current directory)")
    parser.add_argument("--list", action="store_true", help="print the layer's steps and exit")
    parser.add_argument("--only", action="append", default=[], help="run only these steps (repeatable)")
    parser.add_argument("--phase", choices=("pre", "post"), default="pre", help="which phase to run")
    args = parser.parse_args(argv)

    if args.list:
        for layer, names in LAYER_ORDER.items():
            print(f"{layer}: {', '.join(names) if names else '(no steps yet)'}")
        return 0
    if not args.layer:
        parser.error("--layer is required unless --list is given")

    ctx = Context(root=Path(args.root).resolve())
    names = LAYER_ORDER[args.layer]
    if args.only:
        unknown = [n for n in args.only if n not in names]
        if unknown:
            print(f"unknown step(s) for {args.layer}: {', '.join(unknown)}", file=sys.stderr)
            return 2
        names = [n for n in names if n in args.only]
    names = [n for n in names if getattr(load(args.layer, n), "PHASE", DEFAULT_PHASE) == args.phase]
    if not names:
        print(f"preflight[{args.layer}]: no {args.phase}-build steps")
        return 0

    total = len(names)
    for index, name in enumerate(names, start=1):
        module = load(args.layer, name)
        label = getattr(module, "NAME", name)
        ok, detail = module.run(ctx)
        verdict = "OK" if ok else "FAIL"
        suffix = f" - {detail}" if detail else ""
        print(f"[{index}/{total}] {label} ... {verdict}{suffix}")
        if not ok:
            print(f"preflight[{args.layer}]: stopped at {label}", file=sys.stderr)
            return 1
    print(f"preflight[{args.layer}]: all {total} step(s) passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
