//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.tasks

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Task list filters available on the desktop board (active/today/overdue/...).
 */
enum class DesktopTaskFilter(val storageKey: String) {
    ACTIVE("active"),
    TODAY("today"),
    OVERDUE("overdue"),
    FUTURE("future"),
    COMPLETED("completed"),
    ARCHIVED("archived"),
    NOT_ACTIVE("not_active"),
    ;

    companion object {
        /**
         * Resolves this filter from its [storageKey]; unknown/blank → [TODAY].
         */
        fun fromStorageKey(storageKey: String?): DesktopTaskFilter = entries.find { it.storageKey == storageKey } ?: TODAY
    }
}

/**
 * Sort orderings for the desktop task list.
 */
enum class DesktopTaskSortOption(val storageKey: String) {
    DUE_DATE_ASC("due_asc"),
    TITLE_ASC("title_asc"),
    CREATED_DESC("created_desc"),
    DIMENSION("dimension"),
    ;

    companion object {
        /**
         * Resolves this sort option from its [storageKey]; unknown/blank → [DUE_DATE_ASC].
         */
        fun fromStorageKey(storageKey: String?): DesktopTaskSortOption =
            entries.find { it.storageKey == storageKey } ?: DUE_DATE_ASC
    }
}

/**
 * Sort orderings for the desktop habit list.
 */
enum class DesktopHabitSortOption(val storageKey: String) {
    BY_NAME("by_name"),
    BY_STATUS("by_status"),
    BY_DUE_TIME("by_due_time"),
    BY_LIFE_DIMENSION("by_life_dimension"),
    ;

    companion object {
        /**
         * Resolves this habit sort option from its [storageKey]; unknown/blank → [BY_STATUS].
         */
        fun fromStorageKey(storageKey: String?): DesktopHabitSortOption =
            entries.find { it.storageKey == storageKey } ?: BY_STATUS
    }
}
/**
 * Task-board loading status surfaced to the desktop UI (loading/ready/empty/error).
 */
enum class DesktopTaskBoardLoadState {
    LOADING,
    READY,
    EMPTY,
    ERROR,
}

@Serializable
/**
 * One serializable task/habit row for the desktop board (status, due date, score).
 */
data class DesktopTaskRecord(
    val id: String,
    val title: String,
    val status: String = "pending",
    val recurrenceEnabled: Boolean = false,
    val dueAtIso: String? = null,
    val createdAtIso: String,
    val lifeDimension: String? = null,
    val currentScore: Double = 0.5,
    @SerialName("completedToday")
    val completedToday: Boolean = false,
)

@Serializable
/**
 * Serializable catalog of all tasks/habits for the desktop<->mobile sync.
 */
data class DesktopTaskCatalogSnapshot(
    val schemaVersion: Int = DesktopTaskBoardContracts.CATALOG_SCHEMA_VERSION,
    val tasks: List<DesktopTaskRecord> = emptyList(),
)

/**
 * Persisted desktop board preferences (filter, sort options, archive/completed toggles).
 */
data class DesktopTaskBoardPreferences(
    val schemaVersion: Int = DesktopTaskBoardContracts.SCHEMA_VERSION,
    val selectedTaskFilter: DesktopTaskFilter = DesktopTaskBoardContracts.DEFAULT_TASK_FILTER,
    val selectedTaskSort: DesktopTaskSortOption = DesktopTaskBoardContracts.DEFAULT_TASK_SORT,
    val selectedHabitSort: DesktopHabitSortOption = DesktopTaskBoardContracts.DEFAULT_HABIT_SORT,
    val showArchivedHabits: Boolean = false,
    val showCompletedHabits: Boolean = true,
)

/**
 * Task/habit counts per filter plus today's completed-habit count.
 */
data class DesktopTaskBoardCounts(
    val totalTaskCount: Int = 0,
    val totalHabitCount: Int = 0,
    val activeTaskFilterCounts: Map<DesktopTaskFilter, Int> = DesktopTaskBoardContracts.defaultTaskFilterCounts(),
    val completedHabitCountToday: Int = 0,
)

