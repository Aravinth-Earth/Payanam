//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Instant

/**
 * DesktopSessionLoggerTest.
 */
class DesktopSessionLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `initialize creates per session log file with build metadata header`() {
        val logsDirectory = temporaryFolder.newFolder("logs").toPath()
        val logger =
            DesktopSessionLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        val logContents = Files.readString(logger.getLogPath())

        assertThat(logger.getLogPath().fileName.toString()).contains("payanam_desktop_${DesktopBuildInfo.PLATFORM_BUILD_NUMBER}")
        assertThat(logContents).contains("Payanam Unified Log | Platform: Windows")
        assertThat(logContents).contains("Build: ${DesktopBuildInfo.PLATFORM_BUILD_NUMBER}")
        assertThat(logContents).contains("Session:")
        assertThat(logContents).contains("Format: timestamp | level | n | thread=<name> | source | message | [data]")
        logger.close()
    }

    @Test
    fun `logger writes structured event entries`() {
        val logsDirectory = temporaryFolder.newFolder("logs-structured").toPath()
        val logger =
            DesktopSessionLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        logger.i(
            source = "DesktopSessionLoggerTest",
            message = "Testing structured log event",
            data = mapOf("screen" to "desktop-home", "build" to DesktopBuildInfo.PLATFORM_BUILD_NUMBER),
        )
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("DesktopSessionLoggerTest")
        assertThat(logContents).contains("Testing structured log event")
        assertThat(logContents).contains("screen=\"desktop-home\"")
        assertThat(logContents).contains("build=${DesktopBuildInfo.PLATFORM_BUILD_NUMBER}")
        assertThat(logContents).doesNotContain("uptimeMs=")
        assertThat(logContents).doesNotContain("seq=")
    }

    @Test
    fun `logger writes warning and error entries`() {
        val logsDirectory = temporaryFolder.newFolder("logs-errors").toPath()
        val logger =
            DesktopSessionLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        logger.w(
            source = "DesktopSessionLoggerTest",
            message = "Warning branch",
            data = mapOf("route" to "settings"),
        )
        logger.e(
            source = "DesktopSessionLoggerTest",
            message = "Error branch",
            error = IllegalStateException("desktop failure"),
        )
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("| WARN  |")
        assertThat(logContents).contains("route=\"settings\"")
        assertThat(logContents).contains("| ERROR |")
        assertThat(logContents).contains("desktop failure")
    }
}
