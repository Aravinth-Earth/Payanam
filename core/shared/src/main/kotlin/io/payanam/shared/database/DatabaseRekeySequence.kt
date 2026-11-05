//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import java.io.File

/**
 * The four-step passphrase change, shared.
 *
 * ## Why the sequence and not a copy
 *
 * Android runs these steps today, on a `net.zetetic.database.sqlcipher.SQLiteDatabase` handle. The desktop
 * could run the same steps on a JDBC `Connection`, and until the driver was measured the only thing anyone
 * could say about that was a hunch. So what is shared here is the SEQUENCE and its DECISIONS — verify the
 * file, open it read/write under the current passphrase, issue the rekey, verify the result — and what is
 * NOT shared is the handle. A caller supplies [openForRekey] and [canOpenWith], and owns everything those
 * touch: on Android a `Context` and a `System.loadLibrary`, on the desktop a `DriverManager` connection.
 *
 * ## What is deliberately NOT here
 *
 * Converting a PLAINTEXT file. That is a different route per platform, and so is left to the caller:
 *
 * - Android opens the plaintext file with the empty key and rekeys it (`DatabaseEncryptionMigrationSupport`).
 * - The desktop's driver REFUSES that exact statement. Measured on
 *   `io.github.s0d3s:sqlcipher-multiplatform-jdbc:0.2.13`:
 *   `java.sql.SQLException: An error occurred with PRAGMA key or rekey. PRAGMA key requires a key of one or
 *   more characters. PRAGMA rekey can only be run on an existing encrypted database. Use sqlcipher_export()
 *   and ATTACH to convert encrypted/plaintext databases.`
 *   Its `ATTACH` + `sqlcipher_export()` route does work (measured), but it writes a NEW file, so it is a
 *   different operation with a different recovery story and is not smuggled in here.
 *
 * ## The gate
 *
 * A rekey is only reported as done when the file opens under the new passphrase AND FAILS under the old one.
 * Both halves are required: a file that still opens with the old key was not rekeyed, and reporting success
 * would leave a user believing their passphrase changed when it did not.
 */
object DatabaseRekeySequence {
    /** What a completed run did. A caller can only report success for [REKEYED]. */
    enum class Outcome {
        /** The file was rekeyed and verified under the new passphrase, and refused under the old one. */
        REKEYED,

        /** The file already opens under the new passphrase, so no statement was issued. */
        ALREADY_ON_TARGET_PASSPHRASE,
    }

    /**
     * A database opened read/write purely so one statement can be run against it.
     *
     * [close] must be called by [rekeyToNewPassphrase]; the caller opens, but the sequence owns the lifetime
     * of the handle it borrowed, so a rekey never leaks the connection.
     */
    interface RekeyableDatabase {
        /** Runs the rekey statement [rekeyToNewPassphrase] built. Must throw if the driver refuses. */
        fun applyRekey(statement: String)

        /** Releases the handle. Called exactly once, even when [applyRekey] throws. */
        fun close()
    }

    /**
     * The first step of the sequence, exposed on its own because a platform that cannot complete a rekey
     * still has to answer the same question before it refuses.
     *
     * A rekey, or any refusal of one, against a file that is not there would be indistinguishable from a
     * silent success — which is the exact outcome the gate below exists to prevent.
     */
    fun requirePresent(databaseFile: File) {
        if (!databaseFile.exists()) {
            throw IllegalStateException(
                "Database file does not exist for passphrase update: " + databaseFile.name,
            )
        }
    }

    /**
     * Rekeys [databaseFile] from [currentPassphrase] to [newPassphrase], or reports
     * [Outcome.ALREADY_ON_TARGET_PASSPHRASE] without touching the file.
     *
     * @param openForRekey opens the file read/write under [currentPassphrase]. Called at most once, and
     *   never at all when the passphrases match.
     * @param canOpenWith whether the file opens readably under the given passphrase. MUST perform a READ,
     *   not merely an open: the desktop's driver opens a file successfully with a WRONG key and only fails
     *   when a page is read, so an open-only implementation would call a wrong passphrase correct.
     * @throws IllegalStateException when the file is missing, or when the rekey did not produce a file that
     *   opens under the new passphrase and refuses the old one. Every such throw means the file's
     *   encryption is now UNVERIFIED, so the message says so rather than describing a routine failure.
     */
    fun rekeyToNewPassphrase(
        databaseFile: File,
        currentPassphrase: String,
        newPassphrase: String,
        openForRekey: (File, String) -> RekeyableDatabase,
        canOpenWith: (File, String) -> Boolean,
    ): Outcome {
        // Step 1 - the file has to be there. A rekey against a missing file would "succeed" at nothing.
        requirePresent(databaseFile)
        // Step 2a - nothing to do. Checked before any handle is opened so a matching passphrase cannot
        // rewrite the file for no reason.
        if (currentPassphrase == newPassphrase) {
            return Outcome.ALREADY_ON_TARGET_PASSPHRASE
        }
        // Step 2b - open read/write under the CURRENT passphrase. The driver's refusal to open, and any
        // other failure here, propagates: an unopenable file must not be "fixed" by rekeying it.
        val handle = openForRekey(databaseFile, currentPassphrase)
        try {
            // Step 3 - the rekey itself. The passphrase is inlined rather than bound, because both drivers
            // take a PRAGMA string here, so it is escaped rather than parameterised.
            handle.applyRekey("PRAGMA rekey = '${escapeSqlLiteral(newPassphrase)}';")
        } finally {
            // Before verification, never after: the handle must be released for the new key to be readable.
            handle.close()
        }
        // Step 4 - the gate. Both halves, as described on this object.
        if (!canOpenWith(databaseFile, newPassphrase)) {
            throw IllegalStateException(
                "Rekey verification failed for " + databaseFile.name +
                    ": the file does not open under the new passphrase, so its encryption is unverified.",
            )
        }
        if (canOpenWith(databaseFile, currentPassphrase)) {
            throw IllegalStateException(
                "Rekey verification failed for " + databaseFile.name +
                    ": the file still opens under the old passphrase, so the rekey did not take effect.",
            )
        }
        return Outcome.REKEYED
    }

    /**
     * Doubles the single quote, which is SQLCipher's only string delimiter in a PRAGMA.
     *
     * Shared rather than private to the Android file so both platforms embed a passphrase the same way. A
     * passphrase containing a quote is a real case: a user may pick `it's mine`, and an unescaped one
     * truncates the statement into a syntactically valid, differently-keyed rekey.
     */
    fun escapeSqlLiteral(value: String): String = value.replace("'", "''")
}
