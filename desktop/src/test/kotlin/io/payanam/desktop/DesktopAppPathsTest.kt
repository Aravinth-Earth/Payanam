//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopBuildInfo
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
 * separators are host-specific, so a literal `C:\\Users\\...` assertion would only ever pass on Windows -
 * the very trap that kept these tests disabled. What is under test is which BASE was selected, and
 * comparing against the same base constructed identically expresses exactly that.
 */
class DesktopAppPathsTest {
    private val windowsOs = "Windows 11"
    private val linuxOs = "Linux"
    private val windowsHome = "C:\\Users\\demo"

    private fun expectedRootName(): String = if (DesktopBuildInfo.IS_DEBUG_BUILD) "Payanam-debug" else "Payanam"

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
    fun `linux root uses Documents directory`() {
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = emptyMap(),
                userHome = "/home/demo",
                osName = linuxOs,
            )

        assertThat(root).isEqualTo(Paths.get("/home/demo", "Documents", expectedRootName()))
    }

    @Test
    fun `linux root honours XDG_DATA_HOME as the parent when it is set`() {
        // This asserts the OPPOSITE of what it used to. `XDG_DATA_HOME` was once "no longer consulted",
        // and the Linux root was always under `Documents` - which is fine until something needs to run
        // the app against a scratch directory. The import loop launches the shipped binary with
        // `XDG_DATA_HOME` pointed at its scratch dir and describes that as isolation; with the variable
        // ignored, the launched app still resolved the operator's real home, and the startup
        // storage-root migration would have moved the real `~/.local/share/Payanam` as a side effect of
        // a fixture run. `HOME` cannot stand in for this: measured on this host, the JVM ignores `HOME`
        // and reports `user.home` as the passwd entry, so an environment-only override at the HOME level
        // does nothing. The override is therefore explicit, opt-in, and must cover both locations the
        // migration looks at.
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = mapOf("XDG_DATA_HOME" to "/xdg/data"),
                userHome = "/home/demo",
                osName = linuxOs,
            )

        assertThat(root).isEqualTo(Paths.get("/xdg/data", "Documents", expectedRootName()))
    }

    @Test
    fun `linux root falls back to the home directory when the override is unset`() {
        // The unset case must reproduce the historical location exactly: `<home>/Documents/Payanam`. It
        // deliberately does NOT fall back to `<home>/.local/share` - that is the LEGACY root's default
        // (see below), and conflating the two would move every existing install on the next run.
        val root =
            DesktopAppPaths.resolveRootDirectory(
                environment = emptyMap(),
                userHome = "/home/demo",
                osName = linuxOs,
            )

        assertThat(root).isEqualTo(Paths.get("/home/demo", "Documents", expectedRootName()))
    }

    // ── Legacy root (what the startup migration looks for) ────────────────────────────

    @Test
    fun `the legacy root follows the same override as the current root`() {
        // THE safety property. The two roots must be isolated together: if a scratch run isolates the
        // new location but not the legacy one, the startup migration still considers the operator's real
        // `~/.local/share/Payanam` and MOVES it - the harness reads as safe while the real data is not.
        val environment = mapOf("XDG_DATA_HOME" to "/xdg/data")

        assertThat(DesktopAppPaths.resolveLegacyRootDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(Paths.get("/xdg/data", expectedRootName()))
    }

    @Test
    fun `the legacy root ignores the xdg override only in the sense that it uses it as its parent`() {
        // The XDG default parent is where an existing install actually lives, so with nothing set the
        // legacy root is the historically correct `~/.local/share/Payanam` - not `Documents`.
        assertThat(DesktopAppPaths.resolveLegacyRootDirectory(emptyMap(), "/home/demo", linuxOs))
            .isEqualTo(Paths.get("/home/demo", ".local", "share", expectedRootName()))

        // And a scratch parent contains BOTH roots, which is the property that makes a run safe.
        val scratch = mapOf("XDG_DATA_HOME" to "/scratch")
        val legacy = DesktopAppPaths.resolveLegacyRootDirectory(scratch, "/home/demo", linuxOs)
        val current = DesktopAppPaths.resolveRootDirectory(scratch, "/home/demo", linuxOs)
        assertThat(legacy.startsWith("/scratch")).isTrue()
        assertThat(current.startsWith("/scratch")).isTrue()
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

        assertThat(root).isEqualTo(Paths.get("/home/demo", "Documents", expectedRootName()))
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

        assertThat(root).isEqualTo(Paths.get("/Users/demo", "Documents", expectedRootName()))
    }

    // ── Subdirectories ────────────────────────────────────────────────────────────────

    @Test
    fun `linux subdirectories hang off the linux root`() {
        val environment = emptyMap<String, String>()
        val expectedRoot = Paths.get("/home/demo", "Documents", expectedRootName())

        assertThat(DesktopAppPaths.resolveLogsDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("logs"))
        assertThat(DesktopAppPaths.resolveDatabaseDirectory(environment, "/home/demo", linuxOs))
            .isEqualTo(expectedRoot.resolve("db"))
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
            .isEqualTo(expectedRoot.resolve("db"))
        assertThat(DesktopAppPaths.resolveSecurityDirectory(environment, windowsHome, windowsOs))
            .isEqualTo(expectedRoot.resolve("security"))
    }

    // ── Real host ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the real host resolves a root that ends in the app directory name`() {
        // Guards the defaults, which every production caller relies on by passing no arguments at all.
        val root = DesktopAppPaths.resolveRootDirectory()

        assertThat(root.fileName.toString()).isEqualTo(expectedRootName())
        assertThat(root.toString()).contains("Payanam")
    }
}
