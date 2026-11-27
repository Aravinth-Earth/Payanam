#!/usr/bin/env python3
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The build orchestrator: common checks once, then each platform's checks and its build.

    python3 build-tools/preflight/build.py android [--release]
    python3 build-tools/preflight/build.py desktop [--release]
    python3 build-tools/preflight/build.py all     [--release]

Ordering is the contract: `all` runs the common layer once, then (android checks -> android build),
then (desktop checks -> desktop build). A single-platform command runs common plus that platform only.
A failing step stops the whole run with its own exit code - the layers already print one line per step.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import run as preflight  # noqa: E402

REPO = HERE.parent.parent


def _run_layer(layer: str, phase: str, root: Path, env: dict[str, str]) -> int:
    print(f"\n== preflight: {layer} ({phase}) ==")
    ctx = preflight.Context(root=root, env=dict(env))
    names = [
        name for name in preflight.LAYER_ORDER[layer]
        if getattr(preflight.load(layer, name), "PHASE", preflight.DEFAULT_PHASE) == phase
    ]
    total = len(names)
    for index, name in enumerate(names, start=1):
        module = preflight.load(layer, name)
        ok, detail = module.run(ctx)
        print(f"[{index}/{total}] {getattr(module, 'NAME', name)} ... {'OK' if ok else 'FAIL'}{' - ' + detail if detail else ''}")
        if not ok:
            print(f"preflight[{layer}]: stopped at {getattr(module, 'NAME', name)}", file=sys.stderr)
            return 1
    env.update(ctx.env)
    return 0


def _gradle(root: Path, env: dict[str, str], *tasks: str) -> int:
    print(f"\n== gradle: {' '.join(tasks)} ==")
    result = subprocess.run(["./gradlew", *tasks], cwd=root, env=env)
    return result.returncode


def _version_and_sha(root: Path) -> tuple[str, str]:
    text = (root / "gradle/version.properties").read_text(encoding="utf-8")
    version = next(line.split("=", 1)[1].strip() for line in text.splitlines() if line.startswith("version="))
    sha = subprocess.run(["git", "rev-parse", "--short=8", "HEAD"], cwd=root, capture_output=True, text=True).stdout.strip() or "nogit"
    return version, sha


