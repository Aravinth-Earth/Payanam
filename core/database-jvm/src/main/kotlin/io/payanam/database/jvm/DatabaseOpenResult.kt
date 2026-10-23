//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.PayanamDatabaseSchema

/**
 * How a database was opened.
 *
 * [READ_ONLY] is a *mode*, not an architecture: the desktop's own working database is read-write,
 * while a foreign export the user only wants to inspect is opened read-only.
 */
enum class DatabaseOpenMode {
    READ_ONLY,
    READ_WRITE,
}

/**
 * The outcome of trying to open the encrypted database.
 *
 * Refusals carry Android's [DatabaseBootIssue] rather than a desktop-only reason enum. The categories
 * are the ones Android's boot screen already presents, so both platforms classify a given database the
 * same way; a second vocabulary would drift the moment either side gained a case. Causes Android's
 * model does not name individually (a missing file, an unreadable one, the native payload absent, a
 * read-only guard that did not hold) are reported as [DatabaseBootIssueType.OPEN_FAILED] with a short
 * machine-readable [DatabaseBootIssue.detailMessage] — never a filesystem path, which must not reach a
 * log the user might paste publicly.
 */
sealed interface DatabaseOpenResult {
    /** The database is open and [session] owns the connection until it is closed. */
    data class Opened(
        val session: PayanamDatabaseSession,
    ) : DatabaseOpenResult

    /** The database was not opened; the refusal has been logged. */
    data class Refused(
        val issue: DatabaseBootIssue,
    ) : DatabaseOpenResult
}

/** Detail codes for refusals Android's [DatabaseBootIssueType] does not sub-classify. */
internal object RefusalDetail {
    const val NOT_FOUND = "not_found"
    const val NOT_READABLE = "not_readable"
    const val NATIVE_UNAVAILABLE = "native_unavailable"
    const val READ_ONLY_NOT_ENFORCED = "read_only_not_enforced"
    const val FOREIGN_KEYS_NOT_ENFORCED = "foreign_keys_not_enforced"
    const val OPEN_THREW = "open_threw"
    const val GUARD_THREW = "guard_threw"
    const val CREATE_THREW = "create_threw"
    const val ALREADY_EXISTS = "already_exists"
    const val NO_PARENT_DIRECTORY = "no_parent_directory"
    const val PARENT_NOT_FOUND = "parent_not_found"
    const val PARENT_NOT_WRITABLE = "parent_not_writable"
}

/**
 * Decides whether [version] is one this build can operate on.
 *
 * Pure, and deliberately separate from the driver: the rule is about schema versions, not about which
 * SQL a driver happens to execute. That the driver CAN now write `user_version` — via the drained-
 * `executeQuery` path [PayanamDatabaseOpener] documents and [PayanamDatabaseOpener.create] uses — is a
 * driver fact recorded there, and it is why a real fixture at the supported version can be built.
 *
 * @return the problem to report, or `null` when the version is usable.
 */
internal fun classifySchemaVersion(version: Int): DatabaseBootIssueType? =
    when {
        version > PayanamDatabaseSchema.VERSION -> DatabaseBootIssueType.DB_TOO_NEW
        version < PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION -> DatabaseBootIssueType.DB_TOO_OLD
        else -> null
    }
