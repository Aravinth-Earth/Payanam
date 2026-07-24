//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.settings.DesktopTopLevelRoute
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.util.Properties

private const val DESKTOP_BOOTSTRAP_SCHEMA_VERSION = 1

data class DesktopBootstrapSnapshot(
    val schemaVersion: Int = DESKTOP_BOOTSTRAP_SCHEMA_VERSION,
    val databaseLifecycleReady: Boolean = false,
    val lastStartupCompletedAtEpochMillis: Long? = null,
    val lastLaunchRouteStorageKey: String? = null,
)

internal class DesktopBootstrapStore(
    bootstrapDirectory: Path = DesktopAppPaths.resolveBootstrapDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(
            databaseDirectory = bootstrapDirectory,
            bootstrapDirectory = bootstrapDirectory,
        ),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    fun ensureSnapshot(): DesktopBootstrapSnapshot {
        if (persistenceDatabase.hasEntry(STATE_ENTRY_KEY)) {
            return loadSnapshot()
        }
        val defaultSnapshot = DesktopBootstrapSnapshot()
        saveSnapshot(defaultSnapshot)
        logEvent(
            "DesktopBootstrapStore.ensureSnapshot",
            "Created desktop bootstrap snapshot",
            emptyMap(),
        )
        return defaultSnapshot
    }

    fun loadSnapshot(): DesktopBootstrapSnapshot {
        val storedPayload = persistenceDatabase.readEntry(STATE_ENTRY_KEY)
        if (storedPayload.isNullOrBlank()) {
            logEvent(
                "DesktopBootstrapStore.loadSnapshot",
                "Using default desktop bootstrap snapshot",
                emptyMap(),
            )
            return DesktopBootstrapSnapshot()
        }

        val properties = Properties()
        StringReader(storedPayload).use(properties::load)
        val snapshot =
            DesktopBootstrapSnapshot(
                schemaVersion = properties.getProperty(KEY_SCHEMA_VERSION)?.toIntOrNull() ?: DESKTOP_BOOTSTRAP_SCHEMA_VERSION,
                databaseLifecycleReady = properties.getProperty(KEY_DATABASE_LIFECYCLE_READY)?.toBooleanStrictOrNull() ?: false,
                lastStartupCompletedAtEpochMillis = properties.getProperty(KEY_LAST_STARTUP_COMPLETED_AT)?.toLongOrNull(),
                lastLaunchRouteStorageKey = properties.getProperty(KEY_LAST_LAUNCH_ROUTE)?.takeIf { it.isNotBlank() },
            )
        logEvent(
            "DesktopBootstrapStore.loadSnapshot",
            "Loaded desktop bootstrap snapshot",
            mapOf(
                "databaseLifecycleReady" to snapshot.databaseLifecycleReady,
                "hasLastStartupCompletedAt" to (snapshot.lastStartupCompletedAtEpochMillis != null),
                "lastLaunchRoute" to snapshot.lastLaunchRouteStorageKey,
            ),
        )
        return snapshot
    }

    fun recordStartupCompleted(route: DesktopTopLevelRoute): DesktopBootstrapSnapshot {
        val updatedSnapshot =
            loadSnapshot().copy(
                lastStartupCompletedAtEpochMillis = clock(),
                lastLaunchRouteStorageKey = route.storageKey,
            )
        saveSnapshot(updatedSnapshot)
        logEvent(
            "DesktopBootstrapStore.recordStartupCompleted",
            "Recorded desktop startup completion",
            mapOf(
                "launchRoute" to route.storageKey,
            ),
        )
        return updatedSnapshot
    }

    fun updateDatabaseLifecycleReady(isReady: Boolean): DesktopBootstrapSnapshot {
        val updatedSnapshot = loadSnapshot().copy(databaseLifecycleReady = isReady)
        saveSnapshot(updatedSnapshot)
        logEvent(
            "DesktopBootstrapStore.updateDatabaseLifecycleReady",
            "Updated desktop database lifecycle readiness",
            mapOf(
                "databaseLifecycleReady" to isReady,
            ),
        )
        return updatedSnapshot
    }

    fun getBootstrapFilePath(): Path = persistenceDatabase.getDatabaseFilePath()

    private fun saveSnapshot(snapshot: DesktopBootstrapSnapshot) {
        val properties =
            Properties().apply {
                setProperty(KEY_SCHEMA_VERSION, snapshot.schemaVersion.toString())
                setProperty(KEY_DATABASE_LIFECYCLE_READY, snapshot.databaseLifecycleReady.toString())
                snapshot.lastStartupCompletedAtEpochMillis?.let {
                    setProperty(KEY_LAST_STARTUP_COMPLETED_AT, it.toString())
                }
                snapshot.lastLaunchRouteStorageKey?.let {
                    setProperty(KEY_LAST_LAUNCH_ROUTE, it)
                }
            }
        val payload =
            StringWriter().use { writer ->
                properties.store(writer, "Payanam Desktop Bootstrap")
                writer.toString()
            }
        persistenceDatabase.writeEntry(STATE_ENTRY_KEY, payload)
    }

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/bootstrap"
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_DATABASE_LIFECYCLE_READY = "databaseLifecycleReady"
        private const val KEY_LAST_STARTUP_COMPLETED_AT = "lastStartupCompletedAtEpochMillis"
        private const val KEY_LAST_LAUNCH_ROUTE = "lastLaunchRoute"
    }
}
