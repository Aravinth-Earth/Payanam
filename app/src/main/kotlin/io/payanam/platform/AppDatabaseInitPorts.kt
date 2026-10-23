//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
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
 *
 * The read goes through androidx.sqlite's [SupportSQLiteOpenHelper] with a
 * [FrameworkSQLiteOpenHelperFactory] rather than the platform's direct open API:
 * the framework helper is the same path Room uses, so the file is opened with the same
 * configuration the app's own database layer expects.
 */
fun appDatabaseInitFlagReader(context: Context): DatabaseInitFlagReader = DatabaseInitFlagReader { file, key ->
    val helper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build(),
    )
    helper.readableDatabase.use { db ->
        db.query("SELECT value FROM app_settings WHERE key = ? LIMIT 1", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }
}

/**
 * The one platform-specific call the promoted WAL orchestration could not keep: running
 * `PRAGMA wal_checkpoint(TRUNCATE)` on a temp copy needs a Room handle. The shared code decides
 * WHETHER the fold may delete the production `-wal`; this only performs it.
 */
fun appDatabaseInitWalConsolidator(context: Context, logger: UnifiedLogger): DatabaseInitWalConsolidator = DatabaseInitWalConsolidator { file, logTag ->
    consolidateWalWithFramework(context = context, dbFile = file, logTag = logTag, logger = logger)
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
 * The fold runs through androidx.sqlite's [SupportSQLiteOpenHelper] with a
 * [FrameworkSQLiteOpenHelperFactory] rather than the platform's direct open API:
 * the framework helper is the same path Room uses, so the temp copy is opened with the same
 * configuration the app's own database layer expects. The old no-op error handler is gone
 * with the direct API; the rationale for allowing a throw on the throwaway file is unchanged —
 * the caller copies first, so a native-layer reaction to a malformed WAL hits only the temp copy,
 * never the user's database.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: a bad WAL can throw anything from the native layer
private fun consolidateWalWithFramework(
    context: Context,
    dbFile: File,
    logTag: String,
    logger: UnifiedLogger,
): Boolean =
    try {
        var report: WalCheckpointRule.FoldReport = WalCheckpointRule.NO_ROW
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.query(WalCheckpointRule.FOLD_STATEMENT).use { cursor ->
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
