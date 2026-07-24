//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files

class DesktopSingleInstanceGuardTest {
    @Test
    fun `acquire returns running process when lock already held`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-test")
        val firstAcquire =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 41234L,
            )

        assertThat(firstAcquire).isInstanceOf(DesktopSingleInstanceAcquireResult.Acquired::class.java)

        val secondAcquire =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 51234L,
            )

        assertThat(secondAcquire).isInstanceOf(DesktopSingleInstanceAcquireResult.AlreadyRunning::class.java)
        val alreadyRunning = (secondAcquire as DesktopSingleInstanceAcquireResult.AlreadyRunning).details
        assertThat(alreadyRunning.processId).isEqualTo(41234L)
        assertThat(alreadyRunning.buildName).contains("Payanam_Windows_")
        assertThat(alreadyRunning.versionDisplayName).contains("#W")
        assertThat(alreadyRunning.acquiredAt).isNotNull()
        assertThat(alreadyRunning.executablePath).isNotNull()

        (firstAcquire as DesktopSingleInstanceAcquireResult.Acquired).lease.close()
    }

    @Test
    fun `lease records log path into running instance details`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-log-path")
        val firstAcquire =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 81234L,
            ) as DesktopSingleInstanceAcquireResult.Acquired

        val logPath = runtimeDirectory.resolve("logs").resolve("payanam_desktop_test.log")
        firstAcquire.lease.recordSessionLogPath(logFilePath = logPath)

        val secondAcquire =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 91234L,
            ) as DesktopSingleInstanceAcquireResult.AlreadyRunning

        assertThat(secondAcquire.details.logFilePath).isEqualTo(logPath.toString())

        firstAcquire.lease.close()
    }

    @Test
    fun `release allows future acquire`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-release")
        val firstAcquire =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 61234L,
            ) as DesktopSingleInstanceAcquireResult.Acquired

        firstAcquire.lease.close()

        val secondAcquire =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 71234L,
            )

        assertThat(secondAcquire).isInstanceOf(DesktopSingleInstanceAcquireResult.Acquired::class.java)
        (secondAcquire as DesktopSingleInstanceAcquireResult.Acquired).lease.close()
    }
}
