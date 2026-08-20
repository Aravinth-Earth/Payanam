//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * FrequencyTest.
 */
class FrequencyTest {

    @Test
    /**
     * Parse reads canonical rule with anchor.
     */
    fun parse_readsCanonicalRuleWithAnchor() {
        /** Frequency. */
        val frequency = Frequency.parse("3/7!start=2026-04-01")

        /** Assert that. */
        assertThat(frequency.numerator).isEqualTo(3)
        /** Assert that. */
        assertThat(frequency.denominator).isEqualTo(7)
        /** Assert that. */
        assertThat(frequency.anchorDate).isEqualTo(LocalDate.of(2026, 4, 1))
    }

    @Test
    /**
     * Legacy parse converts config frequency to canonical values.
     */
    fun legacyParse_convertsConfigFrequencyToCanonicalValues() {
        /** Frequency. */
        val frequency = Frequency.legacyParse("CONFIG:type=FREQUENCY|freq=5/7|start=2026-04-01")

        /** Assert that. */
        assertThat(frequency.numerator).isEqualTo(5)
        /** Assert that. */
        assertThat(frequency.denominator).isEqualTo(7)
    }

    @Test
    /**
     * Is serialized rule accepts canonical and rejects legacy.
     */
    fun isSerializedRule_acceptsCanonicalAndRejectsLegacy() {
        /** Assert that. */
        assertThat(Frequency.isSerializedRule("2/7")).isTrue()
        /** Assert that. */
        assertThat(Frequency.isSerializedRule("2/7!start=2026-04-01")).isTrue()
        /** Assert that. */
        assertThat(Frequency.isSerializedRule("CONFIG:type=FREQUENCY|freq=2/7")).isFalse()
        /** Assert that. */
        assertThat(Frequency.isSerializedRule("FREQ=DAILY;INTERVAL=1")).isFalse()
    }

    @Test
    /**
     * Recurrence config parse reads canonical frequency rule.
     */
    fun recurrenceConfigParse_readsCanonicalFrequencyRule() {
        /** Config. */
        val config = RecurrenceConfig.parse("2/7!start=2026-04-01")

        /** Assert that. */
        assertThat(config.type).isEqualTo(RecurrenceType.FREQUENCY)
        /** Assert that. */
        assertThat(config.frequencyNumerator).isEqualTo(2)
        /** Assert that. */
        assertThat(config.frequencyDenominator).isEqualTo(7)
        /** Assert that. */
        assertThat(config.startDate).isEqualTo(LocalDate.of(2026, 4, 1))
    }
}
