//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * DesktopBootstrapStoreTest.
 */
class DesktopBootstrapStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `ensure snapshot creates persisted desktop bootstrap file`() {
        val bootstrapDirectory = temporaryFolder.newFolder("bootstrap-default").toPath()
        val persistenceDatabase =
            DesktopPersistenceDatabase(
                databaseDirectory = bootstrapDirectory,
                bootstrapDirectory = bootstrapDirectory,
            )
        val store = DesktopBootstrapStore(persistenceDatabase = persistenceDatabase, clock = { 1234L })

        val snapshot = store.ensureSnapshot()

        assertThat(snapshot).isEqualTo(DesktopBootstrapSnapshot())
        assertThat(Files.exists(store.getBootstrapFilePath())).isTrue()
        assertThat(persistenceDatabase.readEntry(DesktopBootstrapStore.STATE_ENTRY_KEY)).contains("databaseLifecycleReady=false")
    }

    @Test
    fun `record startup completed persists timestamp and route`() {
        val bootstrapDirectory = temporaryFolder.newFolder("bootstrap-startup").toPath()
        val store = DesktopBootstrapStore(bootstrapDirectory = bootstrapDirectory, clock = { 987654321L })
        store.ensureSnapshot()

        val updatedSnapshot = store.recordStartupCompleted(DesktopTopLevelRoute.NOTES)
        val reloadedSnapshot = store.loadSnapshot()

        assertThat(updatedSnapshot.lastStartupCompletedAtEpochMillis).isEqualTo(987654321L)
        assertThat(updatedSnapshot.lastLaunchRouteStorageKey).isEqualTo("notes")
        assertThat(reloadedSnapshot).isEqualTo(updatedSnapshot)
    }

    @Test
    fun `update database lifecycle ready persists latest readiness state`() {
        val bootstrapDirectory = temporaryFolder.newFolder("bootstrap-database-ready").toPath()
        val store = DesktopBootstrapStore(bootstrapDirectory = bootstrapDirectory, clock = { 13579L })
        store.ensureSnapshot()

        val readySnapshot = store.updateDatabaseLifecycleReady(isReady = true)
        val reloadedReadySnapshot = store.loadSnapshot()
        val resetSnapshot = store.updateDatabaseLifecycleReady(isReady = false)
        val reloadedResetSnapshot = store.loadSnapshot()

        assertThat(readySnapshot.databaseLifecycleReady).isTrue()
        assertThat(reloadedReadySnapshot.databaseLifecycleReady).isTrue()
        assertThat(resetSnapshot.databaseLifecycleReady).isFalse()
        assertThat(reloadedResetSnapshot.databaseLifecycleReady).isFalse()
    }
}
