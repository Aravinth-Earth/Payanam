//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.database.PayanamDatabaseSchema
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop platforms that have a data-layout rule.
 *
 * Only Windows and Linux are build targets. macOS is deliberately absent: it would need
 * `~/Library/Application Support/Payanam`, and [DesktopPlatform.of] must not silently hand it the Linux
 * layout if it is ever added. Unrecognised names map to [LINUX] only because Linux is the POSIX-ish
 * default among the platforms actually shipped; adding macOS means adding a real rule here.
 */
internal enum class DesktopPlatform {
    WINDOWS,
    LINUX,
    ;

    companion object {
        /** Maps a `java.os.name` value to a layout rule. */
        fun of(osName: String): DesktopPlatform = if (osName.startsWith(prefix = "Windows", ignoreCase = true)) WINDOWS else LINUX
    }
}

/**
 * App data paths, resolved per platform.
 *
 * Each platform's layout is defined exactly once, and one platform never falls through to another's:
 *
 * | Platform | Root |
 * |----------|------|
 * | Windows  | `%LOCALAPPDATA%\Payanam` → `%APPDATA%\Payanam` → `<home>\AppData\Local\Payanam` |
 * | Linux    | `$XDG_DATA_HOME/Payanam` → `<home>/.local/share/Payanam` |
 *
 * [osName] is an explicit parameter rather than an ambient `System.getProperty` read for two reasons:
 * both layouts stay unit-testable on either host, and a Linux build cannot regress into a Windows path.
 * That regression is what happened before this: the resolver knew only the Windows variables, so on
 * Linux it fell through to a literal `~/AppData/Local/Payanam` and put the database, the logs and the
 * single-instance lock into a Windows-shaped directory that no XDG-aware tool, backup or user would
 * look in.
 */
object DesktopAppPaths {
    /**
     * The database file's name inside `resolveDatabaseDirectory()`.
     *
     * The same name Android uses, because the two platforms read the SAME file: export from one is import
     * into the other, so a per-platform name would be a second convention to reconcile for no gain.
     * The value itself now lives in [PayanamDatabaseSchema.DATABASE_NAME] — this constant previously
     * spelled the same literal out a second time, which is precisely the drift the shared schema
     * object exists to remove. It is kept as a name because call sites in this module read better
     * against the desktop layout API they are already using.
     */
    const val DATABASE_FILE_NAME: String = PayanamDatabaseSchema.DATABASE_NAME

    private const val APP_DIRECTORY_NAME = "Payanam"
    private const val WINDOWS_FALLBACK_APPDATA = "AppData"
    private const val WINDOWS_FALLBACK_LOCAL = "Local"
    private const val XDG_FALLBACK_LOCAL = ".local"
    private const val XDG_FALLBACK_SHARE = "share"

    /**
     * App data root for the host platform (by default, the real host).
     *
     * @param environment variable lookup, injectable for tests
     * @param userHome the user's home directory, injectable for tests
     * @param osName the `java.os.name` value that selects the layout, injectable for tests
     */
    fun resolveRootDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path =
        when (DesktopPlatform.of(osName)) {
            DesktopPlatform.WINDOWS -> windowsRootDirectory(environment, userHome)
            DesktopPlatform.LINUX -> linuxRootDirectory(environment, userHome)
        }

    /**
     * Windows root: `%LOCALAPPDATA%\Payanam`, then `%APPDATA%\Payanam`, then `<home>\AppData\Local\Payanam`.
     */
    private fun windowsRootDirectory(
        environment: Map<String, String>,
        userHome: String,
    ): Path {
        val localAppData = environment["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
        if (localAppData != null) {
            return Paths.get(localAppData, APP_DIRECTORY_NAME)
        }

        val roamingAppData = environment["APPDATA"]?.takeIf { it.isNotBlank() }
        if (roamingAppData != null) {
            return Paths.get(roamingAppData, APP_DIRECTORY_NAME)
        }

        return Paths.get(userHome, WINDOWS_FALLBACK_APPDATA, WINDOWS_FALLBACK_LOCAL, APP_DIRECTORY_NAME)
    }

    /**
     * Linux root: `$XDG_DATA_HOME/Payanam`, then `~/.local/share/Payanam`.
     *
     * The XDG basedir spec requires `XDG_DATA_HOME` to be an **absolute** path, so a relative or blank
     * value is ignored and the default used rather than silently resolving relative to the process
     * working directory. Windows variables are never consulted on Linux.
     */
    private fun linuxRootDirectory(
        environment: Map<String, String>,
        userHome: String,
    ): Path {
        val xdgDataHome = environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() && it.startsWith("/") }
        if (xdgDataHome != null) {
            return Paths.get(xdgDataHome, APP_DIRECTORY_NAME)
        }

        return Paths.get(userHome, XDG_FALLBACK_LOCAL, XDG_FALLBACK_SHARE, APP_DIRECTORY_NAME)
    }

    /**
     * `<root>/logs` — session and crash logs.
     */
    fun resolveLogsDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("logs")

    /**
     * `<root>/preferences` — settings and board state.
     */
    fun resolvePreferencesDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("preferences")

    /**
     * `<root>/bootstrap` — bootstrap state.
     */
    fun resolveBootstrapDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("bootstrap")

    /**
     * `<root>/security` — security store.
     */
    fun resolveSecurityDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("security")

    /**
     * `<root>/database` — SQLite state store.
     */
    fun resolveDatabaseDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("database")

    /**
     * `<root>/runtime` — transient runtime artifacts (locks, sockets).
     */
    fun resolveRuntimeDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("runtime")

    /**
     * `<root>/exports` — user-initiated data exports.
     */
    fun resolveExportDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("exports")
}
