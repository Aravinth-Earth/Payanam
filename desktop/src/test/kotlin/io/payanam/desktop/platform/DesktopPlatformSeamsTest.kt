//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopBuildInfo
import io.payanam.desktop.reconcileAfterSettingChange
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.NoOpUnifiedLogger
import io.payanam.shared.platform.BackupBackgroundCapability
import io.payanam.shared.platform.SupportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Tests for the desktop platform seams.
 *
 * These exist because the desktop's seams are where a capability the platform lacks could quietly become a
 * silent no-op. Each one is asserted in both directions: the seam does what it says when the platform can,
 * and REPORTS the limitation when it cannot, so a screen can explain itself instead of offering a control
 * that does nothing.
 */
class DesktopPlatformSeamsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- backups ----

    @Test
    fun `a non-positive interval is rejected rather than treated as off`(): Unit =
        runBlocking {
            val scheduler = DesktopBackupScheduler(scope, NoOpUnifiedLogger, runBackup = {})
            // Silently treating a bad interval as "disabled" would leave the user believing backups run.
            val zero = runCatching { scheduler.schedule(0) }
            val negative = runCatching { scheduler.schedule(-5) }
            assertThat(zero.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(negative.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(scheduler.intervalMinutes).isNull()
        }

    @Test
    fun `scheduling records the interval and cancelling clears it`(): Unit =
        runBlocking {
            val scheduler = DesktopBackupScheduler(scope, NoOpUnifiedLogger, runBackup = {})
            scheduler.schedule(60)
            assertThat(scheduler.intervalMinutes).isEqualTo(60)
            assertThat(scheduler.capability).isEqualTo(BackupBackgroundCapability.FOREGROUND_ONLY)
            scheduler.cancel()
            assertThat(scheduler.intervalMinutes).isNull()
        }

    @Test
    fun `a changed interval replaces the previous schedule`(): Unit =
        runBlocking {
            val scheduler = DesktopBackupScheduler(scope, NoOpUnifiedLogger, runBackup = {})
            scheduler.schedule(60)
            scheduler.schedule(15)
            assertThat(scheduler.intervalMinutes).isEqualTo(15)
        }

    @Test
    fun `a write of either backup key reconciles the schedule and any other key does not`(): Unit =
        runBlocking {
            val rows =
                mutableMapOf<String, String?>(
                    AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED to "true",
                    AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL to "2h",
                )
            val seams =
                DesktopPlatformSeams(
                    scope,
                    NoOpUnifiedLogger,
                    runBackup = {},
                    backupRows = { rows },
                )
            // The negative control FIRST: a non-backup key must leave the schedule alone, or the cases
            // below would pass on a reconcile that fires for every row.
            reconcileAfterSettingChange(seams, AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES)
            assertThat(seams.backupIntervalMinutes.value).isNull()
            reconcileAfterSettingChange(seams, AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL)
            assertThat(seams.backupIntervalMinutes.value).isEqualTo(120)
            rows[AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED] = "false"
            reconcileAfterSettingChange(seams, AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED)
            assertThat(seams.backupIntervalMinutes.value).isNull()
        }

    // ---- capabilities and debug flags ----

    @Test
    fun `the desktop reports every capability it lacks`() {
        val capabilities = desktopCapabilities()
        assertThat(capabilities.biometrics).isEqualTo(SupportState.NOT_APPLICABLE)
        assertThat(capabilities.supportsDynamicColor).isFalse()
        assertThat(capabilities.supportsHaptics).isFalse()
        assertThat(capabilities.reminders).isEqualTo(SupportState.NOT_SURFACED)
        assertThat(capabilities.backupBackground).isEqualTo(SupportState.IN_PROCESS_ONLY)
        assertThat(capabilities.hasLimitations).isTrue()
    }

    @Test
    fun `debug logging starts at the build type's default and reports the value in force`() {
        val flags = DesktopDebugFlags()
        assertThat(flags.debugLoggingEnabled).isEqualTo(DesktopBuildInfo.IS_DEBUG_BUILD)
        assertThat(flags.setDebugLoggingEnabled(true)).isTrue()
        assertThat(flags.debugLoggingEnabled).isTrue()
    }
}
