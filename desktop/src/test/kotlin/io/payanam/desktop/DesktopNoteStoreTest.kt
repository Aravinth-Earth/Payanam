//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime

class DesktopNoteStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `load state seeds notes file when missing`() {
        val store = DesktopNoteStore(databaseDirectory = temporaryFolder.newFolder("desktop-notes-seed").toPath())

        val state = store.loadState()

        assertThat(state.errorMessage).isNull()
        assertThat(state.snapshot.notes).isEmpty()
        assertThat(Files.exists(store.getNotesFilePath())).isTrue()
    }

    @Test
    fun `create update and delete note persists snapshot`() {
        val databaseDirectory = temporaryFolder.newFolder("desktop-notes-crud").toPath()
        var currentTime = LocalDateTime.parse("2026-04-02T10:00:00")
        val persistenceDatabase = DesktopPersistenceDatabase(databaseDirectory = databaseDirectory)
        val store =
            DesktopNoteStore(
                persistenceDatabase = persistenceDatabase,
                now = { currentTime },
                nextId = { "note-1" },
            )

        val created =
            store.createNote(
                title = "First note",
                details = "Details",
                dimensionId = "dim_learning_growth",
                dimensionLabel = "Learning & Growth",
                tags = listOf("study", "deep"),
            )
        currentTime = LocalDateTime.parse("2026-04-02T10:15:00")
        val updated =
            store.updateNote(
                noteId = "note-1",
                title = "Updated note",
                details = "",
                dimensionId = "dim_mental_health",
                dimensionLabel = "Mental Health",
                tags = listOf("clarity"),
            )
        val deleted = store.deleteNote("note-1")

        assertThat(created.snapshot.notes).hasSize(1)
        assertThat(
            created.snapshot.notes
                .single()
                .title,
        ).isEqualTo("First note")
        assertThat(
            created.snapshot.notes
                .single()
                .tags,
        ).containsExactly("study", "deep").inOrder()

        assertThat(updated.errorMessage).isNull()
        assertThat(
            updated.snapshot.notes
                .single()
                .title,
        ).isEqualTo("Updated note")
        assertThat(
            updated.snapshot.notes
                .single()
                .details,
        ).isNull()
        assertThat(
            updated.snapshot.notes
                .single()
                .dimensionId,
        ).isEqualTo("dim_mental_health")
        assertThat(
            updated.snapshot.notes
                .single()
                .tags,
        ).containsExactly("clarity")

        assertThat(deleted.snapshot.notes).isEmpty()
        assertThat(persistenceDatabase.readEntry(DesktopNoteStore.STATE_ENTRY_KEY)).isEqualTo("{}")
    }

    @Test
    fun `load state exposes error when notes file cannot be decoded`() {
        val databaseDirectory = temporaryFolder.newFolder("desktop-notes-invalid").toPath()
        val persistenceDatabase = DesktopPersistenceDatabase(databaseDirectory = databaseDirectory)
        val store = DesktopNoteStore(persistenceDatabase = persistenceDatabase)
        persistenceDatabase.writeEntry(DesktopNoteStore.STATE_ENTRY_KEY, "{not-json")

        val state = store.loadState()

        assertThat(state.snapshot.notes).isEmpty()
        assertThat(state.errorMessage).contains("Unexpected JSON token")
    }
}
