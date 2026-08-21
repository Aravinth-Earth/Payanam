//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.event

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Broadcasts a signal whenever the score cascade recomputes any score layer
 * (habit L1, dimension L2, or day L3). Subscribers (e.g. the Lenses habit-score
 * matrix) collect this to refresh derived values — ranks, dimension/day metrics —
 * without a full UI rebuild. [date] is the day whose tail was recomputed; the
 * catch-up path emits [LocalDate.now] since it spans many days.
 */
@Singleton
/**
 * Provides the score change event bus.
 */
class ScoreChangeEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<LocalDate>(extraBufferCapacity = 16)
    /** Hot stream of score-change dates; replay-free, conflated by SharedFlow. */
    val events: SharedFlow<LocalDate> = _events.asSharedFlow()

    /** Publish a recomputed day so subscribers can refresh derived views. */
    fun emit(date: LocalDate) {
        _events.tryEmit(date)
    }
}
