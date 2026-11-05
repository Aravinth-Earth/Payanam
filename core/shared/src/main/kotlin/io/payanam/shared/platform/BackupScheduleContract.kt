//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

/**
 * Where a platform runs its periodic database backup.
 *
 * Android schedules through `WorkManager`, which survives process death and defers to the OS's battery and
 * idle rules; the desktop has no such scheduler and runs it in-process. That difference is legitimate — what
 * must not differ is the meaning of the two calls, which is what this fixes.
 *
 * Both methods are idempotent by contract. The settings screen and the app's startup path both reconcile the
 * schedule, so "schedule" is routinely called with the interval already in force; an implementation that
 * stacked a second periodic job each time would run N backups per interval after N reconciliations.
 */
interface BackupScheduler {
    /**
     * Ensures a backup runs every [intervalMinutes], replacing any schedule already in force.
     *
     * A non-positive interval is a programming error rather than a request to disable: disabling has its own
     * call, and silently treating a bad interval as "off" would leave the user believing backups run.
     */
    suspend fun schedule(intervalMinutes: Int)

    /** Ensures no backup runs. Idempotent. */
    suspend fun cancel()
}

/** Whether a platform can run backups while the app is not in the foreground. */
enum class BackupBackgroundCapability {
    /**
     * The schedule survives the app being closed — Android's `WorkManager` does.
     */
    BACKGROUND_CAPABLE,

    /**
     * Backups run only while the app is running — the desktop's in-process timer.
     *
     * Reported rather than hidden, because "your last backup was three days ago" means something different
     * on a platform that only backs up while it is open, and the UI has to be able to say so.
     */
    FOREGROUND_ONLY,
}
