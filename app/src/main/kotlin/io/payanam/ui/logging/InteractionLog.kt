//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.logging

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.payanam.common.logging.UnifiedLogger

/**
 * Debug-only interaction trace: the app records the UI elements the user actually touches, so an
 * E2E run can be verified from the app's own log instead of relying only on external assertions.
 *
 * Two layers, because Compose exposes no public app-wide click hook
 * ([androidx.compose.ui.platform.AndroidComposeView] is `internal`, so the semantics tree cannot be
 * read from outside the composition):
 *
 * 1. **Catch-all** — [recordTouch] / [recordKey], wired from the activity's event dispatch. Records
 *    *every* gesture with position and time, so no interaction is ever missed. Element identity is
 *    `unresolved` in this layer.
 * 2. **Named** — [trackedClick] at an interactive element records that element's stable id, giving
 *    the log readable, greppable names for the controls the suite exercises.
 *
 * Behaviour:
 * - No-op unless debug logging is enabled ([UnifiedLogger.isDebugLoggingEnabled]).
 * - Never throws: logging must not break the app, so a failure degrades instead of propagating.
 * - Local only — writes through [UnifiedLogger] into the app's own session log; nothing is sent
 *   anywhere.
 */
object InteractionLog {

    private const val SOURCE = "Interaction"
    private const val UNRESOLVED = "unresolved"

    // Lazy: UnifiedLogger must not be resolved during class init (it breaks plain JVM tests).
    private val logger by lazy { UnifiedLogger.getInstance() }

    /** Records a catch-all touch gesture. Call from the activity's touch dispatch. */
    fun recordTouch(event: MotionEvent) {
        if (!isEnabled() || event.actionMasked != MotionEvent.ACTION_UP) {
            return
        }
        emit(
            action = "tap",
            element = UNRESOLVED,
            detail = "x=${event.rawX.toInt()} y=${event.rawY.toInt()}",
        )
    }

    /** Records a hardware/soft key gesture (e.g. Back). */
    fun recordKey(keyCode: Int) {
        if (!isEnabled()) {
            return
        }
        emit(action = "key", element = "keycode_$keyCode", detail = "")
    }

    /** Records a named interaction: the stable id of the control that was activated. */
    fun recordElement(element: String, action: String = "tap", detail: String = "") {
        if (!isEnabled()) {
            return
        }
        emit(action = action, element = element, detail = detail)
    }

    private fun isEnabled(): Boolean =
        UnifiedLogger.isInitialized() && UnifiedLogger.isDebugLoggingEnabled()

    private fun emit(action: String, element: String, detail: String) {
        runCatching {
            val suffix = if (detail.isBlank()) "" else " $detail"
            logger.i(
                SOURCE,
                "INTERACTION_EVENT action=$action element=$element$suffix " +
                    "tMs=${SystemClock.elapsedRealtime()}",
                mapOf("action" to action, "element" to element),
            )
        }
    }
}

/**
 * A [clickable] that also records the interaction under a stable [element] id.
 *
 * Use on the interactive elements the E2E suite exercises so the app log names them, e.g.
 * `Modifier.trackedClick(element = "add_task", onClick = viewModel::addTask)`.
 *
 * Behaviour is identical to a plain `clickable` — same optional [onClickLabel], same [role], same
 * `enabled` handling and the same ripple from `LocalIndication`. The only addition is the debug-mode
 * log line.
 */
@Composable
fun Modifier.trackedClick(
    element: String,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this.clickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
) {
    InteractionLog.recordElement(element = element)
    onClick()
}
