#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The temp directory can hold the builders' scratch space.

Gradle, the Kotlin daemon, jpackage and dpkg-deb all stage large files in TMPDIR. On a host where that
is a small tmpfs, the build dies with "Disk quota exceeded" - an environment fault with no code defect
to fix. Probe it once, before any Gradle invocation, and move the run onto a larger filesystem when it
cannot hold the scratch space. A healthy machine sees one line and nothing changes.
"""
import os
import shutil
from pathlib import Path

NAME = "Temp directory capacity"
PROBE_BYTES = 64 * 1024 * 1024


def _probe(directory: Path) -> bool:
    probe = directory / f"payanam-tmp-probe.{os.getpid()}"
    try:
        remaining = PROBE_BYTES
        with open(probe, "wb") as handle:
            chunk = b"\0" * (1024 * 1024)
            while remaining > 0:
                take = min(remaining, len(chunk))
                handle.write(chunk[:take])
                remaining -= take
            handle.flush()
            os.fsync(handle.fileno())
        written = probe.stat().st_size
    except OSError:
        return False
    finally:
        probe.unlink(missing_ok=True)
    return written == PROBE_BYTES


def run(ctx) -> tuple[bool, str]:
    requested = os.environ.get("PAYANAM_BUILD_TMP")
    primary = Path(requested) if requested else Path(os.environ.get("TMPDIR", "/tmp"))
    if _probe(primary):
        return True, f"{primary} holds {PROBE_BYTES // (1024 * 1024)} MB"
    fallback = Path(requested or "/var/tmp/payanam-build-tmp")
    try:
        fallback.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        return False, (
            f"{primary} failed the 64 MB probe and {fallback} could not be created ({error}); "
            "set PAYANAM_BUILD_TMP to a writable directory on a larger filesystem"
        )
    if not _probe(fallback):
        return False, f"{primary} failed the probe and {fallback} cannot hold 64 MB either"
    ctx.env["TMPDIR"] = str(fallback)
    os.environ["TMPDIR"] = str(fallback)
    return True, f"moved to {fallback} (free {shutil.disk_usage(fallback).free // (1024 * 1024)} MB)"
