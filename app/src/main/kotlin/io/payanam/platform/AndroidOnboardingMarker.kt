//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether THIS INSTALLATION has been through the shared onboarding tour.
 *
 * DEVICE-LEVEL, and deliberately not a database row. The fact it replaces was read from the encrypted
 * database's own `database_init_completed` / tour flags - so the forgot-passphrase reset, which deletes that
 * database, answered "never onboarded" to the one user who had demonstrably used the app long enough to want
 * it wiped. The owner's ruling (2026-10-14): *"When a user is using a reset option which means user already
 * know how an app works only then they will be using the reset option. So in that case we don't need to show
 * them the onboarding flow once more."* A genuinely fresh install has no marker and still sees the tour.
 *
 * Stored in the app's private [SharedPreferences], the same device-level home as
 * `MainActivity`'s restart marker, and read/written exactly the same way. Nothing here touches the database,
 * the vault or the security store, and nothing clears it: a wipe is precisely the event that must not.
 */
class AndroidOnboardingMarker(
    private val preferences: SharedPreferences,
) {
    /** True once the tour has been finished on this installation. */
    fun hasCompletedOnboarding(): Boolean = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    /** Records that the tour has been finished. */
    fun markOnboardingCompleted() {
        preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }

    companion object {
        /** The preferences file, separate from the restart marker's so neither can clear the other. */
        const val PREFS_NAME = "payanam_onboarding"

        /** The one key this marker stores. */
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        /** Builds the marker over this app's private preferences file. */
        fun from(context: Context): AndroidOnboardingMarker =
            AndroidOnboardingMarker(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
