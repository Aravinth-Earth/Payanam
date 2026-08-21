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
        val input = TaskInput(title = "Test")
        assertThat(input.description).isNull()
        assertThat(input.dueDate).isNull()
        assertThat(input.notificationMode).isNull()
    }

    @Test
    /**
     * Task input exposes all fields.
     */
    fun taskInput_exposesAllFields() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
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
        assertThat(input.title).isEqualTo("Title")
        assertThat(input.description).isEqualTo("Desc")
        assertThat(input.status).isEqualTo("pending")
        assertThat(input.dueDate).isEqualTo(now)
        assertThat(input.archivedAt).isEqualTo(now.plusDays(1))
        assertThat(input.recurrenceEnabled).isTrue()
        assertThat(input.recurrenceRule).isEqualTo("FREQ=DAILY")
        assertThat(input.durationMinutes).isEqualTo(45)
        assertThat(input.impactLevel).isEqualTo("High Impact")
        assertThat(input.goalAlignment).isEqualTo("Strong Alignment")
        assertThat(input.energyLevel).isEqualTo("High")
        assertThat(input.controlLevel).isEqualTo("Self")
        assertThat(input.lifeIntentionCategory).isEqualTo("Health & Wellness")
        assertThat(input.explicitUrgency).isEqualTo(0.8)
        assertThat(input.focusRequired).isEqualTo(0.6)
        assertThat(input.blockedReason).isEqualTo("WAITING")
        assertThat(input.completionRate).isEqualTo(0.9)
        assertThat(input.externalDependency).isEqualTo("Vendor")
        assertThat(input.notificationMode).isEqualTo("custom")
        assertThat(input.customNotificationMinutes).isEqualTo(15)
    }

    @Test
    /**
     * Note input holds values.
     */
    fun noteInput_holdsValues() {
        val input = NoteInput(
            title = "Note",
            details = "Details",
            lifeIntentionCategory = "Recreation"
        )
        assertThat(input.title).isEqualTo("Note")
        assertThat(input.details).isEqualTo("Details")
        assertThat(input.lifeIntentionCategory).isEqualTo("Recreation")
    }

    @Test
    /**
     * Journal response input defaults to null response.
     */
    fun journalResponseInput_defaultsToNullResponse() {
        val input = DayJournalResponseInput(
            scope = JournalPromptScope.OVERALL,
            promptKey = "gratitude"
        )
        assertThat(input.responseText).isNull()
    }

    @Test
    /**
     * Journal response input exposes fields.
     */
    fun journalResponseInput_exposesFields() {
        val input = DayJournalResponseInput(
            scope = JournalPromptScope.DIMENSION,
            dimensionKey = "health",
            promptKey = "energy",
            responseText = "High"
        )
        assertThat(input.scope).isEqualTo(JournalPromptScope.DIMENSION)
        assertThat(input.dimensionKey).isEqualTo("health")
        assertThat(input.promptKey).isEqualTo("energy")
        assertThat(input.responseText).isEqualTo("High")
    }

    @Test
    /**
     * Journal prompt scope values are stable.
     */
    fun journalPromptScope_valuesAreStable() {
        assertThat(JournalPromptScope.valueOf("OVERALL")).isEqualTo(JournalPromptScope.OVERALL)
        assertThat(JournalPromptScope.valueOf("DIMENSION")).isEqualTo(JournalPromptScope.DIMENSION)
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
