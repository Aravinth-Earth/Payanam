//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * DesktopAppPaths.
 */
object DesktopAppPaths {
    private const val APP_DIRECTORY_NAME = "Payanam"

    /**
     * Resolve root directory.
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
     * Resolve logs directory.
     */
    fun resolveLogsDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("logs")

    /**
     * Resolve preferences directory.
     */
    fun resolvePreferencesDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("preferences")

    /**
     * Resolve bootstrap directory.
     */
    fun resolveBootstrapDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("bootstrap")

    /**
     * Resolve security directory.
     */
    fun resolveSecurityDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("security")

    /**
     * Resolve database directory.
     */
    fun resolveDatabaseDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("database")

    /**
     * Resolve runtime directory.
     */
    fun resolveRuntimeDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("runtime")

    /**
     * Resolve export directory.
     */
    fun resolveExportDirectory(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path = resolveRootDirectory(environment = environment, userHome = userHome).resolve("exports")
}
