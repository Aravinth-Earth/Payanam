//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

/**
 * A [UnifiedLogger] that discards every entry.
 *
 * This is the default for platform-neutral library code that must not require a logger to exist.
 * [AppLogger.current] starts as this, so a log call from a library class can never crash the caller
 * and can never reach for a platform singleton — which is exactly what used to happen when
 * `core/domain` and `core/scoring` called the Android singleton directly and their unguarded
 * property initialisers threw on a plain JVM.
 */
object NoOpUnifiedLogger : UnifiedLogger {
    override fun d(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) = Unit

    override fun i(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) = Unit

    override fun w(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) = Unit

    override fun e(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
    ) = Unit

    override fun eSync(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
    ) = Unit
}
