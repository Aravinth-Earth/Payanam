//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:backing-property-naming")

package io.payanam.database.session

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PayanamDatabase
import io.payanam.database.migration.MIGRATION_16_17
import io.payanam.database.migration.MIGRATION_17_18
import io.payanam.database.migration.MIGRATION_18_19
import io.payanam.database.security.DatabaseEncryptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crypto-gate holder for the Room database session.
 *
 * The Room instance is null at startup. It is only created after explicit user
 * authentication via [openDatabase]. [closeDatabase] nulls the reference (called only
 * before import-replace operations). Inactivity timeout closes the open Room session,
 * wiping the SQLCipher key from process RAM and forcing fresh auth on the next foreground
 * resume without restarting the whole app process.
 *
 * All repositories obtain DAOs via [requireDatabase] rather than holding injected DAO
 * references. Background services (TrackingService, AutoBackupWorker) must NOT call
 * [touch]; only user-initiated operations reset the inactivity timer.
 */
@Singleton
class DatabaseSessionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val encryptionManager: DatabaseEncryptionManager,
    ) {
        private val logger = UnifiedLogger.getInstance()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mutex = Mutex()

        @Volatile private var _db: PayanamDatabase? = null

        @Volatile private var _openPassphrase: String? = null
        private val _isOpen = MutableStateFlow(false)
        val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

        private var inactivityJob: Job? = null
        private var periodicCheckpointJob: Job? = null

        /**
         * Opens Room with SQLCipher using [passphrase]. Idempotent if already open with the
         * same passphrase; callers should not call this redundantly.
         */
        suspend fun openDatabase(passphrase: String): Result<Unit> =
            mutex.withLock {
                logger.i(
                    "DatabaseSessionManager.openDatabase",
                    "DB open requested",
                    mapOf(
                        "alreadyOpen" to (_db != null),
                        "passphraseLength" to passphrase.length,
                        "encryptionEnabled" to encryptionManager.isEncryptionEnabled(),
                    ),
                )
                return runCatching {
                    if (_db != null) {
                        logger.w("DatabaseSessionManager.openDatabase", "DB already open; closing before re-open")
                        _db?.close()
                        _db = null
                    }
                    if (DatabaseHealthChecker.hasDatabaseArtifacts(context)) {
                        val health =
                            DatabaseHealthChecker.checkDatabaseHealth(
                                context = context,
                                sqlCipherPassphrase = passphrase,
                            )
                        if (!health.isHealthy) {
                            error(health.errorMessage ?: "Database cannot be opened safely.")
                        }
                        if (health.needsMigration) {
                            logger.i(
                                "DatabaseSessionManager.openDatabase",
                                "Database requires supported Room migration; proceeding with open",
                                mapOf(
                                    "currentVersion" to health.currentVersion,
                                    "targetVersion" to health.targetVersion,
                                ),
                            )
                        }
                    }
                    val bytes = SQLiteDatabase.getBytes(passphrase.toCharArray())
                    val db =
                        Room
                            .databaseBuilder(
                                context,
                                PayanamDatabase::class.java,
                                PayanamDatabase.DATABASE_NAME,
                            ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                            .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)
                            .openHelperFactory(SupportFactory(bytes))
                            .build()
                    // Force open so SQLCipher validation happens now (throws on wrong passphrase)
                    db.openHelper.writableDatabase
                    _db = db
                    _openPassphrase = passphrase
                    _isOpen.value = true
                    configureWalAutoCheckpoint(db)
                    logger.i(
                        "DatabaseSessionManager.openDatabase",
                        "DB session opened",
                        mapOf("dbName" to PayanamDatabase.DATABASE_NAME),
                    )
                    startInactivityTimer()
                    startPeriodicCheckpointTimer()
                }.onFailure { error ->
                    _db = null
                    _openPassphrase = null
                    _isOpen.value = false
                    logger.e("DatabaseSessionManager.openDatabase", "Failed to open DB session", error)
                }
            }

        /**
         * Closes the Room instance and nulls the reference. Only call this before an
         * import-replace operation that overwrites the DB file on disk.
         */
        fun closeDatabase() {
            val wasOpen = _db != null
            inactivityJob?.cancel()
            inactivityJob = null
            periodicCheckpointJob?.cancel()
            periodicCheckpointJob = null
            val db = _db
            _db = null
            _openPassphrase = null
            _isOpen.value = false
            db?.close()
            logger.i(
                "DatabaseSessionManager.closeDatabase",
                "DB session closed explicitly",
                mapOf("wasOpen" to wasOpen),
            )
        }

        /**
         * Returns the passphrase that was used to open the current DB session.
         * Only valid while the session is open. Used by import/re-keying flows that need
         * the passphrase without going through the Keystore (which requires fresh auth).
         *
         * @throws IllegalStateException if the session is not currently open.
         */
        fun requireOpenPassphrase(): String {
            val passphrase = _openPassphrase
            if (passphrase == null) {
                logger.e(
                    "DatabaseSessionManager.requireOpenPassphrase",
                    "No open passphrase available while requested",
                    IllegalStateException("DB not open"),
                )
                error("DatabaseSessionManager: no passphrase available — DB not open")
            }
            logger.d(
                "DatabaseSessionManager.requireOpenPassphrase",
                "Provided open passphrase handle",
                mapOf("length" to passphrase.length),
            )
            return passphrase
        }

        /**
         * Returns the open [PayanamDatabase] instance.
         *
         * @throws IllegalStateException if the session has not been opened via [openDatabase].
         */
        fun requireDatabase(): PayanamDatabase {
            val db = _db
            if (db == null) {
                logger.e(
                    "DatabaseSessionManager.requireDatabase",
                    "Database requested while session closed",
                    IllegalStateException("DB not open"),
                )
                error("DatabaseSessionManager: DB not open — call openDatabase() after user auth")
            }
            return db
        }

        /**
         * Returns true if the DB session is currently open.
         */
        fun isDbOpen(): Boolean = _db != null

        /**
         * Resets the inactivity timer. Call this from ViewModel write operations that
         * are triggered by direct user interaction. Do NOT call from background services.
         */
        fun touch() {
            if (_db != null) {
                startInactivityTimer()
            } else {
                logger.d("DatabaseSessionManager.touch", "Session touch ignored because DB is closed")
            }
        }

        /**
         * Flushes WAL journal to the main DB file. Call on lifecycle boundaries
         * (Activity.onStop, Service.onDestroy, pre-kill) to reduce data-loss window.
         * No-op if the DB session is not open.
         */
        fun checkpoint() {
            val db = _db ?: return
            try {
                val cursor = db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
                var busy = -1
                var logPages = -1
                var checkpointedPages = -1
                if (cursor.moveToFirst()) {
                    busy = cursor.getInt(0)
                    logPages = cursor.getInt(1)
                    checkpointedPages = cursor.getInt(2)
                }
                cursor.close()
                logger.d(
                    "DatabaseSessionManager.checkpoint",
                    "WAL checkpoint completed",
                    mapOf("busy" to busy, "logPages" to logPages, "checkpointedPages" to checkpointedPages),
                )
            } catch (e: Exception) {
                logger.e("DatabaseSessionManager.checkpoint", "WAL checkpoint failed", e)
            }
        }

        /**
         * TEST ONLY: Injects a pre-built in-memory [PayanamDatabase] as the active session.
         * Bypasses SQLCipher auth; use only in Robolectric / unit-test setup.
         */
        @VisibleForTesting
        internal fun openWithTestDatabase(db: PayanamDatabase) {
            _db = db
            _openPassphrase = "test"
            _isOpen.value = true
        }

        // -------- private --------

        private fun startInactivityTimer() {
            val wasInactive = inactivityJob == null
            inactivityJob?.cancel()
            val timeoutMs = encryptionManager.getSessionTimeoutMinutes() * 60_000L
            inactivityJob =
                scope.launch {
                    if (wasInactive) {
                        logger.d(
                            "DatabaseSessionManager.inactivityTimer",
                            "Inactivity timer armed",
                            mapOf("timeoutMinutes" to encryptionManager.getSessionTimeoutMinutes()),
                        )
                    }
                    delay(timeoutMs)
                    logger.i(
                        "DatabaseSessionManager.inactivityTimer",
                        "Inactivity timeout reached; closing DB session for silent re-auth",
                    )
                    closeDatabaseForTimeout()
                }
        }

        private fun startPeriodicCheckpointTimer() {
            periodicCheckpointJob?.cancel()
            periodicCheckpointJob =
                scope.launch {
                    logger.d(
                        "DatabaseSessionManager.periodicCheckpoint",
                        "Periodic checkpoint timer started",
                        mapOf("intervalMinutes" to PERIODIC_CHECKPOINT_INTERVAL_MINUTES),
                    )
                    while (isActive) {
                        delay(PERIODIC_CHECKPOINT_INTERVAL_MS)
                        if (_db == null) return@launch
                        checkpoint()
                    }
                }
        }

        private fun configureWalAutoCheckpoint(db: PayanamDatabase) {
            try {
                val cursor =
                    db.openHelper.writableDatabase.query(
                        "PRAGMA wal_autocheckpoint=$WAL_AUTO_CHECKPOINT_PAGES",
                    )
                val effectivePages =
                    if (cursor.moveToFirst()) {
                        cursor.getInt(0)
                    } else {
                        WAL_AUTO_CHECKPOINT_PAGES
                    }
                cursor.close()
                logger.i(
                    "DatabaseSessionManager.configureWalAutoCheckpoint",
                    "Configured WAL auto-checkpoint",
                    mapOf("pages" to effectivePages),
                )
            } catch (e: Exception) {
                logger.w(
                    "DatabaseSessionManager.configureWalAutoCheckpoint",
                    "Failed to configure WAL auto-checkpoint; using engine default",
                    mapOf("error" to (e.message ?: "Unknown error")),
                )
            }
        }

        private fun closeDatabaseForTimeout() {
            logger.i("DatabaseSessionManager.closeDatabaseForTimeout", "Closing DB session after inactivity timeout")
            CrashSafeBreadcrumbs.record(
                context = context,
                source = "DatabaseSessionManager.closeDatabaseForTimeout",
                stage = "started",
            )
            checkpoint()

            try {
                val prefs = context.getSharedPreferences("payanam_process_lifecycle", Context.MODE_PRIVATE)
                prefs
                    .edit()
                    .putString("last_exit_reason", "inactivity_timeout")
                    .putLong("last_exit_timestamp", System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                logger.e("DatabaseSessionManager.closeDatabaseForTimeout", "Failed to write timeout sentinel", e)
            }

            val db = _db
            inactivityJob = null
            periodicCheckpointJob?.cancel()
            periodicCheckpointJob = null
            _db = null
            _openPassphrase = null
            _isOpen.value = false
            db?.close()

            CrashSafeBreadcrumbs.record(
                context = context,
                source = "DatabaseSessionManager.closeDatabaseForTimeout",
                stage = "session_closed_for_timeout",
            )
        }

        private companion object {
            private const val WAL_AUTO_CHECKPOINT_PAGES = 200
            private const val PERIODIC_CHECKPOINT_INTERVAL_MINUTES = 30L
            private const val PERIODIC_CHECKPOINT_INTERVAL_MS =
                PERIODIC_CHECKPOINT_INTERVAL_MINUTES * 60_000L
        }
    }
