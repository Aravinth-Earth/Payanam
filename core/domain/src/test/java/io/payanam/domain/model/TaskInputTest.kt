//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * TaskInputTest.
 */
class TaskInputTest {

    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("TaskInputTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * Task input allows optional fields.
     */
    fun taskInput_allowsOptionalFields() {
        /** Input. */
        val input = TaskInput(title = "Test")
        /** Assert that. */
        assertThat(input.description).isNull()
        /** Assert that. */
        assertThat(input.dueDate).isNull()
        /** Assert that. */
        assertThat(input.notificationMode).isNull()
    }

    @Test
    /**
     * Task input exposes all fields.
     */
    fun taskInput_exposesAllFields() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Input. */
        val input = TaskInput(
            title = "Title",
            description = "Desc",
            status = "pending",
            dueDate = now,
            archivedAt = now.plusDays(1),
            recurrenceEnabled = true,
            recurrenceRule = "FREQ=DAILY",
            durationMinutes = 45,
            impactLevel = "High Impact",
            goalAlignment = "Strong Alignment",
            energyLevel = "High",
            controlLevel = "Self",
            lifeIntentionCategory = "Health & Wellness",
            explicitUrgency = 0.8,
            focusRequired = 0.6,
            blockedReason = "WAITING",
            completionRate = 0.9,
            externalDependency = "Vendor",
            notificationMode = "custom",
            customNotificationMinutes = 15
        )

        /** Assert that. */
        assertThat(input.title).isEqualTo("Title")
        /** Assert that. */
        assertThat(input.description).isEqualTo("Desc")
        /** Assert that. */
        assertThat(input.status).isEqualTo("pending")
        /** Assert that. */
        assertThat(input.dueDate).isEqualTo(now)
        /** Assert that. */
        assertThat(input.archivedAt).isEqualTo(now.plusDays(1))
        /** Assert that. */
        assertThat(input.recurrenceEnabled).isTrue()
        /** Assert that. */
        assertThat(input.recurrenceRule).isEqualTo("FREQ=DAILY")
        /** Assert that. */
        assertThat(input.durationMinutes).isEqualTo(45)
        /** Assert that. */
        assertThat(input.impactLevel).isEqualTo("High Impact")
        /** Assert that. */
        assertThat(input.goalAlignment).isEqualTo("Strong Alignment")
        /** Assert that. */
        assertThat(input.energyLevel).isEqualTo("High")
        /** Assert that. */
        assertThat(input.controlLevel).isEqualTo("Self")
        /** Assert that. */
        assertThat(input.lifeIntentionCategory).isEqualTo("Health & Wellness")
        /** Assert that. */
        assertThat(input.explicitUrgency).isEqualTo(0.8)
        /** Assert that. */
        assertThat(input.focusRequired).isEqualTo(0.6)
        /** Assert that. */
        assertThat(input.blockedReason).isEqualTo("WAITING")
        /** Assert that. */
        assertThat(input.completionRate).isEqualTo(0.9)
        /** Assert that. */
        assertThat(input.externalDependency).isEqualTo("Vendor")
        /** Assert that. */
        assertThat(input.notificationMode).isEqualTo("custom")
        /** Assert that. */
        assertThat(input.customNotificationMinutes).isEqualTo(15)
    }

    @Test
    /**
     * Note input holds values.
     */
    fun noteInput_holdsValues() {
        /** Input. */
        val input = NoteInput(
            title = "Note",
            details = "Details",
            lifeIntentionCategory = "Recreation"
        )
        /** Assert that. */
        assertThat(input.title).isEqualTo("Note")
        /** Assert that. */
        assertThat(input.details).isEqualTo("Details")
        /** Assert that. */
        assertThat(input.lifeIntentionCategory).isEqualTo("Recreation")
    }

    @Test
    /**
     * Journal response input defaults to null response.
     */
    fun journalResponseInput_defaultsToNullResponse() {
        /** Input. */
        val input = DayJournalResponseInput(
            scope = JournalPromptScope.OVERALL,
            promptKey = "gratitude"
        )
        /** Assert that. */
        assertThat(input.responseText).isNull()
    }

    @Test
    /**
     * Journal response input exposes fields.
     */
    fun journalResponseInput_exposesFields() {
        /** Input. */
        val input = DayJournalResponseInput(
            scope = JournalPromptScope.DIMENSION,
            dimensionKey = "health",
            promptKey = "energy",
            responseText = "High"
        )

        /** Assert that. */
        assertThat(input.scope).isEqualTo(JournalPromptScope.DIMENSION)
        /** Assert that. */
        assertThat(input.dimensionKey).isEqualTo("health")
        /** Assert that. */
        assertThat(input.promptKey).isEqualTo("energy")
        /** Assert that. */
        assertThat(input.responseText).isEqualTo("High")
    }

    @Test
    /**
     * Journal prompt scope values are stable.
     */
    fun journalPromptScope_valuesAreStable() {
        /** Assert that. */
        assertThat(JournalPromptScope.valueOf("OVERALL")).isEqualTo(JournalPromptScope.OVERALL)
        /** Assert that. */
        assertThat(JournalPromptScope.valueOf("DIMENSION")).isEqualTo(JournalPromptScope.DIMENSION)
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
