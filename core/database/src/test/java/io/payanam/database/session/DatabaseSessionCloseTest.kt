//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The session's close contract, over a real Room handle.
 *
 * Every close path now tears the state down under one lock, so these pin what a caller may assume after
 * one: the session reports closed, no handle is reachable, and a second close is not an error.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseSessionCloseTest {
    private lateinit var context: Context
    private lateinit var database: PayanamDatabase
    private lateinit var manager: DatabaseSessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        manager = DatabaseSessionManager(context, DatabaseEncryptionManager(context))
        manager.openWithTestDatabase(database)
    }

    @After
    fun tearDown() {
        manager.closeDatabase()
    }

    @Test
    fun `a session is open after it is installed`() {
        assertThat(manager.isDbOpen()).isTrue()
        assertThat(manager.isOpen.value).isTrue()
    }

    @Test
    fun `a close makes the handle unreachable and reports the session closed`() {
        manager.closeDatabase()

        assertThat(manager.isDbOpen()).isFalse()
        assertThat(manager.isOpen.value).isFalse()
        assertThat(runCatching { manager.requireDatabase() }.isFailure).isTrue()
        assertThat(runCatching { manager.requireOpenPassphrase() }.isFailure).isTrue()
    }

    @Test
    fun `a second close is a no-op rather than an error`() {
        manager.closeDatabase()

        val second = runCatching { manager.closeDatabase() }

        assertThat(second.isSuccess).isTrue()
        assertThat(manager.isDbOpen()).isFalse()
    }

    @Test
    fun `an in-process swap withholds the closed state until it reopens`() {
        manager.beginInProcessSwap()
        manager.closeDatabase()

        // The handle is gone, but the UI signal stays open: the screen that owns the import prompt must
        // not be replaced by the unlock gate mid-swap.
        assertThat(manager.isDbOpen()).isFalse()
        assertThat(manager.isOpen.value).isTrue()

        manager.endInProcessSwap(sessionOpen = true)

        assertThat(manager.isOpen.value).isTrue()
    }

    @Test
    fun `a failed in-process swap publishes the closed state`() {
        manager.beginInProcessSwap()
        manager.closeDatabase()
        manager.endInProcessSwap(sessionOpen = false)

        assertThat(manager.isOpen.value).isFalse()
    }
}
