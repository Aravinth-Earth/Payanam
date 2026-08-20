//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.HabitMetricDao
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.HabitL1Summary
import io.payanam.domain.repository.HabitMetricRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * HabitMetricRepositoryImpl.
 */
class HabitMetricRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : HabitMetricRepository {
        private val logger = UnifiedLogger.getInstance()

        override suspend fun getLatestPerHabit(): Map<String, HabitL1Summary> {
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Rows. */
            val rows = db.habitMetricDao().getLatestPerHabit()
            logger.d(
                "HabitMetricRepositoryImpl.getLatestPerHabit",
                "Fetched latest L1 rows",
                /** Map of. */
                mapOf("count" to rows.size),
            )
            return rows.associate { it.habitId to it.toSummary() }
        }

        override suspend fun getLatestForHabit(habitId: String): HabitL1Summary? {
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Rows. */
            val rows = db.habitMetricDao().getLatestPerHabit()
            return rows.firstOrNull { it.habitId == habitId }?.toSummary()
        }

        override suspend fun getForHabitRange(habitId: String, start: String, end: String): List<HabitL1Summary> {
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Rows. */
            val rows = db.habitMetricDao().getForHabitRange(habitId, start, end)
            logger.d(
                "HabitMetricRepositoryImpl.getForHabitRange",
                "Fetched L1 rows for window",
                /** Map of. */
                mapOf("habitId" to habitId, "start" to start, "end" to end, "rows" to rows.size),
            )
            return rows.map { it.toSummary() }
        }
    }

private fun io.payanam.database.entity.HabitMetricEntity.toSummary() = HabitL1Summary(
    habitId = habitId,
    dayKey = dayKey,
    score = score,
    runningAvg = runningAvg,
    progress = progress,
    streakPos = streakPos,
    streakNet = streakNet,
    posContinue = posContinue,
)
