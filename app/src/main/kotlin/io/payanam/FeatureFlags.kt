//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam

import io.payanam.common.logging.UnifiedLogger

/**
 * Compile-time feature flag surface. All flags map to BuildConfig fields defined in
 * app/build.gradle.kts (defaultConfig block).
 *
 * Full reference — module status, gate locations, coverage exclusions, regression test:
 * docs/feature-flags-reference.md
 */
object FeatureFlags {
    private val logger = UnifiedLogger.getInstance()

    /** Minimal mode enabled. */
    val minimalModeEnabled: Boolean = BuildConfig.MINIMAL_MODE
    /** Scoring enabled. */
    val scoringEnabled: Boolean = BuildConfig.SCORING_ENABLED
    /** Recurring tasks enabled. */
    val recurringTasksEnabled: Boolean = BuildConfig.RECURRING_TASKS_ENABLED
    /** Reminders enabled. */
    val remindersEnabled: Boolean = BuildConfig.REMINDERS_ENABLED
    /** Tags enabled. */
    val tagsEnabled: Boolean = BuildConfig.TAGS_ENABLED
    /** Plans cta enabled. */
    val plansCtaEnabled: Boolean = BuildConfig.PLANS_CTA_ENABLED
    /** Focus mode settings enabled. */
    val focusModeSettingsEnabled: Boolean = BuildConfig.FOCUS_MODE_SETTINGS_ENABLED
    /** Score settings enabled. */
    val scoreSettingsEnabled: Boolean = BuildConfig.SCORE_SETTINGS_ENABLED

    init {
        logger.i(
            "FeatureFlags",
            "Loaded feature flags",
            /** Map of. */
            mapOf(
                "minimalModeEnabled" to minimalModeEnabled.toString(),
                "scoringEnabled" to scoringEnabled.toString(),
                "recurringTasksEnabled" to recurringTasksEnabled.toString(),
                "remindersEnabled" to remindersEnabled.toString(),
                "tagsEnabled" to tagsEnabled.toString(),
                "plansCtaEnabled" to plansCtaEnabled.toString(),
                "focusModeSettingsEnabled" to focusModeSettingsEnabled.toString(),
                "scoreSettingsEnabled" to scoreSettingsEnabled.toString(),
            ),
        )
    }
}
