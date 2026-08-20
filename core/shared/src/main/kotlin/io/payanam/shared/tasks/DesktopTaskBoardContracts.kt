//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.tasks

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DesktopTaskFilter.
 *
 * @property storageKey Persistent storage key for the filter.
 */
enum class DesktopTaskFilter(val storageKey: String) {
    /** A c t i v e. */
    ACTIVE("active"),
    /** T o d a y. */
    TODAY("today"),
    /** O v e r d u e. */
    OVERDUE("overdue"),
    /** F u t u r e. */
    FUTURE("future"),
    /** C o m p l e t e d. */
    COMPLETED("completed"),
    /** A r c h i v e d. */
    ARCHIVED("archived"),
    /** N o t  a c t i v e. */
    NOT_ACTIVE("not_active"),
    ;

    companion object {
        /**
         * From storage key.
         */
        fun fromStorageKey(storageKey: String?): DesktopTaskFilter = entries.find { it.storageKey == storageKey } ?: TODAY
    }
}

/**
 * DesktopTaskSortOption.
 *
 * @property storageKey Persistent storage key for the sort option.
 */
enum class DesktopTaskSortOption(val storageKey: String) {
    /** D u e  d a t e  a s c. */
    DUE_DATE_ASC("due_asc"),
    /** T i t l e  a s c. */
    TITLE_ASC("title_asc"),
    /** C r e a t e d  d e s c. */
    CREATED_DESC("created_desc"),
    /** D i m e n s i o n. */
    DIMENSION("dimension"),
    ;

    companion object {
        /**
         * From storage key.
         */
        fun fromStorageKey(storageKey: String?): DesktopTaskSortOption =
            entries.find { it.storageKey == storageKey } ?: DUE_DATE_ASC
    }
}

/**
 * DesktopHabitSortOption.
 *
 * @property storageKey Persistent storage key for the sort option.
 */
enum class DesktopHabitSortOption(val storageKey: String) {
    /** B y  n a m e. */
    BY_NAME("by_name"),
    /** B y  s t a t u s. */
    BY_STATUS("by_status"),
    /** B y  d u e  t i m e. */
    BY_DUE_TIME("by_due_time"),
    /** B y  l i f e  d i m e n s i o n. */
    BY_LIFE_DIMENSION("by_life_dimension"),
    ;

    companion object {
        /**
         * From storage key.
         */
        fun fromStorageKey(storageKey: String?): DesktopHabitSortOption =
            entries.find { it.storageKey == storageKey } ?: BY_STATUS
    }
}

/**
 * DesktopTaskBoardLoadState.
 */
enum class DesktopTaskBoardLoadState {
    /** L o a d i n g. */
    LOADING,
    /** R e a d y. */
    READY,
    /** E m p t y. */
    EMPTY,
    /** E r r o r. */
    ERROR,
}

@Serializable
/**
 * DesktopTaskRecord.

 */
data class DesktopTaskRecord(
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Status. */
    val status: String = "pending",
    /** Recurrence enabled. */
    val recurrenceEnabled: Boolean = false,
    /** Due at iso. */
    val dueAtIso: String? = null,
    /** Created at iso. */
    val createdAtIso: String,
    /** Life dimension. */
    val lifeDimension: String? = null,
    /** Current score. */
    val currentScore: Double = 0.5,
    @SerialName("completedToday")
    /** Completed today. */
    val completedToday: Boolean = false,
)

@Serializable
/**
 * DesktopTaskCatalogSnapshot.

 */
data class DesktopTaskCatalogSnapshot(
    /** Schema version. */
    val schemaVersion: Int = DesktopTaskBoardContracts.CATALOG_SCHEMA_VERSION,
    /** Tasks. */
    val tasks: List<DesktopTaskRecord> = emptyList(),
)

/**
 * DesktopTaskBoardPreferences.

 */
data class DesktopTaskBoardPreferences(
    /** Schema version. */
    val schemaVersion: Int = DesktopTaskBoardContracts.SCHEMA_VERSION,
    /** Selected task filter. */
    val selectedTaskFilter: DesktopTaskFilter = DesktopTaskBoardContracts.DEFAULT_TASK_FILTER,
    /** Selected task sort. */
    val selectedTaskSort: DesktopTaskSortOption = DesktopTaskBoardContracts.DEFAULT_TASK_SORT,
    /** Selected habit sort. */
    val selectedHabitSort: DesktopHabitSortOption = DesktopTaskBoardContracts.DEFAULT_HABIT_SORT,
    /** Show archived habits. */
    val showArchivedHabits: Boolean = false,
    /** Show completed habits. */
    val showCompletedHabits: Boolean = true,
)

