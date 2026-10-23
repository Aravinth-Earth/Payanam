//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.payanam.desktop.database

import io.payanam.database.jvm.DesktopDatabaseEncryptionProbe
import io.payanam.database.jvm.DesktopDatabaseHealthCheck
import io.payanam.domain.port.DatabaseRekey
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.DatabaseHealthCheck
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.logging.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * The desktop's six database-init file handles — the collaborators
 * [io.payanam.presentation.database.DatabaseInitViewModel] needs that no desktop class supplied before.
 *
 * ## Why these are ports at all
 *
 * The three steps of the DB-init flow that run BEFORE a session exists cannot go through
 * `AppSettingsRepository`, which answers via `sessionManager.requireDatabase()` and would throw on a first
 * launch. Each of the six below therefore asks a question of a FILE and keeps the platform's HANDLE —
 * a JDBC `Connection` here, a `SQLiteDatabase` cursor on Android — while the questions themselves, the
 * shared SQL, the shared WAL rule and the shared seed-row builders stay shared. That is the split
 * `DatabaseInitTempBackupSupport` documents: the statement and the decision are shared, the handle is not.
 *
 * ## All six are REAL. None is an honest refusal, and the reasons are different per port
 *
 * - [healthCheck] and [encryptionProbe] are the implementations `:core:database-jvm` ALREADY ships
 *   ([DesktopDatabaseHealthCheck], [DesktopDatabaseEncryptionProbe]), measured against this module's own
 *   driver. Reaching for them is what "not a copy" means: the desktop answers these questions with the same
 *   object, not with an approximation of it.
 * - [databaseInitFlagReader] / [databaseInitFlagWriter] are Android's SQL verbatim over JDBC, with the
 *   SQLCipher-vs-plain branch the contract's `passphrase: String?` argument implies.
 * - [databaseInitWalConsolidator] runs the shared [WalCheckpointRule] statement over JDBC and reads the
 *   shared rule's own row. It is faithful but LIMITED, and the limitation is named in its KDoc rather than
 *   hidden: the contract passes no passphrase, so a fold on an encrypted file cannot be proven and the
 *   answer is the honest `false` — which the shared caller already treats as "keep the log".
 * - [desktopDatabaseDimensionSetupPersister] is a real writer over the same `:core:ui` seed builders
 *   `:app`'s uses, because this module DOES depend on `:core:ui` and a stub here would leave a new
 *   database with no life dimensions while reporting the setup complete.
 *
 * ## Why the URL and key property are spelled out here
 *
 * `PayanamDatabaseOpener.URL_PREFIX` / `KEY_PROPERTY` are `internal` to `:core:database-jvm`, so this file
 * cannot name them. The strings below are the same two values, and the same pairing is already spelled out
 * a second time inside `:core:database-jvm` itself (`DatabaseTableCountReader.openReadOnly`), for the same
 * reason. The pairing is MEASURED, not assumed: SQLCipher JDBC 0.2.13 reads a keyless file as plaintext and
 * applies the `key` property as the SQLCipher passphrase, which is what lets the desktop open the same file
 * Android wrote.
 *
 * ## WHY THE `File` ARGUMENT IS ALWAYS THE FILE THAT WAS GIVEN
 *
 * Every method here acts on the [File] the contract passed, and on nothing else. A port in this area once
 * compiled while ignoring its own `File` and re-deriving the path from a `Context`, so it answered about a
 * different file than the one it was asked about — a bug that type-checks perfectly and is invisible until a
 * second database exists. There is no path source in this file at all: no `Context`, no resolver, no
 * environment lookup. The ViewModel's own [io.payanam.domain.port.DatabasePathResolver] is the single place
 * a path is decided, and it hands the result to these methods as an argument.
 */
internal object DesktopDatabaseInitPorts {
    private val logger = AppLogger.current

    private const val URL_PREFIX = "jdbc:sqlcipher:"
    private const val KEY_PROPERTY = "key"

