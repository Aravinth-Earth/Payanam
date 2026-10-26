//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

/**
 * Single source of truth for update-related preference keys.
 *
 * These are **row keys in the shared `app_settings` table**, not Android preference names: both the
 * Android settings pipeline and the app-start checker read and write through them, and the desktop reads
 * the same rows. So the values are a wire contract with Android, not a private constant — which is why
 * this object is shared rather than living in the app module, and why
 * `UpdatePrefKeysContractTest` pins every literal.
 *
 * `public`, deliberately: the original was `internal` in `:app`, which is module-scoped, so it could not
 * survive the move unchanged. Anything that copies these values instead of importing them re-creates the
 * second answer this object exists to prevent.
 */
object UpdatePrefKeys {
    const val AUTO_DOWNLOAD = "auto_download_enabled"
    const val PROMPT_INSTALL = "prompt_install_enabled"
    const val WIFI_ONLY = "wifi_only_enabled"
    const val AUTO_CHECK = "auto_check_enabled"
    const val ACTIVE_DOWNLOAD_ID = "active_download_id"
    const val ACTIVE_DOWNLOAD_FILE = "active_download_file"

    /**
     * The `major.minor.patch` version of the last completed download. The row key keeps its old
     * literal because it is persisted data on existing installs, and renaming it would orphan the value.
     */
    const val LAST_DOWNLOADED_VERSION = "last_downloaded_build"
    const val LAST_DOWNLOADED_FILE = "last_downloaded_file"
    const val LAST_DOWNLOADED_AT = "last_downloaded_at"
    const val ACTIVE_DOWNLOAD_URL = "active_download_url"
    const val ACTIVE_DOWNLOAD_SHA256_URL = "active_download_sha256_url"
}
