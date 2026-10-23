//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import io.payanam.BuildConfig
import io.payanam.R
import io.payanam.shared.updates.UpdateChannel

// The app-module half of the update-check feature. The checker itself now lives in :core:presentation
// (io.payanam.presentation.updates.UpdateChecker); what stays here is the part that is genuinely the
// application's: resource ids and the build type this install is running. Both are what kept the
// checker out of shared code, and both are now two tiny functions beside the app's own call sites.

/** Display label resource for a channel (used by dropdown + status rows). */
fun UpdateChannel.labelResId(): Int = when (this) {
    UpdateChannel.DEV -> R.string.settings_update_channel_dev
    UpdateChannel.BETA -> R.string.settings_update_channel_beta
    UpdateChannel.STABLE -> R.string.settings_update_channel_stable
}

/**
 * Build-type tokens used in artifact names:
 * `Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>.apk`.
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

/**
 * Build type a channel ships — one artifact per channel (naming plan):
 * dev → debug, beta/stable → release.
 */
internal fun UpdateChannel.shippedApkType(): String = when (this) {
    UpdateChannel.DEV -> ApkBuildType.DEBUG
    UpdateChannel.BETA, UpdateChannel.STABLE -> ApkBuildType.RELEASE
}
