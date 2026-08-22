//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Path
import java.nio.file.Paths
object DesktopAppPaths {
    private const val APP_DIRECTORY_NAME = "Payanam"
    /**
     * App data root: %LOCALAPPDATA%\Payanam, falling back to %APPDATA%, then
     * the default Windows user path.
     */
    fun resolveRootDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path {
        val localAppData = environment["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
        if (localAppData != null) {
            return Paths.get(localAppData, APP_DIRECTORY_NAME)
        }

        val roamingAppData = environment["APPDATA"]?.takeIf { it.isNotBlank() }
        if (roamingAppData != null) {
            return Paths.get(roamingAppData, APP_DIRECTORY_NAME)
        }

        return Paths.get(userHome, "AppData", "Local", APP_DIRECTORY_NAME)
    }
    /**
     * <root>/logs — session and crash logs.
     */
    fun resolveLogsDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("logs")
    /**
     * <root>/preferences — legacy settings file location.
     */
    fun resolvePreferencesDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("preferences")
    /**
     * <root>/bootstrap — legacy bootstrap state location.
     */
    fun resolveBootstrapDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("bootstrap")
    /**
     * <root>/security — legacy security store location.
     */
    fun resolveSecurityDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("security")
    /**
     * <root>/database — SQLite state store location.
     */
    fun resolveDatabaseDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("database")
    /**
     * <root>/runtime — transient runtime artifacts (locks, sockets).
     */
    fun resolveRuntimeDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("runtime")
    /**
     * <root>/exports — user-initiated data exports.
     */
    fun resolveExportDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("exports")
}
