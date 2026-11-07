//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.version

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The build label is the only place the three build facts are spelled, so each part's absence is pinned
 * here: a label that invents a date or a commit is exactly the bug this class exists to prevent.
 */
class BuildLabelTest {
    @Test
    fun `full build shows version, short sha and minute-precision timestamp`() {
        val label = BuildLabel.of("1.0.0", "f75faced", "20261107_162458")

        assertThat(label).isEqualTo("1.0.0 · f75faced · 2026-11-07 16:24")
    }

    @Test
    fun `a build with no recorded stamp drops the date instead of showing zeros`() {
        assertThat(BuildLabel.of("1.0.0", "f75faced", "00000000_000000"))
            .isEqualTo("1.0.0 · f75faced")
        assertThat(BuildLabel.of("1.0.0", "f75faced", ""))
            .isEqualTo("1.0.0 · f75faced")
    }

    @Test
    fun `a source-tarball build shows the version alone`() {
        assertThat(BuildLabel.of("1.0.0", "nogit", "00000000_000000")).isEqualTo("1.0.0")
        assertThat(BuildLabel.of("1.0.0", "", "")).isEqualTo("1.0.0")
    }

    @Test
    fun `an unparsable stamp is shown as it came, never hidden`() {
        assertThat(BuildLabel.of("1.0.0", "f75faced", "2026-11-07T16:24:58"))
            .isEqualTo("1.0.0 · f75faced · 2026-11-07T16:24:58")
    }

    @Test
    fun `parts are trimmed so a padded property cannot widen the label`() {
        assertThat(BuildLabel.of(" 1.0.0 ", " f75faced ", " 20261107_162458 "))
            .isEqualTo("1.0.0 · f75faced · 2026-11-07 16:24")
    }
}
