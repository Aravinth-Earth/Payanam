//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.UnifiedLensSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.LocalDate

private val PROGRESSIVE_HISTORY_LIMITS = listOf(14, 30, 60, 90, 180, 365, Int.MAX_VALUE)

internal class LensHistoryBackfillCoordinator(
    private val logger: UnifiedLogger,
) {
    private var backfillJob: Job? = null
    /**
     * Returns true when the cancel.
     */
    fun cancel() {
        if (backfillJob?.isActive == true) {
            logger.d("LensHistoryBackfillCoordinator.cancel", "Cancelling active backfill job")
        }
        backfillJob?.cancel()
    }
    /**
     * Performs the next limit after.
     */
    fun nextLimitAfter(currentDays: Int): Int? = PROGRESSIVE_HISTORY_LIMITS.firstOrNull { it > currentDays }
    /**
     * Performs the schedule.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun schedule(
        scope: CoroutineScope,
        lensRepository: LensRepository,
        focusDate: LocalDate,
        seededDataByDay: Map<String, UnifiedLensSnapshot>,
        expectedRange: ResolvedLensWindowRange,
        maxHistoryLimit: Int = Int.MAX_VALUE,
        loadSnapshot: suspend (String, Map<String, UnifiedLensSnapshot>) -> UnifiedLensSnapshot,
        isCurrentSelection: () -> Boolean,
        onBackfillReady: (TimeModuleHistorySummary) -> Unit,
    ) {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            try {
                var lastAppliedDays = 0
                for (historyLimit in PROGRESSIVE_HISTORY_LIMITS) {
                    if (historyLimit > maxHistoryLimit) {
                        return@launch
                    }
                    if (!isCurrentSelection()) {
                        logger.d(
                            "LensHistoryBackfillCoordinator.schedule",
                            "Ignoring stale time-history backfill",
                            mapOf(
                                "mode" to expectedRange.mode.name,
                                "window" to expectedRange.window.name,
                                "pageIndex" to expectedRange.pageIndex,
                            ),
                        )
                        return@launch
                    }
                    val summary = withContext(Dispatchers.Default) {
                        buildTimeModuleHistorySummary(
                            lensRepository = lensRepository,
                            focusDate = focusDate,
                            seededDataByDay = seededDataByDay,
                            historyDayLimit = historyLimit,
                            snapshotLoader = { dayKey -> loadSnapshot(dayKey, seededDataByDay) },
                        )
                    } ?: return@launch
                    if (summary.totalDays <= lastAppliedDays) {
                        if (summary.totalDays < historyLimit || historyLimit == Int.MAX_VALUE) {
                            return@launch
                        }
                        continue
                    }
                    onBackfillReady(summary)
                    lastAppliedDays = summary.totalDays
                    logger.d(
                        "LensHistoryBackfillCoordinator.schedule",
                        "Time-history backfill applied",
                        mapOf(
                            "days" to summary.totalDays,
                            "historyLimit" to historyLimit,
                            "mode" to expectedRange.mode.name,
                            "window" to expectedRange.window.name,
                            "pageIndex" to expectedRange.pageIndex,
                        ),
                    )
                    if (summary.totalDays < historyLimit || historyLimit == Int.MAX_VALUE) {
                        return@launch
                    }
                    yield()
                }
            } catch (_: CancellationException) {
                logger.d("LensHistoryBackfillCoordinator.schedule", "Time-history backfill cancelled")
            } catch (e: Exception) {
                logger.e("LensHistoryBackfillCoordinator.schedule", "Failed to backfill time history", e)
            }
        }
    }
}
