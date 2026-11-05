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
 * | Linux    | `<home>/Documents/Payanam` (debug: `<home>/Documents/Payanam-debug`) |
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
     * Linux root: `<home>/Documents/Payanam`, or `<home>/Documents/Payanam-debug` on a debug build.
     *
     * `XDG_DATA_HOME`, when set, replaces `<home>` as the parent. This is what makes the layout
     * overridable: a harness (the import loop) launches the shipped binary with `XDG_DATA_HOME` pointed at
     * a scratch directory and reasonably expects every path the app resolves to land inside it. Before
     * this, the Linux branch ignored the environment entirely — the parameter was threaded through for
     * the Windows layouts only — so an isolated run still read and wrote the operator's real `Documents`,
     * and a startup migration would have MOVED the real data. An override that covers one of the two
     * locations the migration looks at is worse than none, because it reads as safety.
     *
     * Note the default parent differs from [resolveLegacyRootDirectory]'s: this root has always been
     * `<home>/Documents`, so an unset variable must keep resolving exactly that, NOT `<home>/.local/share`.
     */
    private fun linuxRootDirectory(
        environment: Map<String, String>,
        userHome: String,
    ): Path {
        val rootName = if (DesktopBuildInfo.IS_DEBUG_BUILD) "$APP_DIRECTORY_NAME-debug" else APP_DIRECTORY_NAME
        val parent = dataHomeOverride(environment) ?: Paths.get(userHome)
        return parent.resolve("Documents").resolve(rootName)
    }

    /** The `XDG_DATA_HOME` override, or null when it is unset or blank. */
    private fun dataHomeOverride(environment: Map<String, String>): Path? =
        environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }

    /**
     * The root an install used BEFORE this layout, so the startup migration can find it.
     *
     * `<xdg>/Payanam` (with the same debug suffix), where `<xdg>` is the `XDG_DATA_HOME` override or its
     * specified default `<home>/.local/share` — i.e. exactly where an XDG-respecting install put itself,
     * and exactly where `XDG_DATA_HOME` points during a run that must not touch the operator's real data.
     * It is deliberately resolved by the SAME override as [resolveRootDirectory] rather than from an
     * ambient `user.home` read: an override that isolates the new location but not the legacy one leaves
     * the migration writing to the real home directory, which is the failure this method exists to stop.
     */
    fun resolveLegacyRootDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path {
        val rootName = if (DesktopBuildInfo.IS_DEBUG_BUILD) "$APP_DIRECTORY_NAME-debug" else APP_DIRECTORY_NAME
        return when (DesktopPlatform.of(osName)) {
            DesktopPlatform.WINDOWS -> windowsRootDirectory(environment, userHome)
            DesktopPlatform.LINUX -> (dataHomeOverride(environment) ?: Paths.get(userHome, ".local", "share")).resolve(rootName)
        }
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
    ): Path = resolveRootDirectory(environment, userHome, osName).resolve("db")

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
