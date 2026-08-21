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
        val entity =
            NoteEntity(
                id = "note-1",
                title = "Title",
                details = "Details",
                lifeIntentionCategory = "Personal Growth",
                createdAt = "2026-01-31T08:00:00Z",
                updatedAt = "2026-01-31T09:00:00Z",
            )
        val domain = entity.toDomain()
        assertThat(domain.createdAt.hour).isEqualTo(8)
        assertThat(domain.updatedAt.hour).isEqualTo(9)
    }

    @Test
    /**
     * Round trip preserves fields.
     */
    fun roundTrip_preservesFields() {
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        val note =
            Note(
                id = "note-2",
                title = "RoundTrip",
                details = "Details",
                lifeIntentionCategory = "Recreation",
                createdAt = now,
                updatedAt = now,
                dimensionId = "dim_recreation",
            )
        val entity = note.toEntity()
        val roundTrip = entity.toDomain()
        assertThat(roundTrip.title).isEqualTo(note.title)
        assertThat(roundTrip.lifeIntentionCategory).isEqualTo(note.lifeIntentionCategory)
        assertThat(roundTrip.dimensionId).isEqualTo("dim_recreation")
        assertThat(entity.dimensionId).isEqualTo("dim_recreation")
        assertThat(entity.dayKey).isEqualTo("2026-01-31")
    }

    @Test
    /**
     * To entity handles null details.
     */
    fun toEntity_handlesNullDetails() {
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        val note =
            Note(
                id = "note-3",
                title = "NullDetails",
                lifeIntentionCategory = "Learning",
                createdAt = now,
                updatedAt = now,
            )
        val entity = note.toEntity()
        assertThat(entity.details).isNull()
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
