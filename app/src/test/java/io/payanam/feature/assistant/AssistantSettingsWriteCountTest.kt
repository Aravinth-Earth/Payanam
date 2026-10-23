//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.assistant.AssistantSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
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
        store = AssistantSettingsStore(sessionManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the logged row count equals the rows a save actually writes`() =
        runBlocking {
            store.save(AssistantSettings())
            assertEquals(
                "the save log quotes STORED_SETTING_ROWS; it must equal the rows a save writes",
                STORED_SETTING_ROWS,
                db.appSettingsDao().getAllSettings().first().size,
            )
        }
}
