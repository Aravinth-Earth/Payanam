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
        /** Snapshot. */
        val snapshot: UnifiedLensSnapshot,
        /** Cached at ms. */
        val cachedAtMs: Long,
    )

    /**
     * Get or load.
     */
    suspend fun getOrLoad(
        /** Day key. */
        dayKey: String,
        seededDataByDay: Map<String, UnifiedLensSnapshot> = emptyMap(),
    ): UnifiedLensSnapshot = loadForDays(listOf(dayKey), seededDataByDay).getValue(dayKey)

    /**
     * Load for days.
     */
    suspend fun loadForDays(
        dayKeys: List<String>,
        seededDataByDay: Map<String, UnifiedLensSnapshot> = emptyMap(),
    ): Map<String, UnifiedLensSnapshot> {
        /** If. */
        if (dayKeys.isEmpty()) return seededDataByDay
        /** Unique day keys. */
        val uniqueDayKeys = dayKeys.distinct()
        /** Dirty day keys. */
        val dirtyDayKeys = lensRepository.getDirtyDayKeys(uniqueDayKeys.toSet())
        /** Snapshots. */
        val snapshots = seededDataByDay.toMutableMap()
        /** Now. */
        val now = System.currentTimeMillis()
        /** Missing. */
        val missing = mutableListOf<String>()

        mutex.withLock {
            dirtyDayKeys.forEach { cache.remove(it) }
            uniqueDayKeys.forEach { dayKey ->
                /** If. */
                if (snapshots.containsKey(dayKey)) return@forEach
                /** Cached. */
                val cached = cache[dayKey]
                /** If. */
                if (cached != null && (now - cached.cachedAtMs) <= SNAPSHOT_CACHE_TTL_MS) {
                    snapshots[dayKey] = cached.snapshot
                } else {
                    /** If. */
                    if (cached != null) cache.remove(dayKey)
                    missing += dayKey
                }
            }
        }

        missing.forEach { dayKey ->
            /** Loaded. */
            val loaded = lensRepository.calculateUnifiedSnapshot(dayKey)
            snapshots[dayKey] = loaded
            mutex.withLock {
                cache[dayKey] = CachedSnapshot(snapshot = loaded, cachedAtMs = now)
                /** Trim locked. */
                trimLocked(now)
            }
            logger.d("LensSnapshotCache.loadForDays", "Snapshot cached", mapOf("dayKey" to dayKey, "size" to cache.size))
        }
        return snapshots
    }

    private fun trimLocked(nowMs: Long) {
        /** Expired keys. */
        val expiredKeys = cache
            .filterValues { cached -> (nowMs - cached.cachedAtMs) > SNAPSHOT_CACHE_TTL_MS }
            .keys
            .toList()
        expiredKeys.forEach { cache.remove(it) }
        /** Overflow evictions. */
        var overflowEvictions = 0
        /** While. */
        while (cache.size > SNAPSHOT_CACHE_MAX_ENTRIES) {
            /** Oldest key. */
            val oldestKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(oldestKey)
            overflowEvictions++
        }
        /** If. */
        if (expiredKeys.isNotEmpty() || overflowEvictions > 0) {
            logger.d(
                "LensSnapshotCache.trimLocked",
                "Cache trimmed",
                /** Map of. */
                mapOf("expiredEvicted" to expiredKeys.size, "overflowEvicted" to overflowEvictions, "remaining" to cache.size),
            )
        }
    }
}
