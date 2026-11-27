#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The package a build produced is complete: the .deb, its checksum, and a launcher that names the app.

Runs after packaging, in the post phase. A .deb without its .sha256 is an artefact nobody can verify,
and a launcher entry without StartupWMClass makes the dock icon a generic one - both shipped silently
before this check existed.
"""
import hashlib
import shutil
import subprocess
import tempfile
from pathlib import Path

NAME = "Desktop package"
PHASE = "post"


def _newest(directory: Path, pattern: str) -> Path | None:
    candidates = sorted(directory.glob(pattern)) if directory.is_dir() else []
    return max(candidates, key=lambda path: path.stat().st_mtime) if candidates else None


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _launcher_names_the_app(deb: Path) -> tuple[bool, str]:
    if not shutil.which("dpkg-deb"):
        return True, "dpkg-deb unavailable; launcher check skipped"
    with tempfile.TemporaryDirectory() as scratch:
        extracted = Path(scratch)
        result = subprocess.run(["dpkg-deb", "-x", str(deb), str(extracted)], capture_output=True, text=True)
        if result.returncode != 0:
            return False, f"could not unpack {deb.name}"
        entries = list(extracted.rglob("*.desktop"))
        if not entries:
            return False, f"{deb.name} ships no .desktop launcher"
        for entry in entries:
            if "StartupWMClass=" in entry.read_text(encoding="utf-8", errors="replace"):
                return True, f"{entry.name} carries StartupWMClass"
        return False, f"{entries[0].name} has no StartupWMClass (the dock shows a generic icon)"


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    deb = Path(ctx.env["PAYANAM_DEB"]) if ctx.env.get("PAYANAM_DEB") else _newest(root / "output/debs", "*.deb")
    if deb is None:
        deb = _newest(root / "desktop/build/compose/binaries/main/deb", "*.deb")
    if deb is None:
        return True, "no .deb built yet (post-build step skipped)"
    if not deb.is_file():
        return False, f"package not found: {deb}"

    checksum_file = deb.with_name(deb.name + ".sha256")
    if not checksum_file.is_file():
        return False, f"{deb.name} has no .sha256 beside it"
    recorded = checksum_file.read_text(encoding="utf-8").split()[0].strip().lower()
    actual = _sha256(deb)
    if recorded != actual:
        return False, f"{checksum_file.name} records {recorded[:12]}..., the file hashes to {actual[:12]}..."

    ok, detail = _launcher_names_the_app(deb)
    if not ok:
        return False, f"{deb.name}: {detail}"
    return True, f"{deb.name} ({deb.stat().st_size // (1024 * 1024)} MB), checksum verified, {detail}"
