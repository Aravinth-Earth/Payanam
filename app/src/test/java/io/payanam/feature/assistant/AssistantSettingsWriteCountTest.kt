//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.assistant.AssistantProvider
import io.payanam.domain.assistant.AssistantSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner

/**
 * [STORED_SETTING_ROWS] is quoted in the save log lines, so it must equal what a save actually
 * writes. Counting the ROWS on a real database is the only way that can be checked: the constant
 * used to be a number in a log line that nothing verified, and it was already wrong.
 */
@RunWith(RobolectricTestRunner::class)
class AssistantSettingsWriteCountTest {
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

    @Test
    fun `the logged row count equals the rows a save actually writes`() = runBlocking {
        store.save(AssistantSettings())
        assertEquals(
            "the save log quotes STORED_SETTING_ROWS; it must equal the rows a save writes",
            STORED_SETTING_ROWS,
            db.appSettingsDao().getAllSettings().first().size,
        )
    }

    @Test
    fun `saveConnection writes the provider and address rows and nothing else`() = runBlocking {
        store.saveAll("test-key-1", AssistantSettings(model = "m1", maxRows = 250))
        val before = db.appSettingsDao().getAllSettings().first().associate { it.key to it.value }

        store.saveConnection(AssistantProvider.OPENAI, "https://api.openai.com/v1")

        val after = db.appSettingsDao().getAllSettings().first().associate { it.key to it.value }
        assertEquals("exactly the two endpoint rows are added", before.size + 2, after.size)
        assertEquals("OPENAI", after["assistant.provider"])
        assertEquals("https://api.openai.com/v1", after["assistant.base_url"])
        // The single-writer discipline: the key, the model, every limit and the flag are untouched.
        assertEquals(
            "the connection write must not touch any other row",
            before,
            after - setOf("assistant.provider", "assistant.base_url"),
        )
    }
}