def _stamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def _publish(root: Path, artifact: Path, platform: str, build_type: str, subdir: str) -> Path:
    version, sha = _version_and_sha(root)
    target_dir = root / "output" / subdir
    target_dir.mkdir(parents=True, exist_ok=True)
    name = f"Payanam_{platform}_{version}_{sha}_{build_type}_{_stamp()}{artifact.suffix}"
    target = target_dir / name
    shutil.copy2(artifact, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_name(target.name + ".sha256").write_text(f"{digest}  {target.name}\n", encoding="utf-8")
    print(f"  published {target.relative_to(root)} ({target.stat().st_size // (1024 * 1024)} MB)")
    return target


def _inject_startup_wm_class(root: Path, deb: Path) -> None:
    """GNOME matches a window to its launcher by StartupWMClass, which jpackage never writes.

    Derived from `mainClass` in desktop/build.gradle.kts with dots replaced by dashes, so renaming the
    main class keeps the dock correct instead of silently regressing it.
    """
    if not shutil.which("dpkg-deb"):
        return
    source = (root / "desktop/build.gradle.kts").read_text(encoding="utf-8")
    import re

    match = re.search(r'mainClass\s*=\s*"([^"]+)"', source)
    if not match:
        return
    wm_class = match.group(1).replace(".", "-")
    with tempfile.TemporaryDirectory() as scratch:
        unpacked = Path(scratch) / "pkg"
        if subprocess.run(["dpkg-deb", "-R", str(deb), str(unpacked)], capture_output=True).returncode != 0:
            print("  could not unpack the .deb to add StartupWMClass", file=sys.stderr)
            return
        entries = [p for p in unpacked.rglob("*.desktop") if "legal" not in p.parts]
        if not entries:
            print("  no .desktop entry found; the dock icon will not match", file=sys.stderr)
            return
        entry = entries[0]
        text = entry.read_text(encoding="utf-8")
        if "StartupWMClass=" in text:
            print(f"  StartupWMClass already present")
            return
        entry.write_text(text.rstrip("\n") + f"\nStartupWMClass={wm_class}\n", encoding="utf-8")
        manifest = unpacked / "DEBIAN/md5sums"
        if manifest.parent.is_dir():
            lines = []
            for path in sorted(p for p in unpacked.rglob("*") if p.is_file() and "DEBIAN" not in p.parts):
                digest = hashlib.md5(path.read_bytes()).hexdigest()
                lines.append(f"{digest}  {path.relative_to(unpacked).as_posix()}")
            manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
        patched = Path(scratch) / "patched.deb"
        if subprocess.run(["dpkg-deb", "-b", str(unpacked), str(patched)], capture_output=True).returncode == 0:
            shutil.copy2(patched, deb)
            print(f"  StartupWMClass={wm_class} added to the launcher entry")


def _retain(directory: Path, pattern: str, keep: int = 5) -> None:
    if not directory.is_dir():
        return
    artifacts = sorted(directory.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    for stale in artifacts[keep:]:
        for sidecar in (stale, stale.with_name(stale.name + ".sha256")):
            sidecar.unlink(missing_ok=True)


def android(root: Path, env: dict[str, str], build_type: str) -> int:
    if _run_layer("android", "pre", root, env):
        return 1
    # The footprint guard refuses an APK patched in place, and an incremental build reuses the previous
    # archive; removing the output directory is what makes the next assemble write it fresh (the old
    # script did the same before assembling).
    shutil.rmtree(root / "app/build/outputs/apk", ignore_errors=True)
    task = "assembleRelease" if build_type == "release" else "assembleDebug"
    code = _gradle(root, env, task)
    if code:
        return code
    apks = sorted((root / "app/build/outputs/apk").rglob("*.apk"), key=lambda p: p.stat().st_mtime)
    if not apks:
        print("  no APK produced", file=sys.stderr)
        return 1
    published = _publish(root, apks[-1], "android", build_type, "apks")
    env["PAYANAM_APK"] = str(published)
    code = _run_layer("android", "post", root, env)
    _retain(published.parent, "*.apk")
    return code


def desktop(root: Path, env: dict[str, str], build_type: str) -> int:
    if _run_layer("desktop", "pre", root, env):
        return 1
    if sys.platform == "win32":
        code = _gradle(root, env, "packageMsi", "packageExe")
    else:
        code = _gradle(root, env, "packageDeb")
    if code:
        return code
    if sys.platform == "win32":
        pairs = sorted((root / "desktop/build/compose/binaries/main").rglob("*.exe")) + sorted(
            (root / "desktop/build/compose/binaries/main").rglob("*.msi")
        )
        if not pairs:
            print("  no exe/msi produced", file=sys.stderr)
            return 1
        for artifact in pairs:
            _publish(root, artifact, "windows", build_type, "windows")
        return _run_layer("desktop", "post", root, env)
    debs = sorted((root / "desktop/build/compose/binaries/main/deb").glob("*.deb"))
    if not debs:
        print("  no .deb produced", file=sys.stderr)
        return 1
    produced = debs[-1]
    _inject_startup_wm_class(root, produced)
    published = _publish(root, produced, "linux", build_type, "debs")
    env["PAYANAM_DEB"] = str(published)
    code = _run_layer("desktop", "post", root, env)
    _retain(published.parent, "*.deb")
    return code


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Build one platform, or all of them, through the preflight.")
    parser.add_argument("target", choices=("android", "desktop", "all"))
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--root", default=str(REPO))
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    build_type = "release" if args.release else "debug"
    env = dict(os.environ)

    if _run_layer("common", "pre", root, env):
        return 1
    if args.target in ("android", "all"):
        code = android(root, env, build_type)
        if code:
            return code
    if args.target in ("desktop", "all"):
        return desktop(root, env, build_type)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
