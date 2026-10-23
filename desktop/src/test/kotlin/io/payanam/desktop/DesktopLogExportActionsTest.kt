//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * The desktop's debug-log export buttons, rendered for real on the JVM target.
 *
 * WHY THIS FILE EXISTS: the on-screen result line used to be the ONLY feedback an export had, so "it did
 * nothing" and "it wrote the file" were indistinguishable from outside. The log lines this test pins are
 * what make an export reviewable afterwards - one requested line and one outcome line per click - and the
 * invariant they carry is the interesting half: the LOG names only the exported file's NAME, while the
 * on-screen line still carries the absolute path, because that is the answer the user asked for.
 *
 * Each case asserts an observable that can fail: the requested line's action value, the outcome line's
 * outcome/fileName values (quoted by the logger's own formatter), the artifact on disk, and the absence of
 * the absolute path from the log while the screen line has it.
 */
class DesktopLogExportActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private lateinit var logsDirectory: File

    @Before
    fun setUp() {
        // The memoized singleton, so both hooks are needed: one to point it at this test's directory and
        // one to keep a leaked instance from pointing later classes at a deleted TemporaryFolder.
        UnifiedLogger.resetForTests()
        logsDirectory = temporaryFolder.newFolder("logs")
        UnifiedLogger.initialize(
            logsDirectory = logsDirectory.toPath(),
            clock = { Instant.parse("2026-03-04T05:06:07Z") },
            debugEnabled = true,
        )
    }

    @After
    fun tearDown() {
        // Leave a VALID singleton behind: this module runs every class in one JVM, and several classes rely
        // on a leaked instance. Resetting to nothing here would break them; pointing the leak at a persistent
        // directory keeps it writable.
        UnifiedLogger.resetForTests()
        UnifiedLogger.initialize(logsDirectory = Files.createTempDirectory("payanam-log-export-after"))
    }

    private fun render() {
        composeRule.setContent {
            io.payanam.core.ui.theme.PayanamTheme(darkTheme = true) {
                CompositionLocalProvider(LocalUiStrings provides strings) {
                    DesktopLogExportActions()
                }
            }
        }
    }

    /** The session log's text; empty until the logger's file exists. */
    private fun logText(): String {
        val path = UnifiedLogger.getInstance().getLogPath()
        return if (Files.isRegularFile(path)) Files.readString(path) else ""
    }

    @Test
    fun `no result line is drawn before any click`() {
        render()

        // The positive control: the two buttons ARE rendered, so the absences below are about the result
        // line and not about a screen that failed to compose.
        composeRule.onNodeWithText(strings.settingsActionExportLatestLog).assertExists()
        composeRule.onNodeWithText(strings.settingsActionExportAllLogs).assertExists()
        composeRule.onAllNodesWithText(strings.settingsSnackbarLatestLogExportFailed).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsSnackbarAllLogsExportFailed).assertCountEquals(0)
    }

    @Test
    fun `the latest-log button exports, logs both lines, and the log carries the name while the screen carries the path`() {
        render()
        // The export copies THIS session's log, so one line must exist first or the outcome is the
        // failure arm - which is its own case below.
        UnifiedLogger.getInstance().i("test", "seed the session log")

        composeRule.onNodeWithText(strings.settingsActionExportLatestLog).performClick()

        val artifact = logsDirectory.resolve("payanam_desktop_latest_export.log")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            logText().contains("action=\"exportLatestLog\"")
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            logText().contains("Debug log exported")
        }
        val text = logText()
        assertThat(text).contains("outcome=\"exported\"")
        // The fileName VALUE is the shared LogSanitizer's business - it redacts path-like data values, which
        // is exactly why the absolute-path invariant below holds - so the arm is asserted through `outcome`,
        // which the sanitizer leaves alone.
        assertThat(text).contains("fileName=\"")
        assertThat(artifact.exists()).isTrue()

        // The invariant: the LOG never carries the absolute path...
        assertThat(text).doesNotContain(logsDirectory.absolutePath)
        // ...while the on-screen line does - that is the answer the user asked for.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText(strings.settingsSnackbarLatestLogExported.formatArgs(artifact.absolutePath))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `the all-logs button writes the archive and logs its own action`() {
        render()
        UnifiedLogger.getInstance().i("test", "seed the session log")

        composeRule.onNodeWithText(strings.settingsActionExportAllLogs).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            logText().contains("action=\"exportAllLogs\"")
        }
        assertThat(logText()).contains("outcome=\"exported\"")
        val archives = logsDirectory.listFiles().orEmpty().filter { it.name.endsWith(".zip") }
        assertThat(archives).hasSize(1)
        assertThat(archives.single().name).startsWith("payanam_desktop_all_logs_")
        // The archive is a copy: the session log it was made from is still there, append-only.
        assertThat(
            logsDirectory
                .resolve(
                    UnifiedLogger
                        .getInstance()
                        .getLogPath()
                        .fileName
                        .toString(),
                ).exists(),
        ).isTrue()
    }

    @Test
    fun `an export that cannot write its target logs the failure arm rather than a silent nothing`() {
        render()
        UnifiedLogger.getInstance().i("test", "seed the session log")

        // The export copies into the SAME directory; a directory that refuses new files makes the copy fail
        // while the open session log keeps accepting appends through its existing handle - the exact split
        // the failure arm exists for, and the reason the file is not deleted (a deleted file under an open
        // writer would swallow the log lines this case asserts).
        val dir = logsDirectory.toPath()
        val original = Files.getPosixFilePermissions(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            composeRule.onNodeWithText(strings.settingsActionExportLatestLog).performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                logText().contains("Debug log exported")
            }
            val text = logText()
            assertThat(text).contains("action=\"exportLatestLog\"")
            // The failure arm, asserted through `outcome` - the shared LogSanitizer redacts the fileName
            // value on BOTH arms, so the outcome key is the one the log leaves legible.
            assertThat(text).contains("outcome=\"failed\"")
            assertThat(text).contains("fileName=\"")
            // The screen reports the failure rather than a path.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule
                    .onAllNodesWithText(strings.settingsSnackbarLatestLogExportFailed)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } finally {
            Files.setPosixFilePermissions(dir, original)
        }
    }
}
