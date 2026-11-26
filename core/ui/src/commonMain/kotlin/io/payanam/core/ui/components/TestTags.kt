//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

/** The `testTag` values a black-box UI flow addresses, published as view resource ids by
 * [exposeTestTagsAsResourceIds] so Maestro's `id:` selector matches them. These strings are test selectors:
 * do not rename a value, and the tags that predate this object keep their literal strings. */
object TestTags {
    /** First run: the three-slide tour, the focus-mode chooser, and the database gate that follows. */
    object Onboarding {
        const val PRIMARY_BUTTON = "onboarding.primary_button"

        const val SKIP_BUTTON = "onboarding.skip_button"

        const val FOCUS_MODE_CONTINUE_BUTTON = "onboarding.focus_mode_continue_button"

        const val FOCUS_MODE_SKIP_BUTTON = "onboarding.focus_mode_skip_button"
    }

    /** The database-init gate: the create/import chooser, its create form, and the dimension step. */
    object DatabaseInit {
        const val CREATE_DATABASE_BUTTON = "db_init.create_database_button"

        const val PASSPHRASE_FIELD = "db_init.passphrase_field"

        const val PASSPHRASE_CONFIRM_FIELD = "db_init.passphrase_confirm_field"

        const val PASSPHRASE_SUBMIT_BUTTON = "db_init.passphrase_submit_button"

        const val DIMENSION_SETUP_CONTINUE_BUTTON = "db_init.dimension_setup_continue_button"
    }

    /** The standalone passphrase-setup gate. */
    object PassphraseSetup {
        const val PASSPHRASE_FIELD = "passphrase_setup.passphrase_field"

        const val CONFIRM_FIELD = "passphrase_setup.confirm_field"

        const val SUBMIT_BUTTON = "passphrase_setup.submit_button"
    }

    /** The locked-database gate. */
    object PassphraseUnlock {
        const val PASSPHRASE_FIELD = "passphrase_unlock.passphrase_field"

        const val UNLOCK_BUTTON = "passphrase_unlock.unlock_button"
    }

    /** The settings screen's collapsible sections, which start collapsed and must be opened by a flow. */
    object Settings {
        const val DEBUG_CARD = "settings.debug_card"

        const val DEBUG_LOGGING_SWITCH = "settings.debug_logging_switch"

        const val DATA_MANAGEMENT_CARD = "settings.data_management_card"

        const val REMINDERS_CARD = "settings.reminders_card"

        const val EXACT_ALARM_ROW = "settings.exact_alarm_row"

        /** Auto-Backup card: header, enable switch, run-now action and the last-run line. */
        const val APPEARANCE_CARD = "settings.appearance_card"

        const val FOCUS_MODE_CARD = "settings.focus_mode_card"

        const val TIME_INSIGHTS_CARD = "settings.time_insights_card"

        /** One focus-mode preset (FocusModePreset.presetId). */
        fun focusPreset(presetId: String): String = "settings.focus.preset." + presetId

        /** One tab-visibility switch in the focus-mode card (the tab's route). */
        fun focusTab(route: String): String = "settings.focus.tab." + route

        /** One insights-visibility switch (its chart preference key). */
        fun insight(key: String): String = "settings.insights." + key

        const val DEFAULT_LANDING_CARD = "settings.default_landing_card"

        /** One theme rung (ThemeModeOption.key). */
        fun themeOption(key: String): String = "settings.theme." + key

        /** One default-landing route (the route name, e.g. notes). */
        fun landingOption(route: String): String = "settings.landing." + route

        const val AUTO_BACKUP_CARD = "settings.auto_backup_card"

        const val AUTO_BACKUP_SWITCH = "settings.auto_backup_switch"

        const val RUN_BACKUP_NOW = "settings.run_backup_now"

        const val BACKUP_LAST_RUN = "settings.backup_last_run"

        /** Rendered ONLY while a manual backup is in flight; absent whenever the card is idle, so a wait
         *  on it can never be satisfied by a persisted last-run line from an earlier run. */
        const val BACKUP_IN_PROGRESS = "settings.backup_in_progress"
    }

    /** The session-log export pair: the same two buttons are rendered by settings and by both DB gates. */
    object LogExport {
        const val EXPORT_LATEST_BUTTON = "log_export.export_latest_button"

        const val EXPORT_ALL_BUTTON = "log_export.export_all_button"
    }

    /** The bottom navigation bar; [tab] is what the bar calls, the constants are the strings it produces. */
    object Nav {
        const val TAB_PREFIX = "nav.tab."

        const val TAB_TASKS = "nav.tab.tasks"

        const val TAB_TIME = "nav.tab.time"

        const val TAB_NOTES = "nav.tab.notes"

        const val TAB_HABITS = "nav.tab.habits"

        const val TAB_JOURNAL = "nav.tab.journal"

        const val TAB_LENSES = "nav.tab.lenses"

        const val TAB_SETTINGS = "nav.tab.settings"

        /** The tag for the bottom-bar destination [route]. */
        fun tab(route: String): String = TAB_PREFIX + route
    }

    /** The Time screen's controls: the start action, the stop action, and a tracked block on the timeline. */
    object Time {
        const val START_TRACKING_FAB = "time.start_tracking_fab"

        const val STOP_TRACKING_BUTTON = "time.stop_tracking_button"

        const val ENTRY_BLOCK = "time.entry_block"

        /** The secondary FAB that opens the manual add-entry modal. */
        const val ADD_ENTRY_FAB = "time.add_entry_fab"
    }

