//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The setup screen builds one of these per dimension row, and the seed-row writer consumes them.
 *
 * These tests moved here with the class so the constructor is exercised in the module that owns it —
 * a data class with no test in its own module is invisible to that module's coverage floor.
 */
class NewDatabaseDimensionInputTest {
    @Test
    fun `a row keeps exactly the fields it was constructed with`() {
        val input =
            NewDatabaseDimensionInput(
                id = "dim_career_work",
                label = "  Career & Work  ",
                colorHex = "#FF9800",
                isEnabled = true,
                iconKey = "work",
            )

        assertThat(input.id).isEqualTo("dim_career_work")
        assertThat(input.label).isEqualTo("  Career & Work  ")
        assertThat(input.colorHex).isEqualTo("#FF9800")
        assertThat(input.isEnabled).isTrue()
        assertThat(input.iconKey).isEqualTo("work")
    }

    @Test
    fun `an omitted icon key defaults from the dimension id, so a caller cannot leave a row iconless by accident`() {
        val input =
            NewDatabaseDimensionInput(
                id = "dim_career_work",
                label = "Career & Work",
                colorHex = "#FF9800",
                isEnabled = true,
            )

        assertThat(input.iconKey).isEqualTo("work")
    }

    @Test
    fun `a disabled row stays disabled rather than defaulting to enabled`() {
        val input =
            NewDatabaseDimensionInput(
                id = "dim_finance",
                label = "Finance",
                colorHex = "#4CAF50",
                isEnabled = false,
            )

        assertThat(input.isEnabled).isFalse()
    }

    @Test
    fun `copy replaces one field and leaves the rest, so the setup screen can edit a row in place`() {
        val original =
            NewDatabaseDimensionInput(
                id = "dim_career_work",
                label = "Career & Work",
                colorHex = "#FF9800",
                isEnabled = true,
            )

        val edited = original.copy(label = "Day job")

        assertThat(edited.label).isEqualTo("Day job")
        assertThat(edited.id).isEqualTo(original.id)
        assertThat(edited.colorHex).isEqualTo(original.colorHex)
        assertThat(edited.isEnabled).isEqualTo(original.isEnabled)
        assertThat(edited).isNotEqualTo(original)
    }
}
