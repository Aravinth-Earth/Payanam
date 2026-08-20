//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

/**
 * Single source of truth for update-related preference keys.
 * Both the Settings UI pipeline (SettingsViewModel) and the
 * app-start checker read/write through these — never duplicated.
 */
internal object UpdatePrefKeys {
    /** Update channel. */
    const val UPDATE_CHANNEL = "update_channel"
    /** Auto download. */
    const val AUTO_DOWNLOAD = "auto_download_enabled"
    /** Prompt install. */
    const val PROMPT_INSTALL = "prompt_install_enabled"
    /** Wifi only. */
    const val WIFI_ONLY = "wifi_only_enabled"
    /** Auto check. */
    const val AUTO_CHECK = "auto_check_enabled"
    /** Active download id. */
    const val ACTIVE_DOWNLOAD_ID = "active_download_id"
    /** Active download file. */
    const val ACTIVE_DOWNLOAD_FILE = "active_download_file"
    /** Last downloaded build. */
    const val LAST_DOWNLOADED_BUILD = "last_downloaded_build"
    /** Last downloaded file. */
    const val LAST_DOWNLOADED_FILE = "last_downloaded_file"
    /** Last downloaded at. */
    const val LAST_DOWNLOADED_AT = "last_downloaded_at"
    /** Active download url. */
    const val ACTIVE_DOWNLOAD_URL = "active_download_url"
}
