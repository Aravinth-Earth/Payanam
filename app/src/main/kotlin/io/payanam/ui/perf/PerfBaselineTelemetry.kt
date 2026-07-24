//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.ui.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import io.payanam.common.logging.UnifiedLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val PERF_SOURCE = "PerfBaseline"
private const val RECOMPOSITION_LOG_INTERVAL = 25

object PerfBaselineTelemetry {
    private val logger = UnifiedLogger.getInstance()
    private val onceEvents = ConcurrentHashMap.newKeySet<String>()
    private val queryCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val recompositionCounters = ConcurrentHashMap<String, AtomicInteger>()

    fun reset() {
        onceEvents.clear()
        queryCounters.clear()
        recompositionCounters.clear()
        logger.i(PERF_SOURCE, "PERF_BASELINE_EVENT screen=perf event=telemetry_reset tMs=${android.os.SystemClock.elapsedRealtime()}")
    }

    fun markEvent(
        screen: String,
        event: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val payload = data.toMutableMap()
        payload["screen"] = screen
        payload["event"] = event
        payload["tMs"] = android.os.SystemClock.elapsedRealtime()
        val messageData = payload.entries.joinToString(" ") { (key, value) -> "$key=$value" }
        logger.i(PERF_SOURCE, "PERF_BASELINE_EVENT $messageData", payload)
    }

    fun markEventOnce(
        key: String,
        screen: String,
        event: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        if (!onceEvents.add(key)) return
        markEvent(screen = screen, event = event, data = data)
    }

    fun incrementQuery(
        screen: String,
        source: String,
        amount: Int = 1,
    ): Int {
        val counterKey = "$screen::$source"
        val total = queryCounters.getOrPut(counterKey) { AtomicInteger(0) }.addAndGet(amount)
        logger.i(
            PERF_SOURCE,
            "PERF_BASELINE_QUERY screen=$screen source=$source delta=$amount total=$total tMs=${android.os.SystemClock.elapsedRealtime()}",
            mapOf(
                "screen" to screen,
                "source" to source,
                "delta" to amount,
                "total" to total,
            ),
        )
        return total
    }

    fun incrementRecomposition(
        screen: String,
        section: String,
    ): Int {
        val counterKey = "$screen::$section"
        val total = recompositionCounters.getOrPut(counterKey) { AtomicInteger(0) }.incrementAndGet()
        if (total <= 3 || total % RECOMPOSITION_LOG_INTERVAL == 0) {
            logger.i(
                PERF_SOURCE,
                "PERF_BASELINE_RECOMPOSITION screen=$screen section=$section total=$total tMs=${android.os.SystemClock.elapsedRealtime()}",
                mapOf("screen" to screen, "section" to section, "total" to total),
            )
        }
        return total
    }
}

@Composable
fun TrackRecomposition(screen: String, section: String) {
    SideEffect {
        PerfBaselineTelemetry.incrementRecomposition(screen = screen, section = section)
    }
}
