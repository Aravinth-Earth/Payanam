//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogSanitizerTest {
    private lateinit var logger: UnifiedLogger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()
        logger.d("LogSanitizerTest.setUp", "Logger initialized")
    }

    @Test
    fun sanitizeData_redactsTaskHabitAndNoteTextFields() {
        val input =
            mapOf(
                "taskTitle" to "Deep Work",
                "habitName" to "Morning Walk",
                "noteText" to "private personal note",
            )

        val output = LogSanitizer.sanitizeData(input)
        logger.d("LogSanitizerTest.sanitizeData_redactsTaskHabitAndNoteTextFields", "Sanitized map", output)

        assertThat(output["taskTitle"]).isEqualTo("<redacted>")
        assertThat(output["habitName"]).isEqualTo("<redacted>")
        assertThat(output["noteText"]).isEqualTo("<redacted>")
    }

    @Test
    fun sanitizeData_keepsNumericAggregatesEvenWhenKeyContainsSensitiveToken() {
        val input =
            mapOf(
                "taskCount" to 4,
                "noteCount" to 2,
                "responseLength" to 120,
            )

        val output = LogSanitizer.sanitizeData(input)
        logger.d("LogSanitizerTest.sanitizeData_keepsNumericAggregatesEvenWhenKeyContainsSensitiveToken", "Sanitized map", output)

        assertThat(output["taskCount"]).isEqualTo(4)
        assertThat(output["noteCount"]).isEqualTo(2)
        assertThat(output["responseLength"]).isEqualTo(120)
    }

    @Test
    fun sanitizeData_masksNonUuidIdentifiers() {
        val input =
            mapOf(
                "taskId" to "12345",
                "journalId" to "wrong-id-value",
            )

        val output = LogSanitizer.sanitizeData(input)
        logger.d("LogSanitizerTest.sanitizeData_masksNonUuidIdentifiers", "Sanitized map", output)

        assertThat(output["taskId"]).isEqualTo("<non-uuid>")
        assertThat(output["journalId"]).isEqualTo("<non-uuid>")
    }

    @Test
    fun sanitizeData_keepsUuidIdentifiersEvenWhenKeyContainsSensitiveModuleTokens() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val input =
            mapOf(
                "taskId" to uuid,
                "habit_id" to uuid,
                "journalEntryId" to uuid,
            )

        val output = LogSanitizer.sanitizeData(input)
        logger.d("LogSanitizerTest.sanitizeData_keepsUuidIdentifiersEvenWhenKeyContainsSensitiveModuleTokens", "Sanitized map", output)

        assertThat(output["taskId"]).isEqualTo(uuid)
        assertThat(output["habit_id"]).isEqualTo(uuid)
        assertThat(output["journalEntryId"]).isEqualTo(uuid)
    }

    @Test
    fun sanitizeData_redactsJournalTaskAndHabitTextPayloadsByKey() {
        val input =
            mapOf(
                "journalPromptText" to "How did today go?",
                "taskSummary" to "Finish private client work",
                "habitReflection" to "Skipped because I was tired",
            )

        val output = LogSanitizer.sanitizeData(input)
        logger.d("LogSanitizerTest.sanitizeData_redactsJournalTaskAndHabitTextPayloadsByKey", "Sanitized map", output)

        assertThat(output["journalPromptText"]).isEqualTo("<redacted>")
        assertThat(output["taskSummary"]).isEqualTo("<redacted>")
        assertThat(output["habitReflection"]).isEqualTo("<redacted>")
    }

    @Test
    fun sanitizeMessage_stripsPathsAndLineBreaks() {
        val input = "Import failed\nat C:\\Users\\user\\Documents\\payanam\\data\\backup.db"
        val output = LogSanitizer.sanitizeMessage(input)
        logger.d("LogSanitizerTest.sanitizeMessage_stripsPathsAndLineBreaks", "Sanitized message", mapOf("message" to output))

        assertThat(output).doesNotContain("\n")
        assertThat(output).doesNotContain("C:\\Users")
        assertThat(output).contains("<path>")
    }
}
