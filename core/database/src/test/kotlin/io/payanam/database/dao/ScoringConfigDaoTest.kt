//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.ScoringConfigEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScoringConfigDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var scoringConfigDao: ScoringConfigDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        scoringConfigDao = database.scoringConfigDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertConfig_and_getConfig() =
        runBlocking {
            val config = createTestScoringConfig()
            scoringConfigDao.upsertConfig(config)

            val retrieved = scoringConfigDao.getConfig()
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.dimensionWeight).isEqualTo(2.5)
            assertThat(retrieved?.impactCritical).isEqualTo(1.0)
        }

    @Test
    fun observeConfig_emitsConfig() =
        runBlocking {
            val config = createTestScoringConfig()
            scoringConfigDao.upsertConfig(config)

            val observed = scoringConfigDao.observeConfig().first()
            assertThat(observed).isNotNull()
            assertThat(observed?.dimensionWeight).isEqualTo(2.5)
        }

    @Test
    fun getConfig_returnsNullWhenNoConfig() =
        runBlocking {
            val retrieved = scoringConfigDao.getConfig()
            assertThat(retrieved).isNull()
        }

    @Test
    fun upsertConfig_replacesExistingConfig() =
        runBlocking {
            val config1 = createTestScoringConfig(dimensionWeight = 2.0)
            val config2 = createTestScoringConfig(dimensionWeight = 3.0)

            scoringConfigDao.upsertConfig(config1)
            scoringConfigDao.upsertConfig(config2)

            val retrieved = scoringConfigDao.getConfig()
            assertThat(retrieved?.dimensionWeight).isEqualTo(3.0)
        }

    @Test
    fun deleteConfig_removesConfig() =
        runBlocking {
            val config = createTestScoringConfig()
            scoringConfigDao.upsertConfig(config)

            scoringConfigDao.deleteConfig()

            val retrieved = scoringConfigDao.getConfig()
            assertThat(retrieved).isNull()
        }

    private fun createTestScoringConfig(
        dimensionWeight: Double = 2.5,
        impactCritical: Double = 1.0,
    ) = ScoringConfigEntity(
        id = 1,
        dimensionWeight = dimensionWeight,
        impactWeight = 1.5,
        alignmentWeight = 1.3,
        energyWeight = 1.0,
        controlWeight = 1.2,
        durationWeight = 0.8,
        impactCritical = impactCritical,
        impactHigh = 0.85,
        impactModerate = 0.6,
        impactLow = 0.35,
        impactMinimal = 0.15,
        alignmentPerfect = 1.0,
        alignmentStrong = 0.8,
        alignmentModerate = 0.5,
        alignmentWeak = 0.25,
        alignmentNone = 0.1,
        energyHigh = 1.0,
        energyModerate = 0.7,
        energyLow = 0.4,
        controlFull = 1.0,
        controlMostly = 0.85,
        controlOffice = 0.6,
        controlExternal = 0.35,
        controlNone = 0.1,
        dimensionCareerWork = 0.8,
        dimensionHealthWellness = 0.9,
        dimensionRelationships = 0.85,
        dimensionPersonalGrowth = 0.8,
        dimensionFinancial = 0.75,
        dimensionSpiritual = 0.6,
        dimensionRecreation = 0.7,
        dimensionLearning = 0.8,
        dimensionContribution = 0.65,
        updatedAt = "2026-02-01T09:00:00Z",
    )
}
