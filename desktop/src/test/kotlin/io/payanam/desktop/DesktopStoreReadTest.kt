//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Instant

/**
 * Provides the desktop store read test.
 */
class DesktopStoreReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun resetSingleton() {
        // The logger is a memoized singleton; a leaked instance from another test class would send
        // these entries to that file instead of this test's temporary directory.
        UnifiedLogger.resetForTests()
    }

    @After
    fun clearSingleton() {
        UnifiedLogger.resetForTests()
    }

    private fun testLogger(folderName: String): UnifiedLogger =
        UnifiedLogger.initialize(
            logsDirectory = temporaryFolder.newFolder(folderName).toPath(),
            clock = { Instant.parse("2026-03-26T05:15:30Z") },
        )

    @Test
    fun `returns the read value and never the fallback when the read succeeds`() {
        val logger = testLogger("logs-read-ok")

        val value =
            readStoreOrFallback(
                storeName = "settings",
                logger = logger,
                read = { "read-value" },
                fallback = { "fallback-value" },
            )

        assertThat(value).isEqualTo("read-value")
        logger.close()
    }

    @Test
    fun `returns the fallback when the read throws`() {
        val logger = testLogger("logs-read-fail")

        val value =
            readStoreOrFallback<String>(
                storeName = "settings",
                logger = logger,
                read = { throw IllegalStateException("read exploded") },
                fallback = { "fallback-value" },
            )

        assertThat(value).isEqualTo("fallback-value")
        logger.close()
    }

    @Test
    fun `warns with the store name and failure class but never the exception message`() {
        val logger = testLogger("logs-read-secret")
        val secret = "SECRET-MESSAGE-MUST-NOT-BE-LOGGED"

        readStoreOrFallback<String>(
            storeName = "settings",
            logger = logger,
            read = { throw IllegalStateException(secret) },
            fallback = { "fallback-value" },
        )
        logger.close()

        val contents = Files.readString(logger.getLogPath())
        assertThat(contents).contains("DesktopStartupRead")
        assertThat(contents).contains("settings")
        assertThat(contents).contains("IllegalStateException")
        // The class name is what the reader needs; the message routinely carries absolute paths.
        assertThat(contents).doesNotContain(secret)
    }
}
