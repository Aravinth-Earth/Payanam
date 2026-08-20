//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * JournalNoteEntityTest.
 */
class JournalNoteEntityTest {
    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    /**
     * Journal note entity exposes fields.
     */
    fun journalNote_entity_exposes_fields() {
        /** Journal note. */
        val journalNote =
            /** Journal note entity. */
            JournalNoteEntity(
                id = "jn-1",
                title = "Morning reflection",
                details = "Focused work before lunch",
                lifeIntentionCategory = "Career & Work",
                dimensionId = "dim_career_work",
                dayKey = "2026-01-31",
                createdAt = "2026-01-31T10:00:00",
                updatedAt = "2026-01-31T10:05:00",
            )

        /** Assert that. */
        assertThat(journalNote.id).isEqualTo("jn-1")
        /** Assert that. */
        assertThat(journalNote.title).isEqualTo("Morning reflection")
        /** Assert that. */
        assertThat(journalNote.details).isEqualTo("Focused work before lunch")
        /** Assert that. */
        assertThat(journalNote.lifeIntentionCategory).isEqualTo("Career & Work")
        /** Assert that. */
        assertThat(journalNote.dimensionId).isEqualTo("dim_career_work")
        /** Assert that. */
        assertThat(journalNote.dayKey).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(journalNote.createdAt).isEqualTo("2026-01-31T10:00:00")
        /** Assert that. */
        assertThat(journalNote.updatedAt).isEqualTo("2026-01-31T10:05:00")
    }

    @Test
    /**
     * Journal note entity defaults copy and components work.
     */
    fun journalNote_entity_defaults_copy_and_components_work() {
        /** Note. */
        val note =
            /** Journal note entity. */
            JournalNoteEntity(
                id = "jn-2",
                title = "Evening recap",
                lifeIntentionCategory = "Health & Wellness",
                dayKey = "2026-02-01",
                createdAt = "2026-02-01T20:00:00",
                updatedAt = "2026-02-01T20:10:00",
            )

        /** Assert that. */
        assertThat(note.details).isNull()
        /** Assert that. */
        assertThat(note.dimensionId).isNull()

        /** Copied. */
        val copied = note.copy(details = "Walked 45 minutes", dimensionId = "dim_health_wellness")
        /** Assert that. */
        assertThat(copied.component1()).isEqualTo("jn-2")
        /** Assert that. */
        assertThat(copied.component2()).isEqualTo("Evening recap")
        /** Assert that. */
        assertThat(copied.component3()).isEqualTo("Walked 45 minutes")
        /** Assert that. */
        assertThat(copied.component4()).isEqualTo("Health & Wellness")
        /** Assert that. */
        assertThat(copied.component5()).isEqualTo("dim_health_wellness")
        /** Assert that. */
        assertThat(copied.component6()).isEqualTo("2026-02-01")
        /** Assert that. */
        assertThat(copied.component7()).isEqualTo("2026-02-01T20:00:00")
        /** Assert that. */
        assertThat(copied.component8()).isEqualTo("2026-02-01T20:10:00")
        /** Assert that. */
        assertThat(copied.toString()).contains("jn-2")
    }
}
