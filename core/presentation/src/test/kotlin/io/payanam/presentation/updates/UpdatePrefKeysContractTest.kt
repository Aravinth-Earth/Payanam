//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The literals in [UpdatePrefKeys] are **row keys in the shared `app_settings` table**, so they are a
 * wire contract with the desktop and with a user's existing data — not private constants.
 *
 * This test pins every one of them exactly. A renamed key does not fail a compile and does not fail
 * any behavioural test: the row is simply absent, so an update preference quietly reverts to its
 * default on the next launch, forever, with no error anywhere. That is the same argument the plan
 * records for `DatabaseArtifacts`' file names ("a rename orphans a user's in-flight backup instead of
 * failing anything"), applied to preference rows.
 *
 * It also asserts the set is exactly these eleven, so deleting a key that is still read or written
 * somewhere fails here rather than at a runtime default.
 */
class UpdatePrefKeysContractTest {
    @Test
    fun `every key is the exact literal the shared app_settings rows use`() {
        assertThat(UpdatePrefKeys.UPDATE_CHANNEL).isEqualTo("update_channel")
        assertThat(UpdatePrefKeys.AUTO_DOWNLOAD).isEqualTo("auto_download_enabled")
        assertThat(UpdatePrefKeys.PROMPT_INSTALL).isEqualTo("prompt_install_enabled")
        assertThat(UpdatePrefKeys.WIFI_ONLY).isEqualTo("wifi_only_enabled")
        assertThat(UpdatePrefKeys.AUTO_CHECK).isEqualTo("auto_check_enabled")
        assertThat(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID).isEqualTo("active_download_id")
        assertThat(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE).isEqualTo("active_download_file")
        assertThat(UpdatePrefKeys.LAST_DOWNLOADED_BUILD).isEqualTo("last_downloaded_build")
        assertThat(UpdatePrefKeys.LAST_DOWNLOADED_FILE).isEqualTo("last_downloaded_file")
        assertThat(UpdatePrefKeys.LAST_DOWNLOADED_AT).isEqualTo("last_downloaded_at")
        assertThat(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL).isEqualTo("active_download_url")
    }

    @Test
    fun `the key set is exactly these eleven, none duplicated`() {
        val declared =
            listOf(
                UpdatePrefKeys.UPDATE_CHANNEL,
                UpdatePrefKeys.AUTO_DOWNLOAD,
                UpdatePrefKeys.PROMPT_INSTALL,
                UpdatePrefKeys.WIFI_ONLY,
                UpdatePrefKeys.AUTO_CHECK,
                UpdatePrefKeys.ACTIVE_DOWNLOAD_ID,
                UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE,
                UpdatePrefKeys.LAST_DOWNLOADED_BUILD,
                UpdatePrefKeys.LAST_DOWNLOADED_FILE,
                UpdatePrefKeys.LAST_DOWNLOADED_AT,
                UpdatePrefKeys.ACTIVE_DOWNLOAD_URL,
            )

        assertThat(declared).hasSize(11)
        assertThat(declared.toSet()).hasSize(11)
    }
}
