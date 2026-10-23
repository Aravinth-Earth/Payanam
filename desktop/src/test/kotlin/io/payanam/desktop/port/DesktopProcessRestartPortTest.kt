//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.port.ProcessRestartOutcome
import io.payanam.domain.port.ProcessRestartRefusal
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The desktop's [io.payanam.domain.port.ProcessRestart], driven end to end WITHOUT starting a process.
 *
 * ## WHY NOTHING HERE SPAWNS
 *
 * The production spawn is the one line in this port that cannot be exercised in a test JVM: starting the
 * installed launcher would open a second real Payanam, and it would lose the single-instance lock race this
 * very port is documented to exist around. So the launch, the command lookup and the session fold are
 * constructor seams, and every test below injects its own. What is under test is therefore the port's
 * DECISION LOGIC and its ORDERING - which is everything that can actually go wrong here - and not the JDK's
 * ability to start a process.
 *
 * ## WHY THE MARKER, NOT THE LOG, IS THE ASSERTION
 *
 * The marker is the artefact that has to survive this process ending, so it is the only thing worth
 * asserting about. A log line can be present while the marker is not, which is exactly the state where a
 * restart happened and left nothing to explain it.
 */
class DesktopProcessRestartPortTest {
    private val fixedInstant = Instant.parse("2026-09-28T09:15:00Z")
    private val launcherPath = "/opt/payanam/bin/Payanam"

    @Test
    fun `an empty launch command is refused, and nothing is written or started`() {
        val fixture = Fixture(command = "")

        val outcome = fixture.port.restartProcess()

        assertThat((outcome as ProcessRestartOutcome.Refused).reason).isEqualTo(ProcessRestartRefusal.NO_LAUNCH_COMMAND)
        // A refusal that had already written a marker would leave a trail for a restart that never
        // happened, and the next launch would read it as one.
        assertThat(Files.exists(fixture.markerPath)).isFalse()
        assertThat(fixture.spawnCount).isEqualTo(0)
        assertThat(fixture.checkpointCount).isEqualTo(0)
    }

    @Test
    fun `a whitespace-only launch command is refused the same way`() {
        // Not contrived: the command is read from the OS and trimmed, so a command that is present but
        // blank must not slip past the emptiness check.
        val fixture = Fixture(command = "   ")

        val outcome = fixture.port.restartProcess()

        assertThat((outcome as ProcessRestartOutcome.Refused).reason).isEqualTo(ProcessRestartRefusal.NO_LAUNCH_COMMAND)
        assertThat(Files.exists(fixture.markerPath)).isFalse()
        assertThat(fixture.spawnCount).isEqualTo(0)
    }

    @Test
    fun `a JVM launch command is refused rather than exec'd`() {
        val fixture = Fixture(command = "/usr/lib/jvm/java-17-openjdk-amd64/bin/java")

        val outcome = fixture.port.restartProcess()

        assertThat((outcome as ProcessRestartOutcome.Refused).reason).isEqualTo(ProcessRestartRefusal.LAUNCH_COMMAND_IS_A_JVM)
        // Exec'ing this would start a JVM with no main class and no classpath: a process that exits at
        // once having shown no window. That silent no-op is exactly what this refusal prevents.
        assertThat(Files.exists(fixture.markerPath)).isFalse()
        assertThat(fixture.spawnCount).isEqualTo(0)
        assertThat(fixture.checkpointCount).isEqualTo(0)
    }

    @Test
    fun `a bare javaw command is refused too`() {
        val fixture = Fixture(command = "javaw")

        val outcome = fixture.port.restartProcess()

        assertThat((outcome as ProcessRestartOutcome.Refused).reason).isEqualTo(ProcessRestartRefusal.LAUNCH_COMMAND_IS_A_JVM)
        assertThat(fixture.spawnCount).isEqualTo(0)
    }

    @Test
    fun `a real launcher path launches, and the marker exists before the spawn`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-restart-marker-order")
        val markerPath = runtimeDirectory.resolve("desktop-restart-marker.txt")
        var markerExistedAtSpawnTime: Boolean? = null
        var markerContentAtSpawnTime: String? = null
        var spawnCount = 0

        val port =
            DesktopProcessRestartPort(
                runtimeDirectory = runtimeDirectory,
                commandOf = { launcherPath },
                spawn = { command ->
                    // THE ORDERING ASSERTION, taken from inside the seam: at the moment the replacement
                    // process is started, the marker must already be on disk. A port that spawned first
                    // reads false here, and a process dying between the two would leave no evidence.
                    markerExistedAtSpawnTime = Files.exists(markerPath)
                    markerContentAtSpawnTime =
                        if (markerExistedAtSpawnTime == true) {
                            Files.readString(markerPath, StandardCharsets.UTF_8)
                        } else {
                            null
                        }
                    assertThat(command).isEqualTo(launcherPath)
                    spawnCount++
                },
                checkpointAndClose = { },
                clock = { fixedInstant },
            )

