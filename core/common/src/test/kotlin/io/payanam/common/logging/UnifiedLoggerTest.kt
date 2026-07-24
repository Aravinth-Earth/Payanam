//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UnifiedLoggerTest {
    private lateinit var context: Context
    private lateinit var logger: UnifiedLogger

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 1)
        }
        logger = UnifiedLogger.getInstance()
        val currentLogFile = File(logger.getCurrentLogPath())
        currentLogFile.parentFile?.mkdirs()
        if (!currentLogFile.exists()) {
            currentLogFile.createNewFile()
        }
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
    }

    @Test
    fun initialize_createsInstance() {
        assertThat(UnifiedLogger.isInitialized()).isTrue()
        assertThat(UnifiedLogger.getInstance()).isNotNull()
    }

    @Test
    fun getInstance_returnsSameInstance() {
        val instance1 = UnifiedLogger.getInstance()
        val instance2 = UnifiedLogger.getInstance()
        assertThat(instance1).isSameInstanceAs(instance2)
    }

    @Test
    fun startNewSession_rotatesCurrentLogFile() {
        val originalPath = logger.getCurrentLogPath()

        Thread.sleep(1100)
        val rotatedPath = logger.startNewSession("test_foreground")

        assertThat(rotatedPath).isNotEqualTo(originalPath)
        assertThat(logger.getCurrentLogPath()).isEqualTo(rotatedPath)
        assertThat(File(rotatedPath).exists()).isTrue()
    }

    @Test
    fun info_log_persistsStructuredSanitizedEntry() {
        logger.i(
            "TestSource.info",
            "Import failed at C:\\Users\\user\\Documents\\secret.db",
            mapOf(
                "taskTitle" to "Private Task",
                "count" to 5,
            ),
        )

        val logs = waitForLogSnippet("TestSource.info")

        assertThat(logs).contains(" | ")
        assertThat(logs).contains("thread=")
        assertThat(logs).doesNotContain("seq=")
        assertThat(logs).doesNotContain("uptimeMs=")
        assertThat(logs).contains("TestSource.info")
        assertThat(logs).contains("<path>")
        assertThat(logs).contains("taskTitle=\"<redacted>\"")
        assertThat(logs).contains("count=5")
        assertThat(logs).doesNotContain("C:\\Users\\user\\Documents\\secret.db")
        assertThat(logs).doesNotContain("Private Task")
    }

    @Test
    fun eSync_usesSanitizedMessageForLogcat() {
        logger.eSync(
            source = "TestSource.error",
            message = "Import failed at C:\\Users\\user\\Documents\\secret.db",
            error = RuntimeException("sensitive failure"),
            data = mapOf("noteText" to "private personal note"),
        )

        val payanamLogs = ShadowLog.getLogsForTag("Payanam")
        val latest = payanamLogs.last()

        assertThat(latest.msg).contains("<path>")
        assertThat(latest.msg).contains("| thread=")
        assertThat(latest.msg).contains("thread=")
        assertThat(latest.msg).doesNotContain("seq=")
        assertThat(latest.msg).doesNotContain("uptimeMs=")
        assertThat(latest.msg).contains("noteText=\"<redacted>\"")
        assertThat(latest.msg).doesNotContain("C:\\Users\\user\\Documents\\secret.db")
        assertThat(latest.msg).doesNotContain("private personal note")
    }

    @Test
    fun multipleLogs_workConcurrently() =
        runTest {
            val jobs =
                List(10) {
                    launch {
                        logger.i("TestSource.concurrent", "Concurrent log $it", mapOf("index" to it))
                    }
                }
            jobs.forEach { it.join() }

            val logs = waitForLogSnippet("Concurrent log 9")
            assertThat(logs).contains("Concurrent log 0")
            assertThat(logs).contains("Concurrent log 9")
            assertThat(logs).doesNotContain("uptimeMs=")
        }

    private fun waitForLogSnippet(snippet: String): String {
        repeat(20) {
            logger.flush()
            Thread.sleep(25)
            val logs = logger.getRecentLogs(40)
            if (logs.contains(snippet)) {
                return logs
            }
        }
        return logger.getRecentLogs(40)
    }
}
