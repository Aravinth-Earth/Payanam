//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * BackupTransferContractsTest.
 */
class BackupTransferContractsTest {

    @Test
    fun `default module selection has an active selection`() {
        assertThat(DataModuleSelection().hasSelection()).isTrue()
    }

    @Test
    fun `empty module selection reports no active modules`() {
        assertThat(
            DataModuleSelection(tasks = false, timeEntries = false, notes = false).hasSelection()
        ).isFalse()
    }

    @Test
    fun `single selected module still reports active selection`() {
        assertThat(
            DataModuleSelection(tasks = false, timeEntries = true, notes = false).hasSelection()
        ).isTrue()
    }

    @Test
    fun `data module selection copy and destructuring preserve module flags`() {
        val defaults = DataModuleSelection()
        val copied = defaults.copy(timeEntries = false, notes = false)
        val (tasks, timeEntries, notes) = copied
        assertThat(defaults.tasks).isTrue()
        assertThat(defaults.timeEntries).isTrue()
        assertThat(defaults.notes).isTrue()
        assertThat(tasks).isTrue()
        assertThat(timeEntries).isFalse()
        assertThat(notes).isFalse()
    }

    @Test
    fun `backup json contract keeps schema version stable`() {
        assertThat(BackupJsonContract.SCHEMA_VERSION).isEqualTo(1)
        assertThat(BackupJsonContract.MODULES_KEY).isEqualTo("modules")
    }

    @Test
    fun `data module selection supports stable json round trip`() {
        val json = Json.encodeToString(
            DataModuleSelection(tasks = true, timeEntries = false, notes = true)
        )
        assertThat(json).isNotEmpty()

        val decoded = Json.decodeFromString<DataModuleSelection>(json)
        assertThat(decoded).isEqualTo(
            DataModuleSelection(tasks = true, timeEntries = false, notes = true)
        )
    }

    @Test
    fun `import mode exposes both supported paths`() {
        assertThat(ImportMode.entries).containsExactly(ImportMode.REPLACE, ImportMode.MERGE).inOrder()
    }

    @Test
    fun `backup payload json encodes wrapped modules with stable keys`() {
        val payload = BackupPayloadEnvelope(
            exportedAt = "2026-03-26T09:00:00",
            modules = BackupModulePayloads(
                tasks = Json.parseToJsonElement("""[{"id":"task-1"}]""").jsonArray,
                notes = Json.parseToJsonElement("""[{"id":"note-1"}]""").jsonArray
            )
        )

        val encoded = BackupPayloadJson.encode(payload)
        val root = Json.parseToJsonElement(encoded).jsonObject
        val modules = root[BackupJsonContract.MODULES_KEY]!!.jsonObject
        assertThat(root[BackupJsonContract.SCHEMA_VERSION_KEY]!!.toString()).isEqualTo("1")
        assertThat(root[BackupJsonContract.EXPORTED_AT_KEY]!!.toString()).isEqualTo("\"2026-03-26T09:00:00\"")
        assertThat(modules[BackupJsonContract.TASKS_KEY]!!.jsonArray).hasSize(1)
        assertThat(modules[BackupJsonContract.NOTES_KEY]!!.jsonArray).hasSize(1)
        assertThat(modules.containsKey(BackupJsonContract.TIME_ENTRIES_KEY)).isFalse()
    }

    @Test
    fun `backup payload models expose defaults and rarely used module getters`() {
        val modules = BackupModulePayloads(
            taskReschedules = Json.parseToJsonElement("""[{"id":"res-1"}]""").jsonArray,
            dayJournalEntries = Json.parseToJsonElement("""[{"id":"entry-1"}]""").jsonArray,
            dayJournalResponses = Json.parseToJsonElement("""[{"id":"response-1"}]""").jsonArray
        )
        val payload = BackupPayloadEnvelope(modules = modules)
        val defaultPayload = BackupPayloadEnvelope()
        assertThat(payload.schemaVersion).isEqualTo(BackupJsonContract.SCHEMA_VERSION)
        assertThat(payload.exportedAt).isNull()
        assertThat(payload.modules.taskReschedules).hasSize(1)
        assertThat(payload.modules.dayJournalEntries).hasSize(1)
        assertThat(payload.modules.dayJournalResponses).hasSize(1)
        assertThat(defaultPayload.modules).isEqualTo(BackupModulePayloads())
        assertThat(defaultPayload.modules.tasks).isNull()
    }