        val outcome = port.restartProcess()

        assertThat(outcome).isEqualTo(ProcessRestartOutcome.Launched)
        assertThat(spawnCount).isEqualTo(1)
        assertThat(markerExistedAtSpawnTime).isTrue()
        assertThat(markerContentAtSpawnTime).contains("requestedAt=$fixedInstant")
        assertThat(markerContentAtSpawnTime).contains("previousCommand=$launcherPath")
        assertThat(markerContentAtSpawnTime).contains("processId=${ProcessHandle.current().pid()}")
    }

    @Test
    fun `the marker is written and is the restart record`() {
        val fixture = Fixture(command = launcherPath)

        val outcome = fixture.port.restartProcess()

        assertThat(outcome).isEqualTo(ProcessRestartOutcome.Launched)
        val marker = Files.readString(fixture.markerPath, StandardCharsets.UTF_8)
        assertThat(marker).contains("previousCommand=$launcherPath")
        assertThat(marker).contains("requestedAt=$fixedInstant")
        assertThat(fixture.spawnCount).isEqualTo(1)
    }

    @Test
    fun `the session fold runs once, and runs before the spawn, even across two restarts`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-restart-checkpoint-once")
        val events = mutableListOf<String>()

        val port =
            DesktopProcessRestartPort(
                runtimeDirectory = runtimeDirectory,
                commandOf = { launcherPath },
                spawn = { events.add("spawn") },
                checkpointAndClose = { events.add("checkpoint") },
                clock = { fixedInstant },
            )

        port.restartProcess()
        port.restartProcess()

        // First call: fold, THEN spawn - the replacement must never race a write this process still owns.
        // Second call: no second fold, because a silent no-op the log cannot distinguish from a fold that
        // never ran is worse than not calling it.
        assertThat(events).containsExactly("checkpoint", "spawn", "spawn").inOrder()
    }

    @Test
    fun `a spawn failure is refused, and the attempt is still recorded`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-restart-spawn-failure")
        val markerPath = runtimeDirectory.resolve("desktop-restart-marker.txt")
        val port =
            DesktopProcessRestartPort(
                runtimeDirectory = runtimeDirectory,
                commandOf = { launcherPath },
                spawn = { throw IOException("No such file or directory") },
                checkpointAndClose = { },
                clock = { fixedInstant },
            )

        val outcome = port.restartProcess()

        assertThat((outcome as ProcessRestartOutcome.Refused).reason).isEqualTo(ProcessRestartRefusal.SPAWN_FAILED)
        assertThat(outcome.detail).isEqualTo("No such file or directory")
        // The marker IS present even though the spawn failed: the attempt happened and the next launch
        // needs to know it did. And this process is still alive - no kill is attempted from here.
        assertThat(Files.exists(markerPath)).isTrue()
    }

    @Test
    fun `a refused restart leaves the port able to restart on a retry`() {
        val runtimeDirectory = Files.createTempDirectory("desktop-restart-retry")
        val refused =
            DesktopProcessRestartPort(
                runtimeDirectory = runtimeDirectory,
                commandOf = { "" },
                spawn = { throw AssertionError("must not spawn for an empty command") },
                checkpointAndClose = { throw AssertionError("must not fold for an empty command") },
                clock = { fixedInstant },
            )
        assertThat(refused.restartProcess()).isInstanceOf(ProcessRestartOutcome.Refused::class.java)

        var spawnCount = 0
        val retried =
            DesktopProcessRestartPort(
                runtimeDirectory = runtimeDirectory,
                commandOf = { launcherPath },
                spawn = { spawnCount++ },
                checkpointAndClose = { },
                clock = { fixedInstant },
            )

        assertThat(retried.restartProcess()).isEqualTo(ProcessRestartOutcome.Launched)
        assertThat(spawnCount).isEqualTo(1)
    }

    /**
     * A port over a temp runtime directory whose seams are counters, so a test can assert on what the port
     * DID rather than on what it logged.
     *
     * The spawn seam deliberately does not start anything: this fixture is the reason the suite needs no
     * child process at all.
     */
    private inner class Fixture(command: String) {
        private val runtimeDirectory: Path = Files.createTempDirectory("desktop-restart")
        val markerPath: Path = runtimeDirectory.resolve("desktop-restart-marker.txt")
        var spawnCount = 0
            private set

        var checkpointCount = 0
            private set

        val port: DesktopProcessRestartPort =
            DesktopProcessRestartPort(
                runtimeDirectory = runtimeDirectory,
                commandOf = { command },
                spawn = { spawnCount++ },
                checkpointAndClose = { checkpointCount++ },
                clock = { fixedInstant },
            )
    }
}
