//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.osToken
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lease-lifecycle tests for [DesktopSingleInstanceGuard].
 *
 * Complements [DesktopSingleInstanceGuardTest] by asserting the falsification control (two independent
 * holders cannot both win) and the on-disk artefacts that prove the lease was acquired and released.
 */
class DesktopSingleInstanceGuardLeaseTest {
    @Test
    fun `two independent acquires on the same runtime directory cannot both win`() {
        // FALSIFICATION CONTROL: the single-instance rule is violated if a second acquire succeeds
        // while the first lease is still held. This is the core invariant.
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-falsification")

        val first =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 11111L,
            ) as DesktopSingleInstanceAcquireResult.Acquired

        val second =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 22222L,
            )

        assertThat(second).isInstanceOf(DesktopSingleInstanceAcquireResult.AlreadyRunning::class.java)
        val details = (second as DesktopSingleInstanceAcquireResult.AlreadyRunning).details
        assertThat(details.processId).isEqualTo(11111L)

        first.lease.close()
    }

    @Test
    fun `lock and metadata files exist after acquire and are removed after lease close`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-artefacts")
        val lockFilePath = runtimeDirectory.resolve("desktop-single-instance.lock")
        val metadataFilePath = runtimeDirectory.resolve("desktop-single-instance.properties")

        val acquired =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 33333L,
            ) as DesktopSingleInstanceAcquireResult.Acquired

        // Both files must exist while the lease is held.
        assertThat(Files.exists(lockFilePath)).isTrue()
        assertThat(Files.exists(metadataFilePath)).isTrue()

        acquired.lease.close()

        // After close(), both files must be gone — a stale lock file would block the next launch.
        assertThat(Files.exists(lockFilePath)).isFalse()
        assertThat(Files.exists(metadataFilePath)).isFalse()
    }

    @Test
    fun `re-acquire after lease close succeeds with a fresh lease`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-reacquire")

        val first =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 44444L,
            ) as DesktopSingleInstanceAcquireResult.Acquired
        first.lease.close()

        val second =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 55555L,
            )

        assertThat(second).isInstanceOf(DesktopSingleInstanceAcquireResult.Acquired::class.java)
        (second as DesktopSingleInstanceAcquireResult.Acquired).lease.close()
    }

    @Test
    fun `alreadyRunning details carry the first holder processId and build info`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-details")

        val first =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 66666L,
            ) as DesktopSingleInstanceAcquireResult.Acquired

        val second =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 77777L,
            ) as DesktopSingleInstanceAcquireResult.AlreadyRunning

        assertThat(second.details.processId).isEqualTo(66666L)
        assertThat(second.details.buildName).contains("Payanam_${osToken(DesktopBuildInfo.PLATFORM)}_")
        assertThat(second.details.versionDisplayName)
            .contains(DesktopBuildInfo.VERSION_DISPLAY_NAME.substringBefore(" "))
        assertThat(second.details.acquiredAt).isNotNull()
        assertThat(second.details.executablePath).isNotNull()

        first.lease.close()
    }

    @Test
    fun `lease close is idempotent — second close does not throw`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-single-instance-idempotent")

        val acquired =
            DesktopSingleInstanceGuard.acquire(
                runtimeDirectory = runtimeDirectory,
                processId = 88888L,
            ) as DesktopSingleInstanceAcquireResult.Acquired

        acquired.lease.close()
        // A second close() must not throw — the production code path may call close() from both
        // the finally block and the shutdown hook.
        acquired.lease.close()

        // And the runtime directory is clean.
        val lockFilePath = runtimeDirectory.resolve("desktop-single-instance.lock")
        assertThat(Files.exists(lockFilePath)).isFalse()
    }
}
