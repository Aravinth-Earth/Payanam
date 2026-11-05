//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * Why a restart was refused, as data the caller can act on.
 *
 * A refusal the user can act on beats a silent no-op: a passphrase change that could not restart the
 * process leaves the app in a state the user has to resolve, and "nothing happened" is the one
 * outcome that leaves them with no way to know that. Each constant names a DIFFERENT remedy, which is
 * why they are separate rather than one `REFUSED`.
 */
enum class ProcessRestartRefusal {
    /**
     * The platform could not report how this process was launched, so there is no command to re-run.
     *
     * The remedy belongs to the platform, not to the caller: only an implementation that knows its own
     * launch mechanism can supply the missing command.
     */
    NO_LAUNCH_COMMAND,

    /**
     * The reported launch command is a JVM binary, not an application launcher.
     *
     * Re-running a bare `java` would start a JVM with no main class and no application classpath, which
     * is not a restart. Refusing with this reason tells the user the platform cannot self-restart from a
     * development launch, which is true and actionable, instead of exec'ing something that exits
     * immediately having shown no window at all.
     */
    LAUNCH_COMMAND_IS_A_JVM,

    /**
     * The restart could not be recorded before it was attempted.
     *
     * A restart that leaves no evidence is a restart that cannot be diagnosed, so this implementation
     * declines rather than performing a relaunch nothing would be left to explain.
     */
    MARKER_NOT_WRITTEN,

    /**
     * The platform's process-end call returned and the process is still running.
     *
     * Reported by the platform that ended itself and found it still alive, which is not a case the
     * platform expects: on Android it means `Process.killProcess` returned without killing anything. The
     * honest answer is "the restart is unconfirmed, and the process you were worried about is still
     * there" — which is neither a success claim nor a claim that no replacement exists.
     */
    PROCESS_DID_NOT_END,

    /**
     * The replacement process could not be started.
     *
     * Distinct from [PROCESS_DID_NOT_END] because the remedy differs: here nothing has been replaced, so
     * this process is the only one running and the caller is the only thing that can do anything about
     * it. Every start failure — a missing executable, a permission denial — lands here, and this process
     * is left running and usable.
     */
    SPAWN_FAILED,
}

/**
 * What a restart request actually achieved.
 *
 * Carries the refusal reason as a typed value rather than leaving it only in a log line, so a caller
 * that can present a message presents the real one instead of a generic failure.
 */
sealed class ProcessRestartOutcome {
    /**
     * A new process was started; this one is expected to end.
     *
     * "Expected" is exactly that — the implementation does not wait for the new process, and the current
     * process is not killed by this call on every platform. What ends it is the caller's own existing
     * exit path, which is also what releases the resources a relaunch needs.
     */
    data object Launched : ProcessRestartOutcome()

    /**
     * No new process was started and this one is untouched; [reason] says why, [detail] carries the
     * platform-specific specifics (a command that looked wrong, a spawn error) when there are any.
     */
    data class Refused(
        val reason: ProcessRestartRefusal,
        val detail: String? = null,
    ) : ProcessRestartOutcome()
}

/**
 * Restarts this process so the next one starts from a clean session.
 *
 * WHY IT IS A PORT: a passphrase change (or a replaced database file) leaves state that only a fresh
 * process can clear — a cached singleton, a live handle on the file just rekeyed — and the two platforms
 * clear it with entirely different mechanisms that no shared module can name. Android re-launches its own
 * task and then kills its PID; the desktop starts the installed launcher and lets its existing exit path
 * release the single-instance lease. The shared caller states the requirement once.
 *
 * **THIS IS NOT A `Unit` CALL, AND THAT IS THE POINT.** The return type is non-`Unit` on purpose, because
 * the two platforms genuinely differ and the difference has to be visible at the call site:
 *
 * - On **Android** this call does not come back. The implementation ends the process, so NOTHING AFTER
 *   THIS CALL RUNS. Any code placed after it on that platform is unreachable, and the compiler will not
 *   say so.
 * - On the **desktop** it comes back with an outcome, and the caller is then responsible for ending this
 *   process through its own exit path.
 *
 * A `Unit` signature would hide the first case behind a promise the compiler cannot keep. A non-`Unit`
 * return states, at the call site, that "this may be the end of the process" — so a caller reads the
 * contract before writing the line after it.
 *
 * The contract deliberately does NOT say the implementation kills the current process on every platform.
 * The desktop must not: a relaunch while its single-instance lock is still held makes the NEW process
 * fail to acquire and exit having shown nothing at all. A caller that wants the old process gone ends it
 * the way it already ends it, which is also what releases the lease before the new one tries to take it.
 */
fun interface ProcessRestart {
    /**
     * Starts a replacement process and reports whether that was achieved.
     *
     * On Android NOTHING AFTER THIS CALL RUNS — the process is ended inside the implementation. Callers
     * written to be platform-neutral must therefore let the call be their last act, and must not place
     * durable work after it.
     */
    fun restartProcess(): ProcessRestartOutcome
}
