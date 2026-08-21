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
 * Provides the score window repository impl.
 */
class ScoreWindowRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : ScoreWindowRepository {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Returns the get dimension window.
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
         * Returns the get day window.
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
         * Performs the earliest day key.
         */
        override suspend fun earliestDayKey(): String? =
            sessionManager.requireDatabase().dayMetricDao().earliestDayKey()

        /**
         * Performs the earliest dimension day key.
         */
        override suspend fun earliestDimensionDayKey(dimensionId: String): String? =
            sessionManager.requireDatabase().dimensionMetricDao().earliestDayKey(dimensionId)

        /**
         * Performs the earliest dimension day key.
         */
        override suspend fun earliestDimensionDayKey(): String? =
            sessionManager.requireDatabase().dimensionMetricDao().earliestDayKeyGlobal()
    }
