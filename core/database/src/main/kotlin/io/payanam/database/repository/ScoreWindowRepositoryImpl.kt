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
 * ScoreWindowRepositoryImpl.
 */
class ScoreWindowRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : ScoreWindowRepository {
        private val logger = UnifiedLogger.getInstance()

        override suspend fun getDimensionWindow(start: String, end: String): List<MetricWindowRow> {
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Rows. */
            val rows = db.dimensionMetricDao().getForWindow(start, end)
            logger.d(
                "ScoreWindowRepositoryImpl.getDimensionWindow",
                "Loaded dimension metric window",
                /** Map of. */
                mapOf("start" to start, "end" to end, "rows" to rows.size),
            )
            return rows.map {
                /** Dimension metric row. */
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

        override suspend fun getDayWindow(start: String, end: String): List<MetricWindowRow> {
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Rows. */
            val rows = db.dayMetricDao().getForWindow(start, end)
            logger.d(
                "ScoreWindowRepositoryImpl.getDayWindow",
                "Loaded day metric window",
                /** Map of. */
                mapOf("start" to start, "end" to end, "rows" to rows.size),
            )
            return rows.map {
                /** Day metric row. */
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

        override suspend fun earliestDayKey(): String? =
            sessionManager.requireDatabase().dayMetricDao().earliestDayKey()

        override suspend fun earliestDimensionDayKey(dimensionId: String): String? =
            sessionManager.requireDatabase().dimensionMetricDao().earliestDayKey(dimensionId)

        override suspend fun earliestDimensionDayKey(): String? =
            sessionManager.requireDatabase().dimensionMetricDao().earliestDayKeyGlobal()
    }
