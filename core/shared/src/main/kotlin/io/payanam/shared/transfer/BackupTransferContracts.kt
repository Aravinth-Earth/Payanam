//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
object BackupJsonContract {
    const val SCHEMA_VERSION = 1
    const val SCHEMA_VERSION_KEY = "schemaVersion"
    const val EXPORTED_AT_KEY = "exportedAt"
    const val MODULES_KEY = "modules"
    const val TASKS_KEY = "tasks"
    const val TASK_OCCURRENCES_KEY = "taskOccurrences"
    const val TASK_RESCHEDULES_KEY = "taskReschedules"
    const val TIME_ENTRIES_KEY = "timeEntries"
    const val NOTES_KEY = "notes"
    const val DAY_JOURNAL_ENTRIES_KEY = "dayJournalEntries"
    const val DAY_JOURNAL_RESPONSES_KEY = "dayJournalResponses"
    const val JOURNAL_NOTES_KEY = "journalNotes"

    private val reservedRootKeys = setOf(
        SCHEMA_VERSION_KEY,
        EXPORTED_AT_KEY,
        MODULES_KEY
    )

    internal fun legacyModulesRoot(root: JsonObject): JsonObject =
        JsonObject(root.filterKeys { it !in reservedRootKeys })
}
/**
 * Defines the contract for import mode.
 */
enum class ImportMode  {
    REPLACE,
    MERGE,
}

@Serializable
/**
 * DataModuleSelection.

 */
data class DataModuleSelection(
    val tasks: Boolean = true,
    val timeEntries: Boolean = true,
    val notes: Boolean = true
) {
    /**
     * Returns true when the has selection.
     */
    fun hasSelection(): Boolean = tasks || timeEntries || notes
}

@Serializable
/**
 * BackupModulePayloads.

 */
data class BackupModulePayloads(
    @SerialName(BackupJsonContract.TASKS_KEY)
    val tasks: JsonArray? = null,
    @SerialName(BackupJsonContract.TASK_OCCURRENCES_KEY)
    val taskOccurrences: JsonArray? = null,
    @SerialName(BackupJsonContract.TASK_RESCHEDULES_KEY)
    val taskReschedules: JsonArray? = null,
    @SerialName(BackupJsonContract.TIME_ENTRIES_KEY)
    val timeEntries: JsonArray? = null,
    @SerialName(BackupJsonContract.NOTES_KEY)
    val notes: JsonArray? = null,
    @SerialName(BackupJsonContract.DAY_JOURNAL_ENTRIES_KEY)
    val dayJournalEntries: JsonArray? = null,
    @SerialName(BackupJsonContract.DAY_JOURNAL_RESPONSES_KEY)
    val dayJournalResponses: JsonArray? = null,
    @SerialName(BackupJsonContract.JOURNAL_NOTES_KEY)
    val journalNotes: JsonArray? = null
)

@Serializable
/**
 * BackupPayloadEnvelope.

 */
data class BackupPayloadEnvelope(
    @SerialName(BackupJsonContract.SCHEMA_VERSION_KEY)
    val schemaVersion: Int = BackupJsonContract.SCHEMA_VERSION,
    @SerialName(BackupJsonContract.EXPORTED_AT_KEY)
    val exportedAt: String? = null,
    @SerialName(BackupJsonContract.MODULES_KEY)
    val modules: BackupModulePayloads = BackupModulePayloads()
)
object BackupPayloadJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    /**
     * Performs the encode.
     */
    fun encode(envelope: BackupPayloadEnvelope): String = json.encodeToString(envelope)
    /**
     * Performs the decode.
     */
    fun decode(jsonText: String): BackupPayloadEnvelope {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val modulesRoot = (root[BackupJsonContract.MODULES_KEY] as? JsonObject)
            ?: BackupJsonContract.legacyModulesRoot(root)
        val modules = BackupModulePayloads(
            tasks = modulesRoot.arrayOrNull(BackupJsonContract.TASKS_KEY),
            taskOccurrences = modulesRoot.arrayOrNull(BackupJsonContract.TASK_OCCURRENCES_KEY),
            taskReschedules = modulesRoot.arrayOrNull(BackupJsonContract.TASK_RESCHEDULES_KEY),
            timeEntries = modulesRoot.arrayOrNull(BackupJsonContract.TIME_ENTRIES_KEY),
            notes = modulesRoot.arrayOrNull(BackupJsonContract.NOTES_KEY),
            dayJournalEntries = modulesRoot.arrayOrNull(BackupJsonContract.DAY_JOURNAL_ENTRIES_KEY),
            dayJournalResponses = modulesRoot.arrayOrNull(BackupJsonContract.DAY_JOURNAL_RESPONSES_KEY),
            journalNotes = modulesRoot.arrayOrNull(BackupJsonContract.JOURNAL_NOTES_KEY)
        )

        return BackupPayloadEnvelope(
            schemaVersion = root[BackupJsonContract.SCHEMA_VERSION_KEY]
                ?.jsonPrimitive
                ?.intOrNull
                ?: BackupJsonContract.SCHEMA_VERSION,
            exportedAt = root[BackupJsonContract.EXPORTED_AT_KEY]
                ?.jsonPrimitive
                ?.contentOrNull,
            modules = modules
        )
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
}
