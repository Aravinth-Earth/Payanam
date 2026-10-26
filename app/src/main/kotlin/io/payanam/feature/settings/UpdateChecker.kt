//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import io.payanam.BuildConfig

// The app-module half of the update-check feature. The checker itself lives in :core:presentation
// (io.payanam.presentation.updates.UpdateChecker); what stays here is the part that is genuinely the
// application's: the build type this install is running.

/**
 * Build-type tokens used in artifact names:
 * `Payanam_<platform>_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.apk`.
 */
internal object ApkBuildType {
    const val DEBUG = "debug"
    const val RELEASE = "release"

    /**
     * Build type of the APK this process is running. Callers pass this IN as a
     * parameter down the selection path (unit tests compile against the debug
     * variant, where reading BuildConfig here would be constant-true).
     */
    fun running(): String = if (BuildConfig.DEBUG) DEBUG else RELEASE
}
