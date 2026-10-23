//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.jvm.DatabaseTableCountReader
import io.payanam.domain.port.DatabaseRekey
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.DatabaseRekeySequence
import io.payanam.shared.database.EncryptedFileClassifier
import io.payanam.shared.logging.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * The desktop's answer to [DatabaseEncryptionProbe], over JDBC.
 *
 * Every method opens the file read-only and lets the DRIVER answer. That is deliberate: the desktop cannot
 * rekey a file it has no migration path for, so it reports what it measured and refuses the rest, rather
 * than approximating Android's conversion.
 */
class DesktopDatabaseEncryptionProbe : DatabaseEncryptionProbe, DatabaseRekey {
    private val logger = AppLogger.current

    override fun isDetectablyEncrypted(
        databaseFile: File,
        logTag: String,
    ): Boolean {
        if (!databaseFile.exists()) return false
        // The FORMAT question is platform-neutral, so it is not answered here at all. This platform supplies
        // the bytes; EncryptedFileClassifier supplies the rule. It used to carry its own header check and its
        // own length threshold, which is how it came to disagree with Android about a 1-byte file.
        val header = readHeader(databaseFile)
        val verdict =
            EncryptedFileClassifier.classify(
                header = header,
                totalLength = databaseFile.length(),
                readSucceeded = header != null,
            )
        val opened =
            runCatching {
                open(databaseFile, passphrase = null, readOnly = true)?.use { true } ?: false
            }.getOrElse { error ->
                logger.w(
                    logTag,
                    "Could not read the file as plaintext, treating it as encrypted",
                    mapOf("failure" to error.javaClass.simpleName),
                )
                true
            }
        // A file with SQLite's plaintext magic is NOT encrypted. Without this the desktop would classify
        // every plaintext database as encrypted and ask the user for a passphrase they do not have.
        if (verdict.hasPlaintextMagic && opened) return false
        return opened || verdict.isEncrypted
    }

