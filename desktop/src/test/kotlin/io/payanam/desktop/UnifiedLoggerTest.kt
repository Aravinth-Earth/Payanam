//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.osToken
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

/**
 * Provides the desktop session logger test.
 */
class UnifiedLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun resetSingleton() {
        // The logger is a memoized singleton; without this, a leaked instance from another
        // test class makes the TemporaryFolder below point at the wrong file.
        UnifiedLogger.resetForTests()
    }

    @Test
    fun `initialize creates per session log file with build metadata header`() {
        val logsDirectory = temporaryFolder.newFolder("logs").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        val logContents = Files.readString(logger.getLogPath())

        val buildType = if (DesktopBuildInfo.IS_DEBUG_BUILD) "debug" else "release"
        val expectedPrefix =
            "payanam_${osToken(DesktopBuildInfo.PLATFORM)}_${DesktopBuildInfo.VERSION_NAME}_" +
                "${DesktopBuildInfo.BUILD_SHORT_SHA}_${buildType}_"
        assertThat(logger.getLogPath().fileName.toString()).startsWith(expectedPrefix)
        assertThat(logger.getLogPath().fileName.toString()).endsWith(".log")
        assertThat(logContents).contains("Payanam Unified Log | Platform: ${DesktopBuildInfo.PLATFORM}")
        assertThat(logContents).contains("Version: ${DesktopBuildInfo.VERSION_DISPLAY_NAME}")
        assertThat(logContents).contains("Session:")
        assertThat(logContents).contains("OS: ${System.getProperty("os.name")}")
        assertThat(logContents).contains("Format: timestamp | level | n | thread=<name> | source | message | [data]")
        logger.close()
    }

    @Test
    fun `logger writes structured event entries`() {
        val logsDirectory = temporaryFolder.newFolder("logs-structured").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        logger.i(
            source = "UnifiedLoggerTest",
            message = "Testing structured log event",
            data = mapOf("screen" to "desktop-home", "build" to DesktopBuildInfo.VERSION_CODE),
        )
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("UnifiedLoggerTest")
        assertThat(logContents).contains("Testing structured log event")
        assertThat(logContents).contains("screen=\"desktop-home\"")
        assertThat(logContents).contains("build=${DesktopBuildInfo.VERSION_CODE}")
        assertThat(logContents).doesNotContain("uptimeMs=")
        assertThat(logContents).doesNotContain("seq=")
    }

    @Test
    fun `logger writes warning and error entries`() {
        val logsDirectory = temporaryFolder.newFolder("logs-errors").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        logger.w(
            source = "UnifiedLoggerTest",
            message = "Warning branch",
            data = mapOf("route" to "settings"),
        )
        logger.e(
            source = "UnifiedLoggerTest",
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

    @Test
    fun `error entries include the cause chain`() {
        val logsDirectory = temporaryFolder.newFolder("logs-cause").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        logger.e(
            source = "UnifiedLoggerTest",
            message = "Error with cause",
            error = IllegalStateException("outer", IllegalArgumentException("inner")),
        )
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("causeChain=")
        assertThat(logContents).contains("IllegalArgumentException:inner")
    }

    @Test
    fun `debug and synchronous error entries are written with their level markers`() {
        val logsDirectory = temporaryFolder.newFolder("logs-levels").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
                // This test asserts the DEBUG marker, so the gate must be on.
                debugEnabled = true,
            )

        logger.d(source = "UnifiedLoggerTest", message = "Debug branch")
        // Synthetic deep stack: e() keeps only MAX_STACK_FRAMES (3), eSync keeps
        // MAX_CRASH_STACK_FRAMES (64). Asserting a frame index > 3 proves the crash
        // budget is really in force — swapping eSync for e() must fail this test.
        val deepError =
            IllegalStateException("boom").apply {
                stackTrace =
                    Array(6) { index ->
                        StackTraceElement("com.example.Crash", "crashFrame$index", "Crash$index.kt", index + 1)
                    }
            }
        logger.eSync(
            source = "UnifiedLoggerTest",
            message = "Crash-imminent branch",
            error = deepError,
        )
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("| DEBUG |")
        assertThat(logContents).contains("| ERROR |")
        assertThat(logContents).contains("Crash-imminent branch")
        assertThat(logContents).contains("boom")
        assertThat(logContents).contains("crashFrame5")
        assertThat(logContents).contains("crashFrame3")
    }

    @Test
    fun `logger sanitizes paths and credentials in messages and data`() {
        val logsDirectory = temporaryFolder.newFolder("logs-sanitized").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        logger.i(
            source = "UnifiedLoggerTest",
            message = "Export written to /home/someone/AppData/Local/Payanam/export.db",
            data =
                mapOf(
                    "exportTarget" to "/home/someone/data/export.db",
                    "otp" to 123456,
                    "taskCount" to 42,
                ),
        )
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        // The desktop write path previously applied no sanitization at all.
        assertThat(logContents).contains("<path>")
        assertThat(logContents).doesNotContain("/home/someone")
        // Credential values are redacted even when numeric. Values are written quoted, so the
        // assertion matches the formatter's output.
        assertThat(logContents).contains("otp=\"<redacted>\"")
        // Aggregates survive, so the log is still diagnosable.
        assertThat(logContents).contains("taskCount=42")
    }

    @Test
    fun `logger redacts a path carried in a logged error message`() {
        val logsDirectory = temporaryFolder.newFolder("logs-error-path").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
            )

        logger.e(
            source = "UnifiedLoggerTest",
            message = "Database open failed",
            error = IllegalStateException("Failed to open /home/someone/data/payanam.db"),
        )
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).doesNotContain("/home/someone")
        assertThat(logContents).contains("<path>")
    }

    @Test
    fun `debug entries are withheld when the gate is off`() {
        val logsDirectory = temporaryFolder.newFolder("logs-debug-off").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
                debugEnabled = false,
            )

        logger.d(source = "UnifiedLoggerTest", message = "verbose debug detail")
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).doesNotContain("verbose debug detail")
        assertThat(logContents).contains("Debug logging: off")
    }

    @Test
    fun `debug entries are written when the gate is on`() {
        val logsDirectory = temporaryFolder.newFolder("logs-debug-on").toPath()
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
                debugEnabled = true,
            )

        logger.d(source = "UnifiedLoggerTest", message = "verbose debug detail")
        logger.close()

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("verbose debug detail")
        assertThat(logContents).contains("DEBUG")
        assertThat(logContents).contains("Debug logging: on")
    }

    @Test
    fun `startup keeps the newest twenty session logs and leaves archives alone`() {
        val logsDirectory = temporaryFolder.newFolder("logs-retention").toPath()
        val base = Instant.parse("2026-03-01T00:00:00Z")
        // One more session log than the retention count, so exactly the oldest must be pruned.
        repeat(21) { index ->
            val file = logsDirectory.resolve("old_$index.log")
            Files.writeString(file, "session $index\n")
            Files.setLastModifiedTime(file, FileTime.from(base.plusSeconds(index.toLong())))
        }
        // An exported archive is not a session log, so the prune must leave it alone.
        val archive = logsDirectory.resolve("payanam_logs_${osToken(DesktopBuildInfo.PLATFORM)}_fixture.zip")
        Files.writeString(archive, "archive")

        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { base.plusSeconds(1_000) },
            )
        logger.close()

        // The 21st-newest file goes; the 20 newest stay, plus this session's own log.
        assertThat(Files.exists(logsDirectory.resolve("old_0.log"))).isFalse()
        assertThat(Files.exists(logsDirectory.resolve("old_1.log"))).isTrue()
        assertThat(Files.exists(archive)).isTrue()
        val retainedOldLogs =
            Files.list(logsDirectory).use { entries ->
                entries.filter { path -> path.fileName.toString().startsWith("old_") }.count()
            }
        assertThat(retainedOldLogs).isEqualTo(20L)
    }

    @Test
    fun `exported copies under the exports subdirectory neither count as sessions nor get pruned`() {
        val logsDirectory = temporaryFolder.newFolder("logs-exports-retention").toPath()
        val exportsDirectory = logsDirectory.resolve("exports")
        Files.createDirectories(exportsDirectory)
        val base = Instant.parse("2026-03-01T00:00:00Z")
        // The copy is the OLDEST file on disk, so a recursive prune would take it first.
        val copy = exportsDirectory.resolve("payanam_desktop_1.0.0_abc1234_debug_20260201_000000.log")
        Files.writeString(copy, "copy\n")
        Files.setLastModifiedTime(copy, FileTime.from(base.minusSeconds(1)))
        repeat(21) { index ->
            val file = logsDirectory.resolve("old_$index.log")
            Files.writeString(file, "session $index\n")
            Files.setLastModifiedTime(file, FileTime.from(base.plusSeconds(index.toLong())))
        }

        val logger =
            UnifiedLogger.initialize(
                logsDirectory = logsDirectory,
                clock = { base.plusSeconds(1_000) },
            )
        logger.close()

        // The session prune still keeps 20 and still drops the oldest session log...
        assertThat(Files.exists(logsDirectory.resolve("old_0.log"))).isFalse()
        assertThat(Files.exists(logsDirectory.resolve("old_1.log"))).isTrue()
        // ...while the copy is neither counted among them nor deleted.
        assertThat(Files.isRegularFile(copy)).isTrue()
    }

    @Test
    fun `the debug gate starts from the build type unless the environment overrides it`() {
        // Unset: the build type decides, which is how Android falls back to BuildConfig.DEBUG.
        assertThat(resolveDefaultDebugLogging(envValue = null, debugBuild = true)).isTrue()
        assertThat(resolveDefaultDebugLogging(envValue = null, debugBuild = false)).isFalse()

        // Set: the environment wins, so a release build can be debugged and a debug build quietened
        // without a rebuild. An explicit non-"true" value is an explicit OFF, not "fall through".
        assertThat(resolveDefaultDebugLogging(envValue = "true", debugBuild = false)).isTrue()
        assertThat(resolveDefaultDebugLogging(envValue = "TRUE", debugBuild = false)).isTrue()
        assertThat(resolveDefaultDebugLogging(envValue = "false", debugBuild = true)).isFalse()
        assertThat(resolveDefaultDebugLogging(envValue = "", debugBuild = true)).isFalse()
    }
}