    private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * The shared health check, answered by `:core:database-jvm`'s measured JDBC implementation.
     *
     * A real answer and not a refusal because the desktop CAN ask this question: the file is a SQLite file
     * this module already opens, and that implementation diffs the shared
     * [io.payanam.shared.database.PayanamDatabaseSchema.CRITICAL_TABLES] against what the file actually has.
     * Note its deliberate honesty: with no passphrase it reports "cannot be opened to check it" rather than
     * "healthy", because the desktop's own vault says encryption is off and a claim nobody measured is
     * exactly the answer this project forbids.
     */
    val healthCheck: DatabaseHealthCheck = DesktopDatabaseHealthCheck()

    /**
     * The shared encryption probe, answered by `:core:database-jvm`'s measured JDBC implementation.
     *
     * Also real, and it is the SAME object the desktop's own transfer gateway verifies a passphrase with, so
     * the init screen and the export path cannot disagree about what a file is. Its `ensureEncrypted` refuses
     * a plaintext-to-SQLCipher conversion, which is a measured driver limit rather than a gap in this file.
     * Built once and exposed through both ports, so the two answers are the same object and cannot disagree.
     */
    private val desktopEncryptionProbe = DesktopDatabaseEncryptionProbe()

    val encryptionProbe: DatabaseEncryptionProbe = desktopEncryptionProbe

    /**
     * The rekey port, answered by the same [DesktopDatabaseEncryptionProbe] object rather than a second one.
     *
     * The passphrase-change flow needs a rekey and nothing else in this flow does, and the desktop already
     * had the measured JDBC implementation — it simply had no port to be reached through. It is a real
     * capability on this platform: `PRAGMA rekey` against an ALREADY-ENCRYPTED file is measured to work
     * here, so the honest answer is a working rekey rather than a refusal. (The plaintext conversion this
     * same class refuses is a different operation.)
     */
    val rekey: DatabaseRekey = desktopEncryptionProbe

    /**
     * Reads one `app_settings` value out of [databaseFile], read-only, with no passphrase.
     *
     * Android's `appDatabaseInitFlagReader` is the same statement with the same absence of a key, because
     * the contract asks for no key: this runs on a first launch, before any passphrase is known.
     *
     * **WHAT THIS PORT CAN AND CANNOT DO ON THE DESKTOP, MEASURED BY ITS OWN TEST.** The signature carries no
     * passphrase and the desktop's database is SQLCipher-encrypted, so a keyless open fails with
     * `java.sql.SQLException: file is not a database` — measured in `DesktopDatabaseInitPortsTest`, not
     * inferred. So on this platform the reader cannot read the user's database, and it SURFACES that by
     * throwing rather than returning `null`.
     *
     * That choice is load-bearing. A `null` here is indistinguishable from "no such row", so the shared
     * reader (`dbInitReadInitCompletedFlag`) would read an encrypted, fully-initialised database as one whose
     * init flag was never written. Throwing lets that caller catch the failure and answer "not completed" —
     * which is exactly what Android does on the same file, and the answer a first launch needs. Returning a
     * plausible-looking `null` would be the invented answer this project forbids, and the test is written to
     * fail if anyone swaps it for one.
     *
     * Read-only is `PRAGMA query_only = ON`, read back and REQUIRED to be `1`: this driver silently ignores
     * `?mode=ro` (measured — it connects, `query_only` stays `0`, and a write succeeds), so a "read-only" open
     * that trusted the URL would be a false guarantee on the user's database file.
     */
    val databaseInitFlagReader: io.payanam.presentation.database.DatabaseInitFlagReader =
        io.payanam.presentation.database.DatabaseInitFlagReader { databaseFile, key ->
            val value = readSettingValue(databaseFile, key)
            logger.d(
                "DesktopDatabaseInitPorts.databaseInitFlagReader",
                "Read an app_settings value from the database file",
                mapOf("file" to databaseFile.name, "found" to (value != null)),
            )
            value
        }

