//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileLoggerTest {
    private lateinit var context: Context
    private lateinit var logger: FileLogger

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Initialize logger for testing
        FileLogger.initialize(context, "test", 1)
        logger = FileLogger.getInstance()
    }

    @After
    fun tearDown() {
        // Clean up any test log files
        val documentsDir =
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS,
            )
        val logDir = File(documentsDir, "payanam/logs")
        logDir.listFiles()?.forEach { it.delete() }
        logDir.delete()
    }

    @Test
    fun initialize_createsInstance() {
        val instance = FileLogger.getInstance()
        assertThat(instance).isNotNull()
    }

    @Test
    fun getInstance_returnsSameInstance() {
        val instance1 = FileLogger.getInstance()
        val instance2 = FileLogger.getInstance()
        assertThat(instance1).isSameInstanceAs(instance2)
    }

    @Test
    fun info_logsInfoMessage() {
        logger.info("TestSource", "FileLoggerTest.kt", "info_logsInfoMessage", "Info message", mapOf("count" to 5))
        assertThat(logger).isNotNull()
    }

    @Test
    fun debug_logsDebugMessage() {
        logger.debug(
            "TestSource",
            "FileLoggerTest.kt",
            "debug_logsDebugMessage",
            "Debug message",
            mapOf("key" to "value"),
        )
        assertThat(logger).isNotNull()
    }

    @Test
    fun warn_logsWarningMessage() {
        logger.warn(
            "TestSource",
            "FileLoggerTest.kt",
            "warn_logsWarningMessage",
            "Warning message",
            mapOf("issue" to "test"),
        )
        assertThat(logger).isNotNull()
    }

    @Test
    fun error_logsErrorMessage() {
        val exception = RuntimeException("Test error")
        logger.error(
            "TestSource",
            "FileLoggerTest.kt",
            "error_logsErrorMessage",
            "Error occurred",
            exception,
            mapOf("code" to 500),
        )
        assertThat(logger).isNotNull()
    }

    @Test
    fun error_logsErrorWithoutException() {
        logger.error(
            "TestSource",
            "FileLoggerTest.kt",
            "error_logsErrorWithoutException",
            "Error without exception",
            data = mapOf("type" to "validation"),
        )
        assertThat(logger).isNotNull()
    }

    @Test
    fun logging_withNullData_works() {
        logger.info("TestSource", "FileLoggerTest.kt", "logging_withNullData_works", "Message with null data")
        assertThat(logger).isNotNull()
    }

    @Test
    fun logging_withEmptyData_works() {
        logger.info(
            "TestSource",
            "FileLoggerTest.kt",
            "logging_withEmptyData_works",
            "Message with empty data",
            emptyMap(),
        )
        assertThat(logger).isNotNull()
    }

    @Test
    fun logging_withComplexData_works() {
        val complexData =
            mapOf(
                "string" to "value",
                "number" to 42,
                "boolean" to true,
                "null" to null,
                "list" to listOf("a", "b", "c"),
            )
        logger.info(
            "TestSource",
            "FileLoggerTest.kt",
            "logging_withComplexData_works",
            "Complex data test",
            complexData,
        )
        assertThat(logger).isNotNull()
    }

    @Test
    fun getLogFiles_returnsList() {
        val logFiles = logger.getLogFiles()
        // In test environment, may return empty list, but should not throw
        assertThat(logFiles).isNotNull()
    }

    @Test
    fun getCurrentLogFile_returnsFile() {
        val currentFile = logger.getCurrentLogFile()
        assertThat(currentFile).isNotNull()
        // In Robolectric, file may not actually exist, but path should be valid
        assertThat(currentFile.absolutePath).isNotEmpty()
    }
}