/**
 * DesktopTaskBoardCounts.

 */
data class DesktopTaskBoardCounts(
    /** Total task count. */
    val totalTaskCount: Int = 0,
    /** Total habit count. */
    val totalHabitCount: Int = 0,
    /** Active task filter counts. */
    val activeTaskFilterCounts: Map<DesktopTaskFilter, Int> = DesktopTaskBoardContracts.defaultTaskFilterCounts(),
    /** Completed habit count today. */
    val completedHabitCountToday: Int = 0,
)

/**
 * DesktopTaskListItem.

 */
data class DesktopTaskListItem(
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Status. */
    val status: String,
    /** Due label. */
    val dueLabel: String,
    /** Dimension label. */
    val dimensionLabel: String,
    /** Score label. */
    val scoreLabel: String,
)

/**
 * DesktopHabitListItem.

 */
data class DesktopHabitListItem(
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Today status label. */
    val todayStatusLabel: String,
    /** Due label. */
    val dueLabel: String,
    /** Dimension label. */
    val dimensionLabel: String,
    /** Score label. */
    val scoreLabel: String,
)

/**
 * DesktopTaskBoardContent.

 */
data class DesktopTaskBoardContent(
    /** Load state. */
    val loadState: DesktopTaskBoardLoadState = DesktopTaskBoardLoadState.LOADING,
    /** Visible tasks. */
    val visibleTasks: List<DesktopTaskListItem> = emptyList(),
    /** Visible habits. */
    val visibleHabits: List<DesktopHabitListItem> = emptyList(),
    /** Error message. */
    val errorMessage: String? = null,
)

/**
 * DesktopTaskBoardSnapshot.

 */
data class DesktopTaskBoardSnapshot(
    /** Preferences. */
    val preferences: DesktopTaskBoardPreferences = DesktopTaskBoardContracts.defaultPreferences(),
    /** Counts. */
    val counts: DesktopTaskBoardCounts = DesktopTaskBoardContracts.defaultCounts(),
    /** Content. */
    val content: DesktopTaskBoardContent = DesktopTaskBoardContracts.defaultContent(),
) {
    /**
     * Visible task count.
     */
    fun visibleTaskCount(): Int = counts.activeTaskFilterCounts[preferences.selectedTaskFilter] ?: 0
}

/**
 * DesktopTaskBoardContracts.
 */
object DesktopTaskBoardContracts {
    /** S c h e m a  v e r s i o n. */
    const val SCHEMA_VERSION = 2
    /** C a t a l o g  s c h e m a  v e r s i o n. */
    const val CATALOG_SCHEMA_VERSION = 1

    /** D e f a u l t  t a s k  f i l t e r. */
    val DEFAULT_TASK_FILTER: DesktopTaskFilter = DesktopTaskFilter.TODAY
    /** D e f a u l t  t a s k  s o r t. */
    val DEFAULT_TASK_SORT: DesktopTaskSortOption = DesktopTaskSortOption.DUE_DATE_ASC
    /** D e f a u l t  h a b i t  s o r t. */
    val DEFAULT_HABIT_SORT: DesktopHabitSortOption = DesktopHabitSortOption.BY_STATUS

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM")
    private const val DEFAULT_DIMENSION = "General"

    /**
     * Default preferences.
     */
    fun defaultPreferences(): DesktopTaskBoardPreferences = DesktopTaskBoardPreferences()

    /**
     * Default counts.
     */
    fun defaultCounts(): DesktopTaskBoardCounts = DesktopTaskBoardCounts()

    /**
     * Default content.
     */
    fun defaultContent(): DesktopTaskBoardContent = DesktopTaskBoardContent()

    /**
     * Default task filter counts.
     */
    fun defaultTaskFilterCounts(): Map<DesktopTaskFilter, Int> =
        /** Map of. */
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
     * Snapshot.
     */
    fun snapshot(
        preferences: DesktopTaskBoardPreferences = defaultPreferences(),
        counts: DesktopTaskBoardCounts = defaultCounts(),
        content: DesktopTaskBoardContent = defaultContent(),
    ): DesktopTaskBoardSnapshot = DesktopTaskBoardSnapshot(preferences = preferences, counts = counts, content = content)

    /**
     * Seeded catalog.
     */
    fun seededCatalog(now: LocalDateTime = LocalDateTime.now()): DesktopTaskCatalogSnapshot = seededDesktopTaskCatalog(now)

