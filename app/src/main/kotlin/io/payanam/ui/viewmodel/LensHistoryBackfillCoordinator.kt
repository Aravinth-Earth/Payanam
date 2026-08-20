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
     * Cancel.
     */
    fun cancel() {
        /** If. */
        if (backfillJob?.isActive == true) {
            logger.d("LensHistoryBackfillCoordinator.cancel", "Cancelling active backfill job")
        }
        backfillJob?.cancel()
    }

    /**
     * Next limit after.
     */
    fun nextLimitAfter(currentDays: Int): Int? = PROGRESSIVE_HISTORY_LIMITS.firstOrNull { it > currentDays }

    /**
     * Schedule.
     */
    fun schedule(
        /** Scope. */
        scope: CoroutineScope,
        /** Lens repository. */
        lensRepository: LensRepository,
        /** Focus date. */
        focusDate: LocalDate,
        seededDataByDay: Map<String, UnifiedLensSnapshot>,
        /** Expected range. */
        expectedRange: ResolvedLensWindowRange,
        maxHistoryLimit: Int = Int.MAX_VALUE,
        loadSnapshot: suspend (String, Map<String, UnifiedLensSnapshot>) -> UnifiedLensSnapshot,
        isCurrentSelection: () -> Boolean,
        onBackfillReady: (TimeModuleHistorySummary) -> Unit,
    ) {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            try {
                /** Last applied days. */
                var lastAppliedDays = 0
                /** For. */
                for (historyLimit in PROGRESSIVE_HISTORY_LIMITS) {
                    /** If. */
                    if (historyLimit > maxHistoryLimit) {
                        return@launch
                    }
                    /** If. */
                    if (!isCurrentSelection()) {
                        logger.d(
                            "LensHistoryBackfillCoordinator.schedule",
                            "Ignoring stale time-history backfill",
                            /** Map of. */
                            mapOf(
                                "mode" to expectedRange.mode.name,
                                "window" to expectedRange.window.name,
                                "pageIndex" to expectedRange.pageIndex,
                            ),
                        )
                        return@launch
                    }
                    /** Summary. */
                    val summary = withContext(Dispatchers.Default) {
                        /** Build time module history summary. */
                        buildTimeModuleHistorySummary(
                            lensRepository = lensRepository,
                            focusDate = focusDate,
                            seededDataByDay = seededDataByDay,
                            historyDayLimit = historyLimit,
                            snapshotLoader = { dayKey -> loadSnapshot(dayKey, seededDataByDay) },
                        )
                    } ?: return@launch

                    /** If. */
                    if (summary.totalDays <= lastAppliedDays) {
                        /** If. */
                        if (summary.totalDays < historyLimit || historyLimit == Int.MAX_VALUE) {
                            return@launch
                        }
                        /** Continue. */
                        continue
                    }
                    /** On backfill ready. */
                    onBackfillReady(summary)
                    lastAppliedDays = summary.totalDays
                    logger.d(
                        "LensHistoryBackfillCoordinator.schedule",
                        "Time-history backfill applied",
                        /** Map of. */
                        mapOf(
                            "days" to summary.totalDays,
                            "historyLimit" to historyLimit,
                            "mode" to expectedRange.mode.name,
                            "window" to expectedRange.window.name,
                            "pageIndex" to expectedRange.pageIndex,
                        ),
                    )
                    /** If. */
                    if (summary.totalDays < historyLimit || historyLimit == Int.MAX_VALUE) {
                        return@launch
                    }
                    /** Yield. */
                    yield()
                }
            } catch (_: CancellationException) {
                logger.d("LensHistoryBackfillCoordinator.schedule", "Time-history backfill cancelled")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensHistoryBackfillCoordinator.schedule", "Failed to backfill time history", e)
            }
        }
    }
}
