#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The critical init, import, auth and task/time paths still emit their structured log markers.

These are the paths a field report is diagnosed from, so losing a marker is losing the evidence, not
just a line of output. The list is explicit and moves with a ViewModel when it is promoted between
modules (the unlock ViewModel moved to :core:presentation, and this path followed it in that change).
"""
import re
from pathlib import Path

NAME = "Critical logging coverage"
CONTRACT = {
    "core/presentation/src/main/kotlin/io/payanam/presentation/database/DatabaseInitViewModel.kt": (
        r"DatabaseInitViewModel\.checkDatabaseStatus",
        r"DatabaseInitViewModel\.executeImportDatabase",
        r"DatabaseInitViewModel\.resumeImportWithPassphrase",
        r"import_reentry_rejected",
    ),
    "app/src/main/kotlin/io/payanam/feature/settings/SettingsEncryptedImportSupport.kt": (
        r"SettingsViewModel\.importDatabase",
        r"SettingsViewModel\.resumeImportWithPassphrase",
        r"SettingsViewModel\.cancelImportPassphrase",
        r"settings_import_reentry_rejected",
    ),
    "core/presentation/src/main/kotlin/io/payanam/presentation/database/DatabasePassphraseUnlockViewModel.kt": (
        r"DatabasePassphraseUnlockViewModel\.unlock",
        r"DatabasePassphraseUnlockViewModel\.startBiometricUnlock",
        r"DatabasePassphraseUnlockViewModel\.forgotPassphraseReset",
    ),
    "core/database/src/main/kotlin/io/payanam/database/security/DatabaseEncryptionManager.kt": (
        r"DatabaseEncryptionManager\.configurePassphrase",
        r"DatabaseEncryptionManager\.verifyPassphrase",
        r"DatabaseEncryptionManager\.disableBiometricUnlock",
    ),
    "core/database/src/main/kotlin/io/payanam/database/security/DatabaseArtifactJanitor.kt": (
        r"DatabaseArtifactJanitor\.",
        r"recoverFromTempBackupIfPrimaryMissing",
        r"pruneCorruptSnapshots",
    ),
    "core/database/src/main/kotlin/io/payanam/database/repository/TaskRepositoryImpl.kt": (
        r"TaskRepositoryImpl\.createTask",
        r"TaskRepositoryImpl\.updateTask",
        r"TaskRepositoryImpl\.markDirtyForTaskDay",
    ),
    "core/database/src/main/kotlin/io/payanam/database/repository/TimeEntryRepositoryImpl.kt": (
        r"TimeEntryRepositoryImpl\.createTimeEntry",
        r"TimeEntryRepositoryImpl\.updateTimeEntry",
        r"TimeEntryRepositoryImpl\.markDirtyForDay",
    ),
}


def run(ctx) -> tuple[bool, str]:
    root = Path(ctx.root)
    violations: list[str] = []
    for relative, patterns in CONTRACT.items():
        path = root / relative
        if not path.is_file():
            violations.append(f"missing file: {relative}")
            continue
        content = path.read_text(encoding="utf-8")
        for pattern in patterns:
            if not re.search(pattern, content):
                violations.append(f"missing logging marker '{pattern}' in {relative}")
    if violations:
        return False, f"{len(violations)} issue(s): " + " | ".join(violations[:6])
    return True, f"{len(CONTRACT)} file(s), {sum(len(v) for v in CONTRACT.values())} marker(s) present"
