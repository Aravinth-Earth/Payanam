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

    /** The tasks list, also drawn in `HABITS_ONLY` mode by the Habits tab, so the add action is one id. */
    object Tasks {
        const val ADD_BUTTON = "tasks.add_button"

        const val ROW_PREFIX = "tasks.row."

        /** The tag for the task row whose id is [taskId]. */
        fun row(taskId: String): String = ROW_PREFIX + taskId
    }

    /** The task detail screen: the Archive action a flow taps to archive the open task. */
    object TaskDetail {
        const val ARCHIVE_BUTTON = "task_detail.archive_button"
    }

    /** The status note dialog the task detail screen opens for Complete, Skip, Miss, and the reminder's More action. */
    object StatusNote {
        const val DIALOG = "status_note.dialog"
    }

    /** The habits list. */
    object Habits {
        const val ROW_PREFIX = "habits.row."

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
