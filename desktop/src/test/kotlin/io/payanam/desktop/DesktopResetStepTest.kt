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
 * Covers the attribution added to the forgot-passphrase reset: an aborted sequence must name the step
 * that failed, and a successful step must stay silent so the stores' own logging is not duplicated.
 */
class DesktopResetStepTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var logger: UnifiedLogger

    @Before
    fun setUp() {
        UnifiedLogger.resetForTests()
        logger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("logs-reset").toPath(),
                clock = { Instant.parse("2026-03-26T05:15:30Z") },
                debugEnabled = false,
            )
    }

    @After
    fun tearDown() {
        logger.close()
        UnifiedLogger.resetForTests()
    }

    @Test
    fun `an aborted step names itself and rethrows the original failure`() {
        val failure = IllegalStateException("store exploded")

        val thrown =
            runCatching {
                runResetStep(ResetStep.RESET_SECURITY_STATE, logger) { throw failure }
            }.exceptionOrNull()

        // Same instance, not a wrapper: callers still see the type they would have seen before.
        assertThat(thrown).isSameInstanceAs(failure)
        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("Passphrase reset aborted")
        assertThat(logContents).contains("reset_security_state")
    }

    @Test
    fun `a successful step writes nothing, so no store success is duplicated`() {
        runResetStep(ResetStep.RESET_SECURITY_STATE, logger) { }

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).doesNotContain("Passphrase reset aborted")
        assertThat(logContents).doesNotContain("reset_security_state")
    }

    @Test
    fun `the abort entry names the step without echoing the failure text`() {
        runCatching {
            runResetStep(ResetStep.RELOAD_BOOTSTRAP_SNAPSHOT, logger) {
                throw IllegalStateException("cannot read /home/someone/AppData/payanam.db")
            }
        }

        val logContents = Files.readString(logger.getLogPath())
        assertThat(logContents).contains("reload_bootstrap_snapshot")
        // The failure's class is recorded - it is what distinguishes a disk error from bad state.
        assertThat(logContents).contains("IllegalStateException")
        // ...but never the message: exception text routinely carries paths.
        assertThat(logContents).doesNotContain("/home/someone")
        assertThat(logContents).doesNotContain("cannot read")
    }
}
