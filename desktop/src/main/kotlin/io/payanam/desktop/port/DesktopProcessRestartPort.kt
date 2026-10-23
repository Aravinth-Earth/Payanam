//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import io.payanam.desktop.DesktopAppPaths
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.domain.port.ProcessRestart
import io.payanam.domain.port.ProcessRestartOutcome
import io.payanam.domain.port.ProcessRestartRefusal
import io.payanam.shared.logging.AppLogger
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The desktop's [ProcessRestart]: a real relaunch, or a refusal that names why.
 *
 * ## WHY THE DESKTOP HAD NONE OF THIS
 *
 * Measured before this file existed: `grep -rn 'ProcessBuilder|Runtime.getRuntime|exitProcess'
 * desktop/src/main/` returned zero hits, while the desktop already reads its own identity
 * (`ProcessHandle.current()` in [io.payanam.desktop.DesktopSingleInstanceGuard]). So the app could see
 * how it was launched and had no way to do anything with the answer.
 *
 * ## THE ORDER, AND WHY EACH STEP IS WHERE IT IS
 *
 * [restart] does exactly this, in this order:
 *
 * 1. **Decide the command, and refuse before touching anything.** A refusal that wrote a marker or
 *    folded the database would leave a trail for a restart that never happened.
 * 2. **Write the marker and force it to the device.** Before the spawn, always: a process that dies
 *    between the two leaves evidence, and a process that dies after the spawn leaves the new one
 *    running with the evidence already on disk.
 * 3. **Checkpoint and close the process session.** Folding the WAL is what the replacement process
 *    needs the old one to have done, and doing it before the spawn means the new process is never
 *    racing a write the old one still owns.
 * 4. **Spawn, and report what happened.** Not waited on: the caller ends this process through its own
 *    exit path, and blocking here would hold the new process's start behind a wait nobody asked for.
 *
 * ## THE DEADLOCK THIS IS BUILT AROUND
 *
 * [io.payanam.desktop.DesktopSingleInstanceGuard.acquire] holds an exclusive `tryLock` on
 * `<runtime>/desktop-single-instance.lock` for this process's whole life. A relaunch issued while that
 * lease is still held gives the NEW process an `OverlappingFileLockException` — it exits having shown
 * no window, which is the silent failure this port exists to stop. So this port **starts the new
 * process and returns; it does not wait, and it does not end this one.** The caller's existing exit
 * path releases the lease (`DesktopSingleInstanceLease.close` is `runCatching`-wrapped throughout, so a
 * second call is a harmless no-op), and the new process finds the lock free.
 *
 * ## WHY A JVM COMMAND IS REFUSED RATHER THAN EXECUTED
 *
 * `ProcessHandle.current().info().command()` is the *JVM's* command line. For an installed build that
 * is the ELF launcher `/opt/payanam/bin/Payanam`, and re-running it is a correct restart. For a
 * `./gradlew run` launch it is the JVM binary, and re-running a bare `java` starts a JVM with no main
 * class and no application classpath — a process that exits at once having done nothing. That is
 * reported as [ProcessRestartRefusal.LAUNCH_COMMAND_IS_A_JVM] instead, because a refusal carrying a
 * real reason is something a user can act on and an exec'd JVM is not.
 *
 * ## WHY THE SEAMS ARE INJECTED
 *
 * [commandOf], [spawn] and [checkpointAndClose] are constructor parameters with production defaults,
 * not private calls hard-coded into the body. A unit test can then drive every branch of [restart] —
 * including the ordering between the marker and the spawn — without starting a process, and the test
 * suite really does not.
 */
