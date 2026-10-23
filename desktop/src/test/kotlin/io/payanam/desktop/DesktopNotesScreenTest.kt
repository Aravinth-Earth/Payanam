//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import io.payanam.core.ui.notes.NotesScreen
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.model.Tag
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.presentation.notes.NotesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The shared Notes screen, rendered for real from the DESKTOP module's own test task.
 *
 * The same assertions exist in `:core:ui`'s `desktopTest` source set, and they are green there — but no build
 * gate in this repo runs that task, while this module's `:desktop:test` is what every desktop change is checked
 * by. A test the build never runs cannot fail a change, so the screen the desktop actually mounts needs a case
 * reachable from the task that the build actually executes.
 *
 * What it proves is the one thing a screen test can prove that a ViewModel test cannot: that the mounted screen
 * draws what the shared state holds, through the shared copy table, with no platform in the loop. The ViewModel
 * is the real one over fake repositories — a fake ViewModel would test the test.
 *
 * `LocalUiStrings` has no default (a read without a provider fails loudly by design), so every case installs the
 * English table, which is also what makes the assertions below about the SCREEN rather than about copy that
 * happens to be hardcoded.
 */
class DesktopNotesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `draws the notes the shared view model publishes`() {
        val notes = FakeNoteRepository(listOf(note(id = "n1", title = "Learn Kotlin")))
        val viewModel = NotesViewModel(noteRepository = notes, tagRepository = FakeTagRepository())

        render(viewModel)

        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            viewModel.uiState.value.notes
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Learn Kotlin").assertIsDisplayed()
        // The title comes from the shared copy table, not from a literal in the screen.
        composeRule.onNodeWithText(UiStrings.of(UiLanguage.ENGLISH).settingsDatabaseNotes).assertIsDisplayed()
    }

    @Test
    fun `filters the list as the user types in the search field`() {
        val notes =
            FakeNoteRepository(
                listOf(
                    note(id = "n1", title = "Learn Kotlin"),
                    note(id = "n2", title = "Volunteer"),
                ),
            )
        val viewModel = NotesViewModel(noteRepository = notes, tagRepository = FakeTagRepository())

        render(viewModel)
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { viewModel.uiState.value.filteredNotes.size == 2 }

        composeRule.onNodeWithText(UiStrings.of(UiLanguage.ENGLISH).locSearchNotes).performTextInput("Volunteer")
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            viewModel.uiState.value.filteredNotes
                .map { it.id } == listOf("n2")
        }

        // The filtered-out note is gone from the tree...
        composeRule.onNodeWithText("Learn Kotlin").assertDoesNotExist()
        // ...and "Volunteer" matches exactly two nodes: the search field holding the query, and the one
        // surviving card. Counting them is what makes this an assertion about the LIST rather than about
        // either node on its own.
        composeRule.onAllNodesWithText("Volunteer").assertCountEquals(2)
    }

    private fun render(viewModel: NotesViewModel) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides UiStrings.of(UiLanguage.ENGLISH)) {
                NotesScreen(viewModel = viewModel)
            }
        }
    }

    private companion object {
        /** Long enough for a real dispatcher hop on a loaded box, short enough to fail rather than hang. */
        const val WAIT_MS = 10_000L

        fun note(
            id: String,
            title: String,
        ): Note {
            val stamp = LocalDateTime.of(2026, 3, 15, 10, 0)
            return Note(
                id = id,
                title = title,
                lifeIntentionCategory = "Learning & Growth",
                dimensionId = "dim_learning_growth",
                createdAt = stamp,
                updatedAt = stamp,
            )
        }
    }
}

/** The notes the screen lists, as a flow the ViewModel observes. */
private class FakeNoteRepository(
    initial: List<Note>,
) : NoteRepository {
    private val notes = MutableStateFlow(initial)

    override fun getAllNotes(): Flow<List<Note>> = notes

    override fun getNotesByDimension(dimension: String): Flow<List<Note>> = flowOf(emptyList())

    override fun getNotesForDate(date: LocalDate): Flow<List<Note>> = flowOf(emptyList())

    override suspend fun getNoteById(id: String): Note? = notes.value.firstOrNull { it.id == id }

    override suspend fun createNote(input: NoteInput): Note = throw UnsupportedOperationException("the screen test never writes")

    override suspend fun updateNote(
        id: String,
        input: NoteInput,
    ): Note = throw UnsupportedOperationException("the screen test never writes")

    override suspend fun deleteNote(id: String) = throw UnsupportedOperationException("the screen test never writes")
}

/** Tag lookups the screen performs while rendering; nothing to suggest, nothing attached. */
private class FakeTagRepository : TagRepository {
    override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())

    override fun searchTagsByPrefix(
        query: String,
        limit: Int,
    ): Flow<List<Tag>> = flowOf(emptyList())

    override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = flowOf(emptyList())

    override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

    override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> = emptyMap()

    override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(emptyList())

    override suspend fun replaceTaskTags(
        taskId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the screen test never writes")

    override suspend fun replaceNoteTags(
        noteId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the screen test never writes")

    override suspend fun replaceTimeEntryTags(
        timeEntryId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the screen test never writes")
}
