//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Export-pipeline hardening tests: atomic publish, session-live entry,
 * retention pruning and zip integrity verification.
 */
@RunWith(RobolectricTestRunner::class)
class UnifiedLoggerExportTest {

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

    /** The export dir used by the logger (Environment.DOCUMENTS + payanam/exported-logs). */
    private suspend fun exportDir(): File {
        val zip = logger.exportAllLogs()!!
        return zip.parentFile!!
    }

    @Test
    fun exportAllLogs_writesAtomicZip_withSessionLiveEntry() = runTest {
        // Logs are buffered asynchronously; wait for the IO scope to drain.
        logger.i("Test", "first buffered line")
        logger.i("Test", "second buffered line")
        Thread.sleep(300)

        val zip = logger.exportAllLogs()

        assertThat(zip).isNotNull()
        assertThat(zip!!.exists()).isTrue()
        // Atomic publish: no .tmp leftover at the final name.
        assertThat(zip.parentFile.listFiles()!!.any { it.name.endsWith(".tmp") }).isFalse()
        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            // session-live.log carries the buffered lines even before flush.
            assertThat(names).contains("session-live.log")
            val live = zf.getInputStream(zf.getEntry("session-live.log")).bufferedReader().use { it.readText() }
            assertThat(live).contains("second buffered line")
        }
    }

    @Test
    fun exportAllLogs_retainsOnlyNewestExports() = runTest {
        // Seed the real export dir with 25 fake artifacts (older timestamps).
        val dir = exportDir()
        repeat(25) { index ->
            File(dir, "payanam_1_20260810-${String.format(java.util.Locale.US, "%02d", index)}00.zip").writeText("x")
        }

        // A real export triggers cleanupOldExports(keepLast = 20).
        val zip = logger.exportAllLogs()

        val remaining = dir.listFiles()!!.filter { it.name.endsWith(".zip") }
        assertThat(remaining.size).isAtMost(20)
        assertThat(remaining).contains(zip)
        assertThat(remaining.map { it.name }).doesNotContain("payanam_1_20260810-0000.zip")
    }

    @Test
    fun exportAllLogs_zipIsVerifiable() = runTest {
        logger.i("Test", "line for integrity check")
        Thread.sleep(300)

        val zip = logger.exportAllLogs()

        assertThat(zip).isNotNull()
        // EOCD magic (PK\x05\x06) starts 22 bytes from the end of a complete
        // zip; the last 4 bytes are the comment-length field (zeros).
        val bytes = zip!!.readBytes()
        val eocdMagic = bytes.copyOfRange(bytes.size - 22, bytes.size - 18)
        assertThat(eocdMagic).isEqualTo(byteArrayOf(0x50, 0x4B, 0x05, 0x06))
        ZipFile(zip).use { assertThat(it.size()).isAtLeast(1) }
    }

    @Test
    fun exportLatestLog_writesCurrentSessionAtomically() = runTest {
        logger.i("Test", "latest session line")
        Thread.sleep(300)

        val exported = logger.exportLatestLog()

        assertThat(exported).isNotNull()
        assertThat(exported!!.exists()).isTrue()
        assertThat(exported.length()).isGreaterThan(0)
        assertThat(exported.parentFile.listFiles()!!.any { it.name.endsWith(".tmp") }).isFalse()
    }
}