    @Test
    fun `backup payload json decodes wrapped envelope metadata and module arrays`() {
        val decoded = BackupPayloadJson.decode(
            """
            {
              "schemaVersion": 1,
              "exportedAt": "2026-03-26T09:00:00",
              "modules": {
                "tasks": [
                  {
                    "id": "task-1"
                  }
                ],
                "timeEntries": [
                  {
                    "id": "time-1"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        assertThat(decoded.schemaVersion).isEqualTo(BackupJsonContract.SCHEMA_VERSION)
        assertThat(decoded.exportedAt).isEqualTo("2026-03-26T09:00:00")
        assertThat(decoded.modules.tasks).hasSize(1)
        assertThat(decoded.modules.timeEntries).hasSize(1)
        assertThat(decoded.modules.notes).isNull()
    }

    @Test
    fun `backup payload json decodes legacy root module payloads without wrapper`() {
        val decoded = BackupPayloadJson.decode(
            """
            {
              "tasks": [
                {
                  "id": "task-legacy-root"
                }
              ],
              "journalNotes": [
                {
                  "id": "journal-note-1"
                }
              ]
            }
            """.trimIndent()
        )
        assertThat(decoded.schemaVersion).isEqualTo(BackupJsonContract.SCHEMA_VERSION)
        assertThat(decoded.exportedAt).isNull()
        assertThat(decoded.modules.tasks).hasSize(1)
        assertThat(decoded.modules.journalNotes).hasSize(1)
        assertThat(decoded.modules.taskOccurrences).isNull()
    }

    @Test
    fun `backup payload json keeps legacy root metadata while filtering non-module keys`() {
        val decoded = BackupPayloadJson.decode(
            """
            {
              "schemaVersion": 3,
              "exportedAt": "2026-03-26T10:30:00",
              "tasks": [
                {
                  "id": "task-legacy-root"
                }
              ]
            }
            """.trimIndent()
        )
        assertThat(decoded.schemaVersion).isEqualTo(3)
        assertThat(decoded.exportedAt).isEqualTo("2026-03-26T10:30:00")
        assertThat(decoded.modules.tasks).hasSize(1)
        assertThat(decoded.modules.timeEntries).isNull()
    }

    @Test
    fun `backup payload json falls back to legacy root when modules field is not an object`() {
        val decoded = BackupPayloadJson.decode(
            """
            {
              "modules": "invalid-wrapper",
              "timeEntries": [
                {
                  "id": "time-legacy-root"
                }
              ]
            }
            """.trimIndent()
        )
        assertThat(decoded.modules.timeEntries).hasSize(1)
        assertThat(decoded.modules.tasks).isNull()
    }

    @Test
    fun `backup payload json ignores malformed module field types`() {
        val decoded = BackupPayloadJson.decode(
            """
            {
              "schemaVersion": 1,
              "modules": {
                "tasks": {"id": "not-an-array"},
                "timeEntries": "invalid-type",
                "notes": 42,
                "journalNotes": [
                  {
                    "id": "journal-note-1"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        assertThat(decoded.modules.tasks).isNull()
        assertThat(decoded.modules.timeEntries).isNull()
        assertThat(decoded.modules.notes).isNull()
        assertThat(decoded.modules.journalNotes).hasSize(1)
    }

    @Test
    fun `backup module payloads expose all module arrays through copy and getters`() {
        val modules =
            BackupModulePayloads(
                tasks = Json.parseToJsonElement("""[{"id":"task-1"}]""").jsonArray,
                taskOccurrences = Json.parseToJsonElement("""[{"id":"occ-1"}]""").jsonArray,
                taskReschedules = Json.parseToJsonElement("""[{"id":"res-1"}]""").jsonArray,
                timeEntries = Json.parseToJsonElement("""[{"id":"time-1"}]""").jsonArray,
                notes = Json.parseToJsonElement("""[{"id":"note-1"}]""").jsonArray,
                dayJournalEntries = Json.parseToJsonElement("""[{"id":"entry-1"}]""").jsonArray,
                dayJournalResponses = Json.parseToJsonElement("""[{"id":"response-1"}]""").jsonArray,
                journalNotes = Json.parseToJsonElement("""[{"id":"journal-note-1"}]""").jsonArray,
            )
        val copied = modules.copy()
        assertThat(copied.tasks).hasSize(1)
        assertThat(copied.taskOccurrences).hasSize(1)
        assertThat(copied.taskReschedules).hasSize(1)
        assertThat(copied.timeEntries).hasSize(1)
        assertThat(copied.notes).hasSize(1)
        assertThat(copied.dayJournalEntries).hasSize(1)
        assertThat(copied.dayJournalResponses).hasSize(1)
        assertThat(copied.journalNotes).hasSize(1)
    }

    @Test
    fun `legacy module root strips reserved metadata keys only`() {
        val legacyRoot =
            Json.parseToJsonElement(
                """
                {
                  "schemaVersion": 7,
                  "exportedAt": "2026-03-27T15:00:00",
                  "modules": {"tasks": []},
                  "notes": [{"id": "note-1"}],
                  "timeEntries": [{"id": "time-1"}]
                }
                """.trimIndent(),
            ).jsonObject

        val filtered = BackupJsonContract.legacyModulesRoot(legacyRoot)
        assertThat(filtered.keys).containsExactly("notes", "timeEntries")
    }
}