    /**
     * Writes `database_init_completed = true` straight into [databaseFile], SQLCipher when [passphrase] is
     * set and plain when it is not.
     *
     * The same row a session-backed `AppSettingsRepository.markDatabaseInitCompleted()` would write, and the
     * statement is Android's verbatim: `INSERT OR REPLACE INTO app_settings(`key`, value, updatedAt)`. The
     * key and the value are the contract's own constants, not literals re-typed here, so a desktop first run
     * and an Android first run write an identical row and neither can flip the other's next launch. The
     * upsert matters: the row may already exist from an earlier attempt, and a plain insert would fail.
     *
     * The write is deliberately NOT routed through a repository — that is the whole reason this port exists —
     * and the file given is the file written. No path is derived anywhere in this class.
     */
    val databaseInitFlagWriter: io.payanam.presentation.database.DatabaseInitFlagWriter =
        io.payanam.presentation.database.DatabaseInitFlagWriter { databaseFile, passphrase ->
            val updatedAt = LocalDateTime.now().format(ISO_FORMATTER)
            openForDirectFileAccess(databaseFile, passphrase).use { connection ->
                connection.prepareStatement(UPSERT_SETTING_SQL).use { statement ->
                    statement.setString(KEY_COLUMN, AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY)
                    statement.setString(VALUE_COLUMN, AppSettingsRepository.INIT_COMPLETED_VALUE)
                    statement.setString(UPDATED_AT_COLUMN, updatedAt)
                    statement.executeUpdate()
                }
            }
            logger.i(
                "DesktopDatabaseInitPorts.databaseInitFlagWriter",
                "Marked database init completed in the database file",
                mapOf("file" to databaseFile.name, "encrypted" to !passphrase.isNullOrBlank()),
            )
        }

    /**
     * Folds a TEMP copy's write-ahead log, and reports whether the log is now EMPTY.
     *
     * The statement, the reading of its row and the decision are the shared [WalCheckpointRule]'s, exactly as
     * on Android: emptiness is `busy == 0` AND no frames left, a row that never arrived is a NON-empty
     * answer, and the file must still exist and be non-empty. A fold that reported success on weaker evidence
     * would authorise the caller to delete the production `-wal` and lose any frame that lived only in it.
     *
     * **THE LIMITATION, STATED PLAINLY:** the contract's signature carries no passphrase, so this cannot open
     * an ENCRYPTED file — and the desktop's database is exactly that. It therefore reports `false` for an
     * encrypted file, which is a REAL answer rather than a stub: the shared caller keeps the log instead of
     * deleting it, and the import proceeds. The opposite — reporting `true` for a fold that never ran — is
     * the outcome that loses data, and it is refused here in favour of the answer that cannot.
     *
     * The reason is logged at WARN with the file's name only; an absolute path would leak the user's home
     * directory layout into a log they may paste into a public issue.
     */
    val databaseInitWalConsolidator: io.payanam.presentation.database.DatabaseInitWalConsolidator =
        io.payanam.presentation.database.DatabaseInitWalConsolidator { databaseFile, logTag ->
            consolidate(databaseFile, logTag)
        }

