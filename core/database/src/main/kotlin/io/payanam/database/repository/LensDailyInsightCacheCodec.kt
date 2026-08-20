//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.HabitPlanItem
import io.payanam.domain.repository.HabitRealityItem
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.TaskPlanItem
import io.payanam.domain.repository.TaskRealityItem
import io.payanam.domain.repository.UnifiedLensSnapshot
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val SNAPSHOT_CODEC_VERSION = "1"
private const val NULL_TOKEN = "~"
private const val LIST_DELIMITER = ";"
private const val FIELD_DELIMITER = ","
private const val MAP_ENTRY_DELIMITER = ";"
private const val MAP_KEY_VALUE_DELIMITER = "="

internal fun encodeUnifiedLensSnapshot(snapshot: UnifiedLensSnapshot): String {
    /** Planning. */
    val planning = snapshot.planning
    /** Reality. */
    val reality = snapshot.reality
    return buildString {
        /** Append. */
        append("v=").append(SNAPSHOT_CODEC_VERSION).append('\n')
        /** Append. */
        append("day=").append(planning.dayKey).append('\n')
        /** Append. */
        append("planning.total=").append(planning.totalPlannedMinutes).append('\n')
        /** Append. */
        append("planning.planScore=").append(planning.planCompletenessScore).append('\n')
        /** Append. */
        append("planning.plannedByDim=").append(encodeIntMap(planning.plannedTimeByDimension)).append('\n')
        /** Append. */
        append("planning.budgetByDim=").append(encodeIntMap(planning.budgetAllocationsByDimension)).append('\n')
        /** Append. */
        append("planning.tasks=").append(encodeTaskPlanItems(planning.plannedTasks)).append('\n')
        /** Append. */
        append("planning.habits=").append(encodeHabitPlanItems(planning.plannedHabits)).append('\n')
        /** Append. */
        append("reality.total=").append(reality.totalActualMinutes).append('\n')
        /** Append. */
        append("reality.actualByDim=").append(encodeIntMap(reality.actualTimeByDimension)).append('\n')
        /** Append. */
        append("reality.budgetByDim=").append(encodeIntMap(reality.budgetAllocationsByDimension)).append('\n')
        /** Append. */
        append("reality.tasks=").append(encodeTaskRealityItems(reality.completedTasks)).append('\n')
        /** Append. */
        append("reality.habits=").append(encodeHabitRealityItems(reality.completedHabits)).append('\n')
        /** Append. */
        append("reality.untracked=").append(reality.untrackedMinutes).append('\n')
        /** Append. */
        append("reality.focusGap=").append(reality.focusGapMinutes).append('\n')
        /** Append. */
        append("reality.supplementalTotal=").append(reality.supplementalActualMinutes).append('\n')
        /** Append. */
        append("reality.supplementalByDim=").append(encodeIntMap(reality.supplementalActualByDimension)).append('\n')
        /** Append. */
        append("reality.timeOnly=").append(reality.actualTimeOnlyMinutes).append('\n')
        /** Append. */
        append("reality.taskOnly=").append(reality.actualTaskMinutes).append('\n')
        /** Append. */
        append("reality.habitOnly=").append(reality.actualHabitMinutes).append('\n')
        /** Append. */
        append("reality.adherence=").append(reality.adherenceScore)
    }
}

