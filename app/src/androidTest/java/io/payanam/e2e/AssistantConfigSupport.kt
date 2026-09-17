//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.feature.assistant.AssistantSettings
import io.payanam.feature.assistant.AssistantSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File
import java.time.Instant

/** SQLite companion-file suffixes that ride next to the canonical database file. */
private val COMPANION_SUFFIXES = listOf("-wal", "-shm", "-journal")

/**
 * Deletes the canonical database file and its SQLite companion files.
 * Shared by e2e seeds and import tests, which both need a pristine canonical path.
 */
fun deleteCanonicalDatabaseArtifacts(context: Context) {
    val basePath = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath
    COMPANION_SUFFIXES.forEach { suffix -> File(basePath + suffix).delete() }
    File(basePath).delete()
}

/**
 * Test-side plumbing for the assistant reconfigure journey.
 *
 * The journey starts from a CONFIGURED assistant (stored key + model), which the UI cannot reach
 * without a live provider key — so the fixture is created through the app's own managers BEFORE
 * the activity launches (the canonical-path creation pattern proven by `ImportSeamTest`), and the
 * journey reaches it through the normal unlock gate afterwards. The rule is self-contained: it
 * resets encryption state and clears the canonical artifacts first, so it does not depend on the
 * runner's `-KeepInstalled:$false` convention alone.
 */
class AssistantSeedRule : ExternalResource() {

    override fun before() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        UnifiedLogger.setDebugLoggingEnabled(true)
        deleteCanonicalDatabaseArtifacts(context)
        val encryptionManager = DatabaseEncryptionManager(context)
        encryptionManager.resetEncryptionState()
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        try {
            check(encryptionManager.configurePassphrase(FreshSetup.TEST_DB_PASSPHRASE)) {
                "seed: configurePassphrase failed"
            }
            runBlocking {
                check(sessionManager.openDatabase(FreshSetup.TEST_DB_PASSPHRASE).isSuccess) {
                    "seed: openDatabase failed"
                }
                val store = AssistantSettingsStore(sessionManager)
                store.saveAll(SEED_KEY, AssistantSettings(model = SEED_MODEL, noticeShown = true))
                // Without this row MainActivity routes the seeded database to DatabaseInit,
                // whose create path deletes DB files and would wipe the seed.
                sessionManager.requireDatabase().appSettingsDao().insertSetting(
                    AppSettingEntity(
                        key = DATABASE_INIT_COMPLETED,
                        value = "true",
                        updatedAt = Instant.now().toString(),
                    ),
                )
            }
        } finally {
            runCatching { sessionManager.closeDatabase() }
        }
    }

    companion object {
        /** Shaped like a key but deliberately not one. */
        const val SEED_KEY = "e2e-seed-not-a-real-key"

        /** Model id used by the seeded configured state. */
        const val SEED_MODEL = "m1"
        private const val DATABASE_INIT_COMPLETED = "database_init_completed"
    }
}