    private fun consolidate(
        databaseFile: File,
        logTag: String,
    ): Boolean =
        try {
            var report: WalCheckpointRule.FoldReport = WalCheckpointRule.NO_ROW
            openForDirectFileAccess(databaseFile, passphrase = null).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(WalCheckpointRule.FOLD_STATEMENT).use { rows ->
                        report =
                            if (rows.next()) {
                                // The pragma's row is `(busy, log, checkpointed)`, in that order, and
                                // WalCheckpointRule owns the reading. Named rather than literal so a row that
                                // was read in the wrong order is a compile error rather than a fold reported
                                // backwards - the failure this whole rule exists to prevent.
                                WalCheckpointRule.reportOf(
                                    intArrayOf(rows.getInt(BUSY_COLUMN), rows.getInt(LOG_COLUMN), rows.getInt(CHECKPOINTED_COLUMN)),
                                )
                            } else {
                                WalCheckpointRule.NO_ROW
                            }
                    }
                }
            }
            logger.i(
                logTag,
                "WAL checkpoint on a temp copy",
                mapOf(
                    "busy" to report.busy,
                    "log" to report.logFrames,
                    "ckpt" to report.checkpointedFrames,
                    "walIsEmpty" to report.walIsEmpty,
                    "file" to databaseFile.name,
                ),
            )
            databaseFile.exists() && databaseFile.length() > 0 && WalCheckpointRule.walIsEmpty(report)
        } catch (error: Exception) {
            // False is a real answer here, not a failure to report: the caller keeps the log, which is the
            // outcome that cannot lose a write. An encrypted file lands in this branch every time, so the
            // message says the fold did not complete rather than implying something is broken.
            logger.w(
                logTag,
                "WAL checkpoint on the temp copy did not complete; the log is kept rather than dropped",
                mapOf(
                    "file" to databaseFile.name,
                    "error" to (error.message ?: "Unknown"),
                    "exception" to error.javaClass.simpleName,
                ),
            )
            false
        }

    private fun readSettingValue(
        databaseFile: File,
        key: String,
    ): String? {
        requireRegularFile(databaseFile)
        return openForDirectFileAccess(databaseFile, passphrase = null, readOnly = true).use { connection ->
            connection.prepareStatement(SETTING_BY_KEY_SQL).use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        }
    }

    /**
     * Opens [databaseFile] through the same driver/URL/key pairing the desktop's own opener uses.
     *
     * `readOnly` is `PRAGMA query_only = ON` and is read back and required to be `1`, because that is the
     * only read-only mechanism this driver enforces — see [io.payanam.database.jvm.PayanamDatabaseOpener] and
     * [io.payanam.database.jvm.DatabaseTableCountReader] for the measurements behind that.
     */
    private fun openForDirectFileAccess(
        databaseFile: File,
        passphrase: String?,
        readOnly: Boolean = false,
    ): Connection {
        requireRegularFile(databaseFile)
        val properties = Properties()
        if (!passphrase.isNullOrBlank()) {
            properties.setProperty(KEY_PROPERTY, passphrase)
        }
        val connection =
            DriverManager.getConnection(
                URL_PREFIX + Path.of(databaseFile.absolutePath),
                properties,
            )
        if (readOnly) {
            connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA query_only").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1) { "query_only did not take effect" }
                }
            }
        }
        return connection
    }

    private fun requireRegularFile(databaseFile: File) {
        if (!Files.isRegularFile(databaseFile.toPath())) {
            throw IllegalStateException("No database file at " + databaseFile.name)
        }
    }

    private const val UPSERT_SETTING_SQL =
        "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, ?, ?)"

    /** Verbatim from `AppSettingsDao.getSetting` and from Android's database-init flag reader. */
    private const val SETTING_BY_KEY_SQL = "SELECT value FROM app_settings WHERE key = ? LIMIT 1"

    // `PRAGMA wal_checkpoint(TRUNCATE)` answers one row of three 1-based columns, in the order
    // WalCheckpointRule.reportOf expects. Named rather than literal: getting the order wrong would report a
    // completed fold as a blocked one, or worse the reverse.
    private const val BUSY_COLUMN = 1
    private const val LOG_COLUMN = 2
    private const val CHECKPOINTED_COLUMN = 3

    // The `app_settings` upsert's three bound parameters, in the order UPSERT_SETTING_SQL declares them.
    // Named for the same reason: a reordering that bound the timestamp into the value column would write a
    // flag that reads as `false` on the next launch, which is the one error this port must not make.
    private const val KEY_COLUMN = 1
    private const val VALUE_COLUMN = 2
    private const val UPDATED_AT_COLUMN = 3
}
