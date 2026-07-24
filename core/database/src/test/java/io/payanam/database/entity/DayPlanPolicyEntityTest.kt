//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DayPlanPolicyEntityTest {
    @Test
    fun dayPlanPolicyEntity_store_copy_and_component_contracts_work() {
        val entity =
            DayPlanPolicyEntity(
                dayKey = "2026-02-22",
                mode = "template",
                templateId = "tpl-weekend",
                isStarred = 1,
                updatedAt = "2026-02-19T09:30:00",
            )

        assertThat(entity.dayKey).isEqualTo("2026-02-22")
        assertThat(entity.mode).isEqualTo("template")
        assertThat(entity.templateId).isEqualTo("tpl-weekend")
        assertThat(entity.isStarred).isEqualTo(1)
        assertThat(entity.updatedAt).isEqualTo("2026-02-19T09:30:00")

        val copied = entity.copy(mode = "custom", templateId = null, isStarred = 0)
        assertThat(copied.component1()).isEqualTo("2026-02-22")
        assertThat(copied.component2()).isEqualTo("custom")
        assertThat(copied.component3()).isNull()
        assertThat(copied.component4()).isEqualTo(0)
        assertThat(copied.toString()).contains("2026-02-22")
    }

    @Test
    fun dayTypeTemplatePreferenceEntity_store_copy_and_component_contracts_work() {
        val entity =
            DayTypeTemplatePreferenceEntity(
                dayType = "weekday",
                templateId = "tpl-weekday",
                updatedAt = "2026-02-19T09:45:00",
            )

        assertThat(entity.dayType).isEqualTo("weekday")
        assertThat(entity.templateId).isEqualTo("tpl-weekday")
        assertThat(entity.updatedAt).isEqualTo("2026-02-19T09:45:00")

        val copied = entity.copy(templateId = null)
        assertThat(copied.component1()).isEqualTo("weekday")
        assertThat(copied.component2()).isNull()
        assertThat(copied.component3()).isEqualTo("2026-02-19T09:45:00")
        assertThat(copied.toString()).contains("weekday")
    }
}
