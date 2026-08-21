//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DayMetricRow
import io.payanam.domain.model.DimensionMetricRow
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Room-backed implementation of [ScoreWindowRepository]. Reads the per-day
 * ([DayMetricDao]) and per-dimension ([DimensionMetricDao]) time-series
 * tables and projects them into [MetricWindowRow] domain rows for the
 * Life Lens scoring UI.
 */
class ScoreWindowRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : ScoreWindowRepository {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Returns dimension metric rows within the inclusive [start]..[end] window,
         * each projected as [MetricWindowRow].
         */
        override suspend fun getDimensionWindow(start: String, end: String): List<MetricWindowRow> {
            val db = sessionManager.requireDatabase()
            val rows = db.dimensionMetricDao().getForWindow(start, end)
            logger.d(
                "ScoreWindowRepositoryImpl.getDimensionWindow",
                "Loaded dimension metric window",
                mapOf("start" to start, "end" to end, "rows" to rows.size),
            )
            return rows.map {
                DimensionMetricRow(
                    dimensionId = it.dimensionId,
                    dayKey = it.dayKey,
                    score = it.score,
                    runningAvg = it.runningAvg,
                    progress = it.progress,
                    streakPos = it.streakPos,
                    streakNet = it.streakNet,
                    posContinue = it.posContinue,
                )
            }
        }

        /**
         * Returns day metric rows within the inclusive [start]..[end] window, each
         * projected as [MetricWindowRow].
         */
        override suspend fun getDayWindow(start: String, end: String): List<MetricWindowRow> {
            val db = sessionManager.requireDatabase()
            val rows = db.dayMetricDao().getForWindow(start, end)
            logger.d(
                "ScoreWindowRepositoryImpl.getDayWindow",
                "Loaded day metric window",
                mapOf("start" to start, "end" to end, "rows" to rows.size),
            )
            return rows.map {
                DayMetricRow(
                    dayKey = it.dayKey,
                    score = it.dayScore,
                    runningAvg = it.runningAvg,
                    progress = it.progress,
                    streakPos = it.streakPos,
                    streakNet = it.streakNet,
                    posContinue = it.posContinue,
                )
            }
        }

        /**
         * Returns the earliest `dayKey` present in the day-metrics table, or null
         * when empty.
         */
        override suspend fun earliestDayKey(): String? =
            sessionManager.requireDatabase().dayMetricDao().earliestDayKey()

        /**
         * Returns the earliest `dayKey` for one [dimensionId]'s dimension-metric
         * rows, or null.
         */
        override suspend fun earliestDimensionDayKey(dimensionId: String): String? =
            sessionManager.requireDatabase().dimensionMetricDao().earliestDayKey(dimensionId)

        /**
         * Returns the earliest `dayKey` across all dimensions in the
         * dimension-metrics table, or null.
         */
        override suspend fun earliestDimensionDayKey(): String? =
            sessionManager.requireDatabase().dimensionMetricDao().earliestDayKeyGlobal()
    }
