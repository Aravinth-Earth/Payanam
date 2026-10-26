//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OsTokenTest {
    @Test
    fun `trims and lowercases the platform`() {
        assertThat(osToken("Linux")).isEqualTo("linux")
        assertThat(osToken(" Windows ")).isEqualTo("windows")
        assertThat(osToken("android")).isEqualTo("android")
    }
}
