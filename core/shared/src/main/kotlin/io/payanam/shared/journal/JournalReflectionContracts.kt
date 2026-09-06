//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.journal

import java.time.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
/**
 * One journal prompt definition: a stable [key] and the prompt text shown to the user.
 */
data class JournalPromptDefinition(
    val key: String,
    val prompt: String,
)

@Serializable
/**
 * All responses for a single day: overall prompts + per-dimension prompt maps.
 */
data class JournalDayRecord(
    val dateIso: String,
    val overallResponses: Map<String, String> = emptyMap(),
    val dimensionResponses: Map<String, Map<String, String>> = emptyMap(),
    val createdAtIso: String,
    val updatedAtIso: String,
)

@Serializable
/**
 * Serializable journal state holding every day record for the desktop<->mobile sync.
 */
data class JournalSnapshot(
    val schemaVersion: Int = JournalReflectionContracts.SCHEMA_VERSION,
    val days: List<JournalDayRecord> = emptyList(),
)
object JournalReflectionContracts {
    const val SCHEMA_VERSION = 1
    val overallPrompts: List<JournalPromptDefinition> =
        listOf(
            JournalPromptDefinition("gratitude", "What am I grateful for today?"),
            JournalPromptDefinition("accomplishment", "What did I accomplish today?"),
            JournalPromptDefinition("learned", "What did I learn today?"),
            JournalPromptDefinition("challenge", "What challenged me today?"),
            JournalPromptDefinition("improve", "What could I have done better?"),
            JournalPromptDefinition("energy", "What gave me energy today?"),
            JournalPromptDefinition("drained", "What drained my energy today?"),
            JournalPromptDefinition("tomorrow", "What's most important for tomorrow?"),
        )
    val dimensionPrompts: List<JournalPromptDefinition> =
        listOf(
            JournalPromptDefinition("progress", "What progress did I make in this area?"),
            JournalPromptDefinition("blockers", "What blocked me in this area?"),
            JournalPromptDefinition("next_action", "What's my next action here?"),
            JournalPromptDefinition("feeling", "How do I feel about this dimension?"),
            JournalPromptDefinition("insight", "Any insights or realizations?"),
        )
    /**
     * Returns an empty [JournalSnapshot] (no days).
     */
    fun emptySnapshot(): JournalSnapshot = JournalSnapshot()
    /**
     * Returns the [JournalDayRecord] for [dateIso] within [snapshot], or null.
     */
    fun dayForDate(
        snapshot: JournalSnapshot,
        dateIso: String,
    ): JournalDayRecord? = snapshot.days.firstOrNull { it.dateIso == dateIso }
    /**
     * Returns a copy of [snapshot] with [response] set for the overall [promptKey] on
     * [dateIso] (whitespace-only clears the entry).
     */
    fun upsertOverallResponse(
        snapshot: JournalSnapshot,
        dateIso: String,
        promptKey: String,
        response: String,
        now: LocalDateTime,
    ): JournalSnapshot =
        upsertDay(snapshot, dateIso, now) { existing ->
            existing.copy(
                overallResponses = existing.overallResponses.updated(promptKey, response),
                updatedAtIso = now.toString(),
            )
        }
    /**
     * Returns a copy of [snapshot] with [response] set for [dimensionId]'s [promptKey]
     * on [dateIso].
     */
    fun upsertDimensionResponse(
        snapshot: JournalSnapshot,
        dateIso: String,
        dimensionId: String,
        promptKey: String,
        response: String,
        now: LocalDateTime,
    ): JournalSnapshot =
        upsertDay(snapshot, dateIso, now) { existing ->
            val currentDimensionResponses = existing.dimensionResponses[dimensionId].orEmpty()
            existing.copy(
                dimensionResponses =
                    existing.dimensionResponses.updated(
                        dimensionId,
                        currentDimensionResponses.updated(promptKey, response),
                    ),
                updatedAtIso = now.toString(),
            )
        }

    private fun upsertDay(
        snapshot: JournalSnapshot,
        dateIso: String,
        now: LocalDateTime,
        update: (JournalDayRecord) -> JournalDayRecord,
    ): JournalSnapshot {
        val existing =
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
        key: String,
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
        key: String,
        responses: Map<String, String>,
    ): Map<String, Map<String, String>> =
        if (responses.isEmpty()) {
            this - key
        } else {
            this + (key to responses)
        }
}
