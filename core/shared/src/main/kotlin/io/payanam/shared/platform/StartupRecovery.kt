//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

/**
 * The startup work every platform does to settle data a previous session could have left mid-flight.
 *
 * The list exists because maintaining it twice has already gone wrong once. The desktop's startup maintenance
 * carried a hand-written copy of Android's steps, and its own KDoc claimed a step ran in the present tense while
 * `grep` found that step's name only inside that KDoc - historical score roll-ups were never backfilled on Linux,
 * and nothing in the tree could tell the two step sets apart. A step named here, enumerated by tests, and called
 * through [StartupRecovery] is a list that cannot drift silently; a list written out in two startup paths is one
 * that will.
 *
 * Order is not declared, because it does not matter and pretending otherwise would create a rule someone has to
 * maintain: each step owns its own idempotence and its own guard.
 */
enum class StartupRecoveryStep {
    /** Returns recurring tasks whose occurrence was left open past its window to a consistent state. */
    REPAIR_STUCK_RECURRING_TASKS,

    /** Advances recurrence for tasks whose schedule has moved on since the last session. */
    AUTO_ADVANCE_RECURRING_TASKS,

    /**
     * Converts a database's historical roll-ups to the current scoring rule shape.
     *
     * An idempotent, guarded, one-time conversion: the guard is an `app_settings` row, so a platform that
     * cannot do this at all is still expected to reach the step and let it report that there was nothing to do.
     * Silently omitting it is what left desktop users reading scores Android would have repaired on open.
     */
    BACKFILL_SCORE_ROLLUPS,
}

/**
 * Runs a platform's startup recovery steps, or the ones that platform can run.
 *
 * One declaration, two implementations: Android's is Room-backed and lives beside its database, and the desktop
 * supplies a JDBC one. They cannot share an implementation, and the reason is structural rather than stylistic -
 * the Room implementation lives in a module that is on only one platform's classpath - so the port is the shared
 * thing, and what makes it worth having is that the **list** is shared even when the bodies are not.
 *
 * [runStartupRecovery] returns the steps it actually ran. That return value is the seam the step-list test uses:
 * a platform that adds a step to this enum and forgets to run it is a platform whose implementation returns fewer
 * steps than [StartupRecoveryStep.entries], which is a failure rather than a green run.
 */
interface StartupRecovery {
    /**
     * Runs this platform's recovery work.
     *
     * A platform whose build disables the habits module still calls this and returns an **empty** list rather than
     * the full enumeration: the steps were skipped, and a list that claimed otherwise would make the drift this
     * port exists to detect indistinguishable from a normal run. The decision is made inside, from the shared flag
     * holder, so the caller never has to know which of the two shapes it is in.
     *
     * Returns the steps that were run, in no particular order.
     *
     * Suspending, because every step is database work: the two recurrence steps read and write rows, and the
     * backfill reads, converts and writes three tables. A non-suspending signature would force each
     * implementation to block the thread it was called on.
     */
    suspend fun runStartupRecovery(): List<StartupRecoveryStep>
}
