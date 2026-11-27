#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The APK carries no orphaned bytes.

A patched-in-place APK keeps the bytes of the entries it replaced. They cost install size and are the
usual residue of an interrupted or non-reproducible build, so the gate refuses an archive holding more
than a page of unreferenced bytes. Runs after the build, so the layer executes it in the post phase.
"""
import struct
from pathlib import Path

NAME = "APK footprint"
PHASE = "post"
MAX_UNREFERENCED = 4096
EOCD_SIGNATURE = b"PK\x05\x06"
SIG_BLOCK_MAGIC = b"APK Sig Block 42"


def _eocd(blob: bytes) -> tuple[int, int, int, int]:
    start = max(0, len(blob) - (22 + 65535))
    index = blob.rfind(EOCD_SIGNATURE, start)
    if index < 0:
        raise ValueError("no end-of-central-directory record")
    entries = struct.unpack_from("<H", blob, index + 10)[0]
    cd_size = struct.unpack_from("<I", blob, index + 12)[0]
    cd_offset = struct.unpack_from("<I", blob, index + 16)[0]
    if entries == 0xFFFF or cd_size == 0xFFFFFFFF or cd_offset == 0xFFFFFFFF:
        raise ValueError("ZIP64 archive is not supported")
    return index, entries, cd_size, cd_offset


def _entries(blob: bytes, cd_offset: int) -> tuple[list[tuple[int, int, int]], int]:
    """(local header offset, footprint, name index) per central-directory entry."""
    found: list[tuple[int, int, int]] = []
    cursor = cd_offset
    declared_end = 0
    while cursor + 46 <= len(blob) and blob[cursor: cursor + 4] == b"PK\x01\x02":
        flags = struct.unpack_from("<H", blob, cursor + 8)[0]
        compressed = struct.unpack_from("<I", blob, cursor + 20)[0]
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", blob, cursor + 28)
        local_offset = struct.unpack_from("<I", blob, cursor + 42)[0]
        if local_offset + 30 > len(blob) or blob[local_offset: local_offset + 4] != b"PK\x03\x04":
            raise ValueError(f"entry at {local_offset} has no local file header")
        local_name, local_extra = struct.unpack_from("<HH", blob, local_offset + 26)
        footprint = 30 + local_name + local_extra + compressed
        found.append((local_offset, footprint, cursor))
        declared_end = max(declared_end, local_offset + footprint)
        cursor += 46 + name_len + extra_len + comment_len
    return found, declared_end


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    apk = Path(ctx.env["PAYANAM_APK"]) if ctx.env.get("PAYANAM_APK") else None
    if apk is None:
        candidates = sorted((root / "app/build/outputs/apk").rglob("*.apk"))
        if not candidates:
            return True, "no APK built yet (post-build step skipped)"
        apk = max(candidates, key=lambda path: path.stat().st_mtime)
    if not apk.is_file():
        return False, f"APK not found: {apk}"

    blob = apk.read_bytes()
    try:
        eocd_index, entries, cd_size, cd_offset = _eocd(blob)
        found, declared_end = _entries(blob, cd_offset)
        if len(found) != entries:
            raise ValueError(f"central directory declares {entries} entries, read {len(found)}")
        signing = 0
        if cd_offset >= 32 and blob[cd_offset - 16: cd_offset] == SIG_BLOCK_MAGIC:
            reported = struct.unpack_from("<Q", blob, cd_offset - 24)[0] + 8
            if reported <= cd_offset and cd_offset - reported >= declared_end:
                signing = reported
        eocd_size = 22 + struct.unpack_from("<H", blob, eocd_index + 20)[0]
        declared = sum(footprint for _, footprint, _ in found)
        unreferenced = len(blob) - declared - signing - cd_size - eocd_size
    except ValueError as error:
        return False, f"{apk.name}: {error}"
    if unreferenced > MAX_UNREFERENCED:
        return False, (
            f"{apk.name} holds {unreferenced} unreferenced bytes (limit {MAX_UNREFERENCED}); "
            "delete app/build/outputs/apk and rebuild so the APK is written fresh"
        )
    return True, f"{apk.name}: {len(found)} entries, {unreferenced} unreferenced bytes"
