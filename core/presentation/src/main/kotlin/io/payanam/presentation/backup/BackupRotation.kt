//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.backup

import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.UnifiedLogger
import java.io.File

/**
 * How many backup directories to keep, and whether to prune the rest at all.
 *
 * Shared by both platforms (Layer 3 ruling 4: common logic lives in a shared
 * module while each platform injects its own paths) so a desktop that clamped
 * or defaulted differently from Android would delete backups Android keeps.
 * The desktop delegates here from its own `BackupRotationPolicy` typealias.
 */
data class BackupRotationPolicy(
    val enabled: Boolean,
    val keepCount: Int = DEFAULT_KEEP_COUNT,
) {
    init {
        require(keepCount >= 1) { "keepCount must be at least 1, was $keepCount" }
    }

    companion object {
        /** Android's default row value for `backup_rotation_count`, when the row is absent. */
        const val DEFAULT_KEEP_COUNT = 50

        /**
         * The upper bound Android coerces a retention count into, beside its lower bound of 1.
         *
         * Named rather than inlined because the number is Android's rule, not a taste: a desktop that
         * clamped to a different ceiling would delete backups on a row value Android keeps, and one that
         * did not clamp at all would hand `0` to a constructor that rejects it - AFTER a backup succeeded.
         */
        const val MAX_KEEP_COUNT = 999
    }
}

/**
 * Reads the rotation policy from the shared `app_settings` rows, exactly as Android's coordinator
 * reads them from its own preferences: absent means disabled, the count falls back to the shared
 * default, and the count is clamped to Android's own 1..999 range BEFORE it can reach a constructor
 * that rejects a smaller one.
 *
 * The row NAMES come from [AppPreferenceKeys], so this cannot drift from the settings screen that
 * writes them or from the Android worker that reads the same two keys. Deriving the numbers here
 * rather than at each call site is what makes the scheduled path and the backup port read rotation
 * identically rather than twice, differently.
 *
 * A row that cannot be parsed is clamped, never thrown: rotation runs after a backup the user is
 * relying on, and a malformed retention row must not turn that backup into a failed one.
 */
fun rotationPolicyFromBackupRows(rows: Map<String, String?>): BackupRotationPolicy =
    BackupRotationPolicy(
        enabled = rows[AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED]?.toBoolean() ?: false,
        keepCount =
            rows[AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT]
                ?.toIntOrNull()
                ?.coerceIn(1, BackupRotationPolicy.MAX_KEEP_COUNT)
                ?: BackupRotationPolicy.DEFAULT_KEEP_COUNT,
    )

/**
 * Prunes the desktop's backup artifacts the way Android's coordinator does: the newest
 * [BackupRotationPolicy.keepCount] artifacts are kept and the rest are deleted, oldest first.
 *
 * Android reads the same two settings rows the desktop's settings screen writes; without this the
 * desktop wrote a rotation toggle no code path ever read, so the toggle did nothing. Only artifacts
 * are considered — [isArtifact] is the platform's matcher injected here — so anything else sitting
 * in the exports folder is never deleted.
 *
 * Returns the paths deleted, oldest first, so a caller can report what happened and a test can
 * assert exactly which artifacts went.
 */
fun pruneBackupArtifacts(
    backupDirectory: File,
    policy: BackupRotationPolicy,
    isArtifact: (File) -> Boolean,
    logger: UnifiedLogger,
): List<File> {
    if (!policy.enabled) return emptyList()
    val backupDirs =
        backupDirectory
            .listFiles { file -> isArtifact(file) }
            ?.toList()
            .orEmpty()
    if (backupDirs.size <= policy.keepCount) return emptyList()
    // TWO keys, and the second one is the fix. `sortedByDescending` is a STABLE sort, so artifacts
    // sharing an mtime keep the order `listFiles` returned them in - and java.io.File specifies no
    // order for that. `drop(keepCount)` then deletes the TAIL, so with tied mtimes the head it keeps
    // is an arbitrary artifact and the newest can be deleted: measured on ntfs3 (this repo's own
    // volume), where listFiles returns directory entries in creation order, restoring a batch of
    // backups written oldest-name-last deletes the newest one under keepCount = 1; on tmpfs the same
    // fixture happens to keep it, so the defect is invisible on one filesystem and real on another.
    //
    // The name breaks the tie because the artifact name IS the stamp: `scheduledBackupArtifactName`
    // builds `auto_bk_<yyyyMMdd-HHmmss>.db`, zero-padded and fixed-width, so descending name order is
    // descending chronological order among the artifacts this app writes. mtime stays the primary key,
    // so this can never promote an artifact over a genuinely newer one - it only makes the order of
    // two artifacts the filesystem cannot order TOTAL.
    val doomed =
        backupDirs
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenByDescending { it.name },
            ).drop(policy.keepCount)
    val deleted = mutableListOf<File>()
    doomed.forEach { candidate ->
        // A directory that cannot be removed is reported and skipped: one locked folder must not
        // stop the rest of the rotation, and must not fail the backup that triggered it.
        val removed = if (candidate.deleteRecursively()) candidate else null
        if (removed != null) {
            deleted += removed
            logger.i(
                "pruneOldBackupDirectories",
                "Pruned an old backup",
                mapOf("path" to removed.absolutePath, "keepCount" to policy.keepCount),
            )
        } else {
            logger.i(
                "pruneOldBackupDirectories",
                "Could not prune an old backup — left in place",
                mapOf("path" to candidate.absolutePath),
            )
        }
    }
    return deleted
}
