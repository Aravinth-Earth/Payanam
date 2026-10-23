//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * The three imported-task queries and writes a settings screen performs, without reaching past the
 * database session into a data-access object.
 *
 * ## What this port is for
 *
 * The settings screen reaches past [DatabaseSessionManager] — which deliberately exposes no
 * `requireDatabase()`, so no shared caller can name a Room database — and into a data-access object
 * directly, for three operations. Each one is asked of "tasks", by import source, so none of them is
 * about a Room type, a table, or a query: all three are statements about the user's tasks that the
 * settings screen needs and the repository does not currently answer.
 *
 * ## Why this is not [io.payanam.domain.repository.TaskRepository]
 *
 * Because each of these three is a BULK operation over an import source, and the repository's contract
 * is per-task: it takes one task id and returns one task. Growing it to answer "every task that came
 * from uHabits" would have added a second, unrelated granularity to an interface whose every other
 * member is single-row, and would have made the shared count depend on loading whole task lists to be
 * counted. A caller asking "how many" must not pay to load the things it is counting.
 *
 * ## Why a separate port rather than a method on [DatabaseSessionManager]
 *
 * Adding these to the session manager would reintroduce, in the very interface that exists to keep the
 * database unreachable, the coupling its KDoc rules out. The session manager owns a LIFECYCLE; these
 * are STATEMENTS about task rows.
 */
interface ImportedTaskReconciliation {
    /**
     * How many tasks carry [source] as their import source.
     *
     * A count and not a list, because the one caller renders the number. An implementation MUST NOT
     * answer by materialising the matching rows and counting them in memory.
     */
    suspend fun countByImportSource(source: String): Int

    /**
     * Assigns [dimensionId] and [lifeIntentionCategory] to every task from [source].
     *
     * SCOPE, STATED BECAUSE IT IS ADEQUATE AND NOT BECAUSE IT IS OBVIOUS: this covers EVERY task from
     * [source] — completed, skipped, archived and not-yet-due alike — and imposes no status or due-date
     * predicate. That is what the statement being ported does today, and this contract states it rather
     * than narrowing it, because a port that quietly promised a subset would make the implementation
     * look wrong for matching the specification. A caller that must not relabel settled history has to
     * ask for that explicitly; see the open question recorded with this port, because narrowing the
     * scope is a behaviour change and belongs to whoever owns the screen's copy, not to a port.
     *
     * @param updatedAt the timestamp to stamp on the rows it changes, so a caller controls the audit
     *   value rather than the implementation inventing a clock.
     * @return how many rows were changed, which is what the caller reports as the result.
     */
    suspend fun bulkAssignDimensionToImportedSource(
        source: String,
        dimensionId: String,
        lifeIntentionCategory: String,
        updatedAt: String,
    ): Int
}
