//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Android's onboarding marker: a device-level fact that must outlive the database it is about.
 *
 * The defect this replaces was one of HOME, not of logic: "has this installation been through the tour?"
 * was answered from flags INSIDE the encrypted database, so the forgot-passphrase reset - which deletes that
 * database - answered "no" to the one user who had used the app long enough to want it wiped. Owner's ruling
 * (2026-10-14): *"When a user is using a reset option which means user already know how an app works only
 * then they will be using the reset option. So in that case we don't need to show them the onboarding flow
 * once more."*
 *
 * These cases are run through the real [android.content.SharedPreferences] of a Robolectric application
 * context, so what is asserted is the storage the app actually reads, not a fake of it.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidOnboardingMarkerTest {
    @Test
    fun `a fresh installation has no marker`() {
        assertThat(marker().hasCompletedOnboarding()).isFalse()
    }

    @Test
    fun `a finished tour is recorded and read back`() {
        marker().markOnboardingCompleted()

        // A NEW instance over the same installed app, which is the shape a restart produces: the claim is
        // about the stored value, not about an in-memory field.
        assertThat(marker().hasCompletedOnboarding()).isTrue()
    }

    @Test
    fun `the marker lives in its own preferences file, beside the restart marker's`() {
        marker().markOnboardingCompleted()

        // NOT `payanam_restart_marker`: the two device-level facts share a home (this app's private
        // preferences) but not a file, so a diagnostic that clears one cannot silently re-arm the tour.
        assertThat(AndroidOnboardingMarker.PREFS_NAME).isNotEqualTo("payanam_restart_marker")
        assertThat(AndroidOnboardingMarker.from(context()).hasCompletedOnboarding()).isTrue()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun marker(): AndroidOnboardingMarker = AndroidOnboardingMarker.from(context())
}
