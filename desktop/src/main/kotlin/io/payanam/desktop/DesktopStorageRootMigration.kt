//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.logging.AppLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One-time migration from the legacy desktop storage root to the new parent-folder layout.
 *
 * ## Legacy layout (before)
 *
 * ```
 * <legacyRoot>/                  e.g. ~/.local/share/Payanam
 * ├── logs/
 * ├── preferences/
 * ├── bootstrap/
 * ├── security/
 * ├── database/                 →  <newParent>/db/
 * ├── runtime/
 * └── exports/
 * ```
 *
 * ## New layout (after)
 *
 * ```
 * <newParent>/                   e.g. ~/Documents/Payanam
 * ├── db/                        ← was database/
 * ├── logs/
 * ├── preferences/
 * ├── bootstrap/
 * ├── security/
 * ├── runtime/
 * └── exports/
 * ```
 *
 * ## When it is safe to call this
 *
 * - The application is **not running**. No process may hold open files inside [legacyRoot].
 * - [legacyRoot] exists and contains at least one recognised child directory.
 * - [newParent] does **not** exist, or exists and is completely empty.
 * - The caller has verified the user intends to migrate.
 *
 * ## When it is NOT safe
 *
 * - The application is running and may have open file handles in the legacy tree.
 * - [newParent] exists and is non-empty — the migration will refuse to overwrite it.
 * - The legacy root is on a different filesystem than the new parent (cross-device move).
 *
 * ## Not wired yet
 *
 * **Nothing in the application calls this code.** It is a standalone, one-time operation that a
 * future startup path or a manual tool must invoke explicitly. Until then, it is inert.
 *
 * ## Idempotency
 *
 * A second call after a successful run is a no-op: the new parent now exists, so
 * [isMigrationNeeded] returns `false`, and [performMigration] reports every child as either
 * already moved or skipped.
 *
 * ## Refusal policy (deliberate)
 *
 * If a target child directory already exists and is **non-empty**, that child is **refused**:
 * the migration skips it and reports [MigrationChildStatus.SKIPPED_TARGET_NON_EMPTY]. It never
 * merges into or overwrites a non-empty target. This is a deliberate safety measure — a
 * non-empty target means something already wrote there, and silently merging could corrupt
 * both trees.
 *
 * ## Reporting
 *
 * The migration takes a [report] lambda (defaults to no-op) so it stays dependency-light and
 * testable without initialising [UnifiedLogger]. The caller is responsible for wiring this to
 * the desktop logger when the migration is eventually invoked.
 */
internal object DesktopStorageRootMigration {
    /**
     * Maps each legacy child directory name to its counterpart in the new layout.
     * `database/` becomes `db/`; all others keep their name.
     */
    private val LEGACY_CHILD_TO_NEW_CHILD: Map<String, String> =
        mapOf(
            "database" to "db",
            "logs" to "logs",
            "preferences" to "preferences",
            "bootstrap" to "bootstrap",
            "security" to "security",
            "runtime" to "runtime",
            "exports" to "exports",
        )

    /**
     * Decides whether a migration is needed.
     *
     * Returns `true` only when [legacyRoot] exists (and is a directory) and [newParent] does not
     * exist. This is a pure read-only check — no filesystem writes are performed.
     */
    fun isMigrationNeeded(
        legacyRoot: Path,
        newParent: Path,
    ): Boolean = Files.isDirectory(legacyRoot) && !Files.exists(newParent)

    /**
     * Performs the one-time migration from [legacyRoot] to [newParent].
     *
     * For each recognised child of [legacyRoot], moves its **contents** into the corresponding
     * child of [newParent]. The mapping is defined by [LEGACY_CHILD_TO_NEW_CHILD].
     *
     * @param legacyRoot the existing legacy storage root (e.g. `~/.local/share/Payanam`)
     * @param newParent the new parent folder (e.g. `~/Documents/Payanam`)
     * @param report called with a human-readable line for each significant event
     * @return a [MigrationReport] describing exactly what was and was not moved
     */
    fun performMigration(
        legacyRoot: Path,
        newParent: Path,
        report: (String) -> Unit = {},
    ): MigrationReport {
        val childReports = mutableListOf<MigrationChildReport>()

        for ((legacyName, newName) in LEGACY_CHILD_TO_NEW_CHILD) {
            val source = legacyRoot.resolve(legacyName)
            val target = newParent.resolve(newName)

            val childReport = migrateChild(source, target, legacyName, newName, report)
            childReports.add(childReport)
        }

        return MigrationReport(
            legacyRoot = legacyRoot,
            newParent = newParent,
            children = childReports,
        )
    }

