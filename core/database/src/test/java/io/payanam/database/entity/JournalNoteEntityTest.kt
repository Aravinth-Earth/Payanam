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
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    /**
     * Journal note entity exposes fields.
     */
    fun journalNote_entity_exposes_fields() {
        val journalNote =
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
        assertThat(journalNote.id).isEqualTo("jn-1")
        assertThat(journalNote.title).isEqualTo("Morning reflection")
        assertThat(journalNote.details).isEqualTo("Focused work before lunch")
        assertThat(journalNote.lifeIntentionCategory).isEqualTo("Career & Work")
        assertThat(journalNote.dimensionId).isEqualTo("dim_career_work")
        assertThat(journalNote.dayKey).isEqualTo("2026-01-31")
        assertThat(journalNote.createdAt).isEqualTo("2026-01-31T10:00:00")
        assertThat(journalNote.updatedAt).isEqualTo("2026-01-31T10:05:00")
    }

    @Test
    /**
     * Journal note entity defaults copy and components work.
     */
    fun journalNote_entity_defaults_copy_and_components_work() {
        val note =
            JournalNoteEntity(
                id = "jn-2",
                title = "Evening recap",
                lifeIntentionCategory = "Health & Wellness",
                dayKey = "2026-02-01",
                createdAt = "2026-02-01T20:00:00",
                updatedAt = "2026-02-01T20:10:00",
            )
        assertThat(note.details).isNull()
        assertThat(note.dimensionId).isNull()
        val copied = note.copy(details = "Walked 45 minutes", dimensionId = "dim_health_wellness")
        assertThat(copied.component1()).isEqualTo("jn-2")
        assertThat(copied.component2()).isEqualTo("Evening recap")
        assertThat(copied.component3()).isEqualTo("Walked 45 minutes")
        assertThat(copied.component4()).isEqualTo("Health & Wellness")
        assertThat(copied.component5()).isEqualTo("dim_health_wellness")
        assertThat(copied.component6()).isEqualTo("2026-02-01")
        assertThat(copied.component7()).isEqualTo("2026-02-01T20:00:00")
        assertThat(copied.component8()).isEqualTo("2026-02-01T20:10:00")
        assertThat(copied.toString()).contains("jn-2")
    }
}
