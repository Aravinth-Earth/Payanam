//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.database.DesktopBackupStatusPort
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties

/**
 * A failure seeded in the status file BEFORE the port is constructed reaches the preferences.
 *
 * The three real runs could not prove this path: they recorded failures in-memory via [recordFailure],
 * which writes the file AND updates the flow. This test isolates the file-seeded path — write the
 * properties file first, then construct the port, and assert the constructor's read surfaces the
 * failure in the preferences state.
 */
class DesktopBackupStatusFileSeedTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private companion object {
        const val AWAIT_MILLIS = 5_000L
        private const val SEEDED_MESSAGE = "Seeded failure for the test"
    }

    private fun sourceWith(port: DesktopBackupStatusPort) =
        DesktopPreferencesSource(
            sessionLogger = NoOpUnifiedLogger,
            systemLanguageTag = "en",
            backupStatusStore = port,
        )

    private fun seedStatusFile(message: String): java.nio.file.Path {
        val dir = temporaryFolder.newFolder().toPath()
        val file = DesktopBackupStatusPort.backupStatusFileIn(dir)
        val properties = Properties()
        properties.setProperty("last_backup_failure_at_millis", System.currentTimeMillis().toString())
        properties.setProperty("last_backup_failure_display", "2026-10-08 12:00:00")
        properties.setProperty("last_backup_failure_message", message)
        file.toFile().outputStream().use { os ->
            properties.store(os, "Payanam Backup Status")
        }
        return dir
    }

    @Test
    fun `a properties file seeded before construction surfaces the failure in preferences`() =
        runBlocking {
            val dir = seedStatusFile(SEEDED_MESSAGE)
            val port = DesktopBackupStatusPort(preferencesDirectory = dir)

            // Prove the constructor read the file
            assertThat(port.status.value.latestFailure).isNotNull()
            assertThat(
                port.status.value.latestFailure
                    ?.message,
            ).isEqualTo(SEEDED_MESSAGE)

            val source = sourceWith(port)

            // Awaited, not first(): the combine runs on its own Dispatchers.Default scope
            val state =
                withTimeout(AWAIT_MILLIS) {
                    source.preferences.first { it.autoBackupLastErrorMessage != null }
                }
            assertThat(state.autoBackupLastErrorMessage).isEqualTo(SEEDED_MESSAGE)
        }

    @Test
    fun `a blank seeded message loads as no failure`() =
        runBlocking {
            val dir = seedStatusFile("")
            val port = DesktopBackupStatusPort(preferencesDirectory = dir)

            // Blank message → no failure (the documented rule)
            assertThat(port.status.value.latestFailure).isNull()

            val source = sourceWith(port)

            // autoBackupLastErrorMessage stays null
            val state = withTimeout(AWAIT_MILLIS) { source.preferences.first() }
            assertThat(state.autoBackupLastErrorMessage).isNull()
        }
}