    /**
     * Moves the contents of [source] into [target], refusing to overwrite a non-empty target.
     */
    private fun migrateChild(
        source: Path,
        target: Path,
        legacyName: String,
        newName: String,
        report: (String) -> Unit,
    ): MigrationChildReport {
        if (!Files.exists(source)) {
            report("SKIP $legacyName → $newName: source does not exist")
            return MigrationChildReport(
                legacyChildName = legacyName,
                newChildName = newName,
                status = MigrationChildStatus.MISSING,
            )
        }

        if (!Files.isDirectory(source)) {
            report("ERROR $legacyName → $newName: source is not a directory")
            return MigrationChildReport(
                legacyChildName = legacyName,
                newChildName = newName,
                status = MigrationChildStatus.ERROR,
                detail = "source is not a directory",
            )
        }

        // List source entries (also serves as the idempotency check: empty source = already moved).
        val sourceEntries =
            try {
                Files.list(source).use { it.toList() }
            } catch (e: IOException) {
                report("ERROR $legacyName → $newName: failed to list source: ${e.message}")
                return MigrationChildReport(
                    legacyChildName = legacyName,
                    newChildName = newName,
                    status = MigrationChildStatus.ERROR,
                    detail = e.message,
                )
            }

        if (sourceEntries.isEmpty()) {
            report("SKIP $legacyName → $newName: source is empty (already moved)")
            return MigrationChildReport(
                legacyChildName = legacyName,
                newChildName = newName,
                status = MigrationChildStatus.MISSING,
                detail = "source is empty",
            )
        }

        // Refuse if target exists and is non-empty — never overwrite or merge.
        if (Files.exists(target) && !isEmptyDirectory(target)) {
            report("SKIP $legacyName → $newName: target exists and is non-empty")
            return MigrationChildReport(
                legacyChildName = legacyName,
                newChildName = newName,
                status = MigrationChildStatus.SKIPPED_TARGET_NON_EMPTY,
            )
        }

        return try {
            // Ensure target exists (create if needed).
            Files.createDirectories(target)

            // Move each entry from source to target.
            var movedCount = 0
            for (entry in sourceEntries) {
                val dest = target.resolve(entry.fileName)
                Files.move(entry, dest)
                movedCount++
            }

            report("MOVED $legacyName → $newName ($movedCount entries)")
            MigrationChildReport(
                legacyChildName = legacyName,
                newChildName = newName,
                status = MigrationChildStatus.MOVED,
                detail = "$movedCount entries moved",
            )
        } catch (e: IOException) {
            report("ERROR $legacyName → $newName: ${e.message}")
            MigrationChildReport(
                legacyChildName = legacyName,
                newChildName = newName,
                status = MigrationChildStatus.ERROR,
                detail = e.message,
            )
        }
    }

    /**
     * Returns `true` if [path] is a directory that contains no entries.
     * Returns `false` on any error — treat as non-empty and refuse.
     */
    private fun isEmptyDirectory(path: Path): Boolean =
        try {
            Files.list(path).use { stream ->
                !stream.findFirst().isPresent
            }
        } catch (e: IOException) {
            // Fail closed: a directory that cannot be listed is treated as non-empty, so the migration
            // refuses it rather than merging into something it could not inspect. Logged rather than
            // swallowed - "refused without saying why" is the failure a later session cannot diagnose.
            AppLogger.current.e(
                "DesktopStorageRootMigration.isEmptyDirectory",
                "A directory could not be listed while checking it for migration; treating it as non-empty",
                e,
                mapOf("path" to path.toString()),
            )
            false
        }
}

/**
 * The outcome for a single child directory during migration.
 */
enum class MigrationChildStatus {
    /** The child's contents were moved to the new location. */
    MOVED,

    /**
     * The target already exists and is non-empty. The child was **refused** — never overwritten
     * or merged. This is deliberate.
     */
    SKIPPED_TARGET_NON_EMPTY,

    /** The source child does not exist in the legacy root. */
    MISSING,

    /** An unexpected error occurred (I/O failure, etc.). */
    ERROR,
}

/**
 * Per-child result of a migration run.
 */
data class MigrationChildReport(
    val legacyChildName: String,
    val newChildName: String,
    val status: MigrationChildStatus,
    val detail: String? = null,
)

/**
 * The full report of a migration run.
 */
data class MigrationReport(
    val legacyRoot: Path,
    val newParent: Path,
    val children: List<MigrationChildReport>,
) {
    /** Children that were successfully moved. */
    val moved: List<MigrationChildReport>
        get() = children.filter { it.status == MigrationChildStatus.MOVED }

    /** Children that were skipped for any reason. */
    val skipped: List<MigrationChildReport>
        get() = children.filter { it.status != MigrationChildStatus.MOVED }

    /** `true` if every recognised child was moved. */
    val isComplete: Boolean
        get() = moved.size == children.size

    /** A one-line summary suitable for logging. */
    val summary: String
        get() = "Migration $legacyRoot → $newParent: ${moved.size} moved, ${skipped.size} skipped"
}
