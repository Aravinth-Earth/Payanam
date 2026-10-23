//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.flags

/**
 * The compile-time feature flags a shared screen or a shared ViewModel needs.
 *
 * WHY THIS TYPE LIVES IN `:core:presentation` AND NOT IN THE UI MODULE:
 *
 * The flags themselves are Android build config - `io.payanam.FeatureFlags` reads `BuildConfig` fields defined
 * in `app/build.gradle.kts` - so nothing shared can read them directly. Both shared layers need them: a
 * composable reads them to lay itself out, and a ViewModel reads them to decide which work to schedule. Only
 * one declaration may exist, or the two layers could disagree; it lives here because a Compose module cannot
 * be seen from a plain-JVM one, and `:core:ui` already depends on this module.
 *
 * Defaults are the FULL feature set: that is what a platform without build flags (the desktop) shows, and it
 * keeps a screen renderable and a ViewModel constructible in a preview or a test without a provider.
 */
data class FeatureFlagsState(
    /** Hides the planning/lens surfaces a minimal build ships without. */
    val minimalModeEnabled: Boolean = false,
    /** Gates recurring-task (habit) behaviour. */
    val recurringTasksEnabled: Boolean = true,
    /** Gates score computation and the score surfaces. */
    val scoringEnabled: Boolean = true,
    /** Gates reminder scheduling. A platform with no reminder channel configures this off. */
    val remindersEnabled: Boolean = true,
    /** Whether the tag editor is offered. Android reads it from `BuildConfig.TAGS_ENABLED`. */
    val tagsEnabled: Boolean = true,
    /**
     * Whether the Time screen offers the day-plan affordances. Android reads it from
     * `BuildConfig.PLANS_CTA_ENABLED`.
     *
     * Enabled by default for the same reason as the flags above: the desktop has no build config, and a
     * default of false would silently hide a working surface there rather than fail loudly.
     */
    val plansCtaEnabled: Boolean = true,
    /**
     * Whether the settings section that edits the focus-mode rows is offered. Android reads it from
     * `BuildConfig.FOCUS_MODE_SETTINGS_ENABLED`.
     *
     * A compile-time decision, like the flags above, and it belongs here rather than at each mount site for
     * the reason this type exists: a screen that hid the section on one platform and offered it on the other
     * would be a divergence no test could see.
     */
    val focusModeSettingsEnabled: Boolean = true,
    /**
     * Whether the settings section that edits the score configuration is offered. Android reads it from
     * `BuildConfig.SCORE_SETTINGS_ENABLED`.
     *
     * Note the shared actions object defaults this to false while this holder defaults it to true: the two are
     * asked at different layers. This holder answers "what does this build ship", and the default here is the
     * full feature set, matching every other flag on this type.
     */
    val scoreSettingsEnabled: Boolean = true,
)

/**
 * The process-wide holder for the platform's [FeatureFlagsState].
 *
 * Shaped like [io.payanam.shared.logging.AppLogger] and
 * [io.payanam.presentation.data.LocalDataLayer], the two conventions this repo already uses for
 * platform-supplied values that platform-neutral code must reach: the platform installs its state once, from
 * its startup path, and everything above reads [current].
 *
 * A composable in `:core:ui` reads the same state through `LocalFeatureFlags`, its composition local. That is
 * one value with two delivery paths rather than two conventions: a plain class cannot read a composition
 * local, and re-deriving the flags inside a composable would give the two layers a way to disagree.
 */
object PlatformFeatureFlags {
    /**
     * The flags in force for this process.
     *
     * Defaults to the full feature set rather than to a placeholder, because "unstated" for a capability flag
     * would otherwise mean "feature removed" - and a build that forgot to install them would run with
     * working surfaces disabled rather than with the ones it was compiled for.
     */
    @Volatile
    var current: FeatureFlagsState = FeatureFlagsState()
        private set

    /** Installs [flags] process-wide. Call once from the app's startup path. */
    fun install(flags: FeatureFlagsState) {
        current = flags
    }

    /**
     * Restores the default full set.
     *
     * Test-only, and called in **both** `@Before` and `@After` for the same reason `LocalDataLayer` documents:
     * test classes in a module share one JVM, so a leaked install would let a later class assert against flags
     * it never set.
     */
    fun resetForTests() {
        current = FeatureFlagsState()
    }
}
