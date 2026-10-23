//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.domain.assistant.AssistantStoredValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Exercises [AssistantSettingsStore] against a REAL Room database (in memory) so the
 * reconfigure-flag lifecycle and the single-writer discipline are asserted on the
 * database rows themselves, not on a mock.
 */
@RunWith(RobolectricTestRunner::class)
class AssistantSettingsStoreTest {
    private lateinit var db: PayanamDatabase
    private lateinit var store: AssistantSettingsStore

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        db =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        val sessionManager = mock<DatabaseSessionManager>()
        whenever(sessionManager.requireDatabase()).thenReturn(db)
        wheneverBlocking { sessionManager.inTransaction<Any?>(any<suspend () -> Any?>()) }.thenAnswer { invocation ->
            val block = invocation.getArgument<suspend () -> Any?>(0)
            runBlocking { db.withTransaction { block() } }
        }
        store = AssistantSettingsStore(sessionManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun raw(key: String): String? = runBlocking { db.appSettingsDao().getSetting(key)?.value }

    /** Seeds a configured assistant (stored key + model) through the normal [AssistantSettingsStore.saveAll] path. */
    private suspend fun seedConfigured() {
        store.saveAll("test-key-1", AssistantSettings(model = "m1"))
    }

    @Test
    fun `beginReconfigure sets the flag and leaves the key and model rows untouched`() = runBlocking {
        seedConfigured()
        store.beginReconfigure()
        assertEquals("true", raw("assistant.setup_required"))
        assertEquals("test-key-1", raw("assistant.api_key"))
        assertEquals("m1", raw("assistant.model"))
    }

    @Test
    fun `saveAll clears the flag unconditionally, including the same-key path`() = runBlocking {
        seedConfigured()
        store.beginReconfigure()
        store.saveAll(null, AssistantSettings(model = "m2"))
        assertEquals("false", raw("assistant.setup_required"))
        assertEquals("m2", raw("assistant.model"))
        assertEquals("test-key-1", raw("assistant.api_key"))
    }

    @Test
    fun `neutral save never writes the model row and never clears the flag`() = runBlocking {
        seedConfigured()
        store.beginReconfigure()
        store.save(AssistantSettings(model = "m2"))
        assertEquals("m1", raw("assistant.model"))
        assertEquals("true", raw("assistant.setup_required"))
    }

    @Test
    fun `clearProviderConfiguration deletes key, model and flag in one transaction`() = runBlocking {
        seedConfigured()
        store.beginReconfigure()
        store.clearProviderConfiguration()
        assertNull(raw("assistant.api_key"))
        assertNull(raw("assistant.model"))
        assertNull(raw("assistant.setup_required"))
    }

    @Test
    fun `loadConfig returns the raw model and the flag while reconfigure is pending`() = runBlocking {
        seedConfigured()
        store.beginReconfigure()
        val config = store.loadConfig()
        assertEquals("test-key-1", config.apiKey)
        assertEquals("m1", config.settings.model)
        assertTrue(config.setupRequired)
    }

    @Test
    fun `loadConfig maps a missing flag row to false`() = runBlocking {
        seedConfigured()
        assertFalse(store.loadConfig().setupRequired)
    }

    @Test
    fun `an unreadable stored row loads the default but is LEFT IN STORAGE`() = runBlocking {
        // The silent-wrong case: a stored value this build cannot parse must not be replaced by
        // the default, because the stored text is the only record of what the user chose.
        seedConfigured()
        seedRaw("assistant.max_rows", "not-a-number")
        val config = store.loadConfig()
        assertEquals("the load still needs a usable value", 500, config.settings.maxRows)
        assertEquals("but the stored row must survive the load", "not-a-number", raw("assistant.max_rows"))
    }

    @Test
    fun `an unreadable stored row is REPORTED with its key in the log`() = runBlocking {
        seedConfigured()
        seedRaw("assistant.max_rows", "not-a-number")
        store.loadConfig()
        val warnings = waitForLog { text -> text.contains("unreadable") }
        assertTrue("an unreadable stored row must reach the log", warnings.contains("assistant.max_rows"))
        assertFalse(
            "the stored VALUE must never be logged",
            warnings.contains("not-a-number"),
        )
    }

    @Test
    fun `a missing stored row is NOT reported as unreadable`() = runBlocking {
        seedConfigured()
        store.loadConfig()
        val warnings = waitForLog { text -> text.contains("unreadable") }
        assertFalse(
            "a first run is benign and must not be reported",
            warnings.contains("unreadable"),
        )
    }

    /**
     * Reads the logger's session file until [predicate] holds.
     *
     * A WARN is only buffered until the logger's own threshold is reached, so each poll asks it to
     * flush and then gives its coroutine a moment — without the flush a single warning never
     * reaches the file and this would assert against an empty log for the wrong reason.
     */
    private fun waitForLog(predicate: (String) -> Boolean): String {
        repeat(40) {
            UnifiedLogger.getInstance().flush()
            val text = UnifiedLogger.getInstance().getRecentLogs(400)
            if (predicate(text)) return text
            Thread.sleep(50)
        }
        return UnifiedLogger.getInstance().getRecentLogs(400)
    }

    /** Writes a row straight through the DAO, bypassing the sanitizing writer. */
    private suspend fun seedRaw(key: String, value: String) {
        db.appSettingsDao().insertSetting(
            AppSettingEntity(key = key, value = value, updatedAt = Instant.now().toString()),
        )
    }
}
