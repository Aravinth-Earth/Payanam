//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.tasks.DesktopTaskCatalogSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * DesktopTaskCatalogStoreTest.
 */
class DesktopTaskCatalogStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `load state seeds catalog when file is missing`() {
        val store = DesktopTaskCatalogStore(databaseDirectory = temporaryFolder.newFolder("task-catalog-seed").toPath())

        val state = store.loadState()

        assertThat(state.errorMessage).isNull()
        assertThat(state.catalog.tasks).isNotEmpty()
        assertThat(Files.exists(store.getCatalogFilePath())).isTrue()
    }

    @Test
    fun `load state exposes error when catalog cannot be decoded`() {
        val databaseDirectory = temporaryFolder.newFolder("task-catalog-invalid").toPath()
        val persistenceDatabase = DesktopPersistenceDatabase(databaseDirectory = databaseDirectory)
        val store = DesktopTaskCatalogStore(persistenceDatabase = persistenceDatabase)
        persistenceDatabase.writeEntry(DesktopTaskCatalogStore.STATE_ENTRY_KEY, "{not-json")

        val state = store.loadState()

        assertThat(state.catalog).isEqualTo(DesktopTaskCatalogSnapshot())
        assertThat(state.errorMessage).contains("Unexpected JSON token")
    }
}
