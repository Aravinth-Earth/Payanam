//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskPriorityTest {
    @Test
    fun `bands a score on the one-point scale`() {
        assertThat(TaskPriority.of(0.0)).isEqualTo("Low")
        assertThat(TaskPriority.of(0.499)).isEqualTo("Low")
        assertThat(TaskPriority.of(0.5)).isEqualTo("Medium")
        assertThat(TaskPriority.of(0.799)).isEqualTo("Medium")
        assertThat(TaskPriority.of(0.8)).isEqualTo("High")
        assertThat(TaskPriority.of(1.0)).isEqualTo("High")
    }

    @Test
    fun `the top band starts where the documented score contract ends`() {
        assertThat(TaskPriority.of(1.0)).isEqualTo("High")
        assertThat(TaskPriority.HIGH_SCORE).isAtLeast(TaskPriority.MEDIUM_SCORE)
    }
}
