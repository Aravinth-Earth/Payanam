//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class FrequencyTest {

    @Test
    fun parse_readsCanonicalRuleWithAnchor() {
        val frequency = Frequency.parse("3/7!start=2026-04-01")

        assertThat(frequency.numerator).isEqualTo(3)
        assertThat(frequency.denominator).isEqualTo(7)
        assertThat(frequency.anchorDate).isEqualTo(LocalDate.of(2026, 4, 1))
    }

    @Test
    fun legacyParse_convertsConfigFrequencyToCanonicalValues() {
        val frequency = Frequency.legacyParse("CONFIG:type=FREQUENCY|freq=5/7|start=2026-04-01")

        assertThat(frequency.numerator).isEqualTo(5)
        assertThat(frequency.denominator).isEqualTo(7)
    }

    @Test
    fun isSerializedRule_acceptsCanonicalAndRejectsLegacy() {
        assertThat(Frequency.isSerializedRule("2/7")).isTrue()
        assertThat(Frequency.isSerializedRule("2/7!start=2026-04-01")).isTrue()
        assertThat(Frequency.isSerializedRule("CONFIG:type=FREQUENCY|freq=2/7")).isFalse()
        assertThat(Frequency.isSerializedRule("FREQ=DAILY;INTERVAL=1")).isFalse()
    }

    @Test
    fun recurrenceConfigParse_readsCanonicalFrequencyRule() {
        val config = RecurrenceConfig.parse("2/7!start=2026-04-01")

        assertThat(config.type).isEqualTo(RecurrenceType.FREQUENCY)
        assertThat(config.frequencyNumerator).isEqualTo(2)
        assertThat(config.frequencyDenominator).isEqualTo(7)
        assertThat(config.startDate).isEqualTo(LocalDate.of(2026, 4, 1))
    }
}