internal class DesktopProcessRestartPort(
    private val runtimeDirectory: Path = DesktopAppPaths.resolveRuntimeDirectory(),
    private val commandOf: () -> String = { currentLaunchCommand() },
    private val spawn: (String) -> Unit = { command -> ProcessBuilder(command).start() },
    private val checkpointAndClose: () -> Unit = { DesktopDatabaseSessionController.checkpointAndCloseProcessSession() },
    private val clock: () -> Instant = { Instant.now() },
) : ProcessRestart {
    private val logger = AppLogger.current

    /**
     * The fold runs at most once per port instance.
     *
     * [DesktopDatabaseSessionController.checkpointAndCloseProcessSession] is itself once-only, so this is
     * not about the production call being harmful twice — it is that a second fold would be a no-op whose
     * silence the log would not distinguish from a fold that never happened, and because the injected seam
     * has no such guarantee of its own. Guarded here, where the guarantee is observable and testable.
     */
    private val checkpointRun = AtomicBoolean(false)

    /**
     * The restart marker this port leaves in [runtimeDirectory].
     *
     * The same directory as `desktop-single-instance.properties` and `desktop-crash-breadcrumbs.txt`:
     * transient process lifecycle state, not a session record anyone browses.
     */
    internal val markerFile: Path
        get() = runtimeDirectory.resolve(RESTART_MARKER_FILE_NAME)

    /**
     * Writes the marker, starts the replacement process, and reports what was achieved.
     *
     * Returns [ProcessRestartOutcome.Launched] once the new process has been started; the caller's own
     * exit path is then what ends this process and releases the single-instance lease. A failure at any
     * step returns [ProcessRestartOutcome.Refused] with this process still running and still usable.
     */
    override fun restartProcess(): ProcessRestartOutcome {
        val command = commandOf().trim()
        val unusableCommand = unusableLaunchCommand(command)
        if (unusableCommand != null) {
            logger.w(
                source = SOURCE,
                message = "Restart refused: this process cannot be relaunched from how it was started",
                data =
                    mapOf(
                        "reason" to unusableCommand.name,
                        "command" to command,
                    ),
            )
            return ProcessRestartOutcome.Refused(reason = unusableCommand, detail = command)
        }

        if (!writeMarker(command)) {
            return ProcessRestartOutcome.Refused(
                reason = ProcessRestartRefusal.MARKER_NOT_WRITTEN,
                detail = markerFile.toString(),
            )
        }

        if (checkpointRun.compareAndSet(false, true)) {
            runCatching(checkpointAndClose).onFailure { error ->
                // Reported, not fatal: the fold is already once-only inside the session controller, and a
                // caller about to end this process must still get its restart rather than an exception.
                logger.w(
                    source = SOURCE,
                    message = "Session fold before the relaunch did not complete; the relaunch continues",
                    data = mapOf("error" to (error.message ?: "unknown")),
                )
            }
        }

        return try {
            spawn(command)
            logger.i(
                source = SOURCE,
                message = "Replacement process started; this process is expected to end through its own exit path",
                data = mapOf("command" to command),
            )
            ProcessRestartOutcome.Launched
        } catch (error: IOException) {
            refuseSpawnFailure(command, error.message ?: "unknown")
        } catch (error: SecurityException) {
            refuseSpawnFailure(command, error.message ?: "SecurityException")
        }
    }

    private fun refuseSpawnFailure(
        command: String,
        detail: String,
    ): ProcessRestartOutcome.Refused {
        logger.w(
            source = SOURCE,
            message = "Restart refused: the replacement process could not be started; this process is still running",
            data =
                mapOf(
                    "reason" to ProcessRestartRefusal.SPAWN_FAILED.name,
                    "command" to command,
                    "error" to detail,
                ),
        )
        return ProcessRestartOutcome.Refused(reason = ProcessRestartRefusal.SPAWN_FAILED, detail = detail)
    }

    /**
     * Writes the marker, forced to the storage device, and reports whether it is there.
     *
     * Forced for the same reason the crash-breadcrumb trail is
     * ([io.payanam.desktop.DesktopCrashBreadcrumbStore]): the whole value of this file is that it is
     * readable by the NEXT process, and a write that is still in the page cache when this process ends
     * is a marker that may not be there — worse than none, because a missing one looks like a restart
     * that was never attempted.
     */
    private fun writeMarker(command: String): Boolean =
        try {
            Files.createDirectories(runtimeDirectory)
            val payload =
                buildString {
                    append("requestedAt=")
                    append(clock().toString())
                    append('\n')
                    append("processId=")
                    append(ProcessHandle.current().pid())
                    append('\n')
                    append("previousCommand=")
                    append(command)
                    append('\n')
                }.toByteArray(StandardCharsets.UTF_8)
            RandomAccessFile(markerFile.toFile(), "rw").use { file ->
                file.setLength(0)
                file.write(payload)
                file.channel.force(true)
            }
            true
        } catch (error: IOException) {
            logger.w(
                source = SOURCE,
                message = "Restart refused: the restart marker could not be written, so the restart would leave no evidence",
                data =
                    mapOf(
                        "reason" to ProcessRestartRefusal.MARKER_NOT_WRITTEN.name,
                        "error" to (error.message ?: "unknown"),
                    ),
            )
            false
        }

    private companion object {
        private const val SOURCE = "DesktopProcessRestartPort.restartProcess"

        /**
         * The marker file's name inside `DesktopAppPaths.resolveRuntimeDirectory()`.
         *
         * Beside the single-instance metadata and the crash-breadcrumb trail rather than in `logs/`: it is
         * transient lifecycle state, and `logs/` is a directory the logger prunes.
         */
        private const val RESTART_MARKER_FILE_NAME = "desktop-restart-marker.txt"

        /**
         * This process's own launch command, or the empty string when the platform cannot report one.
         *
         * The empty string is a real answer on some hosts (a process whose command line the kernel does
         * not expose), which is why it becomes [ProcessRestartRefusal.NO_LAUNCH_COMMAND] rather than a
         * guess.
         */
        private fun currentLaunchCommand(): String =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("")

        /**
         * The refusal a command earns, or null when the command can be re-run.
         *
         * Split on BOTH separators rather than through `Path.of`, because the string being judged is a
         * command from ANOTHER platform's perspective as often as this one's: a Windows path read on Linux
         * has to be judged by the same rule, and a `Path.of` that throws on a foreign separator would turn
         * a refusal into a crash.
         */
        private fun unusableLaunchCommand(command: String): ProcessRestartRefusal? {
            if (command.isEmpty()) {
                return ProcessRestartRefusal.NO_LAUNCH_COMMAND
            }
            val fileName = command.substringAfterLast('/').substringAfterLast('\\')
            return if (fileName.startsWith("java", ignoreCase = true)) {
                ProcessRestartRefusal.LAUNCH_COMMAND_IS_A_JVM
            } else {
                null
            }
        }
    }
}
