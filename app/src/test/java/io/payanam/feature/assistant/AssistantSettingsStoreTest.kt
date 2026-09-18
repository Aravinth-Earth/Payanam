//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

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
}
