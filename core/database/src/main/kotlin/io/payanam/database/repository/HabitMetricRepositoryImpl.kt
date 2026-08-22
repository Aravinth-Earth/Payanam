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
 * Room-backed implementation of [HabitMetricRepository]. Reads the per-habit L1
 * time-series ([HabitMetricEntity]) and maps rows to [HabitL1Summary] (the
 * cumulative score / running average / progress / streak state).
 */
class HabitMetricRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : HabitMetricRepository {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Returns the current L1 state for every habit, keyed by habit id. Uses each
         * habit's MAX(dayKey) row (cumulative state), so it is one snapshot per
         * habit.
         */
        override suspend fun getLatestPerHabit(): Map<String, HabitL1Summary> {
            val db = sessionManager.requireDatabase()
            val rows = db.habitMetricDao().getLatestPerHabit()
            logger.d(
                "HabitMetricRepositoryImpl.getLatestPerHabit",
                "Fetched latest L1 rows",
                mapOf("count" to rows.size),
            )
            return rows.associate { it.habitId to it.toSummary() }
        }

        /**
         * Returns the current L1 state for one [habitId] (its MAX(dayKey) row), or
         * null when the habit has no metric history yet.
         */
        override suspend fun getLatestForHabit(habitId: String): HabitL1Summary? {
            val db = sessionManager.requireDatabase()
            val rows = db.habitMetricDao().getLatestPerHabit()
            return rows.firstOrNull { it.habitId == habitId }?.toSummary()
        }

        /**
         * Returns the L1 rows for [habitId] within the inclusive [start]..[end]
         * window (chronological), each mapped to [HabitL1Summary]. Backs the
         * habit activity-detail view.
         */
        override suspend fun getForHabitRange(habitId: String, start: String, end: String): List<HabitL1Summary> {
            val db = sessionManager.requireDatabase()
            val rows = db.habitMetricDao().getForHabitRange(habitId, start, end)
            logger.d(
                "HabitMetricRepositoryImpl.getForHabitRange",
                "Fetched L1 rows for window",
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
