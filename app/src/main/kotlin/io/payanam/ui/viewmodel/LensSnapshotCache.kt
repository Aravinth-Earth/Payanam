//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.UnifiedLensSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SNAPSHOT_CACHE_TTL_MS = 5 * 60 * 1000L
private const val SNAPSHOT_CACHE_MAX_ENTRIES = 96

internal class LensSnapshotCache(
    private val lensRepository: LensRepository,
    private val logger: UnifiedLogger,
) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, CachedSnapshot>()

    private data class CachedSnapshot(
        val snapshot: UnifiedLensSnapshot,
        val cachedAtMs: Long,
    )
    /**
     * Returns the get or load.
     */
    suspend fun getOrLoad(
        dayKey: String,
        seededDataByDay: Map<String, UnifiedLensSnapshot> = emptyMap(),
    ): UnifiedLensSnapshot = loadForDays(listOf(dayKey), seededDataByDay).getValue(dayKey)
    /**
     * Loads the load for days.
     */
    suspend fun loadForDays(
        dayKeys: List<String>,
        seededDataByDay: Map<String, UnifiedLensSnapshot> = emptyMap(),
    ): Map<String, UnifiedLensSnapshot> {
        if (dayKeys.isEmpty()) return seededDataByDay
        val uniqueDayKeys = dayKeys.distinct()
        val dirtyDayKeys = lensRepository.getDirtyDayKeys(uniqueDayKeys.toSet())
        val snapshots = seededDataByDay.toMutableMap()
        val now = System.currentTimeMillis()
        val missing = mutableListOf<String>()

        mutex.withLock {
            dirtyDayKeys.forEach { cache.remove(it) }
            uniqueDayKeys.forEach { dayKey ->
                if (snapshots.containsKey(dayKey)) return@forEach
                val cached = cache[dayKey]
                if (cached != null && (now - cached.cachedAtMs) <= SNAPSHOT_CACHE_TTL_MS) {
                    snapshots[dayKey] = cached.snapshot
                } else {
                    if (cached != null) cache.remove(dayKey)
                    missing += dayKey
                }
            }
        }

        missing.forEach { dayKey ->
            val loaded = lensRepository.calculateUnifiedSnapshot(dayKey)
            snapshots[dayKey] = loaded
            mutex.withLock {
                cache[dayKey] = CachedSnapshot(snapshot = loaded, cachedAtMs = now)
                trimLocked(now)
            }
            logger.d("LensSnapshotCache.loadForDays", "Snapshot cached", mapOf("dayKey" to dayKey, "size" to cache.size))
        }
        return snapshots
    }

    private fun trimLocked(nowMs: Long) {
        val expiredKeys = cache
            .filterValues { cached -> (nowMs - cached.cachedAtMs) > SNAPSHOT_CACHE_TTL_MS }
            .keys
            .toList()
        expiredKeys.forEach { cache.remove(it) }
        var overflowEvictions = 0
        while (cache.size > SNAPSHOT_CACHE_MAX_ENTRIES) {
            val oldestKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(oldestKey)
            overflowEvictions++
        }
        if (expiredKeys.isNotEmpty() || overflowEvictions > 0) {
            logger.d(
                "LensSnapshotCache.trimLocked",
                "Cache trimmed",
                mapOf("expiredEvicted" to expiredKeys.size, "overflowEvicted" to overflowEvictions, "remaining" to cache.size),
            )
        }
    }
}
