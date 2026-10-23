//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.presentation.database.DatabaseDimensionSetupPersister
import io.payanam.presentation.database.DatabaseInitFlagReader
import io.payanam.presentation.database.DatabaseInitFlagWriter
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.ui.viewmodel.persistNewDatabaseDimensionSetup
import java.io.File

/**
 * Android's [DatabaseInitFlagWriter]: marks DB-init as completed by writing the flag row into the
 * database FILE itself.
 *
 * It still goes through [DatabaseEncryptionMigrationSupport] rather than doing its own SQL: that
 * support class already owns the SQLCipher-vs-plain branch and the `INSERT OR REPLACE` upsert, and
 * the shared contract's KEY and VALUE come from `AppSettingsRepository`, so the row written is the
 * one a session-backed `markDatabaseInitCompleted()` would write.
 */
fun appDatabaseInitFlagWriter(context: Context): DatabaseInitFlagWriter = DatabaseInitFlagWriter { file, passphrase ->
    DatabaseEncryptionMigrationSupport.markDatabaseInitCompleted(
        context = context,
        databaseFile = file,
        passphrase = passphrase,
        logTag = "DatabaseInitViewModel.markDatabaseInitCompletedDirect",
    )
}

/**
 * Reads one `app_settings` value out of the file itself, read-only.
 *
 * This must NOT go through `AppSettingsRepository`: it answers via
 * `sessionManager.requireDatabase()`, and this runs during `detectBootstrapPlaceholder` on a first
 * launch, where no session exists yet and that call would throw. The value is parsed by the shared
 * helper with `String.toBoolean`, so a missing row, a null and junk all read as `false`.
 */
fun appDatabaseInitFlagReader(): DatabaseInitFlagReader = DatabaseInitFlagReader { file, key ->
    android.database.sqlite.SQLiteDatabase
        .openDatabase(file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        .use { db ->
            db.rawQuery("SELECT value FROM app_settings WHERE key = ? LIMIT 1", arrayOf(key)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }
}

/**
 * The one platform-specific call the promoted WAL orchestration could not keep: running
 * `PRAGMA wal_checkpoint(TRUNCATE)` on a temp copy needs a Room handle. The shared code decides
 * WHETHER the fold may delete the production `-wal`; this only performs it.
 */
fun appDatabaseInitWalConsolidator(logger: UnifiedLogger): DatabaseInitWalConsolidator = DatabaseInitWalConsolidator { file, logTag ->
    consolidateWalWithFramework(dbFile = file, logTag = logTag, logger = logger)
}

/**
 * Runs `PRAGMA wal_checkpoint(TRUNCATE)` on a TEMP copy of the database and reports whether the log was
 * actually emptied.
 *
 * The AND, not the file's existence, is the answer: the next step deletes the production `-wal`, and
 * that delete is unrecoverable for any frame that existed only in it. A merge that opened without
 * error but folded nothing would report success on the same evidence the old gate used, so the shared
 * [WalCheckpointRule] reads the pragma's own `(busy, log, checkpointed)` row
 * and requires an empty log. Both halves are needed: `busy = 1` alone can be reported with an empty
 * log, and a non-busy row with frames left is a fold that did not run.
 *
 * The no-op [android.database.DatabaseErrorHandler] is deliberate and applies ONLY to this temp copy —
 * a native-layer reaction to a malformed WAL must be allowed to hit a throwaway file, never the
 * user's database, which is why the caller copies first and this never sees the production path.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: a bad WAL can throw anything from the native layer
private fun consolidateWalWithFramework(
    dbFile: File,
    logTag: String,
    logger: UnifiedLogger,
): Boolean =
    try {
        var report: WalCheckpointRule.FoldReport = WalCheckpointRule.NO_ROW
        android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            android.database.DatabaseErrorHandler { /* no-op: temp file only, not production DB */ },
        ).use { db ->
            db.rawQuery(WalCheckpointRule.FOLD_STATEMENT, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    report =
                        WalCheckpointRule.reportOf(
                            intArrayOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)),
                        )
                } else {
                    report = WalCheckpointRule.NO_ROW
                }
            }
        }
        logger.i(
            logTag,
            "WAL checkpoint via temp copy",
            mapOf(
                "busy" to report.busy,
                "log" to report.logFrames,
                "ckpt" to report.checkpointedFrames,
                "walIsEmpty" to report.walIsEmpty,
            ),
        )
        dbFile.exists() && dbFile.length() > 0 && WalCheckpointRule.walIsEmpty(report)
    } catch (e: Exception) {
        logger.w(
            logTag,
            "WAL checkpoint on temp copy failed; preserving WAL to avoid data loss",
            mapOf("error" to (e.message ?: "Unknown"), "exception" to e.javaClass.simpleName),
        )
        false
    }

/**
 * The new database's mandatory life-dimension seed writer.
 *
 * A lambda over [persistNewDatabaseDimensionSetup] and not a move: that writer builds its seed rows with
 * `:core:ui` helpers, so it cannot follow the ViewModel into `:core:presentation`. The behaviour is
 * unchanged - the atomic [io.payanam.domain.repository.LifeDimensionCatalogRepository.replaceAllDimensions]
 * swap, the uniform weight, and the same log lines.
 */
fun appDatabaseDimensionSetupPersister(): DatabaseDimensionSetupPersister = DatabaseDimensionSetupPersister { lifeDimensionCatalogRepository, appSettingsRepository, dimensionInputs ->
    persistNewDatabaseDimensionSetup(
        lifeDimensionCatalogRepository = lifeDimensionCatalogRepository,
        appSettingsRepository = appSettingsRepository,
        dimensionInputs = dimensionInputs,
    )
}
