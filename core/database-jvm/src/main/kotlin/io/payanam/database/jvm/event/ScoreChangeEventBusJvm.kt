//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm.event

import io.payanam.domain.port.ScoreChangeStream
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * JVM implementation of [ScoreChangeStream] — broadcasts a signal whenever the score cascade
 * recomputes any score layer (habit L1, dimension L2, or day L3).
 *
 * Desktop twin of Android's `ScoreChangeEventBus`. The desktop DOES recompute scores via
 * [ScoreRollupTriggerJdbc], so this is a real bus, not the empty-flow stub in
 * [DesktopUnsupportedPorts].
 *
 * Subscribers (e.g. the Lenses habit-score matrix) collect [events] to refresh derived values —
 * ranks, dimension/day metrics — without a full UI rebuild.
 */
class ScoreChangeEventBusJvm : ScoreChangeStream {
    private val _events = MutableSharedFlow<LocalDate>(extraBufferCapacity = 16)

    /** Hot stream of score-change dates; replay-free, conflated by SharedFlow. */
    override val events: SharedFlow<LocalDate> = _events.asSharedFlow()

    /** Publish a recomputed day so subscribers can refresh derived views. */
    fun emit(date: LocalDate) {
        _events.tryEmit(date)
    }
}
