//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DayPlanPolicyEntityTest.
 */
class DayPlanPolicyEntityTest {
    @Test
    /**
     * Day plan policy entity store copy and component contracts work.
     */
    fun dayPlanPolicyEntity_store_copy_and_component_contracts_work() {
        /** Entity. */
        val entity =
            /** Day plan policy entity. */
            DayPlanPolicyEntity(
                dayKey = "2026-02-22",
                mode = "template",
                templateId = "tpl-weekend",
                isStarred = 1,
                updatedAt = "2026-02-19T09:30:00",
            )

        /** Assert that. */
        assertThat(entity.dayKey).isEqualTo("2026-02-22")
        /** Assert that. */
        assertThat(entity.mode).isEqualTo("template")
        /** Assert that. */
        assertThat(entity.templateId).isEqualTo("tpl-weekend")
        /** Assert that. */
        assertThat(entity.isStarred).isEqualTo(1)
        /** Assert that. */
        assertThat(entity.updatedAt).isEqualTo("2026-02-19T09:30:00")

        /** Copied. */
        val copied = entity.copy(mode = "custom", templateId = null, isStarred = 0)
        /** Assert that. */
        assertThat(copied.component1()).isEqualTo("2026-02-22")
        /** Assert that. */
        assertThat(copied.component2()).isEqualTo("custom")
        /** Assert that. */
        assertThat(copied.component3()).isNull()
        /** Assert that. */
        assertThat(copied.component4()).isEqualTo(0)
        /** Assert that. */
        assertThat(copied.toString()).contains("2026-02-22")
    }

    @Test
    /**
     * Day type template preference entity store copy and component contracts work.
     */
    fun dayTypeTemplatePreferenceEntity_store_copy_and_component_contracts_work() {
        /** Entity. */
        val entity =
            /** Day type template preference entity. */
            DayTypeTemplatePreferenceEntity(
                dayType = "weekday",
                templateId = "tpl-weekday",
                updatedAt = "2026-02-19T09:45:00",
            )

        /** Assert that. */
        assertThat(entity.dayType).isEqualTo("weekday")
        /** Assert that. */
        assertThat(entity.templateId).isEqualTo("tpl-weekday")
        /** Assert that. */
        assertThat(entity.updatedAt).isEqualTo("2026-02-19T09:45:00")

        /** Copied. */
        val copied = entity.copy(templateId = null)
        /** Assert that. */
        assertThat(copied.component1()).isEqualTo("weekday")
        /** Assert that. */
        assertThat(copied.component2()).isNull()
        /** Assert that. */
        assertThat(copied.component3()).isEqualTo("2026-02-19T09:45:00")
        /** Assert that. */
        assertThat(copied.toString()).contains("weekday")
    }
}