/**
 * Display-ready task row (formatted due/score/dimension labels) for the board list.
 */
data class DesktopTaskListItem(
    val id: String,
    val title: String,
    val status: String,
    val dueLabel: String,
    val dimensionLabel: String,
    val scoreLabel: String,
)

/**
 * Display-ready habit row (today status + formatted labels) for the board list.
 */
data class DesktopHabitListItem(
    val id: String,
    val title: String,
    val todayStatusLabel: String,
    val dueLabel: String,
    val dimensionLabel: String,
    val scoreLabel: String,
)

/**
 * What the board currently shows: load state + visible tasks/habits (+ error).
 */
data class DesktopTaskBoardContent(
    val loadState: DesktopTaskBoardLoadState = DesktopTaskBoardLoadState.LOADING,
    val visibleTasks: List<DesktopTaskListItem> = emptyList(),
    val visibleHabits: List<DesktopHabitListItem> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Full desktop board state: preferences + counts + content.
 */
data class DesktopTaskBoardSnapshot(
    val preferences: DesktopTaskBoardPreferences = DesktopTaskBoardContracts.defaultPreferences(),
    val counts: DesktopTaskBoardCounts = DesktopTaskBoardContracts.defaultCounts(),
    val content: DesktopTaskBoardContent = DesktopTaskBoardContracts.defaultContent(),
) {
    /**
     * Count of tasks matching the currently selected task filter.
     */
    fun visibleTaskCount(): Int = counts.activeTaskFilterCounts[preferences.selectedTaskFilter] ?: 0
}
object DesktopTaskBoardContracts {
    const val SCHEMA_VERSION = 2
    const val CATALOG_SCHEMA_VERSION = 1
    val DEFAULT_TASK_FILTER: DesktopTaskFilter = DesktopTaskFilter.TODAY
    val DEFAULT_TASK_SORT: DesktopTaskSortOption = DesktopTaskSortOption.DUE_DATE_ASC
    val DEFAULT_HABIT_SORT: DesktopHabitSortOption = DesktopHabitSortOption.BY_STATUS

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM")
    private const val DEFAULT_DIMENSION = "General"
    /**
     * Returns board preferences populated with the defaults.
     */
    fun defaultPreferences(): DesktopTaskBoardPreferences = DesktopTaskBoardPreferences()
    /**
     * Returns board counts initialized to zero.
     */
    fun defaultCounts(): DesktopTaskBoardCounts = DesktopTaskBoardCounts()
    /**
     * Returns an empty loading board content.
     */
    fun defaultContent(): DesktopTaskBoardContent = DesktopTaskBoardContent()
    /**
     * Returns a zeroed per-filter count map (every filter → 0).
     */
    fun defaultTaskFilterCounts(): Map<DesktopTaskFilter, Int> =
        mapOf(
            DesktopTaskFilter.ACTIVE to 0,
            DesktopTaskFilter.TODAY to 0,
            DesktopTaskFilter.OVERDUE to 0,
            DesktopTaskFilter.FUTURE to 0,
            DesktopTaskFilter.COMPLETED to 0,
            DesktopTaskFilter.ARCHIVED to 0,
            DesktopTaskFilter.NOT_ACTIVE to 0,
        )
    /**
     * Assembles a board snapshot from preferences + counts + content.
     */
    fun snapshot(
        preferences: DesktopTaskBoardPreferences = defaultPreferences(),
        counts: DesktopTaskBoardCounts = defaultCounts(),
        content: DesktopTaskBoardContent = defaultContent(),
    ): DesktopTaskBoardSnapshot = DesktopTaskBoardSnapshot(preferences = preferences, counts = counts, content = content)
    /**
     * Returns a demo catalog of tasks/habits for first-run desktop seeding.
     */
    fun seededCatalog(now: LocalDateTime = LocalDateTime.now()): DesktopTaskCatalogSnapshot = seededDesktopTaskCatalog(now)
    /**
     * Builds a full board snapshot from a [catalog]: filters, sorts, and computes
     * visible lists + counts (or an error content when [errorMessage] is set).
     */
    fun boardSnapshotForCatalog(
        catalog: DesktopTaskCatalogSnapshot,
        preferences: DesktopTaskBoardPreferences,
        errorMessage: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): DesktopTaskBoardSnapshot {
        if (errorMessage != null) {
            return snapshot(
                preferences = preferences,
                counts = defaultCounts(),
                content =
                    DesktopTaskBoardContent(
                        loadState = DesktopTaskBoardLoadState.ERROR,
                        errorMessage = errorMessage,
                    ),
            )
        }

        val tasks = catalog.tasks.filterNot { it.recurrenceEnabled }
        val habits = catalog.tasks.filter { it.recurrenceEnabled }
        val counts = buildCounts(tasks = tasks, habits = habits, now = now)
        val visibleTasks = sortTasks(filterTasks(tasks, preferences.selectedTaskFilter, now), preferences.selectedTaskSort, now)
        val visibleHabits =
            sortHabits(
                habits = habits,
                sortOption = preferences.selectedHabitSort,
                showArchivedHabits = preferences.showArchivedHabits,
                showCompletedHabits = preferences.showCompletedHabits,
                now = now,
            )
        val contentState =
            when {
                visibleTasks.isEmpty() && visibleHabits.isEmpty() && catalog.tasks.isEmpty() -> DesktopTaskBoardLoadState.EMPTY
                else -> DesktopTaskBoardLoadState.READY
            }
        return snapshot(
            preferences = preferences,
            counts = counts,
            content =
                DesktopTaskBoardContent(
                    loadState = contentState,
                    visibleTasks = visibleTasks.map(::toTaskListItem),
                    visibleHabits = visibleHabits.map { toHabitListItem(record = it, now = now) },
                ),
        )
    }

    private fun buildCounts(
        tasks: List<DesktopTaskRecord>,
        habits: List<DesktopTaskRecord>,
        now: LocalDateTime,
    ): DesktopTaskBoardCounts {
        val today = now.toLocalDate()
        val counts =
            mapOf(
                DesktopTaskFilter.ACTIVE to tasks.count(DesktopTaskRecordPredicates::isActive),
                DesktopTaskFilter.TODAY to tasks.count { DesktopTaskRecordPredicates.isDueToday(it, today) && DesktopTaskRecordPredicates.isActionable(it) },
                DesktopTaskFilter.OVERDUE to tasks.count { DesktopTaskRecordPredicates.isOverdue(it, now) && DesktopTaskRecordPredicates.isActionable(it) },
                DesktopTaskFilter.FUTURE to tasks.count { DesktopTaskRecordPredicates.isFuture(it, today) && DesktopTaskRecordPredicates.isActionable(it) },
                DesktopTaskFilter.COMPLETED to tasks.count { it.status == "completed" },
                DesktopTaskFilter.ARCHIVED to tasks.count { it.status == "archived" },
                DesktopTaskFilter.NOT_ACTIVE to tasks.count { !DesktopTaskRecordPredicates.isActive(it) },
            )
        return DesktopTaskBoardCounts(
            totalTaskCount = tasks.size,
            totalHabitCount = habits.size,
            activeTaskFilterCounts = counts,
            completedHabitCountToday = habits.count { it.completedToday },
        )
    }

    private fun filterTasks(
        tasks: List<DesktopTaskRecord>,
        filter: DesktopTaskFilter,
        now: LocalDateTime,
    ): List<DesktopTaskRecord> {
        val today = now.toLocalDate()
        return when (filter) {
            DesktopTaskFilter.ACTIVE -> tasks.filter(DesktopTaskRecordPredicates::isActive)
            DesktopTaskFilter.TODAY -> tasks.filter { DesktopTaskRecordPredicates.isDueToday(it, today) && DesktopTaskRecordPredicates.isActionable(it) }
            DesktopTaskFilter.OVERDUE -> tasks.filter { DesktopTaskRecordPredicates.isOverdue(it, now) && DesktopTaskRecordPredicates.isActionable(it) }
            DesktopTaskFilter.FUTURE -> tasks.filter { DesktopTaskRecordPredicates.isFuture(it, today) && DesktopTaskRecordPredicates.isActionable(it) }
            DesktopTaskFilter.COMPLETED -> tasks.filter { it.status == "completed" }
            DesktopTaskFilter.ARCHIVED -> tasks.filter { it.status == "archived" }
            DesktopTaskFilter.NOT_ACTIVE -> tasks.filter { !DesktopTaskRecordPredicates.isActive(it) }
        }
    }

    @Suppress("MagicNumber")
    private fun sortTasks(
        tasks: List<DesktopTaskRecord>,
        sortOption: DesktopTaskSortOption,
        now: LocalDateTime,
    ): List<DesktopTaskRecord> =
        when (sortOption) {
            DesktopTaskSortOption.DUE_DATE_ASC ->
                tasks.sortedWith(compareBy<DesktopTaskRecord> { DesktopTaskRecordFormatting.parseDateTime(it.dueAtIso) ?: now.plusYears(10) }.thenBy { it.title.lowercase() })

            DesktopTaskSortOption.TITLE_ASC -> tasks.sortedBy { it.title.lowercase() }
            DesktopTaskSortOption.CREATED_DESC ->
                tasks.sortedByDescending { DesktopTaskRecordFormatting.parseDateTime(it.createdAtIso) ?: LocalDateTime.MIN }

            DesktopTaskSortOption.DIMENSION ->
                tasks.sortedWith(compareBy<DesktopTaskRecord> { DesktopTaskRecordFormatting.normalizeDimension(it.lifeDimension) }.thenBy { it.title.lowercase() })
        }

    @Suppress("MagicNumber")
    private fun sortHabits(
        habits: List<DesktopTaskRecord>,
        sortOption: DesktopHabitSortOption,
        showArchivedHabits: Boolean,
        showCompletedHabits: Boolean,
        now: LocalDateTime,
    ): List<DesktopTaskRecord> {
        val filteredHabits =
            habits.filter { habit ->
                (showArchivedHabits || habit.status != "archived") &&
                    (showCompletedHabits || !habit.completedToday)
            }
        return when (sortOption) {
            DesktopHabitSortOption.BY_NAME -> filteredHabits.sortedBy { it.title.lowercase() }
            DesktopHabitSortOption.BY_STATUS ->
                filteredHabits.sortedWith(
                    compareBy<DesktopTaskRecord> { DesktopTaskRecordPredicates.habitStatusRank(it) }
                        .thenByDescending { it.currentScore }
                        .thenBy { it.title.lowercase() },
                )

            DesktopHabitSortOption.BY_DUE_TIME ->
                filteredHabits.sortedWith(
                    compareBy<DesktopTaskRecord> { DesktopTaskRecordFormatting.parseDateTime(it.dueAtIso) ?: now.plusYears(10) }
                        .thenBy { it.title.lowercase() },
                )

            DesktopHabitSortOption.BY_LIFE_DIMENSION ->
                filteredHabits.sortedWith(compareBy<DesktopTaskRecord> { DesktopTaskRecordFormatting.normalizeDimension(it.lifeDimension) }.thenBy { it.title.lowercase() })
        }
    }

    private fun toTaskListItem(record: DesktopTaskRecord): DesktopTaskListItem =
        DesktopTaskListItem(
            id = record.id,
            title = record.title,
            status = record.status.replaceFirstChar(Char::uppercase),
            dueLabel = DesktopTaskRecordFormatting.dueLabel(record.dueAtIso),
            dimensionLabel = DesktopTaskRecordFormatting.normalizeDimension(record.lifeDimension),
            scoreLabel = DesktopTaskRecordFormatting.scoreLabel(record.currentScore),
        )

    private fun toHabitListItem(
        record: DesktopTaskRecord,
        now: LocalDateTime,
    ): DesktopHabitListItem =
        DesktopHabitListItem(
            id = record.id,
            title = record.title,
            todayStatusLabel = DesktopTaskRecordPredicates.habitStatusLabel(record, now.toLocalDate()),
            dueLabel = DesktopTaskRecordFormatting.dueLabel(record.dueAtIso),
            dimensionLabel = DesktopTaskRecordFormatting.normalizeDimension(record.lifeDimension),
            scoreLabel = DesktopTaskRecordFormatting.scoreLabel(record.currentScore),
        )
}

private object DesktopTaskRecordFormatting {
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM")
    private const val DEFAULT_DIMENSION = "General"

    /**
     * Formats [dueAtIso] as "dd MMM HH:mm", or "No due date" when absent.
     */
    fun dueLabel(dueAtIso: String?): String {
        val dueAt = parseDateTime(dueAtIso) ?: return "No due date"
        return "${dateFormatter.format(dueAt.toLocalDate())} ${dueAt.toLocalTime()}"
    }

    /**
     * Formats a 0-1 [score] as a whole-percent string (e.g. "72%").
     */
    @Suppress("MagicNumber")
    fun scoreLabel(score: Double): String = "${(score * 100).toInt()}%"
    /**
     * Coerces a blank dimension value to "General".
     */
    fun normalizeDimension(value: String?): String = value?.takeIf { it.isNotBlank() } ?: DEFAULT_DIMENSION
    /**
     * Parses an ISO date-time string, or null when blank/invalid.
     */
    fun parseDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)
}

