//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

/**
 * The single source of truth for the Payanam database schema contract.
 *
 * These facts describe the encrypted SQLite database that BOTH platforms read. They live here, in
 * `core/shared`, because the desktop app cannot import them from `core/database` — that module is an
 * Android library (`android.library`) while the desktop depends only on `core:shared`. Keeping them in
 * Android-only code forced the desktop to hardcode its own copies, which would drift silently the
 * moment either value changed. A hardcoded copy of the refusal floor or the critical-table list would
 * be a second source of truth for a schema fact, and this project's rule is that the Android schema is
 * the cross-platform reference with no divergence.
 *
 * The Android side (`core/database`) consumes these values too, so a bump moves both platforms
 * together.
 */
object PayanamDatabaseSchema {
    /**
     * Current schema version of [io.payanam.database.PayanamDatabase].
     *
     * A reader that finds a NEWER version must refuse to operate: the file may contain columns and
     * tables it cannot interpret, and guessing would risk corrupting the user's data. A `const val` is
     * used deliberately — it inlines at call sites across module boundaries, so Room's
     * `@Database(version = ...)` parameter (which requires a compile-time constant) still resolves.
     */
    const val VERSION = 22

    /**
     * The oldest schema version that may still be opened and migrated in place.
     *
     * Build #1081 was the first beta build shipped to users, and it used schema 16. Anything older is
     * outside the supported in-place Room migration contract, so both platforms refuse below this.
     *
     * NOTE: Android migrates versions in `[MIN_MIGRATABLE_VERSION, VERSION)` in place. The desktop
     * cannot run Room migrations, so it READS that range read-only instead of refusing it — refusing
     * would lock a user out of a perfectly readable export. That difference is deliberate and
     * documented; the floor itself is shared.
     */
    const val MIN_MIGRATABLE_VERSION = 16

    /**
     * Tables that must exist for a database to be usable, regardless of schema version.
     *
     * Used as an integrity guard: a truncated or corrupted file can still report a valid
     * `user_version` in its header while missing tables, so the version number alone is not sufficient
     * evidence that the schema is sound.
     *
     * Every one of these is present in all supported versions 16..21. Tables added later (for example
     * the metrics tables added at v18) are deliberately NOT listed, so this guard stays valid for the
     * whole supported range.
     */
    val CRITICAL_TABLES: Set<String> =
        setOf("tasks", "time_entries", "notes", "day_journal_entries", "app_settings")

    /**
     * The on-disk file name of the encrypted database, identical on BOTH platforms.
     *
     * This is the name of the file itself, not just a label: changing it makes a platform open a
     * different path, so the app silently starts on a fresh empty database while the user's real
     * rows sit untouched in the old file. That reads to a user as total data loss, and nothing
     * crashes, so the value is pinned by a test in this module's own idiom.
     *
     * It lived in `io.payanam.database.PayanamDatabase` (an Android library class the desktop cannot
     * import) and the desktop carried a second hardcoded copy of the same string in
     * `DesktopAppPaths.DATABASE_FILE_NAME`. Two copies of a file name that both platforms must agree
     * on is the drift this object exists to remove, so both now read this one declaration.
     */
    const val DATABASE_NAME = "payanam.db"
}
