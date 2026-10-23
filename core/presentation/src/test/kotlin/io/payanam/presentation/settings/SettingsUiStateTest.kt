//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.settings

import io.payanam.presentation.download.DownloadUiState
import io.payanam.shared.updates.ChannelStatus
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateCheckResult
import io.payanam.shared.updates.UpdateOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state contract of the Settings screen, on its own.
 *
 * The platform facts (version, schema, channel) are constructor arguments here, which is the point
 * this file pins: nothing about them may become a default again, because a default reports a value
 * nothing read. Every other field has a default, and the defaults are what a caller gets before the
 * first load completes, so they are asserted field by field rather than assumed.
 */
class SettingsUiStateTest {
    private fun state(
        updateCheckResult: UpdateCheckResult? = null,
        channel: UpdateChannel = UpdateChannel.STABLE,
    ) = SettingsUiState(
        currentDatabaseSchemaVersion = 42,
        minimumSupportedSchemaVersion = 3,
        appVersion = "1.2.3",
        buildNumber = 1234,
        updateChannel = channel,
        updateCheckResult = updateCheckResult,
    )

    @Test
    fun `the five platform facts are carried verbatim, never substituted`() {
        val state = state()
        assertEquals(42, state.currentDatabaseSchemaVersion)
        assertEquals(3, state.minimumSupportedSchemaVersion)
        assertEquals("1.2.3", state.appVersion)
        assertEquals(1234, state.buildNumber)
        assertEquals(UpdateChannel.STABLE, state.updateChannel)
    }

    @Test
    fun `defaults are the unmeasured state, not invented values`() {
        val state = state()
        assertEquals(0, state.taskCount)
        assertEquals(0, state.timeEntryCount)
        assertEquals(0, state.noteCount)
        assertEquals(0L, state.databaseSizeKb)
        assertEquals(emptyList<DatabaseArtifactUiModel>(), state.databaseArtifacts)
        assertEquals(0, state.importedUhabitsHabitCount)
        assertEquals(10, state.unlockSessionTimeoutMinutes)
        assertFalse(state.biometricUnlockEnabled)
        assertFalse(state.isExporting)
        assertFalse(state.isImporting)
        assertFalse(state.isUhabitsImporting)
        assertFalse(state.isBulkMappingImportedHabits)
        assertNull(state.exportResult)
        assertNull(state.importResult)
        assertNull(state.uhabitsImportResult)
        assertNull(state.bulkHabitMappingResult)
        assertFalse(state.showDeleteExportPrompt)
        assertFalse(state.awaitingImportPassphrase)
        assertNull(state.importPassphraseError)
        assertFalse(state.isCheckingForUpdate)
        assertFalse(state.autoDownloadEnabled)
        assertFalse(state.promptInstallEnabled)
        assertFalse(state.wifiOnlyEnabled)
        assertFalse(state.autoCheckEnabled)
        assertEquals(DownloadUiState.Idle, state.downloadState)
        assertNull(state.pendingInstallPath)
        assertFalse(state.isFDroidBuild)
    }

    @Test
    fun `the three result types are independently absent, not defaulted together`() {
        val only = state().copy(exportResult = ExportResult.Success("payanam.db"))
        assertEquals("payanam.db", (only.exportResult as ExportResult.Success).fileName)
        assertNull(only.importResult)
        assertNull(only.uhabitsImportResult)
        assertNull(only.bulkHabitMappingResult)
    }

    @Test
    fun `copy changes one field and leaves the rest alone`() {
        val before = state()
        val after = before.copy(buildNumber = 1235)
        assertEquals(1235, after.buildNumber)
        assertEquals(before.appVersion, after.appVersion)
        assertEquals(before.currentDatabaseSchemaVersion, after.currentDatabaseSchemaVersion)
        assertEquals(before.updateChannel, after.updateChannel)
        assertEquals(before.taskCount, after.taskCount)
    }

    @Test
    fun `equality is by value, so a reload to the same numbers is not a change`() {
        assertEquals(state(), state())
        assertNotEquals(state(), state().copy(noteCount = 1))
    }

    @Test
    fun `a missing update result is stale, so the screen re-checks instead of showing nothing`() {
        assertTrue(state().isUpdateResultStale())
    }

    @Test
    fun `a fresh result is not stale and an old one is`() {
        val now = System.currentTimeMillis()
        val fresh =
            UpdateCheckResult(
                outcome = UpdateOutcome.UP_TO_DATE,
                latestBuildNumber = 1234,
                releaseUrl = null,
                error = null,
                checkedAtMs = now,
            )
        assertFalse(state(fresh).isUpdateResultStale())

        // One second inside the window, not sitting on the 15-minute line: the predicate reads the
        // clock again when it runs, so a value placed exactly on the boundary turns stale on the
        // next millisecond and this test would flake on a slow machine while passing in isolation.
        val justInside =
            UpdateCheckResult(
                outcome = UpdateOutcome.UP_TO_DATE,
                latestBuildNumber = 1234,
                releaseUrl = null,
                error = null,
                checkedAtMs = now - 15 * 60 * 1000L + 1000L,
            )
        assertFalse(state(justInside).isUpdateResultStale())

        // One millisecond past the line, so it is stale on the stale side no matter how much time
        // passes between reading the clock here and reading it again inside the predicate.
        val justOutside =
            UpdateCheckResult(
                outcome = UpdateOutcome.UP_TO_DATE,
                latestBuildNumber = 1234,
                releaseUrl = null,
                error = null,
                checkedAtMs = now - 15 * 60 * 1000L - 1,
            )
        assertTrue(state(justOutside).isUpdateResultStale())
    }

    @Test
    fun `the staleness window is 15 minutes, measured from the result's own timestamp`() {
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.UPDATE_AVAILABLE,
                latestBuildNumber = 2000,
                releaseUrl = "https://example.invalid/v2000",
                error = null,
                channelStatuses =
                    listOf(
                        ChannelStatus(
                            channel = UpdateChannel.DEV,
                            buildNumber = 2000,
                            releaseUrl = "https://example.invalid/v2000",
                            apkDownloadUrl = "https://example.invalid/app.apk",
                            apkSha256Url = "https://example.invalid/app.apk.sha256",
                        ),
                    ),
                checkedAtMs = 0L,
            )
        assertTrue(state(result).isUpdateResultStale())
    }

    @Test
    fun `a failed check keeps its error and its channel alongside`() {
        val failed =
            UpdateCheckResult(
                outcome = UpdateOutcome.FAILED,
                latestBuildNumber = null,
                releaseUrl = null,
                error = UpdateCheckError.RATE_LIMITED,
                checkedAtMs = System.currentTimeMillis(),
            )
        val withResult = state(failed, channel = UpdateChannel.BETA)
        assertEquals(UpdateCheckError.RATE_LIMITED, withResult.updateCheckResult?.error)
        assertEquals(UpdateChannel.BETA, withResult.updateChannel)
    }

    @Test
    fun `a download in flight does not disturb the fields the settings rows own`() {
        val downloading = DownloadUiState.Downloading("app.apk", 40, 100)
        val state = state().copy(downloadState = downloading, pendingInstallPath = "/tmp/payanam.apk")
        assertSame(downloading, state.downloadState)
        assertEquals("/tmp/payanam.apk", state.pendingInstallPath)
        assertEquals(42, state.currentDatabaseSchemaVersion)
    }
}
