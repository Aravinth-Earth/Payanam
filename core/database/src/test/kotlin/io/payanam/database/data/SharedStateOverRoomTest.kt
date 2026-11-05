//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.repository.NoteRepositoryImpl
import io.payanam.database.repository.TagRepositoryImpl
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.CanonicalDimensionDefinition
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Note
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import io.payanam.presentation.notes.NotesViewModel
import io.payanam.presentation.notes.notesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android half of the shared-class execution proof: a class from `:core:presentation` - the very
 * ViewModel the Android Notes screen uses - executing over this module's Room repositories, reached the way
 * the app reaches them (`LocalDataLayer.current`).
 *
 * A compile is not proof that the shared state layer RUNS here: it compiles against `:core:domain`, and the
 * failure this guards against is a shared ViewModel that cannot execute over Room - an interface method the
 * Room implementation cannot honour, a threading assumption that only holds on the JVM, or a suspension
 * point that returns on a dispatcher the repository cannot use.
 *
 * The layer is built ANONYMOUSLY here rather than through the app's `RoomPayanamDataLayer`, so this test
 * needs no production visibility widening: the claim under test is about the shared class and the Room
 * repositories, and the app's class only pairs those two by constructor.
 */
@RunWith(RobolectricTestRunner::class)
class SharedStateOverRoomTest {
    private lateinit var database: PayanamDatabase
    private lateinit var sessionManager: DatabaseSessionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        sessionManager = DatabaseSessionManager(context, DatabaseEncryptionManager(context))
        sessionManager.openWithTestDatabase(database)
        LocalDataLayer.resetForTests()
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        database.close()
    }

    /**
     * Seeds a canonical life dimension.
     *
     * `journal_notes.dimensionId` is a foreign key into `life_dimensions`, and a bare in-memory database has
     * no dimension rows, so a note written against a dimension id fails with a foreign-key violation. The id
     * comes from the catalog rather than a literal, because the canonical ids are `dim_*` - a literal like
     * `"career"` is not one of them and has no row to point at.
     */
    private fun seedLifeDimension(definition: CanonicalDimensionDefinition) {
        val now = "2026-07-01T06:00:00"
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO life_dimensions " +
                "(id, key, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt) " +
                "VALUES (?, ?, ?, NULL, ?, NULL, ?, 1, 1.0, ?, ?)",
            arrayOf<Any>(
                definition.id,
                definition.slug,
                definition.fallbackLabel,
                definition.defaultColorHex,
                definition.sortOrder,
                now,
                now,
            ),
        )
    }

    private fun installRoomLayer() {
        LocalDataLayer.install(
            object : PayanamDataLayer by UninstalledDataLayer {
                override val notes: NoteRepository = NoteRepositoryImpl(sessionManager)
                override val tags: TagRepository = TagRepositoryImpl(sessionManager)
            },
        )
    }

    @Test
    fun `the shared notes view model reads and writes through the Room backed layer`(): Unit =
        runBlocking {
            seedLifeDimension(DimensionTaxonomyCatalog.WORK_LIVELIHOOD)
            installRoomLayer()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val viewModel = notesViewModel(scope)

            viewModel.createNote(
                title = "Parity note",
                details = "written through the shared view model",
                dimensionId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
                dimensionLabel = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel,
                tags = listOf("parity"),
            )

            // The write went through the shared ViewModel into Room, so it must come back out of the
            // VIEWMODEL - not through a shortcut this test arranged.
            assertThat(waitForNotes(viewModel).map { it.title }).contains("Parity note")

            // And a second, independent reader over the same session sees the same row, which is what makes
            // this a data-layer proof rather than a cache assertion.
            val repository = NoteRepositoryImpl(sessionManager)
            assertThat(repository.getAllNotes().first().map { it.title }).contains("Parity note")
        }

    @Test
    fun `the shared view model's tag suggestions come from the Room backed tag repository`(): Unit =
        runBlocking {
            seedLifeDimension(DimensionTaxonomyCatalog.PHYSICAL_HEALTH)
            installRoomLayer()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val viewModel = notesViewModel(scope)

            viewModel.createNote(
                title = "Tagged",
                details = null,
                dimensionId = DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id,
                dimensionLabel = DimensionTaxonomyCatalog.PHYSICAL_HEALTH.fallbackLabel,
                tags = listOf("morning"),
            )

            assertThat(waitForTagSuggestions(viewModel)).contains("morning")
        }

    /**
     * Waits for [read], carrying the ViewModel's own state into the failure message.
     *
     * The ViewModel catches repository exceptions and puts the message in `error` instead of propagating it,
     * so a plain "expected to contain" assertion would hide the actual cause behind an empty list. Naming the
     * error here is what makes a red test diagnosable.
     */
    private suspend fun <T> awaitState(
        viewModel: NotesViewModel,
        describe: (io.payanam.presentation.notes.NotesScreenUiState) -> String,
        read: (io.payanam.presentation.notes.NotesScreenUiState) -> T,
        isReady: (T) -> Boolean,
    ): T {
        repeat(50) {
            val state = viewModel.uiState.value
            val value = read(state)
            if (isReady(value)) return value
            delay(20)
        }
        val state = viewModel.uiState.value
        throw AssertionError("state did not settle; error=${state.error} isLoading=${state.isLoading} (${describe(state)})")
    }

    private suspend fun waitForNotes(viewModel: NotesViewModel): List<Note> =
        awaitState(
            viewModel,
            describe = { "notes=${it.notes.size}" },
            read = { it.notes },
            isReady = { it.isNotEmpty() },
        )

    private suspend fun waitForTagSuggestions(viewModel: NotesViewModel): List<String> =
        awaitState(
            viewModel,
            describe = { "suggestions=${it.tagSuggestions}" },
            read = { it.tagSuggestions },
            isReady = { it.isNotEmpty() },
        )
}
