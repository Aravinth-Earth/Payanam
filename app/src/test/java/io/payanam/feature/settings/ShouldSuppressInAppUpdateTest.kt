//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldSuppressInAppUpdateTest {

    @Test
    fun `suppresses when this is the F-Droid build`() {
        assertTrue(shouldSuppressInAppUpdate(buildFlag = true))
    }

    @Test
    fun `does not suppress for a GitHub build`() {
        assertFalse(shouldSuppressInAppUpdate(buildFlag = false))
    }
}
