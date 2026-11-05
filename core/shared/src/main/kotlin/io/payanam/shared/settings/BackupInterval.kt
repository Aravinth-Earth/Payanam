//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

/**
 * Auto-backup cadence options, persisted by [key] with [minutes] for scheduling.
 *
 * WHY THIS LIVES HERE AND NOT IN `:core:ui`: this enum is platform-neutral - a key, a duration and a
 * resolver - yet it sat in `io.payanam.core.ui.preferences`, which `:core:ui` exposes to `:app` and
 * `:desktop`. `:core:ui` declares `api(project(":core:presentation"))`, so that layer sits BELOW it and
 * can never import a type from it: a shared ViewModel promoted into `:core:presentation` could not
 * name this enum, so a Gradle dependency cycle was the only way to give it the one.
 * `:core:shared` is the module that edge was already forced to create - it is a plain JVM module with
 * NO project dependencies at all, and both sides of that cycle already depend on it. It already holds
 * `FocusModePreset`, the same shape of type - a curated settings enum `:core:ui` renders - so this is a
 * sibling, not a new home.
 *
 * NOTHING ABOUT THE VALUES MOVED: the constant names, the [key] each one persists, the [minutes] each
 * one schedules and the declaration order are all unchanged, because the order IS the value - the
 * persisted rows are keyed by [key], not by ordinal, and every reader resolves through [fromKey].
 * Nothing anywhere switches on `.name` or calls `.valueOf`, so there is no ordinal- or name-based
 * reflection that a package change could move under it.
 *
 * `:core:ui` keeps a typealias back to this, so every existing caller - and every caller the promotion
 * will add - resolves the same type through the package it already imports.
 */
enum class BackupInterval(
    val key: String,
    val minutes: Long,
) {
    FIFTEEN_MIN("15m", 15),
    THIRTY_MIN("30m", 30),
    SIXTY_MIN("60m", 60),
    TWO_HOURS("2h", 120),
    SIX_HOURS("6h", 360),
    TWELVE_HOURS("12h", 720),
    DAILY("24h", 1440),
    ;

    companion object {
        /**
         * Resolves the option whose [key] matches, or null when unknown.
         */
        fun fromKey(key: String?): BackupInterval? = entries.find { it.key == key }
    }
}
