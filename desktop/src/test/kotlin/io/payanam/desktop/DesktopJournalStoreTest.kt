//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

class DesktopJournalStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `load state seeds journal file when missing`() {
        val store =
            DesktopJournalStore(
                databaseDirectory = temporaryFolder.newFolder("desktop-journal-seed").toPath(),
                today = { LocalDate.parse("2026-04-02") },
            )

        val state = store.loadState()

        assertThat(state.errorMessage).isNull()
        assertThat(state.selectedDateIso).isEqualTo("2026-04-02")
        assertThat(state.snapshot.days).isEmpty()
        assertThat(Files.exists(store.getJournalFilePath())).isTrue()
    }

    @Test
    fun `save responses and select date persist journal snapshot`() {
        val databaseDirectory = temporaryFolder.newFolder("desktop-journal-crud").toPath()
        var currentNow = LocalDateTime.parse("2026-04-02T11:00:00")
        val persistenceDatabase = DesktopPersistenceDatabase(databaseDirectory = databaseDirectory)
        val store =
            DesktopJournalStore(
                persistenceDatabase = persistenceDatabase,
                today = { LocalDate.parse("2026-04-03") },
                now = { currentNow },
            )

        var state = store.loadState()
        state = store.selectDate(state, "2026-04-02")
        state = store.saveOverallResponse(state, "gratitude", "Family dinner")
        currentNow = LocalDateTime.parse("2026-04-02T11:15:00")
        state = store.saveDimensionResponse(state, "dim_learning_growth", "progress", "Read two chapters")

        assertThat(state.selectedDateIso).isEqualTo("2026-04-02")
        assertThat(state.lastSavedDateIso).isEqualTo("2026-04-02")
        assertThat(state.snapshot.days).hasSize(1)
        assertThat(
            state.snapshot.days
                .single()
                .overallResponses["gratitude"],
        ).isEqualTo("Family dinner")
        assertThat(
            state.snapshot.days
                .single()
                .dimensionResponses["dim_learning_growth"]
                ?.get("progress"),
        ).isEqualTo("Read two chapters")
        assertThat(persistenceDatabase.readEntry(DesktopJournalStore.STATE_ENTRY_KEY)).contains("Family dinner")
    }

    @Test
    fun `select date does not move past today`() {
        val store =
            DesktopJournalStore(
                databaseDirectory = temporaryFolder.newFolder("desktop-journal-bounds").toPath(),
                today = { LocalDate.parse("2026-04-02") },
            )

        val state = store.selectDate(store.loadState(), "2026-04-08")

        assertThat(state.selectedDateIso).isEqualTo("2026-04-02")
    }
}
