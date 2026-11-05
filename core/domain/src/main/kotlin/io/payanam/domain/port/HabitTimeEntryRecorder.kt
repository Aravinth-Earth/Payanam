//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.domain.model.Task
import java.time.LocalDateTime

/**
 * Records the time a habit actually took, when the user's settings ask for it.
 *
 * Named for what it does rather than for the use case that implements it: the shared state layer asks for the
 * recording, and whether that means writing an entry, skipping it, or consulting a setting first is the
 * implementation's business.
 */
interface HabitTimeEntryRecorder {
    /**
     * Records time for the completion of [task].
     *
     * @param actualCompletedAt When the habit was completed, or null to use the current time.
     * @param actualDurationMinutes How long it took, or null when the duration is unknown.
     */
    suspend operator fun invoke(task: Task, actualCompletedAt: LocalDateTime?, actualDurationMinutes: Int?)
}
