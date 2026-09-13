//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The process-start mirror of the debug-logging toggle: an explicit disable
 * must be effective from startup (before the encrypted-DB preference can be
 * read); a never-set value falls back to the build-type default.
 */
@RunWith(RobolectricTestRunner::class)
class DebugLoggingPrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `never stored falls back to the build-type default`() {
        assertEquals(BuildConfig.DEBUG, DebugLoggingPrefs.resolve(context))
    }

    @Test
    fun `a stored disable is respected`() {
        DebugLoggingPrefs.write(context, false)
        assertFalse(DebugLoggingPrefs.resolve(context))
    }

    @Test
    fun `a stored enable round-trips`() {
        DebugLoggingPrefs.write(context, true)
        assertTrue(DebugLoggingPrefs.resolve(context))
    }
}
