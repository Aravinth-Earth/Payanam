//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

/**
 * Single source of truth for update-related preference keys.
 * Both the Settings UI pipeline (SettingsViewModel) and the
 * app-start checker read/write through these — never duplicated.
 */
internal object UpdatePrefKeys {
    const val UPDATE_CHANNEL = "update_channel"
    const val AUTO_DOWNLOAD = "auto_download_enabled"
    const val PROMPT_INSTALL = "prompt_install_enabled"
    const val WIFI_ONLY = "wifi_only_enabled"
    const val AUTO_CHECK = "auto_check_enabled"
    const val ACTIVE_DOWNLOAD_ID = "active_download_id"
    const val ACTIVE_DOWNLOAD_FILE = "active_download_file"
    const val ACTIVE_DOWNLOAD_URL = "active_download_url"
}
