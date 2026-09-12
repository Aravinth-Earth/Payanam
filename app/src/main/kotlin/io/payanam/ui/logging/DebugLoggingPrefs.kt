//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.logging

import android.content.Context
import io.payanam.BuildConfig

/**
 * Plain-SharedPreferences mirror of the debug-logging toggle.
 *
 * The persisted preference lives in the encrypted DB, which cannot be read
 * before unlock — but the logging flag must be correct from process start
 * (the interaction trace records pre-unlock touches). The mirror carries only
 * the boolean the user chose; when it was never written, the build-type
 * default [BuildConfig.DEBUG] applies. Keeping an explicit "off" effective
 * from process start is the point: a disabled user must never spend the
 * pre-unlock window recording.
 */
object DebugLoggingPrefs {

    private const val PREFS_NAME = "payanam_debug_logging"
    private const val KEY_ENABLED = "debug_logging_enabled"

    /** The user's stored choice, or the build-type default when never set. */
    fun resolve(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, BuildConfig.DEBUG)

    /** Mirrors the toggle value; called whenever the stored preference changes. */
    fun write(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
