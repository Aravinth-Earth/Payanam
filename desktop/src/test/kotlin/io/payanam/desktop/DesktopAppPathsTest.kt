//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Paths

/**
 * Tests for per-platform app data paths.
 *
 * These used to be `@Ignore`d as "Windows-only - runs in Desktop CI", which is why nothing caught the
 * Linux app writing into `~/AppData/Local/Payanam`: the resolver had no Linux branch at all, and the only
 * tests that could have shown it did not run. The platform is now an injected parameter, so both layouts
 * are asserted on any host.
 *
 * Expectations are built with [Paths.get] rather than compared to a literal string. Java's path
 * separators are host-specific, so a literal `C:\Users\...` assertion would only ever pass on Windows -
 * the very trap that kept these tests disabled. What is under test is which BASE was selected, and
 * comparing against the same base constructed identically expresses exactly that.
 */
class DesktopAppPathsTest {
    private val windowsOs = "Windows 11"
    private val linuxOs = "Linux"
    private val windowsHome = "C:\\Users\\demo"

    // ── Windows layout ────────────────────────────────────────────────────────────────

    @Test
    fun `windows root prefers LOCALAPPDATA`() {
        val localAppData = "C:\\Users\\demo\\AppData\\Local"
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = mapOf("LOCALAPPDATA" to localAppData),
                userHome = windowsHome,
                osName = windowsOs,
            )

        assertThat(root).isEqualTo(Paths.get(localAppData, "Payanam"))
    }

    @Test
    fun `windows root falls back to APPDATA when local is missing`() {
        val roamingAppData = "C:\\Users\\demo\\AppData\\Roaming"
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = mapOf("APPDATA" to roamingAppData),
                userHome = windowsHome,
                osName = windowsOs,
            )

        assertThat(root).isEqualTo(Paths.get(roamingAppData, "Payanam"))
    }

    @Test
    fun `windows root falls back to the user home when both variables are missing`() {
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = emptyMap(),
                userHome = windowsHome,
                osName = windowsOs,
            )

        assertThat(root).isEqualTo(Paths.get(windowsHome, "AppData", "Local", "Payanam"))
    }

    // ── Linux layout ──────────────────────────────────────────────────────────────────

    @Test
    fun `linux root uses XDG_DATA_HOME when set`() {
        val xdgDataHome = "/home/demo/.local/share"
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = mapOf("XDG_DATA_HOME" to xdgDataHome),
                userHome = "/home/demo",
                osName = linuxOs,
            )

        assertThat(root).isEqualTo(Paths.get(xdgDataHome, "Payanam"))
    }

    @Test
    fun `linux root defaults to local share when XDG_DATA_HOME is unset`() {
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = emptyMap(),
                userHome = "/home/demo",
                osName = linuxOs,
            )

        assertThat(root).isEqualTo(Paths.get("/home/demo/.local", "share", "Payanam"))
    }

    @Test
    fun `linux root ignores a relative XDG_DATA_HOME`() {
        // The XDG basedir spec requires an absolute path. Accepting a relative one would resolve
        // somewhere under the process working directory, which is not a home for a database.
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = mapOf("XDG_DATA_HOME" to "relative/path"),
                userHome = "/home/demo",
                osName = linuxOs,
            )

        assertThat(root).isEqualTo(Paths.get("/home/demo/.local", "share", "Payanam"))
    }

    @Test
    fun `linux root ignores windows variables entirely`() {
        // THE regression guard. Before this, the Windows branch ran unconditionally and these variables
        // decided the root on every platform, so Linux wrote its database, logs and single-instance lock
        // into a literal ~/AppData/Local/Payanam. A Linux build must never resolve a Windows path, even
        // when Windows-shaped variables are present in the environment.
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment =
                    mapOf(
                        "LOCALAPPDATA" to windowsHome,
                        "APPDATA" to "$windowsHome\\AppData\\Roaming",
                    ),
                userHome = "/home/demo",
                osName = linuxOs,
            )

        assertThat(root).isEqualTo(Paths.get("/home/demo/.local", "share", "Payanam"))
        assertThat(root.toString()).doesNotContain("AppData")
    }

    @Test
    fun `an unrecognised os name uses the linux layout`() {
        // Pinned deliberately: macOS is not a build target, and this asserts the documented decision
        // rather than leaving an ambient fallback. Adding macOS means adding a real rule for
        // ~/Library/Application Support and updating this test.
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = emptyMap(),
                userHome = "/Users/demo",
                osName = "Mac OS X",
            )

        assertThat(root).isEqualTo(Paths.get("/Users/demo/.local", "share", "Payanam"))
    }

    // ── Subdirectories ────────────────────────────────────────────────────────────────

    @Test
    fun `linux subdirectories hang off the linux root`() {
        val environment = mapOf("XDG_DATA_HOME" to "/xdg/data")
        val expectedRoot = Paths.get("/xdg/data", "Payanam")

        assertThat(DesktopAppPaths.resolveLogsDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("logs"))
        assertThat(DesktopAppPaths.resolveDatabaseDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("database"))
        assertThat(DesktopAppPaths.resolveRuntimeDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("runtime"))
        assertThat(DesktopAppPaths.resolvePreferencesDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("preferences"))
        assertThat(DesktopAppPaths.resolveBootstrapDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("bootstrap"))
        assertThat(DesktopAppPaths.resolveSecurityDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("security"))
        assertThat(DesktopAppPaths.resolveExportDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("exports"))
    }

    @Test
    fun `windows subdirectories hang off the windows root`() {
        val localAppData = "C:\\Users\\demo\\AppData\\Local"
        val environment = mapOf("LOCALAPPDATA" to localAppData)
        val expectedRoot = Paths.get(localAppData, "Payanam")

        assertThat(DesktopAppPaths.resolveLogsDirectory(environment, windowsHome, windowsOs))
            .isEqualTo(expectedRoot.resolve("logs"))
        assertThat(DesktopAppPaths.resolveDatabaseDirectory(environment, windowsHome, windowsOs))
            .isEqualTo(expectedRoot.resolve("database"))
        assertThat(DesktopAppPaths.resolveSecurityDirectory(environment, windowsHome, windowsOs))
            .isEqualTo(expectedRoot.resolve("security"))
    }

    // ── Real host ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the real host resolves a root that ends in the app directory name`() {
        // Guards the defaults, which every production caller relies on by passing no arguments at all.
        val root = DesktopAppPaths.resolveRootDirectory()

        assertThat(root.fileName.toString()).isEqualTo("Payanam")
        assertThat(root.toString()).contains("Payanam")
    }
}
