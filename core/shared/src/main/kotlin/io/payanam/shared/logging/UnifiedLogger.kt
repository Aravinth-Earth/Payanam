//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

/**
 * Cross-platform logging contract.
 *
 * Android implementation: [io.payanam.common.logging.UnifiedLogger]
 * Desktop implementation: [io.payanam.desktop.UnifiedLogger]
 * No-op implementation: [NoOpUnifiedLogger] — the default for platform-neutral library code, installed
 * via [AppLogger].
 *
 * All parameters use Kotlin stdlib types — no Android or platform-specific imports.
 * `data` is non-null with default `emptyMap()` (callers pass nothing when no extra data).
 */
interface UnifiedLogger {
    /**
     * Logs a DEBUG-level entry. Implementations may gate this (e.g. Android writes it
     * only when debug logging is enabled) — callers should treat it as best-effort detail.
     */
    fun d(
        source: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    )

    /** Logs an INFO-level entry. */
    fun i(
        source: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    )

    /** Logs a WARN-level entry. */
    fun w(
        source: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    )

    /**
     * Logs an ERROR-level entry, attaching [error]'s class, message, stack, and cause chain.
     */
    fun e(
        source: String,
        message: String,
        error: Throwable? = null,
        data: Map<String, Any?> = emptyMap(),
    )

    /**
     * Synchronously logs an ERROR entry, bypassing any async buffer.
     * Used where a crash is imminent and the log must hit disk immediately.
     */
    fun eSync(
        source: String,
        message: String,
        error: Throwable? = null,
        data: Map<String, Any?> = emptyMap(),
    )
}
