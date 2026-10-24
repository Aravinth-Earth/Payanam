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
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantProvider
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
    fun `clearProviderConfiguration deletes key, model, flag, provider and address in one transaction`() = runBlocking {
        seedConfigured()
        store.beginReconfigure()
        store.saveConnection(AssistantProvider.OPENROUTER, "https://openrouter.ai/api/v1")
        store.clearProviderConfiguration()
        assertNull(raw("assistant.api_key"))
        assertNull(raw("assistant.model"))
        assertNull(raw("assistant.setup_required"))
        // The endpoint pair goes too: a stale address would hand the next configuration a host the
        // user had already removed.
        assertNull(raw("assistant.provider"))
        assertNull(raw("assistant.base_url"))
    }

    // --- the stored endpoint ---------------------------------------------------------------------

    @Test
    fun `an install with neither endpoint row loads the endpoint it already used`() = runBlocking {
        // THE UPGRADE GUARANTEE: builds before the provider list stored neither row, and that user
        // must keep exactly the endpoint they already had.
        val connection = store.loadConfig().connection
        assertEquals(AssistantProvider.OPENCODE, connection.provider)
        assertEquals(AssistantDefaults.BASE_URL, connection.baseUrl)
        // The upgrade path invents nothing and writes nothing: neither endpoint row appears.
        assertNull(raw("assistant.provider"))
        assertNull(raw("assistant.base_url"))
    }

    @Test
    fun `a saved provider and address load back as stored`() = runBlocking {
        store.saveConnection(AssistantProvider.OPENROUTER, "https://openrouter.ai/api/v1")
        val connection = store.loadConfig().connection
        assertEquals(AssistantProvider.OPENROUTER, connection.provider)
        assertEquals("https://openrouter.ai/api/v1", connection.baseUrl)
        assertEquals("the row holds the enum name", "OPENROUTER", raw("assistant.provider"))
    }

    @Test
    fun `a custom address is kept verbatim and not replaced by a provider default`() = runBlocking {
        // A trailing slash AND a non-default port: neither is normalised, and this provider has no
        // default that could stand in for the address the user typed.
        store.saveConnection(AssistantProvider.CUSTOM_LOCAL, "http://192.168.1.20:8080/v1/")
        val connection = store.loadConfig().connection
        assertEquals(AssistantProvider.CUSTOM_LOCAL, connection.provider)
        assertEquals("http://192.168.1.20:8080/v1/", connection.baseUrl)
        assertEquals("http://192.168.1.20:8080/v1/", raw("assistant.base_url"))
    }

    @Test
    fun `an unrecognised stored provider leaves the assistant unset up and is reported`() = runBlocking {
        seedConfigured()
        seedRaw("assistant.provider", "GEMINI")
        val marker = markLog()
        val config = store.loadConfig()
        // A name this build cannot match means we cannot say which host the stored key was entered
        // for, so nothing from that configuration is used: no provider, no address, no key.
        assertNull(config.connection.provider)
        assertEquals("the address is not guessed either", "", config.connection.baseUrl)
        assertEquals("the stored key is never offered as usable", "", config.apiKey)
        assertTrue("the user must choose again", config.setupRequired)
        assertEquals("the stored text is left in place", "GEMINI", raw("assistant.provider"))
        // Unmatched is not absent: refusing must never be silent.
        val warnings = waitForLogAfter(marker) { text -> text.contains("unreadable") }
        assertTrue("the refusal must reach the log", warnings.contains("assistant.provider"))
        assertFalse("the stored VALUE must never be logged", warnings.contains("GEMINI"))
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
        val marker = markLog()
        store.loadConfig()
        val warnings = waitForLogAfter(marker) { text -> text.contains("unreadable") }
        assertTrue("an unreadable stored row must reach the log", warnings.contains("assistant.max_rows"))
        assertFalse(
            "the stored VALUE must never be logged",
            warnings.contains("not-a-number"),
        )
    }

    @Test
    fun `a missing stored row is NOT reported as unreadable`() = runBlocking {
        seedConfigured()
        val marker = markLog()
        store.loadConfig()
        val warnings = waitForLogAfter(marker) { text -> text.contains("unreadable") }
        assertFalse(
            "a first run is benign and must not be reported",
            warnings.contains("unreadable"),
        )
    }

    /**
     * Writes a marker line and returns its text, so an assertion can ignore everything before it.
     *
     * The logger is a process-wide singleton: its recent lines carry whatever the earlier tests in
     * this class wrote. A predicate over the whole buffer can therefore be satisfied by a STALE line
     * from another test, which made these assertions depend on test order — a new test that reported
     * an unreadable provider row was enough to break an older test about an unreadable max-rows row.
     */
    private fun markLog(): String {
        val marker = "test-marker-${System.nanoTime()}"
        UnifiedLogger.getInstance().i("AssistantSettingsStoreTest", marker, emptyMap())
        return marker
    }

    /** Polls the log until [predicate] holds for the text written after [marker]. */
    private fun waitForLogAfter(
        marker: String,
        predicate: (String) -> Boolean,
    ): String {
        repeat(40) {
            UnifiedLogger.getInstance().flush()
            val after = logsAfter(marker)
            if (predicate(after)) return after
            Thread.sleep(50)
        }
        return logsAfter(marker)
    }

    private fun logsAfter(marker: String): String {
        val text = UnifiedLogger.getInstance().getRecentLogs(400)
        val index = text.lastIndexOf(marker)
        return if (index >= 0) text.substring(index + marker.length) else ""
    }

    /** Writes a row straight through the DAO, bypassing the sanitizing writer. */
    private suspend fun seedRaw(key: String, value: String) {
        db.appSettingsDao().insertSetting(
            AppSettingEntity(key = key, value = value, updatedAt = Instant.now().toString()),
        )
    }
}