internal fun decodeUnifiedLensSnapshot(
    /** Day key. */
    dayKey: String,
    /** Encoded. */
    encoded: String,
): UnifiedLensSnapshot? =
    runCatching {
        /** Values. */
        val values =
            /** Encoded. */
            encoded
                .lineSequence()
                .map { line -> line.split("=", limit = 2) }
                .filter { parts -> parts.size == 2 }
                .associate { parts -> parts[0] to parts[1] }

        /** Planning. */
        val planning =
            /** Planning lens data. */
            PlanningLensData(
                dayKey = values["day"] ?: dayKey,
                totalPlannedMinutes = values["planning.total"]?.toIntOrNull() ?: 0,
                plannedTimeByDimension = decodeIntMap(values["planning.plannedByDim"]),
                budgetAllocationsByDimension = decodeIntMap(values["planning.budgetByDim"]),
                plannedTasks = decodeTaskPlanItems(values["planning.tasks"]),
                plannedHabits = decodeHabitPlanItems(values["planning.habits"]),
                timeGoals = emptyList(),
                planCompletenessScore = values["planning.planScore"]?.toFloatOrNull() ?: 0f,
            )
        /** Reality. */
        val reality =
            /** Reality lens data. */
            RealityLensData(
                dayKey = values["day"] ?: dayKey,
                totalActualMinutes = values["reality.total"]?.toIntOrNull() ?: 0,
                actualTimeByDimension = decodeIntMap(values["reality.actualByDim"]),
                budgetAllocationsByDimension = decodeIntMap(values["reality.budgetByDim"]),
                completedTasks = decodeTaskRealityItems(values["reality.tasks"]),
                completedHabits = decodeHabitRealityItems(values["reality.habits"]),
                untrackedMinutes = values["reality.untracked"]?.toIntOrNull() ?: 0,
                focusGapMinutes = values["reality.focusGap"]?.toIntOrNull() ?: 0,
                adherenceScore = values["reality.adherence"]?.toFloatOrNull() ?: 0f,
                supplementalActualMinutes = values["reality.supplementalTotal"]?.toIntOrNull() ?: 0,
                supplementalActualByDimension = decodeIntMap(values["reality.supplementalByDim"]),
                actualTimeOnlyMinutes = values["reality.timeOnly"]?.toIntOrNull() ?: 0,
                actualTaskMinutes = values["reality.taskOnly"]?.toIntOrNull() ?: 0,
                actualHabitMinutes = values["reality.habitOnly"]?.toIntOrNull() ?: 0,
            )
        /** Unified lens snapshot. */
        UnifiedLensSnapshot(planning = planning, reality = reality)
    }.onFailure { error ->
        /** If. */
        if (UnifiedLogger.isInitialized()) {
            UnifiedLogger.getInstance().w(
                "LensDailyInsightCacheCodec.decodeUnifiedLensSnapshot",
                "Failed to decode cached unified snapshot",
                /** Map of. */
                mapOf("dayKey" to dayKey, "error" to (error.message ?: "unknown")),
            )
        }
    }.getOrNull()

private fun encodeIntMap(map: Map<String, Int>): String {
    /** If. */
    if (map.isEmpty()) return ""
    return map.entries.joinToString(MAP_ENTRY_DELIMITER) { (key, value) ->
        "${encodeNullableText(key)}$MAP_KEY_VALUE_DELIMITER$value"
    }
}

