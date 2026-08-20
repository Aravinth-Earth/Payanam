//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming", "MagicNumber")

package io.payanam.ui.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import io.payanam.common.logging.UnifiedLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val PERF_SOURCE = "PerfBaseline"
private const val RECOMPOSITION_LOG_INTERVAL = 25

/**
 * PerfBaselineTelemetry.
 */
object PerfBaselineTelemetry {
    private val logger = UnifiedLogger.getInstance()
    private val onceEvents = ConcurrentHashMap.newKeySet<String>()
    private val queryCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val recompositionCounters = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Reset.
     */
    fun reset() {
        onceEvents.clear()
        queryCounters.clear()
        recompositionCounters.clear()
        logger.i(PERF_SOURCE, "PERF_BASELINE_EVENT screen=perf event=telemetry_reset tMs=${android.os.SystemClock.elapsedRealtime()}")
    }

    /**
     * Mark event.
     */
    fun markEvent(
        /** Screen. */
        screen: String,
        /** Event. */
        event: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        /** Payload. */
        val payload = data.toMutableMap()
        payload["screen"] = screen
        payload["event"] = event
        payload["tMs"] = android.os.SystemClock.elapsedRealtime()
        /** Message data. */
        val messageData = payload.entries.joinToString(" ") { (key, value) -> "$key=$value" }
        logger.i(PERF_SOURCE, "PERF_BASELINE_EVENT $messageData", payload)
    }

    /**
     * Mark event once.
     */
    fun markEventOnce(
        /** Key. */
        key: String,
        /** Screen. */
        screen: String,
        /** Event. */
        event: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        /** If. */
        if (!onceEvents.add(key)) return
        /** Mark event. */
        markEvent(screen = screen, event = event, data = data)
    }

    /**
     * Increment query.
     */
    fun incrementQuery(
        /** Screen. */
        screen: String,
        /** Source. */
        source: String,
        amount: Int = 1,
    ): Int {
        /** Counter key. */
        val counterKey = "$screen::$source"
        /** Total. */
        val total = queryCounters.getOrPut(counterKey) { AtomicInteger(0) }.addAndGet(amount)
        logger.i(
            /** Perf source. */
            PERF_SOURCE,
            "PERF_BASELINE_QUERY screen=$screen source=$source delta=$amount total=$total tMs=${android.os.SystemClock.elapsedRealtime()}",
            /** Map of. */
            mapOf(
                "screen" to screen,
                "source" to source,
                "delta" to amount,
                "total" to total,
            ),
        )
        return total
    }

    /**
     * Increment recomposition.
     */
    fun incrementRecomposition(
        /** Screen. */
        screen: String,
        /** Section. */
        section: String,
    ): Int {
        /** Counter key. */
        val counterKey = "$screen::$section"
        /** Total. */
        val total = recompositionCounters.getOrPut(counterKey) { AtomicInteger(0) }.incrementAndGet()
        /** If. */
        if (total <= 3 || total % RECOMPOSITION_LOG_INTERVAL == 0) {
            logger.i(
                /** Perf source. */
                PERF_SOURCE,
                "PERF_BASELINE_RECOMPOSITION screen=$screen section=$section total=$total tMs=${android.os.SystemClock.elapsedRealtime()}",
                /** Map of. */
                mapOf("screen" to screen, "section" to section, "total" to total),
            )
        }
        return total
    }
}

@Composable
/**
 * Track recomposition.
 */
fun TrackRecomposition(screen: String, section: String) {
    SideEffect {
        PerfBaselineTelemetry.incrementRecomposition(screen = screen, section = section)
    }
}
