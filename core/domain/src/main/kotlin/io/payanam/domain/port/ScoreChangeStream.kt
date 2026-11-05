//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The days whose score layers were recomputed, as a stream a screen can react to.
 *
 * WHY THIS IS A PORT AND NOT THE EVENT BUS ITSELF:
 *
 * The bus lives in `:core:database` (`ScoreChangeEventBus`), which a platform-neutral ViewModel cannot see.
 * The shared state layer only ever *listens* - it refreshes derived values when a cascade elsewhere
 * recomputes them - so the port states exactly that, and the bus implements it.
 *
 * The stream is deliberately declared as a cold [Flow] rather than the bus's `SharedFlow`: a listener's
 * contract is "collect these", and widening it to the concrete type would hand every consumer the ability to
 * emit into a bus it does not own.
 */
interface ScoreChangeStream {
    /** Hot stream of score-change dates; replay-free, so a collector sees only changes published after it subscribes. */
    val events: Flow<LocalDate>
}
