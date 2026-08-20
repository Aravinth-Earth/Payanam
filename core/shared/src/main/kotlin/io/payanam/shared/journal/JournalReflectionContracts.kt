//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.journal

import java.time.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
/**
 * JournalPromptDefinition.

 */
data class JournalPromptDefinition(
    /** Key. */
    val key: String,
    /** Prompt. */
    val prompt: String,
)

@Serializable
/**
 * JournalDayRecord.

 */
data class JournalDayRecord(
    /** Date iso. */
    val dateIso: String,
    /** Overall responses. */
    val overallResponses: Map<String, String> = emptyMap(),
    /** Dimension responses. */
    val dimensionResponses: Map<String, Map<String, String>> = emptyMap(),
    /** Created at iso. */
    val createdAtIso: String,
    /** Updated at iso. */
    val updatedAtIso: String,
)

@Serializable
/**
 * JournalSnapshot.

 */
data class JournalSnapshot(
    /** Schema version. */
    val schemaVersion: Int = JournalReflectionContracts.SCHEMA_VERSION,
    /** Days. */
    val days: List<JournalDayRecord> = emptyList(),
)

/**
 * JournalReflectionContracts.
 */
object JournalReflectionContracts {
    /** S c h e m a  v e r s i o n. */
    const val SCHEMA_VERSION = 1

    /** Overall prompts. */
    val overallPrompts: List<JournalPromptDefinition> =
        /** List of. */
        listOf(
            /** Journal prompt definition. */
            JournalPromptDefinition("gratitude", "What am I grateful for today?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("accomplishment", "What did I accomplish today?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("learned", "What did I learn today?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("challenge", "What challenged me today?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("improve", "What could I have done better?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("energy", "What gave me energy today?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("drained", "What drained my energy today?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("tomorrow", "What's most important for tomorrow?"),
        )

    /** Dimension prompts. */
    val dimensionPrompts: List<JournalPromptDefinition> =
        /** List of. */
        listOf(
            /** Journal prompt definition. */
            JournalPromptDefinition("progress", "What progress did I make in this area?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("blockers", "What blocked me in this area?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("next_action", "What's my next action here?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("feeling", "How do I feel about this dimension?"),
            /** Journal prompt definition. */
            JournalPromptDefinition("insight", "Any insights or realizations?"),
        )

    /**
     * Empty snapshot.
     */
    fun emptySnapshot(): JournalSnapshot = JournalSnapshot()

    /**
     * Day for date.
     */
    fun dayForDate(
        /** Snapshot. */
        snapshot: JournalSnapshot,
        /** Date iso. */
        dateIso: String,
    ): JournalDayRecord? = snapshot.days.firstOrNull { it.dateIso == dateIso }

    /**
     * Upsert overall response.
     */
    fun upsertOverallResponse(
        /** Snapshot. */
        snapshot: JournalSnapshot,
        /** Date iso. */
        dateIso: String,
        /** Prompt key. */
        promptKey: String,
        /** Response. */
        response: String,
        /** Now. */
        now: LocalDateTime,
    ): JournalSnapshot =
        /** Upsert day. */
        upsertDay(snapshot, dateIso, now) { existing ->
            existing.copy(
                overallResponses = existing.overallResponses.updated(promptKey, response),
                updatedAtIso = now.toString(),
            )
        }

    /**
     * Upsert dimension response.
     */
    fun upsertDimensionResponse(
        /** Snapshot. */
        snapshot: JournalSnapshot,
        /** Date iso. */
        dateIso: String,
        /** Dimension id. */
        dimensionId: String,
        /** Prompt key. */
        promptKey: String,
        /** Response. */
        response: String,
        /** Now. */
        now: LocalDateTime,
    ): JournalSnapshot =
        /** Upsert day. */
        upsertDay(snapshot, dateIso, now) { existing ->
            val currentDimensionResponses = existing.dimensionResponses[dimensionId].orEmpty()
            existing.copy(
                dimensionResponses =
                    existing.dimensionResponses.updated(
                        /** Dimension id. */
                        dimensionId,
                        currentDimensionResponses.updated(promptKey, response),
                    ),
                updatedAtIso = now.toString(),
            )
        }

    private fun upsertDay(
        /** Snapshot. */
        snapshot: JournalSnapshot,
        /** Date iso. */
        dateIso: String,
        /** Now. */
        now: LocalDateTime,
        update: (JournalDayRecord) -> JournalDayRecord,
    ): JournalSnapshot {
        val existing =
            /** Day for date. */
            dayForDate(snapshot, dateIso)
                ?: JournalDayRecord(
                    dateIso = dateIso,
                    createdAtIso = now.toString(),
                    updatedAtIso = now.toString(),
                )
        val updated = update(existing)
        return snapshot.copy(
            days =
                (snapshot.days.filterNot { it.dateIso == dateIso } + updated)
                    .sortedByDescending(JournalDayRecord::dateIso),
        )
    }

    private fun Map<String, String>.updated(
        /** Key. */
        key: String,
        /** Response. */
        response: String,
    ): Map<String, String> {
        val normalized = response.trim()
        return if (normalized.isEmpty()) {
            this - key
        } else {
            this + (key to normalized)
        }
    }

    private fun Map<String, Map<String, String>>.updated(
        /** Key. */
        key: String,
        responses: Map<String, String>,
    ): Map<String, Map<String, String>> =
        /** If. */
        if (responses.isEmpty()) {
            this - key
        } else {
            this + (key to responses)
        }
}
