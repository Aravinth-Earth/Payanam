//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class LensSnapshotCacheDirtyDayTest {
    private lateinit var repository: FakeLensRepository
    private lateinit var cache: LensSnapshotCache

    @Before
    fun setup() {
        repository = FakeLensRepository()
        cache = LensSnapshotCache(repository, AppLogger.current)
    }

    @Test
    fun getOrLoad_recomputesWhenDayIsMarkedDirty() =
        runBlocking {
            val dayKey = "2026-02-20"
            cache.getOrLoad(dayKey)
            assertEquals(1, repository.calculateCalls)

            repository.dirtyDays = setOf(dayKey)
            cache.getOrLoad(dayKey)
            assertEquals(2, repository.calculateCalls)
        }

    @Test
    fun loadForDays_batchesUniqueDayKeys() =
        runBlocking {
            val dayA = "2026-02-20"
            val dayB = "2026-02-21"

            cache.loadForDays(listOf(dayA, dayA, dayB))
            assertEquals(2, repository.calculateCalls)
        }

    @Test
    fun loadForDays_withNoDaysReadsNothing() =
        runBlocking {
            val seeded = mapOf("2026-02-20" to cache.getOrLoad("2026-02-20"))
            val before = repository.calculateCalls

            val loaded = cache.loadForDays(emptyList(), seeded)

            assertEquals("an empty request returns the seed as-is", seeded, loaded)
            assertEquals("and reads no further day", before, repository.calculateCalls)
        }

    @Test
    fun seededDays_areNotReadFromTheRepository() =
        runBlocking {
            val dayKey = "2026-02-20"
            val seeded = cache.getOrLoad(dayKey)
            val before = repository.calculateCalls

            val loaded = cache.loadForDays(listOf(dayKey), mapOf(dayKey to seeded))

            assertEquals(seeded, loaded.getValue(dayKey))
            assertEquals("a seeded day is served without a read", before, repository.calculateCalls)
        }

    @Test
    fun theCacheEvictsItsOldestEntriesAtItsCap() =
        runBlocking {
            // The cache caps at 96 entries and evicts oldest-first, so a 100-day sweep leaves the first days
            // out, and reading one of them again has to go back to the repository.
            val days = (1..100).map { day -> "2026-01-%02d".format(day) }
            cache.loadForDays(days)
            assertEquals("every day was read once", 100, repository.calculateCalls)

            cache.loadForDays(listOf(days.first()))
            assertEquals("the evicted oldest day is read again", 101, repository.calculateCalls)

            cache.loadForDays(listOf(days.last()))
            assertEquals("the newest day is still cached", 101, repository.calculateCalls)
        }

    @Test
    fun aCachedDayComesBackWithoutARead() =
        runBlocking {
            val dayKey = LocalDate.now().toString()
            cache.getOrLoad(dayKey)
            val first = repository.calculateCalls

            val again = cache.getOrLoad(dayKey)

            assertEquals("a fresh day is served from memory", first, repository.calculateCalls)
            assertEquals(dayKey, again.planning.dayKey)
        }
}
