//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

import io.payanam.presentation.tasks.TaskFilter
import io.payanam.shared.settings.BackupInterval
import io.payanam.shared.settings.FocusModePreset

/**
 * The preference rows whose stored value is a closed literal domain, keyed to where that domain is
 * declared, so a settings write can log the value without logging user text.
 *
 * WHY A TABLE AND NOT A `LogSanitizer` EXEMPTION: the sanitizer's `contentClassTokens` contains
 * `"value"` and matches it as a SUBSTRING, so `AppPreferencesWriter.setSetting` logs
 * `value="<redacted>"` for every row. That rule is the repo's committed one - a log must not carry user
 * content - and it stays exactly as it is. What was missing is a positive statement about the rows that
 * cannot carry user text: an enum, a Boolean, or a number. This table is that statement. A key is
 * admitted only with the declaration that closes its domain, and [literalFor] re-checks the value
 * against that domain, so a caller that passes free text for an admitted key still logs no value.
 *
 * WHY THE FIELD IS `enumLiteral` AND NOT `enumValue`: the sanitizer classifies a data value by whether
 * its KEY contains a content token, and `"value"` is one of them. `"enumValue"` normalizes to
 * `"enumvalue"`, which CONTAINS `"value"`, so that name would be redacted. `"enumLiteral"` contains no
 * entry of `contentClassTokens` or `secretClassTokens` and does not end in `id`, so
 * `LogSanitizer.sanitizeData` returns it unchanged - pinned by
 * `LogSanitizerTest.sanitizeData_keepsTheEnumLiteralFieldButWouldRedactAnEnumValueField`.
 */
object NonContentPreferenceValues {
    /**
     * The data-map field [AppPreferencesWriter.setSetting] adds for an admitted row.
     *
     * A data field rather than a message fragment: a message is free text the sanitizer shape-checks for
     * paths, emails and credentials, and a value drawn from a closed set needs none of that.
     */
    const val FIELD = "enumLiteral"

    private const val TRUE = "true"
    private const val FALSE = "false"

    /**
     * The values `AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE` may hold: the app's top-level routes.
     *
     * `SettingsDefaultLandingSection` (`:core:ui`) offers exactly its six landing routes
     * (`TOP_LEVEL_ROUTES` - tasks, habits, time, journal, notes, lenses), and the dedicated writers store
     * `"time"` and `"tasks"`. Every one of those is in [AppPreferencesWriter.ALL_TAB_ROUTES], which is
     * also the list the focus-mode rewrite writes, so the domain is this class's own route vocabulary
     * rather than a second copy of it. `settings` is included because it is a real route row; a landing
     * on it would be odd, but it is still not user text.
     */
    private val launchRoutes = AppPreferencesWriter.ALL_TAB_ROUTES.toSet()

    /**
     * Key -> the exact literals its row may hold, each with the declaration that closes the domain.
     *
     * A key whose value is a free string (`auto_backup_last_run`'s timestamp, a dimension label, an
     * exported path) is deliberately absent: no amount of key naming makes those safe.
     */
    private val enumDomains: Map<String, Set<String>> =
        mapOf(
            // ThemeModeOption (core/ui .../core/ui/preferences/AppPreferencesState.kt): SYSTEM("system"),
            // LIGHT("light"), DARK("dark").
            AppPreferenceKeys.KEY_THEME_MODE to setOf("system", "light", "dark"),
            // AppLanguageOption: SYSTEM("system"), ENGLISH("en"), TAMIL("ta").
            AppPreferenceKeys.KEY_APP_LANGUAGE to setOf("system", "en", "ta"),
            // FontFamilyOption: its four stored keys. The "roboto"/"nunito"/... names its fromKey also
            // accepts are read-side migrations of rows older builds wrote; this writer stores option keys.
            AppPreferenceKeys.KEY_FONT_FAMILY to setOf("sans-serif", "monospace", "serif", "cursive"),
            // TimeFormatOption: TWENTY_FOUR("24h"), TWELVE("12h").
            AppPreferenceKeys.KEY_TIME_FORMAT to setOf("24h", "12h"),
            // BackupInterval, from the enum itself, so a cadence added there is admitted without a copy.
            AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL to BackupInterval.entries.map { interval -> interval.key }.toSet(),
            // FocusModePreset.presetId, from the enum itself.
            AppPreferenceKeys.KEY_ACTIVE_PRESET to FocusModePreset.entries.map { preset -> preset.presetId }.toSet(),
            AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE to launchRoutes,
            // TaskFilter, from the enum itself: the same eight keys are the stored filter and the landing
            // filter, which is why both rows share one domain.
            AppPreferenceKeys.KEY_TASK_FILTER_OPTION to TaskFilter.entries.map { filter -> filter.key }.toSet(),
            AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER to TaskFilter.entries.map { filter -> filter.key }.toSet(),
        )

    /**
     * Keys whose row is written from a Kotlin `Boolean`, so `true`/`false` are the only literals.
     *
     * The chart set is the writer's own `CHART_VISIBILITY_KEYS`, not a prefix rule: a chart row is a
     * toggle, and the shared set is what `setChartVisibility` validates against.
     */
    private val booleanKeys: Set<String> =
        setOf(
            AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED,
            AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED,
            AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED,
            AppPreferenceKeys.KEY_AUTO_TRACK_HABIT_TIME,
            AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED,
        ) + AppPreferenceKeys.CHART_VISIBILITY_KEYS

    /**
     * Prefixes whose rows hold one Boolean per route / dimension: the suffix names the ROW, the value is
     * still only `true`/`false`, which is what [literalFor] checks.
     */
    private val booleanPrefixes: List<String> =
        listOf(
            AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX,
            AppPreferenceKeys.KEY_AUTO_TRACK_DIMENSION_PREFIX,
        )

    /** Keys whose row is a number: an Int the writer clamps, or the `%.2f`-formatted hour height. */
    private val numericKeys: Set<String> =
        setOf(
            AppPreferenceKeys.KEY_DAY_BOUNDARY_HOUR,
            AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT,
            AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP,
        )

    /** A plain decimal, which is every numeric row this writer stores; no text can match it. */
    private val plainNumber = Regex("-?\\d{1,3}(?:\\.\\d{1,2})?")

    /**
     * The literal [value] may be logged as, or null when the row is not provably non-content.
     *
     * [key] must be admitted by name AND [value] must sit inside that key's declared domain. An admitted
     * key carrying text a caller invented returns null, which is the point of checking both: the table
     * alone would trust every caller of a `String` parameter such as [AppPreferencesWriter.setThemeMode].
     */
    fun literalFor(
        key: String,
        value: String,
    ): String? =
        when {
            enumDomains.containsKey(key) -> value.takeIf { literal -> literal in enumDomains.getValue(key) }
            key in booleanKeys || booleanPrefixes.any { prefix -> key.startsWith(prefix) } ->
                value.takeIf { literal -> literal == TRUE || literal == FALSE }

            else -> value.takeIf { literal -> key in numericKeys && plainNumber.matches(literal) }
        }
}
