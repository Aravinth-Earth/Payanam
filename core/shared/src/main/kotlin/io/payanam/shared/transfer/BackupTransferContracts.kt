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

/**
 * BackupJsonContract.
 */
object BackupJsonContract {
    /** S c h e m a  v e r s i o n. */
    const val SCHEMA_VERSION = 1
    /** S c h e m a  v e r s i o n  k e y. */
    const val SCHEMA_VERSION_KEY = "schemaVersion"
    /** E x p o r t e d  a t  k e y. */
    const val EXPORTED_AT_KEY = "exportedAt"
    /** M o d u l e s  k e y. */
    const val MODULES_KEY = "modules"
    /** T a s k s  k e y. */
    const val TASKS_KEY = "tasks"
    /** T a s k  o c c u r r e n c e s  k e y. */
    const val TASK_OCCURRENCES_KEY = "taskOccurrences"
    /** T a s k  r e s c h e d u l e s  k e y. */
    const val TASK_RESCHEDULES_KEY = "taskReschedules"
    /** T i m e  e n t r i e s  k e y. */
    const val TIME_ENTRIES_KEY = "timeEntries"
    /** N o t e s  k e y. */
    const val NOTES_KEY = "notes"
    /** D a y  j o u r n a l  e n t r i e s  k e y. */
    const val DAY_JOURNAL_ENTRIES_KEY = "dayJournalEntries"
    /** D a y  j o u r n a l  r e s p o n s e s  k e y. */
    const val DAY_JOURNAL_RESPONSES_KEY = "dayJournalResponses"
    /** J o u r n a l  n o t e s  k e y. */
    const val JOURNAL_NOTES_KEY = "journalNotes"

    private val reservedRootKeys = setOf(
        /** S c h e m a  v e r s i o n  k e y. */
        SCHEMA_VERSION_KEY,
        /** E x p o r t e d  a t  k e y. */
        EXPORTED_AT_KEY,
        /** M o d u l e s  k e y. */
        MODULES_KEY
    )

    internal fun legacyModulesRoot(root: JsonObject): JsonObject =
        /** Json object. */
        JsonObject(root.filterKeys { it !in reservedRootKeys })
}

/**
 * ImportMode.
 */
/**
 * ImportMode.
 */
enum class ImportMode  {
    /** Replace. */
    REPLACE,
    /** Merge. */
    MERGE,
}

@Serializable
/**
 * DataModuleSelection.

 */
data class DataModuleSelection(
    /** Tasks. */
    val tasks: Boolean = true,
    /** Time entries. */
    val timeEntries: Boolean = true,
    /** Notes. */
    val notes: Boolean = true
) {
    /**
     * Has selection.
     */
    fun hasSelection(): Boolean = tasks || timeEntries || notes
}

@Serializable
/**
 * BackupModulePayloads.

 */
data class BackupModulePayloads(
    @SerialName(BackupJsonContract.TASKS_KEY)
    /** Tasks. */
    val tasks: JsonArray? = null,
    @SerialName(BackupJsonContract.TASK_OCCURRENCES_KEY)
    /** Task occurrences. */
    val taskOccurrences: JsonArray? = null,
    @SerialName(BackupJsonContract.TASK_RESCHEDULES_KEY)
    /** Task reschedules. */
    val taskReschedules: JsonArray? = null,
    @SerialName(BackupJsonContract.TIME_ENTRIES_KEY)
    /** Time entries. */
    val timeEntries: JsonArray? = null,
    @SerialName(BackupJsonContract.NOTES_KEY)
    /** Notes. */
    val notes: JsonArray? = null,
    @SerialName(BackupJsonContract.DAY_JOURNAL_ENTRIES_KEY)
    /** Day journal entries. */
    val dayJournalEntries: JsonArray? = null,
    @SerialName(BackupJsonContract.DAY_JOURNAL_RESPONSES_KEY)
    /** Day journal responses. */
    val dayJournalResponses: JsonArray? = null,
    @SerialName(BackupJsonContract.JOURNAL_NOTES_KEY)
    /** Journal notes. */
    val journalNotes: JsonArray? = null
)

@Serializable
/**
 * BackupPayloadEnvelope.

 */
data class BackupPayloadEnvelope(
    @SerialName(BackupJsonContract.SCHEMA_VERSION_KEY)
    /** Schema version. */
    val schemaVersion: Int = BackupJsonContract.SCHEMA_VERSION,
    @SerialName(BackupJsonContract.EXPORTED_AT_KEY)
    /** Exported at. */
    val exportedAt: String? = null,
    @SerialName(BackupJsonContract.MODULES_KEY)
    /** Modules. */
    val modules: BackupModulePayloads = BackupModulePayloads()
)

/**
 * BackupPayloadJson.
 */
object BackupPayloadJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Encode.
     */
    fun encode(envelope: BackupPayloadEnvelope): String = json.encodeToString(envelope)

    /**
     * Decode.
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