private object DesktopTaskRecordPredicates {
    /**
     * True when the task is live (status active or pending).
     */
    fun isActive(record: DesktopTaskRecord): Boolean = record.status == "active" || record.status == "pending"
    /**
     * True when the task is neither completed nor archived.
     */
    fun isActionable(record: DesktopTaskRecord): Boolean = record.status != "completed" && record.status != "archived"
    /**
     * True when the parsed due date falls on [today].
     */
    fun isDueToday(
        record: DesktopTaskRecord,
        today: LocalDate,
    ): Boolean = DesktopTaskRecordFormatting.parseDateTime(record.dueAtIso)?.toLocalDate() == today
    /**
     * True when the due date is before [now] and the task is not completed.
     */
    fun isOverdue(
        record: DesktopTaskRecord,
        now: LocalDateTime,
    ): Boolean =
        (DesktopTaskRecordFormatting.parseDateTime(record.dueAtIso)?.isBefore(now) == true) &&
            record.status != "completed"
    /**
     * True when the due date is after [today] (or unparseable → treated as future).
     */
    fun isFuture(
        record: DesktopTaskRecord,
        today: LocalDate,
    ): Boolean {
        val dueAt = DesktopTaskRecordFormatting.parseDateTime(record.dueAtIso)
        return dueAt == null || dueAt.toLocalDate().isAfter(today)
    }

    /**
     * Sort rank for habit status (completed-today=2, archived=3, else 1).
     */
    @Suppress("MagicNumber")
    fun habitStatusRank(record: DesktopTaskRecord): Int =
        when {
            record.completedToday -> 2
            record.status == "archived" -> 3
            else -> 1
        }
    /**
     * Human label for a habit's status: Archived / Completed today / Due today / Open.
     */
    fun habitStatusLabel(
        record: DesktopTaskRecord,
        today: LocalDate,
    ): String =
        when {
            record.status == "archived" -> "Archived"
            record.completedToday -> "Completed today"
            isDueToday(record, today) -> "Due today"
            else -> "Open"
        }
}
