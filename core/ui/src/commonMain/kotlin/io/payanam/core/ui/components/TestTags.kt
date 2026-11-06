//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

/**
 * The `testTag` values a black-box UI flow addresses, in one place.
 *
 * WHY THIS OBJECT EXISTS: a flow driving the app from outside cannot match the app's own copy - the
 * text differs between EN and TA, and the in-app language switch changes it mid-session - so every
 * control a flow touches is addressed by a tag instead. A `testTag` is invisible outside Compose on
 * its own; [exposeTestTagsAsResourceIds] at the app root publishes each one as a view resource id,
 * which is what UIAutomator and Maestro's `id:` selector match.
 *
 * HOW THE VALUES ARE NAMED: `screen.element` or `screen.element.qualifier`, lowercase snake_case, no
 * locale words, and the screen prefix names the flow's step rather than the Kotlin file that draws it.
 *
 * THE TAGS THAT PREDATE THIS OBJECT KEEP THEIR LITERAL VALUES (`habit_checkmark_panel`,
 * `settings_backup_interval`, `dimension_icon_<key>`, ...). The in-process Compose suite asserts on
 * those strings, so renaming one to fit the convention below would break a passing test for cosmetics.
 */
object TestTags {
    /** First run: the three-slide tour, the focus-mode chooser, and the database gate that follows. */
    object Onboarding {
        /** The tour's single action: "next" on every slide but the last, where it starts the app. */
        const val PRIMARY_BUTTON = "onboarding.primary_button"

        /** The tour's skip link, present on every slide but the last. */
        const val SKIP_BUTTON = "onboarding.skip_button"

        /** The focus-mode chooser's continue, which submits the preset it has selected. */
        const val FOCUS_MODE_CONTINUE_BUTTON = "onboarding.focus_mode_continue_button"

        /** The focus-mode chooser's skip, which submits the default preset. */
        const val FOCUS_MODE_SKIP_BUTTON = "onboarding.focus_mode_skip_button"
    }

    /** The database-init gate: the create/import chooser, its create form, and the dimension step. */
    object DatabaseInit {
        /** "Create new empty database", on both branches that offer it. */
        const val CREATE_DATABASE_BUTTON = "db_init.create_database_button"

        /** The create form's passphrase field. */
        const val PASSPHRASE_FIELD = "db_init.passphrase_field"

        /** The create form's passphrase confirmation field. */
        const val PASSPHRASE_CONFIRM_FIELD = "db_init.passphrase_confirm_field"

        /** The create form's submit button. */
        const val PASSPHRASE_SUBMIT_BUTTON = "db_init.passphrase_submit_button"

        /** The mandatory dimension step's continue, which creates the database. */
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
        /** The Debug section header: the tap that reveals the logging switch and the log export. */
        const val DEBUG_CARD = "settings.debug_card"

        /** The debug-logging on/off switch inside the Debug section. */
        const val DEBUG_LOGGING_SWITCH = "settings.debug_logging_switch"

        /** The Data Management section header. */
        const val DATA_MANAGEMENT_CARD = "settings.data_management_card"
    }

    /**
     * The session-log export pair.
     *
     * One group rather than one per screen because the SAME two buttons are rendered by settings and by
     * the two DB gates that offer a log export before the app is open.
     */
    object LogExport {
        const val EXPORT_LATEST_BUTTON = "log_export.export_latest_button"

        const val EXPORT_ALL_BUTTON = "log_export.export_all_button"
    }

    /**
     * The bottom navigation bar.
     *
     * The bar is a loop over the tab set, so [tab] is what the bar calls; the constants below are the
     * same strings it produces, listed so a flow has a greppable set of destinations.
     */
    object Nav {
        const val TAB_PREFIX = "nav.tab."

        const val TAB_TASKS = "nav.tab.tasks"

        const val TAB_TIME = "nav.tab.time"

        const val TAB_NOTES = "nav.tab.notes"

        const val TAB_HABITS = "nav.tab.habits"

        const val TAB_JOURNAL = "nav.tab.journal"

        const val TAB_LENSES = "nav.tab.lenses"

        const val TAB_SETTINGS = "nav.tab.settings"

        /** The id of the destination whose route is [route]. */
        fun tab(route: String): String = TAB_PREFIX + route
    }

    /** The tasks list, rendered by the shared screen that also draws the Habits tab. */
    object Tasks {
        /**
         * The add action.
         *
         * ONE id for both surfaces: the Habits tab renders the same screen in `HABITS_ONLY` mode, so its
         * floating action is the same control calling the same navigation.
         */
        const val ADD_BUTTON = "tasks.add_button"

        /** Per-row prefix; the id is [row]. */
        const val ROW_PREFIX = "tasks.row."

        /** The id of the row for [taskId]. */
        fun row(taskId: String): String = ROW_PREFIX + taskId
    }

    /** The habits list. */
    object Habits {
        /** Per-row prefix; the id is [row]. */
        const val ROW_PREFIX = "habits.row."

        /** The id of the card for the habit stored as [taskId]. */
        fun row(taskId: String): String = ROW_PREFIX + taskId
    }

    /**
     * The add-task form, which is also the habit form: the Habits tab's add action opens this same
     * screen, so a habit flow uses these tags instead of a second set addressing one composable twice.
     */
    object TaskAdd {
        const val TITLE_FIELD = "task_add.title_field"

        /** One-time, i.e. recurrence off. */
        const val TASK_TYPE_ONE_TIME = "task_add.task_type.one_time"

        /** Recurring, i.e. recurrence on: what makes the saved task a habit. */
        const val TASK_TYPE_RECURRING = "task_add.task_type.recurring"

        /** Reopens the rule picker once recurrence is on. */
        const val RECURRENCE_CHANGE_BUTTON = "task_add.recurrence_change_button"

        /** Opens the date picker; the form already carries today's date. */
        const val DUE_DATE_BUTTON = "task_add.due_date_button"

        const val DUE_DATE_CONFIRM_BUTTON = "task_add.due_date_confirm_button"

        /** Opens the time picker; the form already carries the current time. */
        const val DUE_TIME_BUTTON = "task_add.due_time_button"

        const val DUE_TIME_CONFIRM_BUTTON = "task_add.due_time_confirm_button"

        /** Switches the time dialog between the dial and typed hour/minute entry. */
        const val DUE_TIME_INPUT_TOGGLE = "task_add.due_time_input_toggle"

        /** The typed hour, shown instead of the dial in the time dialog's keyboard mode. */
        const val DUE_TIME_HOUR_FIELD = "task_add.due_time_hour_field"

        /** The typed minute, shown instead of the dial in the time dialog's keyboard mode. */
        const val DUE_TIME_MINUTE_FIELD = "task_add.due_time_minute_field"

        /** Reminder offset follows the due time. */
        const val REMINDER_MODE_AUTO = "task_add.reminder_mode.auto"

        /** Reminder offset is a fixed number of minutes before the due time. */
        const val REMINDER_MODE_CUSTOM = "task_add.reminder_mode.custom"

        const val REMINDER_MODE_OFF = "task_add.reminder_mode.off"

        const val REMINDER_CUSTOM_MINUTES_FIELD = "task_add.reminder_custom_minutes_field"

        const val SAVE_BUTTON = "task_add.save_button"
    }

    /**
     * The recurrence-rule dialog.
     *
     * Screen-neutral because the add form and the edit form open the SAME dialog, so a prefix tied to
     * one of them would be a lie on the other.
     */
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

        /** The route to the system notification settings, offered in the dialog's body. */
        const val SETTINGS_BUTTON = "notification_permission.settings_button"
    }
}
