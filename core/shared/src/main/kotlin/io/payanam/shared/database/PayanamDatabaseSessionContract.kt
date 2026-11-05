//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

/**
 * One open database, expressed so BOTH platforms can satisfy it.
 *
 * Deliberately narrow. Android's session is a Room database and the desktop's is a JDBC connection over
 * SQLCipher: the only things platform-neutral code may ask of either are what the schema version is,
 * whether it is still open, and how to close it. Anything that needs a connection, a `Cursor` or a
 * `Query` is repository work and belongs behind `PayanamDataLayer`, not here.
 *
 * The desktop's `PayanamDatabaseSession` implements this directly; Android's session manager supplies an
 * adapter. The two therefore report the same [schemaVersion] from the same single-sourced constant
 * ([PayanamDatabaseSchema.VERSION]) instead of each hardcoding its own.
 */
interface PayanamDatabaseHandle {
    /** The schema version the OPEN database reports, not the version this build writes. */
    val schemaVersion: Int

    /** False once [close] has run. Reading it must be safe after close. */
    val isOpen: Boolean

    /**
     * Releases the underlying handle. Idempotent: closing an already-closed handle is not an error, because
     * a shutdown path and a failure path routinely both close.
     */
    fun close()
}

/**
 * The outcome of asking a platform to open a database.
 *
 * [Refused] carries a [DatabaseBootIssue] rather than a message, so both platforms refuse the same
 * databases with the same case and each renders its own copy.
 */
sealed interface DatabaseOpenOutcome {
    /** Open, and [handle] is the caller's to close. */
    data class Opened(
        val handle: PayanamDatabaseHandle,
    ) : DatabaseOpenOutcome

    /** Not opened, for the reason in [issue]. */
    data class Refused(
        val issue: DatabaseBootIssue,
    ) : DatabaseOpenOutcome
}

/**
 * Opens the platform's encrypted database, and answers the separate question of whether a passphrase
 * matches a file.
 *
 * The two are separate entry points on purpose and both platforms keep them apart: "is this the right
 * passphrase" is asked by the import-onboarding step, which must answer BEFORE copying anything, while
 * "may we operate on this database" additionally involves the schema guard, sidecars and availability.
 * A single method would force the import path to open a database it has not yet decided to accept.
 *
 * [passphrase] is a [CharArray] so the caller can wipe the buffer it owns. Each platform's underlying API
 * takes a `String` and therefore makes one immutable copy inside the adapter; that is a limitation of the
 * legacy APIs, recorded rather than hidden, and it does not change the caller's obligation to clear its
 * own array.
 */
interface PayanamDatabaseOpener {
    /**
     * True only if the file exists, is readable, has no sidecar beside it, and actually decrypts with
     * [passphrase].
     *
     * A missing file, an unreadable file or a present sidecar all return `false` WITHOUT claiming a
     * passphrase verdict — they are not evidence about the passphrase, and a caller that treats them as
     * one would tell the user their passphrase is wrong when the real problem is the file.
     */
    suspend fun verifyPassphrase(
        databasePath: String,
        passphrase: CharArray,
    ): Boolean

    /**
     * Opens [databasePath] under the requested access mode.
     *
     * [readOnly] is a request, not a hint: a platform that cannot honour it must refuse rather than open
     * read-write, because the caller is relying on it to avoid mutating a database it is only inspecting.
     */
    suspend fun open(
        databasePath: String,
        passphrase: CharArray,
        readOnly: Boolean = false,
    ): DatabaseOpenOutcome
}

/** The issue that caused a refusal, or null when the database opened. */
fun DatabaseOpenOutcome.refusalIssue(): DatabaseBootIssue? = (this as? DatabaseOpenOutcome.Refused)?.issue

/** The open handle, or null when the database was refused. */
fun DatabaseOpenOutcome.handleOrNull(): PayanamDatabaseHandle? = (this as? DatabaseOpenOutcome.Opened)?.handle