private fun decodeIntMap(encoded: String?): Map<String, Int> {
    /** If. */
    if (encoded.isNullOrBlank()) return emptyMap()
    return encoded
        .split(MAP_ENTRY_DELIMITER)
        .mapNotNull { entry ->
            /** Parts. */
            val parts = entry.split(MAP_KEY_VALUE_DELIMITER, limit = 2)
            /** If. */
            if (parts.size != 2) return@mapNotNull null
            /** Key. */
            val key = decodeNullableText(parts[0]) ?: return@mapNotNull null
            /** Value. */
            val value = parts[1].toIntOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
}

private fun encodeTaskPlanItems(items: List<TaskPlanItem>): String =
    items.joinToString(LIST_DELIMITER) { item ->
        /** List of. */
        listOf(
            /** Encode nullable text. */
            encodeNullableText(item.taskId),
            /** Encode nullable text. */
            encodeNullableText(item.title),
            /** Encode nullable text. */
            encodeNullableText(item.dimensionId),
            item.estimatedMinutes.toString(),
            /** Encode nullable text. */
            encodeNullableText(item.dueDate),
            /** Encode nullable text. */
            encodeNullableText(item.priority),
        ).joinToString(FIELD_DELIMITER)
    }

private fun decodeTaskPlanItems(encoded: String?): List<TaskPlanItem> {
    /** If. */
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.split(LIST_DELIMITER).mapNotNull { value ->
        /** Parts. */
        val parts = value.split(FIELD_DELIMITER)
        /** If. */
        if (parts.size != 6) return@mapNotNull null
        /** Task plan item. */
        TaskPlanItem(
            taskId = decodeNullableText(parts[0]) ?: return@mapNotNull null,
            title = decodeNullableText(parts[1]) ?: return@mapNotNull null,
            dimensionId = decodeNullableText(parts[2]),
            estimatedMinutes = parts[3].toIntOrNull() ?: return@mapNotNull null,
            dueDate = decodeNullableText(parts[4]) ?: "",
            priority = decodeNullableText(parts[5]) ?: "",
        )
    }
}

private fun encodeHabitPlanItems(items: List<HabitPlanItem>): String =
    items.joinToString(LIST_DELIMITER) { item ->
        /** List of. */
        listOf(
            /** Encode nullable text. */
            encodeNullableText(item.habitId),
            /** Encode nullable text. */
            encodeNullableText(item.title),
            /** Encode nullable text. */
            encodeNullableText(item.dimensionId),
            item.estimatedMinutes.toString(),
            /** Encode nullable text. */
            encodeNullableText(item.recurrenceRule),
        ).joinToString(FIELD_DELIMITER)
    }

private fun decodeHabitPlanItems(encoded: String?): List<HabitPlanItem> {
    /** If. */
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.split(LIST_DELIMITER).mapNotNull { value ->
        /** Parts. */
        val parts = value.split(FIELD_DELIMITER)
        /** If. */
        if (parts.size != 5) return@mapNotNull null
        /** Habit plan item. */
        HabitPlanItem(
            habitId = decodeNullableText(parts[0]) ?: return@mapNotNull null,
            title = decodeNullableText(parts[1]) ?: return@mapNotNull null,
            dimensionId = decodeNullableText(parts[2]),
            estimatedMinutes = parts[3].toIntOrNull() ?: return@mapNotNull null,
            recurrenceRule = decodeNullableText(parts[4]) ?: "",
        )
    }
}

private fun encodeTaskRealityItems(items: List<TaskRealityItem>): String =
    items.joinToString(LIST_DELIMITER) { item ->
        /** List of. */
        listOf(
            /** Encode nullable text. */
            encodeNullableText(item.taskId),
            /** Encode nullable text. */
            encodeNullableText(item.title),
            /** Encode nullable text. */
            encodeNullableText(item.dimensionId),
            /** Encode nullable text. */
            encodeNullableText(item.actualMinutes?.toString()),
            /** Encode nullable text. */
            encodeNullableText(item.completedAt),
            /** Encode nullable text. */
            encodeNullableText(item.status),
            /** Encode nullable text. */
            encodeNullableText(item.adherenceGap?.toString()),
        ).joinToString(FIELD_DELIMITER)
    }

private fun decodeTaskRealityItems(encoded: String?): List<TaskRealityItem> {
    /** If. */
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.split(LIST_DELIMITER).mapNotNull { value ->
        /** Parts. */
        val parts = value.split(FIELD_DELIMITER)
        /** If. */
        if (parts.size != 7) return@mapNotNull null
        /** Task reality item. */
        TaskRealityItem(
            taskId = decodeNullableText(parts[0]) ?: return@mapNotNull null,
            title = decodeNullableText(parts[1]) ?: return@mapNotNull null,
            dimensionId = decodeNullableText(parts[2]),
            actualMinutes = decodeNullableText(parts[3])?.toIntOrNull(),
            completedAt = decodeNullableText(parts[4]),
            status = decodeNullableText(parts[5]) ?: "",
            adherenceGap = decodeNullableText(parts[6])?.toIntOrNull(),
        )
    }
}

private fun encodeHabitRealityItems(items: List<HabitRealityItem>): String =
    items.joinToString(LIST_DELIMITER) { item ->
        /** List of. */
        listOf(
            /** Encode nullable text. */
            encodeNullableText(item.habitId),
            /** Encode nullable text. */
            encodeNullableText(item.title),
            /** Encode nullable text. */
            encodeNullableText(item.dimensionId),
            /** Encode nullable text. */
            encodeNullableText(item.actualMinutes?.toString()),
            /** Encode nullable text. */
            encodeNullableText(item.completedAt),
            /** Encode nullable text. */
            encodeNullableText(item.status),
        ).joinToString(FIELD_DELIMITER)
    }

private fun decodeHabitRealityItems(encoded: String?): List<HabitRealityItem> {
    /** If. */
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.split(LIST_DELIMITER).mapNotNull { value ->
        /** Parts. */
        val parts = value.split(FIELD_DELIMITER)
        /** If. */
        if (parts.size != 6) return@mapNotNull null
        /** Habit reality item. */
        HabitRealityItem(
            habitId = decodeNullableText(parts[0]) ?: return@mapNotNull null,
            title = decodeNullableText(parts[1]) ?: return@mapNotNull null,
            dimensionId = decodeNullableText(parts[2]),
            actualMinutes = decodeNullableText(parts[3])?.toIntOrNull(),
            completedAt = decodeNullableText(parts[4]),
            status = decodeNullableText(parts[5]) ?: "",
        )
    }
}

private fun encodeNullableText(value: String?): String {
    /** If. */
    if (value == null) return NULL_TOKEN
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun decodeNullableText(value: String): String? {
    /** If. */
    if (value == NULL_TOKEN) return null
    return String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
