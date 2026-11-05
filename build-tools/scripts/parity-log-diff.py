#!/usr/bin/env python3
"""Cross-platform LOG parity check for Payanam (Android vs Linux desktop).

WHY THIS EXISTS. Functional parity is not only "the screen works on both platforms": for the SAME
interaction both apps must also emit the same STRUCTURED LOG MARKERS, so a behaviour that works on one
platform and silently fails on the other shows up as a missing success marker or an extra error line -
reproducible and traceable - instead of as a difference nobody looked for.

THE SHARED FORMAT. Both apps write the same session-log line shape
(`core/common/.../UnifiedLogger.kt` and `desktop/.../UnifiedLogger.kt`):

    timestamp | level | n | thread=<name> | source | message | [data]

An Android LOGCAT capture is also accepted, where the same marker reads:

    ... I Payanam : [source][n | thread=<name>] message | [data]

Only the marker `(level, source, message)` is compared - timestamps, sequence numbers and thread names
differ by construction and carry no parity meaning.

USAGE
    python3 build-tools/scripts/parity-log-diff.py --android <file> --desktop <file> \
        [--grep <source-substring>] [--max-print 40]

Exit code: 1 if any marker appears on one side only, or the WARN/ERROR counts differ; otherwise 0.
Pure Python 3 stdlib. This is a DIAGNOSTIC, not a gate on its own: it reports where the two logs disagree.
"""

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

# The shared session-log line: ts | level | seq | thread=.. | source | message | [data]
SESSION_RE = re.compile(
    r"^.+?\s\|\s(?P<level>[A-Z]+)\s*\|\s\d+\s\|\sthread=[^|]*\|\s(?P<source>[^|]+?)\s\|\s"
    r"(?P<message>.*?)(?:\s\|\s\[.*\])?\s*$"
)
# An Android logcat line: "... I Payanam : [source][seq | thread=..] message | [data]"
LOGCAT_RE = re.compile(
    r"\s(?P<level>[IWDVE])\s+Payanam\s*:\s*\[(?P<source>[^\]]+)\]\[[^\]]*\]\s*"
    r"(?P<message>.*?)(?:\s\|\s\[.*\])?\s*$"
)
LEVEL_NAMES = {"I": "INFO", "W": "WARN", "E": "ERROR", "D": "DEBUG", "V": "DEBUG"}
# DEBUG entries follow the BUILD, not the behaviour: an Android debug build logs them and a released
# desktop build does not (the in-app switch changes it at runtime). So they are excluded from the default
# comparison, which is about behaviour - INFO and above.
LEVEL_ORDER = {"DEBUG": 0, "INFO": 1, "WARN": 2, "ERROR": 3}


def parse(path):
    """Parse one log file into a list of (level, source, message) markers.

    Each line is tried against the shared session format first, then the logcat format; a line matching
    neither (a stack frame, a blank line) is skipped. The same marker logged twice is de-duplicated by the
    Counter at the call site.
    """
    markers = []
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        m = SESSION_RE.match(line)
        if m:
            markers.append((m.group("level").strip().upper(), m.group("source").strip(), m.group("message").strip()))
            continue
        m = LOGCAT_RE.search(line)
        if m:
            markers.append(
                (LEVEL_NAMES.get(m.group("level"), m.group("level")), m.group("source").strip(), m.group("message").strip())
            )
    return markers


def keys(markers, grep, min_level):
    """The comparable marker keys: scoped to sources containing [grep], at [min_level] and above."""
    floor = LEVEL_ORDER.get(min_level, 1)
    return Counter(
        (lvl, src, msg)
        for (lvl, src, msg) in markers
        if (grep is None or grep in src) and LEVEL_ORDER.get(lvl, 1) >= floor
    )


def describe(key):
    """One marker as `LEVEL source: message`."""
    lvl, src, msg = key
    return f"{lvl:<5} {src}: {msg}"


def main(argv=None):
    """CLI entry point; returns the process exit code."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--android", required=True, help="Android logcat capture OR exported session log")
    ap.add_argument("--desktop", required=True, help="desktop session log")
    ap.add_argument("--grep", default=None, help="only compare markers whose source contains this")
    ap.add_argument("--min-level", default="INFO", choices=["DEBUG", "INFO", "WARN", "ERROR"],
                    help="lowest level compared; DEBUG is build-dependent and excluded by default")
    ap.add_argument("--max-print", type=int, default=40, help="max markers to list per side")
    args = ap.parse_args(argv)

    a = keys(parse(args.android), args.grep, args.min_level)
    d = keys(parse(args.desktop), args.grep, args.min_level)
    only_a = sorted(set(a) - set(d))
    only_d = sorted(set(d) - set(a))

    print(f"markers: android={sum(a.values())} desktop={sum(d.values())}")
    for name, markers in (("android", only_a), ("desktop", only_d)):
        print(f"== only on {name}: {len(markers)} ==")
        for key in markers[: args.max_print]:
            print(f"  {describe(key)}")

    a_err = sum(c for (lvl, _, _), c in a.items() if lvl in ("WARN", "ERROR"))
    d_err = sum(c for (lvl, _, _), c in d.items() if lvl in ("WARN", "ERROR"))
    print(f"warn/error markers: android={a_err} desktop={d_err}")

    diverged = bool(only_a or only_d) or a_err != d_err
    print("DIVERGED" if diverged else "PARITY")
    return 1 if diverged else 0


if __name__ == "__main__":
    sys.exit(main())