    override fun canOpen(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ): Boolean =
        runCatching {
            open(databaseFile, passphrase, readOnly = true)?.use { connection ->
                // The open ITSELF succeeds with a wrong key - only reading a page fails. So this must READ,
                // exactly as PayanamDatabaseOpener.verifyPassphrase does, or a wrong passphrase would be
                // reported as correct and the user sent in circles.
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { rows -> rows.next() }
                }
                true
            } ?: false
        }.getOrElse { error ->
            logger.w(
                logTag,
                "File did not open with the supplied passphrase",
                mapOf("failure" to error.javaClass.simpleName),
            )
            false
        }

    override fun ensureEncrypted(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ) {
        // The sequence itself is shared and MEASURED, not assumed: this driver does run `PRAGMA rekey`
        // against an ENCRYPTED file, and the result opens under the new passphrase and fails under the old
        // one (io.github.s0d3s:sqlcipher-multiplatform-jdbc:0.2.13, probed directly).
        //
        // What it does NOT do is convert a PLAINTEXT file, which is the only case this method can still be
        // asked for. Measured, not inferred:
        //   java.sql.SQLException: An error occurred with PRAGMA key or rekey. PRAGMA key requires a key of
        //   one or more characters. PRAGMA rekey can only be run on an existing encrypted database. Use
        //   sqlcipher_export() and ATTACH to convert encrypted/plaintext databases.
        // That recommended route is measured working too, but it writes a NEW file, so it is a different
        // operation from a rekey and belongs to the import flow, not to this contract.
        // Step 1 of the shared sequence, which this platform answers before it refuses. Exposed separately
        // because a refusal must not be distinguishable from a success against a file that is not there.
        DatabaseRekeySequence.requirePresent(databaseFile)
        logger.w(
            logTag,
            "Re-encryption refused: this platform has no plaintext-to-SQLCipher path",
            mapOf("file" to databaseFile.name),
        )
        throw UnsupportedOperationException(
            "Re-encryption of a PLAINTEXT database is not supported on this platform; its driver refuses " +
                "`PRAGMA rekey` on an unencrypted database (measured: \"PRAGMA rekey can only be run on an " +
                "existing encrypted database\"). Import an encrypted database instead. A passphrase change on " +
                "an ALREADY-ENCRYPTED database does work on this platform and goes through " +
                "DatabaseRekeySequence.",
        )
    }

    /**
     * Rekeys an ALREADY-ENCRYPTED [databaseFile] from [currentPassphrase] to [newPassphrase].
     *
     * This is the desktop half of Android's `DatabaseEncryptionMigrationSupport.rekeyEncryptedDatabase`, and
     * it is the same shared sequence rather than a parallel copy — the step order, the identical-passphrase
     * short-circuit and the both-halves-of-the-gate verification all live in [DatabaseRekeySequence]. Only
     * the handle is this platform's: a JDBC [java.sql.Connection] opened with the `key` property
     * [PayanamDatabaseOpener] uses.
     *
     * The capability is MEASURED, not assumed. Against
     * `io.github.s0d3s:sqlcipher-multiplatform-jdbc-core:0.2.13` the `PRAGMA rekey` left a file of the same
     * size that opened under the new passphrase and failed under the old one with
     * `java.sql.SQLException: file is not a database`.
     *
     * @throws IllegalStateException when the file is missing, the driver refuses to open it under
     *   [currentPassphrase], or the result fails the shared gate. The file's encryption is then UNVERIFIED.
     */
    override fun rekeyEncryptedDatabase(
        databaseFile: File,
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
        logTag: String,
    ): DatabaseRekeySequence.Outcome =
        rekeyFile(
            databaseFile = databaseFile,
            // The one place a passphrase stops being erasable on this platform: the JDBC `key` property
            // and the PRAGMA literal are both Strings, so the conversion is unavoidable and confined here.
            currentPassphrase = currentPassphrase.concatToString(),
            newPassphrase = newPassphrase.concatToString(),
            logTag = logTag,
        )

    /**
     * The rekey over this module's driver, for a caller that already holds Strings.
     *
     * Kept as the String form so this file's own test exercises the real statement against the real driver
     * without a [CharArray] round trip, and so the import path's internal use does not have to pretend to be
     * a vault caller. The port entry point above is what a passphrase change calls.
     */
    fun rekeyFile(
        databaseFile: File,
        currentPassphrase: String,
        newPassphrase: String,
        logTag: String = "DesktopDatabaseEncryptionProbe",
    ): DatabaseRekeySequence.Outcome {
        val outcome =
            DatabaseRekeySequence.rekeyToNewPassphrase(
                databaseFile = databaseFile,
                currentPassphrase = currentPassphrase,
                newPassphrase = newPassphrase,
                openForRekey = { file, passphrase ->
                    val connection = openWritable(file, passphrase)
                    object : DatabaseRekeySequence.RekeyableDatabase {
                        override fun applyRekey(statement: String) {
                            connection.createStatement().use { it.execute(statement) }
                        }

                        override fun close() {
                            connection.close()
                        }
                    }
                },
                // canOpen() and not the private `open`: the shared gate's two halves are only meaningful if
                // the check READS, because this driver opens a file successfully with a wrong key.
                canOpenWith = { file, passphrase -> canOpen(file, passphrase, logTag) },
            )
        logger.i(
            logTag,
            "Database rekey completed",
            mapOf("file" to databaseFile.name, "outcome" to outcome.name),
        )
        return outcome
    }

    override fun readTableCounts(
        databaseFile: File,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int> = DatabaseTableCountReader.readTableCounts(databaseFile.toPath(), passphrase, tableNames)

    private fun open(
        databaseFile: File,
        passphrase: String?,
        readOnly: Boolean,
    ): java.sql.Connection? {
        if (!Files.isRegularFile(databaseFile.toPath())) return null
        val properties = Properties()
        if (!passphrase.isNullOrBlank()) {
            properties.setProperty(PayanamDatabaseOpener.KEY_PROPERTY, passphrase)
        }
        val connection =
            DriverManager.getConnection(
                PayanamDatabaseOpener.URL_PREFIX + databaseFile.toPath().toAbsolutePath(),
                properties,
            )
        if (readOnly) {
            // `?mode=ro` is silently ignored by this driver (measured), so read-only is PRAGMA + a read-back.
            connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA query_only").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1) { "query_only did not take effect" }
                }
            }
        }
        return connection
    }

    /**
     * Opens the file read/write under [passphrase], with no `query_only` guard.
     *
     * Separate from [open] because that one enforces read-only: a rekey issued over a `query_only`
     * connection would be rejected by SQLite as a write, and the resulting error would name the pragma
     * rather than the real cause. A read/write handle is exactly the one thing the desktop must supply to
     * take part in the shared sequence.
     *
     * **THE BUSY TIMEOUT IS NOT OPTIONAL HERE, and it is not an optimisation.** A rekey takes an EXCLUSIVE
     * lock on the file, and the rekey runs while other connections may still be settling - the app's own
     * session, the assistant's reader, a health check. Without a timeout this driver refuses the lock
     * INSTANTLY, and it DROPS the SQLite return code on the way out (`safeExec` -> `NativeBridge.exec` ->
     * `pop`), so the refused COMMIT is invisible: the statement returns as if it had worked, the file is
     * left half-rekeyed, and the shared gate then reports a file that was never rekeyed. The same wait
     * every other connection in this module gets, issued by the opener's own helper so that the pragma
     * text and the number live in ONE place.
     */
    private fun openWritable(
        databaseFile: File,
        passphrase: String,
    ): java.sql.Connection {
        val properties = Properties()
        properties.setProperty(PayanamDatabaseOpener.KEY_PROPERTY, passphrase)
        val connection =
            DriverManager.getConnection(
                PayanamDatabaseOpener.URL_PREFIX + databaseFile.toPath().toAbsolutePath(),
                properties,
            )
        try {
            PayanamDatabaseOpener.applyBusyTimeout(connection)
        } catch (error: java.sql.SQLException) {
            // A connection that could not be given a busy timeout is not one to hand back: closing it here
            // releases whatever lock it already holds, so the caller's rollback is not fighting it.
            runCatching { connection.close() }
            throw error
        }
        return connection
    }

    /**
     * The first header's worth of bytes, or `null` when the file could not be read at all.
     *
     * Reading is the platform's job; deciding what the bytes MEAN is [EncryptedFileClassifier]'s. A short read
     * returns the bytes actually read (possibly fewer than a full header) rather than padding them, because
     * the classifier judges a short file as too short to be a database and must be able to see that.
     */
    private fun readHeader(databaseFile: File): ByteArray? =
        runCatching {
            databaseFile.inputStream().use { stream ->
                val buffer = ByteArray(EncryptedFileClassifier.MINIMUM_PROBE_BYTES)
                val read = stream.read(buffer)
                if (read <= 0) null else buffer.copyOf(read)
            }
        }.getOrNull()
}
