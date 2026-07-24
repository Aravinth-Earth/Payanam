//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopDatabaseStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `ensure initialized creates desktop database artifact`() {
        val store = DesktopDatabaseStore(databaseDirectory = temporaryFolder.newFolder("desktop-db").toPath())

        val snapshot = store.ensureInitialized()

        assertThat(snapshot.hasArtifacts).isTrue()
        assertThat(snapshot.initCompleted).isTrue()
        assertThat(Files.exists(store.getDatabaseFilePath())).isTrue()
    }
}
