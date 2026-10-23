//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.port.formatBackupDisplayTimestamp
import io.payanam.shared.database.PayanamDatabaseSchema
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * Tests for Android's backup ports.
 *
 * The status port is asserted against the REAL store under Robolectric and through its live flow — a
 * delegation that copied the snapshot instead of exposing the flow would fail the second assertion.
 * The coordinator port is asserted by CALLING IT through a real coordinator over a real database
 * file: the whole backup runs (checkpoint skip, verified snapshot, copy, status recording), so the
 * result and the recorded status the port produces are the platform's own, not a stub's.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidBackupPortsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        // The store resolves the app-wide logger at construction; tests initialize it like the app does.
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    fun `the status port exposes the store's live snapshot and its formatter`() {
        val store = io.payanam.service.BackupStatusStore(context)
        val port = AndroidBackupStatusPort(store)

        assertThat(port.status.value).isEqualTo(store.status.value)

        // Recorded through the concrete store AFTER the port was built: a copy would not see it.
        store.recordSuccess(1_700_000_000_000L)
        assertThat(port.status.value.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
        assertThat(port.formatBackupTimestamp(0L)).isEqualTo(formatBackupDisplayTimestamp(0L))
    }

    @Test
    fun `the backup port runs the platform coordinator's real backup`() = runBlocking {
        val databaseFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        // A real, readable database file, so the coordinator's verified-snapshot path can complete.
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database -> database.version = 1 }

        val store = io.payanam.service.BackupStatusStore(context)
        val coordinator =
            io.payanam.service.DatabaseBackupCoordinator(
                context,
                mock<io.payanam.database.session.DatabaseSessionManager>(),
                store,
            )
        val port = AndroidDatabaseBackupPort(coordinator)

        val result = port.backupToAppBackupDirectory(BackupTrigger.MANUAL)

        // The coordinator's own result and its own recorded status, reached only through the port.
        assertThat(result.attemptsUsed).isEqualTo(1)
        assertThat(java.io.File(result.destinationPath).exists()).isTrue()
        assertThat(store.status.value.lastSuccessAtMillis).isGreaterThan(0L)
    }
}