    /** The tasks list, also drawn in `HABITS_ONLY` mode by the Habits tab, so the add action is one id. */
    object Tasks {
        const val ADD_BUTTON = "tasks.add_button"

        const val SEARCH_FIELD = "tasks.search_field"

        const val SORT_BUTTON = "tasks.sort_button"

        /** The All/Active/… chip row, one tag per filter (TaskFilter.key). */
        fun filterChip(key: String): String = "tasks.filter." + key

        /** One entry of the sort menu (TaskSortOption.name). */
        fun sortOption(name: String): String = "tasks.sort." + name

        const val ROW_PREFIX = "tasks.row."

        /** The tag for the task row whose id is [taskId]. */
        fun row(taskId: String): String = ROW_PREFIX + taskId
    }

    /** The task detail screen: the Archive action a flow taps to archive the open task. */
    object TaskDetail {
        /** Removes the task from the detail screen; the dialog's confirm is DELETE_CONFIRM. */
        const val DELETE_BUTTON = "task_detail.delete_button"

        const val DELETE_CONFIRM = "task_detail.delete_confirm"
        const val ARCHIVE_BUTTON = "task_detail.archive_button"

        /** The detail screen's three status actions; each opens the status-note dialog. */
        const val COMPLETE_BUTTON = "task_detail.complete_button"

        const val SKIP_BUTTON = "task_detail.skip_button"

        const val MISS_BUTTON = "task_detail.miss_button"

        /** Opens the edit screen. */
        const val EDIT_BUTTON = "task_detail.edit_button"
    }

    /** The status note dialog the task detail screen opens for Complete, Skip, Miss, and the reminder's More action. */
    object StatusNote {
        const val DIALOG = "status_note.dialog"

        /** The dialog's own confirm and cancel; the confirm reuses the action's copy, hence a tag. */
        const val CONFIRM_BUTTON = "status_note.confirm_button"

        const val DISMISS_BUTTON = "status_note.dismiss_button"
    }

    /** The habits list. */
    object Notes {
        const val ADD_BUTTON = "notes.add_button"

        const val SEARCH_FIELD = "notes.search_field"

        const val TITLE_FIELD = "notes.title_field"

        const val DETAILS_FIELD = "notes.details_field"

        const val TAGS_FIELD = "notes.tags_field"

        const val SAVE_BUTTON = "notes.save_button"

        const val DELETE_BUTTON = "notes.delete_button"

        const val DELETE_CONFIRM = "notes.delete_confirm"
    }

    object Habits {
        const val ROW_PREFIX = "habits.row."

        const val SEARCH_BUTTON = "habits.search_button"

        const val SEARCH_FIELD = "habits.search_field"

        const val SORT_BUTTON = "habits.sort_button"

        const val FILTER_BUTTON = "habits.filter_button"

        /** One entry of the sort menu (HabitSortOption.name). */
        fun sortOption(name: String): String = "habits.sort." + name

        /** One row of the filter menu: hide_completed, hide_all_marked, due_today, show_archived. */
        fun filterOption(key: String): String = "habits.filter." + key

        /** The tag for the habit row whose id is [taskId]. */
        fun row(taskId: String): String = ROW_PREFIX + taskId
    }

    /** The add-task form, which is also the habit form: the Habits tab's add action opens this screen. */
    object TaskAdd {
        const val TITLE_FIELD = "task_add.title_field"

        const val TASK_TYPE_ONE_TIME = "task_add.task_type.one_time"

        const val TASK_TYPE_RECURRING = "task_add.task_type.recurring"

        const val RECURRENCE_CHANGE_BUTTON = "task_add.recurrence_change_button"

        const val DUE_DATE_BUTTON = "task_add.due_date_button"

        const val DUE_DATE_CONFIRM_BUTTON = "task_add.due_date_confirm_button"

        const val DUE_TIME_BUTTON = "task_add.due_time_button"

        const val DUE_TIME_CONFIRM_BUTTON = "task_add.due_time_confirm_button"

        const val DUE_TIME_INPUT_TOGGLE = "task_add.due_time_input_toggle"

        const val DUE_TIME_HOUR_FIELD = "task_add.due_time_hour_field"

        const val DUE_TIME_MINUTE_FIELD = "task_add.due_time_minute_field"

        const val REMINDER_MODE_AUTO = "task_add.reminder_mode.auto"

        const val REMINDER_MODE_CUSTOM = "task_add.reminder_mode.custom"

        const val REMINDER_MODE_OFF = "task_add.reminder_mode.off"

        const val REMINDER_CUSTOM_MINUTES_FIELD = "task_add.reminder_custom_minutes_field"

        const val SAVE_BUTTON = "task_add.save_button"
    }

    /** The recurrence-rule dialog, shared by the add and edit forms, so its prefix is screen-neutral. */
    /** The edit form's own controls; the add form's tags do not apply to this screen. */
    object TaskEdit {
        const val TITLE_FIELD = "task_edit.title_field"

        const val SAVE_BUTTON = "task_edit.save_button"
    }

    object Recurrence {
        const val PRESET_DAILY = "recurrence.preset.daily"

        const val PRESET_WEEKDAYS = "recurrence.preset.weekdays"

        const val PRESET_WEEKLY = "recurrence.preset.weekly"

        const val CONFIRM_BUTTON = "recurrence.confirm_button"
    }

    /** The offer raised after an action committed while notifications are denied. */
    object NotificationPermission {
        const val ALLOW_BUTTON = "notification_permission.allow_button"

        const val DISMISS_BUTTON = "notification_permission.dismiss_button"

        const val SETTINGS_BUTTON = "notification_permission.settings_button"
    }
}
