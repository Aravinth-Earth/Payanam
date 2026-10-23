//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.platform.ReminderRequest
import java.util.concurrent.TimeUnit

/**
 * The desktop's real delivery hook: `notify-send`, with the miss logged rather than skipped.
 *
 * WHY A PROCESS AND NOT A LIBRARY: `notify-send` is libnotify's own CLI, already installed on this machine
 * and packaged by every freedesktop distribution, so the delivery costs the artifact nothing - no native
 * library, no hand-rolled D-Bus wire protocol, no new dependency in the version catalog. The module
 * already spawns processes the same way for the restart port, with the same kind of injected seam.
 *
 * WHY THE MISS ARM IS THE POINT: the sink's capability flips to SUPPORTED only when a delivery returns
 * true, so a hook that guessed would make the desktop claim a channel it may not have. Every way the miss
 * can happen - the spawn raising `IOException` (no `notify-send` on PATH, or no bus to connect to), a
 * non-zero exit (a bus that exists but has no owner for the name), or the process outliving the timeout -
 * lands in one arm that LOGS and returns false, and the whole body is guarded so a delivery can never
 * take the sink's coroutine down with it.
 *
 * [send] is injected for the same reason `DesktopProcessRestartPort` injects its command: every branch is
 * drivable by a test without starting a process.
 */
internal fun desktopFreedesktopReminderDelivery(
    logger: UnifiedLogger,
    send: (List<String>) -> Int = ::runNotifySend,
): (ReminderRequest) -> Boolean =
    { request ->
        val outcome = runCatching { send(notifySendCommand(request)) }
        val exit = outcome.getOrNull()
        if (exit == EXIT_SUCCESS) {
            true
        } else {
            logger.w(
                source = "DesktopFreedesktopReminderDelivery",
                message = "Reminder not delivered: no notification daemon answered",
                data =
                    mapOf(
                        // The failure CLASS, never the message - the same convention the seams use.
                        "failure" to (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "nonZeroExit"),
                        "exitCode" to exit,
                        "reminderId" to request.id,
                    ),
            )
            false
        }
    }

/** The one argument list, built once so a test's `send` sees exactly what production spawns. */
internal fun notifySendCommand(request: ReminderRequest): List<String> =
    listOf(
        "notify-send",
        "--app-name=Payanam",
        // `--` so a title that begins with a dash is a title, not a flag.
        "--",
        request.title,
        request.body,
    )

/**
 * The one process spawn. A spawn that fails throws (the caller's guard turns it into the miss arm); a
 * process that outlives the timeout is destroyed and reported as a non-zero exit, so a hung daemon cannot
 * hold the sink's coroutine open.
 */
private fun runNotifySend(command: List<String>): Int {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(NOTIFY_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return EXIT_TIMED_OUT
    }
    return process.exitValue()
}

private const val EXIT_SUCCESS = 0

/** Non-zero, and distinct from any real exit code, so a timeout is legible in the log's own data. */
private const val EXIT_TIMED_OUT = -1

/** How long a delivery may take before it is the miss. Generous; a daemon answers in milliseconds. */
private const val NOTIFY_SEND_TIMEOUT_SECONDS = 5L
