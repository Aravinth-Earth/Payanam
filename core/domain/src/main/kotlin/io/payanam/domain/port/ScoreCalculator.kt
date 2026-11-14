//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.model.Task

/**
 * Scores one task.
 *
 * The scoring implementation lives in the scoring module, which the shared state layer reaches through this
 * port rather than a concrete object, so a ViewModel can compute a score without knowing the algorithm.
 */
interface ScoreCalculator {
    /**
     * Returns the score for [task] under the user's current configuration.
     */
    fun calculateScore(task: Task): Double

    /**
     * Returns the score for [task] under [config].
     *
     * Callers that hold the user's stored configuration must use this overload; the default ignores
     * [config] so a calculator with no configurable weights needs no change.
     */
    fun calculateScore(task: Task, config: ScoringConfig): Double = calculateScore(task)
}
