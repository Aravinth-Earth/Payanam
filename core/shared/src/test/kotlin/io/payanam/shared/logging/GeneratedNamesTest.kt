//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeneratedNamesTest {
    private val platform = "Android"
    private val version = "1.0.0"
    private val sha = "abc1234"
    private val debugBuild = true
    private val timestamp = "20261026_101500"

    @Test
    fun `every kind follows one template`() {
        val expected =
            listOf(
                "artefact" to "Payanam_android_1.0.0_abc1234_debug_20261026_101500",
                "session log" to "payanam_android_1.0.0_abc1234_debug_20261026_101500.log",
                "log export" to "payanam_logs_android_1.0.0_abc1234_debug_20261026_101500.zip",
                "db export" to "payanam_backup_encrypted_android_1.0.0_abc1234_debug_20261026_101500.db",
                "auto backup" to "auto_bk_android_1.0.0_abc1234_debug_20261026_101500",
            )
        val actual =
            mapOf(
                "artefact" to generatedName("Payanam", platform, version, sha, "debug", timestamp),
                "session log" to sessionLogName(platform, version, sha, debugBuild, timestamp),
                "log export" to logExportName(platform, version, sha, debugBuild, timestamp),
                "db export" to dbExportName(platform, version, sha, debugBuild, timestamp),
                "auto backup" to autoBackupDirName(platform, version, sha, debugBuild, timestamp),
            )
        expected.forEach { (kind, name) -> assertThat(actual[kind]).isEqualTo(name) }
    }

    @Test
    fun `a release build carries the release token`() {
        assertThat(sessionLogName(platform, version, sha, debugBuild = false, timestamp))
            .isEqualTo("payanam_android_1.0.0_abc1234_release_20261026_101500.log")
    }
}
