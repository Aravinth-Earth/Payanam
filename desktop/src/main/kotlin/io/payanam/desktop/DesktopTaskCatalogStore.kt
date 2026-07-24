//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.tasks.DesktopTaskBoardContracts
import io.payanam.shared.tasks.DesktopTaskCatalogSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class DesktopTaskCatalogState(
    val catalog: DesktopTaskCatalogSnapshot,
    val errorMessage: String? = null,
)

internal class DesktopTaskCatalogStore(
    databaseDirectory: Path = DesktopAppPaths.resolveDatabaseDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(databaseDirectory = databaseDirectory),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    fun loadState(): DesktopTaskCatalogState {
        val storedPayload = persistenceDatabase.readEntry(STATE_ENTRY_KEY)
        if (storedPayload.isNullOrBlank()) {
            val seededCatalog = DesktopTaskBoardContracts.seededCatalog()
            saveCatalog(seededCatalog)
            logEvent(
                "DesktopTaskCatalogStore.loadState",
                "Seeded desktop task catalog",
                mapOf("recordCount" to seededCatalog.tasks.size),
            )
            return DesktopTaskCatalogState(catalog = seededCatalog)
        }

        return try {
            val catalog =
                json.decodeFromString<DesktopTaskCatalogSnapshot>(storedPayload)
            logEvent(
                "DesktopTaskCatalogStore.loadState",
                "Loaded desktop task catalog",
                mapOf("recordCount" to catalog.tasks.size),
            )
            DesktopTaskCatalogState(catalog = catalog)
        } catch (error: Exception) {
            logEvent(
                "DesktopTaskCatalogStore.loadState",
                "Failed to decode desktop task catalog",
                mapOf("error" to error.message),
            )
            DesktopTaskCatalogState(
                catalog = DesktopTaskCatalogSnapshot(),
                errorMessage = error.message ?: "Desktop task catalog could not be read.",
            )
        }
    }

    fun getCatalogFilePath(): Path = persistenceDatabase.getDatabaseFilePath()

    private fun saveCatalog(catalog: DesktopTaskCatalogSnapshot) {
        persistenceDatabase.writeEntry(STATE_ENTRY_KEY, json.encodeToString(catalog))
    }

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/task_catalog"
    }
}
