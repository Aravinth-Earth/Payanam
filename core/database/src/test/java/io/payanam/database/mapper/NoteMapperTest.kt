//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.NoteEntity
import io.payanam.database.mapper.NoteMapper.toDomain
import io.payanam.database.mapper.NoteMapper.toEntity
import io.payanam.domain.model.Note
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * NoteMapperTest.
 */
class NoteMapperTest {
    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("NoteMapperTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * To domain parses zulu dates.
     */
    fun toDomain_parsesZuluDates() {
        /** Entity. */
        val entity =
            /** Note entity. */
            NoteEntity(
                id = "note-1",
                title = "Title",
                details = "Details",
                lifeIntentionCategory = "Personal Growth",
                createdAt = "2026-01-31T08:00:00Z",
                updatedAt = "2026-01-31T09:00:00Z",
            )

        /** Domain. */
        val domain = entity.toDomain()
        /** Assert that. */
        assertThat(domain.createdAt.hour).isEqualTo(8)
        /** Assert that. */
        assertThat(domain.updatedAt.hour).isEqualTo(9)
    }

    @Test
    /**
     * Round trip preserves fields.
     */
    fun roundTrip_preservesFields() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        /** Note. */
        val note =
            /** Note. */
            Note(
                id = "note-2",
                title = "RoundTrip",
                details = "Details",
                lifeIntentionCategory = "Recreation",
                createdAt = now,
                updatedAt = now,
                dimensionId = "dim_recreation",
            )

        /** Entity. */
        val entity = note.toEntity()
        /** Round trip. */
        val roundTrip = entity.toDomain()

        /** Assert that. */
        assertThat(roundTrip.title).isEqualTo(note.title)
        /** Assert that. */
        assertThat(roundTrip.lifeIntentionCategory).isEqualTo(note.lifeIntentionCategory)
        /** Assert that. */
        assertThat(roundTrip.dimensionId).isEqualTo("dim_recreation")
        /** Assert that. */
        assertThat(entity.dimensionId).isEqualTo("dim_recreation")
        /** Assert that. */
        assertThat(entity.dayKey).isEqualTo("2026-01-31")
    }

    @Test
    /**
     * To entity handles null details.
     */
    fun toEntity_handlesNullDetails() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        /** Note. */
        val note =
            /** Note. */
            Note(
                id = "note-3",
                title = "NullDetails",
                lifeIntentionCategory = "Learning",
                createdAt = now,
                updatedAt = now,
            )

        /** Entity. */
        val entity = note.toEntity()
        /** Assert that. */
        assertThat(entity.details).isNull()
    }

    private fun initLogger(): UnifiedLogger {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