    /**
     * Board snapshot for catalog.
     */
    fun boardSnapshotForCatalog(
        /** Catalog. */
        catalog: DesktopTaskCatalogSnapshot,
        /** Preferences. */
        preferences: DesktopTaskBoardPreferences,
        errorMessage: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): DesktopTaskBoardSnapshot {
        /** If. */
        if (errorMessage != null) {
            return snapshot(
                preferences = preferences,
                counts = defaultCounts(),
                content =
                    /** Desktop task board content. */
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
            /** Sort habits. */
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
                /** Desktop task board content. */
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
        /** Now. */
        now: LocalDateTime,
    ): DesktopTaskBoardCounts {
        val today = now.toLocalDate()
        val counts =
            /** Map of. */
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
        /** Filter. */
        filter: DesktopTaskFilter,
        /** Now. */
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
        /** Sort option. */
        sortOption: DesktopTaskSortOption,
        /** Now. */
        now: LocalDateTime,
    ): List<DesktopTaskRecord> =
        /** When. */
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
        /** Sort option. */
        sortOption: DesktopHabitSortOption,
        /** Show archived habits. */
        showArchivedHabits: Boolean,
        /** Show completed habits. */
        showCompletedHabits: Boolean,
        /** Now. */
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
        /** Desktop task list item. */
        DesktopTaskListItem(
            id = record.id,
            title = record.title,
            status = record.status.replaceFirstChar(Char::uppercase),
            dueLabel = DesktopTaskRecordFormatting.dueLabel(record.dueAtIso),
            dimensionLabel = DesktopTaskRecordFormatting.normalizeDimension(record.lifeDimension),
            scoreLabel = DesktopTaskRecordFormatting.scoreLabel(record.currentScore),
        )

    private fun toHabitListItem(
        /** Record. */
        record: DesktopTaskRecord,
        /** Now. */
        now: LocalDateTime,
    ): DesktopHabitListItem =
        /** Desktop habit list item. */
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
     * Due label.
     */
    @Suppress("MagicNumber")
    fun dueLabel(dueAtIso: String?): String {
        val dueAt = parseDateTime(dueAtIso) ?: return "No due date"
        return "${dateFormatter.format(dueAt.toLocalDate())} ${dueAt.toLocalTime()}"
    }

    /**
     * Score label.
     */
    @Suppress("MagicNumber")
    fun scoreLabel(score: Double): String = "${(score * 100).toInt()}%"

    /**
     * Normalize dimension.
     */
    fun normalizeDimension(value: String?): String = value?.takeIf { it.isNotBlank() } ?: DEFAULT_DIMENSION

    /**
     * Parse date time.
     */
    fun parseDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)
}

private object DesktopTaskRecordPredicates {
    /**
     * Is active.
     */
    fun isActive(record: DesktopTaskRecord): Boolean = record.status == "active" || record.status == "pending"

    /**
     * Is actionable.
     */
    fun isActionable(record: DesktopTaskRecord): Boolean = record.status != "completed" && record.status != "archived"

    /**
     * Is due today.
     */
    fun isDueToday(
        /** Record. */
        record: DesktopTaskRecord,
        /** Today. */
        today: LocalDate,
    ): Boolean = DesktopTaskRecordFormatting.parseDateTime(record.dueAtIso)?.toLocalDate() == today

    /**
     * Is overdue.
     */
    fun isOverdue(
        /** Record. */
        record: DesktopTaskRecord,
        /** Now. */
        now: LocalDateTime,
    ): Boolean =
        (DesktopTaskRecordFormatting.parseDateTime(record.dueAtIso)?.isBefore(now) == true) &&
            record.status != "completed"

    /**
     * Is future.
     */
    fun isFuture(
        /** Record. */
        record: DesktopTaskRecord,
        /** Today. */
        today: LocalDate,
    ): Boolean {
        val dueAt = DesktopTaskRecordFormatting.parseDateTime(record.dueAtIso)
        return dueAt == null || dueAt.toLocalDate().isAfter(today)
    }

    /**
     * Habit status rank.
     */
    @Suppress("MagicNumber")
    fun habitStatusRank(record: DesktopTaskRecord): Int =
        when {
            record.completedToday -> 2
            record.status == "archived" -> 3
            else -> 1
        }

    /**
     * Habit status label.
     */
    fun habitStatusLabel(
        /** Record. */
        record: DesktopTaskRecord,
        /** Today. */
        today: LocalDate,
    ): String =
        when {
            record.status == "archived" -> "Archived"
            record.completedToday -> "Completed today"
            /** Is due today. */
            isDueToday(record, today) -> "Due today"
            else -> "Open"
        }
}
