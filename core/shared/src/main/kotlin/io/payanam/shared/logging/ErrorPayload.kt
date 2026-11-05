//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

/**
 * Builds the error portion of a log entry's data payload.
 *
 * Shared by both platform logger implementations
 * ([io.payanam.common.logging.UnifiedLogger] and [io.payanam.desktop.UnifiedLogger])
 * so stack-frame truncation, cause-chain walking, and the message fallback are
 * identical on every platform.
 */
object ErrorPayload {
    /** Stack frames retained for a normal error entry. */
    const val MAX_STACK_FRAMES = 3

    /** Stack frames retained for a crash-imminent synchronous entry. */
    const val MAX_CRASH_STACK_FRAMES = 64

    /** Maximum depth of the cause chain appended to an error entry. */
    const val MAX_CAUSE_DEPTH = 8

    /** Data-map key carrying the error message. */
    const val KEY_ERROR = "error"

    /** Data-map key carrying the exception class name. */
    const val KEY_EXCEPTION = "exception"

    /** Data-map key carrying the joined stack frames. */
    const val KEY_STACK = "stack"

    /** Data-map key carrying the joined cause chain. */
    const val KEY_CAUSE_CHAIN = "causeChain"

    /**
     * Every key [build] can add.
     *
     * A sanitizer must recognise these to treat logger-built diagnostics differently from
     * caller-supplied data. Exposed here so the names have one home: hard-coding them at the
     * sanitizer would let a rename here silently stop the sanitizer matching them.
     */
    val diagnosticKeys: Set<String> = setOf(KEY_ERROR, KEY_EXCEPTION, KEY_STACK, KEY_CAUSE_CHAIN)

    /**
     * Returns [data] unchanged when [error] is null; otherwise a copy enriched with an error
     * message, exception class, stack and (when present) causeChain.
     */
    fun build(
        error: Throwable?,
        data: Map<String, Any?>,
        stackFrameLimit: Int,
    ): Map<String, Any?> {
        if (error == null) return data

        val combined = data.toMutableMap()
        combined[KEY_ERROR] = error.message ?: error.javaClass.simpleName
        combined[KEY_EXCEPTION] = error.javaClass.simpleName
        combined[KEY_STACK] =
            error.stackTrace.take(stackFrameLimit).joinToString(" > ") {
                "${it.fileName}:${it.lineNumber}:${it.methodName}"
            }
        val causeTrace = buildCauseChain(error)
        if (causeTrace.isNotEmpty()) {
            combined[KEY_CAUSE_CHAIN] = causeTrace
        }
        return combined
    }

    private fun buildCauseChain(error: Throwable): String =
        buildString {
            var current: Throwable? = error.cause
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (isNotEmpty()) append(" || ")
                append("${current.javaClass.simpleName}:${current.message ?: "no-message"}")
                current = current.cause
                depth++
            }
        }
}
