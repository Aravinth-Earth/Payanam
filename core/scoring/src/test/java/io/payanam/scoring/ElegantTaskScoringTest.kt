//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.model.Task
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class ElegantTaskScoringTest {

    private lateinit var logger: UnifiedLogger

    @Before
    fun setup() {
        logger = initLogger()
        logger.d("ElegantTaskScoringTest.setup", "Logger initialized for tests")
    }

    @Test
    fun calculateScore_returnsBetweenZeroAndOne() {
        val task = baseTask()
        val score = ElegantTaskScoring.calculateScore(task)
        assertThat(score).isAtLeast(0.0)
        assertThat(score).isAtMost(1.0)
    }

    @Test
    fun higherImpact_increasesScore() {
        val lowImpact = baseTask(impact = "Minimal Impact")
        val highImpact = baseTask(impact = "Critical Impact")
        val lowScore = ElegantTaskScoring.calculateScore(lowImpact)
        val highScore = ElegantTaskScoring.calculateScore(highImpact)
        assertThat(highScore).isGreaterThan(lowScore)
    }

    @Test
    fun shorterDuration_hasHigherScore() {
        val shortTask = baseTask(durationMinutes = 15)
        val longTask = baseTask(durationMinutes = 240)
        val shortScore = ElegantTaskScoring.calculateScore(shortTask)
        val longScore = ElegantTaskScoring.calculateScore(longTask)
        assertThat(shortScore).isGreaterThan(longScore)
    }

    @Test
    fun defaults_matchExpectedValues() {
        val defaults = ElegantTaskScoring.getDefaults()
        assertThat(defaults.durationMinutes).isEqualTo(10)
        assertThat(defaults.impactLevel).isEqualTo("Moderate Impact")
        assertThat(defaults.lifeIntentionCategory).isEqualTo("Career & Work")
    }

    @Test
    fun options_includeLifeDimensions() {
        val options = ElegantTaskScoring.getOptions()
        assertThat(options.lifeDimensions).contains("Career & Work")
        assertThat(options.impactLevels).contains("Moderate Impact")
    }

    @Test
    fun calculateScore_withCustomConfig_usesConfigWeights() {
        val task = baseTask()
        val customConfig = ScoringConfig(
            dimensionWeightsById = mapOf(LifeDimension.CAREER_WORK.id to 0.9),
            dimensionWeights = mapOf(LifeDimension.CAREER_WORK to 0.9),
            impactLevelWeights = mapOf("Moderate Impact" to 0.8),
            alignmentWeights = mapOf("Moderate Alignment" to 0.7),
            energyLevelWeights = mapOf("Moderate" to 0.6),
            controlLevelWeights = mapOf("Office/Colleagues Dependent" to 0.5),
            dimensionWeight = 0.3,
            impactWeight = 0.25,
            alignmentWeight = 0.2,
            energyWeight = 0.15,
            controlWeight = 0.05,
            durationWeight = 0.05
        )
        val score = ElegantTaskScoring.calculateScore(task, customConfig)
        assertThat(score).isAtLeast(0.0)
        assertThat(score).isAtMost(1.0)
    }

    @Test
    fun calculateScore_prefersDimensionIdWeights() {
        val highWeightTask = baseTask(lifeIntentionCategory = "Unknown", dimensionId = "dim_health_wellness")
        val lowWeightTask = baseTask(lifeIntentionCategory = "Unknown", dimensionId = "dim_spiritual")
        val customConfig = ScoringConfig(
            dimensionWeightsById = mapOf(
                "dim_health_wellness" to 0.95,
                "dim_spiritual" to 0.35
            ),
            impactLevelWeights = mapOf("Moderate Impact" to 0.8),
            alignmentWeights = mapOf("Moderate Alignment" to 0.8),
            energyLevelWeights = mapOf("Moderate" to 0.8),
            controlLevelWeights = mapOf("Office/Colleagues Dependent" to 0.8)
        )

        val highScore = ElegantTaskScoring.calculateScore(highWeightTask, customConfig)
        val lowScore = ElegantTaskScoring.calculateScore(lowWeightTask, customConfig)
        assertThat(highScore).isGreaterThan(lowScore)
    }

    @Test
    fun calculateScore_dimensionId_fallsBackToLegacyEnumWeightWhenByIdMissing() {
        val task = baseTask(lifeIntentionCategory = "Unknown", dimensionId = LifeDimension.LEARNING.id)
        val customConfig = ScoringConfig(
            dimensionWeightsById = emptyMap(),
            dimensionWeights = mapOf(LifeDimension.LEARNING to 0.95, LifeDimension.CAREER_WORK to 0.2),
            impactLevelWeights = mapOf("Moderate Impact" to 0.8),
            alignmentWeights = mapOf("Moderate Alignment" to 0.8),
            energyLevelWeights = mapOf("Moderate" to 0.8),
            controlLevelWeights = mapOf("Office/Colleagues Dependent" to 0.8)
        )

        val highTaskScore = ElegantTaskScoring.calculateScore(task, customConfig)
        val lowTaskScore = ElegantTaskScoring.calculateScore(
            baseTask(lifeIntentionCategory = "Unknown", dimensionId = LifeDimension.CAREER_WORK.id),
            customConfig
        )
        assertThat(highTaskScore).isGreaterThan(lowTaskScore)
    }

    @Test
    fun calculateScore_unknownDimensionIdWithKnownLabel_usesLabelFallback() {
        val task = baseTask(
            lifeIntentionCategory = "Health & Wellness",
            dimensionId = "dim_not_present"
        )
        val config = ScoringConfig(
            dimensionWeightsById = emptyMap(),
            dimensionWeights = mapOf(LifeDimension.HEALTH_WELLNESS to 0.95, LifeDimension.CAREER_WORK to 0.2),
            impactLevelWeights = mapOf("Moderate Impact" to 0.8),
            alignmentWeights = mapOf("Moderate Alignment" to 0.8),
            energyLevelWeights = mapOf("Moderate" to 0.8),
            controlLevelWeights = mapOf("Office/Colleagues Dependent" to 0.8)
        )

        val score = ElegantTaskScoring.calculateScore(task, config)
        val lowScore = ElegantTaskScoring.calculateScore(
            baseTask(lifeIntentionCategory = "Career & Work", dimensionId = null),
            config
        )
        assertThat(score).isGreaterThan(lowScore)
    }

    @Test
    fun calculateScore_withUnknownLifeDimension_usesDefault() {
        val task = baseTask(lifeIntentionCategory = "Unknown Category")
        val score = ElegantTaskScoring.calculateScore(task)
        assertThat(score).isAtLeast(0.0)
        assertThat(score).isAtMost(1.0)
    }

    @Test
    fun calculateScore_withUnknownImpactLevel_usesDefault() {
        val task = baseTask(impact = "Unknown Impact")
        val score = ElegantTaskScoring.calculateScore(task)
        assertThat(score).isAtLeast(0.0)
        assertThat(score).isAtMost(1.0)
    }

    @Test
    fun calculateScore_treats_major_impact_as_high_impact() {
        val highImpactTask = baseTask(impact = "High Impact")
        val majorImpactTask = baseTask(impact = "Major Impact")

        val highImpactScore = ElegantTaskScoring.calculateScore(highImpactTask)
        val majorImpactScore = ElegantTaskScoring.calculateScore(majorImpactTask)

        assertThat(majorImpactScore).isEqualTo(highImpactScore)
    }

    @Test
    fun calculateScore_treats_high_alignment_as_strong_alignment() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val strongTask = Task(
            id = "task-strong",
            title = "Strong",
            createdAt = now,
            updatedAt = now,
            impactLevel = "Moderate Impact",
            goalAlignment = "Strong Alignment",
            energyLevel = "Moderate",
            controlLevel = "Office/Colleagues Dependent",
            lifeIntentionCategory = "Career & Work",
            durationMinutes = 10
        )
        val highTask = strongTask.copy(id = "task-high", goalAlignment = "High Alignment")

        val strongScore = ElegantTaskScoring.calculateScore(strongTask)
        val highScore = ElegantTaskScoring.calculateScore(highTask)

        assertThat(highScore).isEqualTo(strongScore)
    }

    @Test
    fun normalizeDuration_edgeCases() {
        // Test very short duration
        val veryShort = baseTask(durationMinutes = 5)
        val shortScore = ElegantTaskScoring.calculateScore(veryShort)

        // Test very long duration
        val veryLong = baseTask(durationMinutes = 480) // 8 hours
        val longScore = ElegantTaskScoring.calculateScore(veryLong)

        // Very short should score higher than very long
        assertThat(shortScore).isGreaterThan(longScore)
    }

    @Test
    fun calculateScore_allFactorsAtMinimum() {
        val task = Task(
            id = "task-min",
            title = "Min Task",
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
            impactLevel = "Minimal Impact",
            goalAlignment = "Minimal Alignment",
            energyLevel = "Low",
            controlLevel = "Fully Dependent",
            lifeIntentionCategory = "Unknown",
            durationMinutes = 480 // Very long
        )
        val score = ElegantTaskScoring.calculateScore(task)
        assertThat(score).isAtLeast(0.0)
        assertThat(score).isAtMost(1.0)
    }

    @Test
    fun calculateScore_allFactorsAtMaximum() {
        val task = Task(
            id = "task-max",
            title = "Max Task",
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
            impactLevel = "Critical Impact",
            goalAlignment = "Perfect Alignment",
            energyLevel = "High",
            controlLevel = "Fully Independent",
            lifeIntentionCategory = "Career & Work",
            durationMinutes = 5 // Very short
        )
        val score = ElegantTaskScoring.calculateScore(task)
        assertThat(score).isAtLeast(0.0)
        assertThat(score).isAtMost(1.0)
    }

    @Test
    fun defaults_and_options_data_classes_support_copy_and_components() {
        val defaults = ElegantTaskScoring.getDefaults().copy(durationMinutes = 15)
        val duration = defaults.component1()
        val impact = defaults.component2()
        val goalAlignment = defaults.component3()
        val energy = defaults.component4()
        val control = defaults.component5()
        val dimension = defaults.component6()

        assertThat(duration).isEqualTo(15)
        assertThat(impact).isEqualTo("Moderate Impact")
        assertThat(goalAlignment).isEqualTo("Moderate Alignment")
        assertThat(energy).isEqualTo("Moderate")
        assertThat(control).isEqualTo("Office/Colleagues Dependent")
        assertThat(dimension).isEqualTo("Career & Work")

        val options = ElegantTaskScoring.getOptions().copy(impactLevels = listOf("X"))
        val impactLevels = options.component1()
        val alignments = options.component2()
        val energies = options.component3()
        val controls = options.component4()
        val dimensions = options.component5()

        assertThat(impactLevels).containsExactly("X")
        assertThat(alignments).contains("Moderate Alignment")
        assertThat(energies).contains("Moderate")
        assertThat(controls).contains("Office/Colleagues Dependent")
        assertThat(dimensions).contains("Career & Work")
    }

    private fun baseTask(
        impact: String = "Moderate Impact",
        durationMinutes: Int = 10,
        lifeIntentionCategory: String = "Career & Work",
        dimensionId: String? = null
    ): Task {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        return Task(
            id = "task-1",
            title = "Test Task",
            createdAt = now,
            updatedAt = now,
            impactLevel = impact,
            goalAlignment = "Moderate Alignment",
            energyLevel = "Moderate",
            controlLevel = "Office/Colleagues Dependent",
            lifeIntentionCategory = lifeIntentionCategory,
            dimensionId = dimensionId,
            durationMinutes = durationMinutes
        )
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
