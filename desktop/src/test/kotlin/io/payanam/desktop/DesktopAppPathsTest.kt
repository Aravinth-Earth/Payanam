//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Test

@Ignore("Windows-only — runs in Desktop CI")
/**
 * DesktopAppPathsTest.
 */
class DesktopAppPathsTest {
    @Test
    fun `resolve root directory prefers LOCALAPPDATA`() {
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\demo\\AppData\\Local"),
                userHome = "C:\\Users\\demo",
            )

        assertThat(root.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Local\\Payanam")
    }

    @Test
    fun `resolve root directory falls back to APPDATA when local is missing`() {
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = mapOf("APPDATA" to "C:\\Users\\demo\\AppData\\Roaming"),
                userHome = "C:\\Users\\demo",
            )

        assertThat(root.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Roaming\\Payanam")
    }

    @Test
    fun `resolve logs directory uses user home fallback`() {
        val logsDirectory =
            DesktopAppPaths.resolveLogsDirectory(
                environment = emptyMap(),
                userHome = "C:\\Users\\demo",
            )

        assertThat(logsDirectory.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Local\\Payanam\\logs")
    }

    @Test
    fun `resolve preferences directory uses local app data root`() {
        val preferencesDirectory =
            DesktopAppPaths.resolvePreferencesDirectory(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\demo\\AppData\\Local"),
                userHome = "C:\\Users\\demo",
            )

        assertThat(preferencesDirectory.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Local\\Payanam\\preferences")
    }

    @Test
    fun `resolve bootstrap directory uses local app data root`() {
        val bootstrapDirectory =
            DesktopAppPaths.resolveBootstrapDirectory(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\demo\\AppData\\Local"),
                userHome = "C:\\Users\\demo",
            )

        assertThat(bootstrapDirectory.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Local\\Payanam\\bootstrap")
    }

    @Test
    fun `resolve security and database directories use local app data root`() {
        val securityDirectory =
            DesktopAppPaths.resolveSecurityDirectory(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\demo\\AppData\\Local"),
                userHome = "C:\\Users\\demo",
            )
        val databaseDirectory =
            DesktopAppPaths.resolveDatabaseDirectory(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\demo\\AppData\\Local"),
                userHome = "C:\\Users\\demo",
            )

        assertThat(securityDirectory.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Local\\Payanam\\security")
        assertThat(databaseDirectory.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Local\\Payanam\\database")
    }

    @Test
    fun `resolve runtime directory uses local app data root`() {
        val runtimeDirectory =
            DesktopAppPaths.resolveRuntimeDirectory(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\demo\\AppData\\Local"),
                userHome = "C:\\Users\\demo",
            )

        assertThat(runtimeDirectory.toString()).isEqualTo("C:\\Users\\demo\\AppData\\Local\\Payanam\\runtime")
    }
}
