//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop
object DesktopBuildInfo {
    /** Target platform name for the desktop build. */
    const val PLATFORM = "Windows"
    /** Sequential build number for the desktop (Windows) platform. */
    const val PLATFORM_BUILD_NUMBER = 616
    /** Overall (cross-platform) build counter at the time of this build. */
    const val OVERALL_BUILD_NUMBER = 2165
    /** Build timestamp in yyyyMMdd_HHmmss form. */
    const val BUILD_TIMESTAMP = "20260808_221149"
    /** Human-readable version label, e.g. "#W616 (timestamp)". */
    const val VERSION_DISPLAY_NAME = "#W616 (20260808_221149)"
    /** Full build artifact name. */
    const val BUILD_NAME = "Payanam_Windows_616_20260808_221149"
}
