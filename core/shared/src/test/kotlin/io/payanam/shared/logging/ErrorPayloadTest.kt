//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the shared error-payload builder used by both platform loggers.
 */
class ErrorPayloadTest {
    @Test
    fun `null error returns the caller map untouched`() {
        val original = mapOf("noteId" to "42")

        val result = ErrorPayload.build(error = null, data = original, stackFrameLimit = 3)

        assertThat(result).isSameInstanceAs(original)
    }

    @Test
    fun `error adds exception message and stack but no cause chain`() {
        val error = IllegalStateException("drive failure").apply { stackTrace = frames(5) }

        val result = ErrorPayload.build(error = error, data = emptyMap(), stackFrameLimit = 3)

        assertThat(result["exception"]).isEqualTo("IllegalStateException")
        assertThat(result["error"]).isEqualTo("drive failure")
        assertThat(result["stack"]).isEqualTo("Crash0.kt:1:crashFrame0 > Crash1.kt:2:crashFrame1 > Crash2.kt:3:crashFrame2")
        assertThat(result).doesNotContainKey("causeChain")
    }

    @Test
    fun `caller data is preserved alongside the error fields`() {
        val result =
            ErrorPayload.build(
                error = IllegalStateException("boom"),
                data = mapOf("route" to "settings", "attempt" to 2),
                stackFrameLimit = 3,
            )

        assertThat(result["route"]).isEqualTo("settings")
        assertThat(result["attempt"]).isEqualTo(2)
        assertThat(result["exception"]).isEqualTo("IllegalStateException")
    }

    @Test
    fun `cause chain is joined with the double pipe separator`() {
        val error =
            IllegalStateException(
                "outer",
                IllegalArgumentException("inner", java.io.IOException("root")),
            )

        val result = ErrorPayload.build(error = error, data = emptyMap(), stackFrameLimit = 3)

        assertThat(result["causeChain"])
            .isEqualTo("IllegalArgumentException:inner || IOException:root")
    }

    @Test
    fun `cause chain stops at the depth cap`() {
        // Chain of 12 nested causes; the cap is MAX_CAUSE_DEPTH (8).
        var error: Throwable = IllegalStateException("cause11")
        repeat(11) { index ->
            error = IllegalStateException("cause${10 - index}", error)
        }

        val result = ErrorPayload.build(error = error, data = emptyMap(), stackFrameLimit = 3)

        val segments = (result["causeChain"] as String).split(" || ")
        assertThat(segments).hasSize(ErrorPayload.MAX_CAUSE_DEPTH)
    }

    @Test
    fun `unlimited stack budget keeps every frame`() {
        val error = IllegalStateException("crash").apply { stackTrace = frames(6) }

        val result =
            ErrorPayload.build(
                error = error,
                data = emptyMap(),
                stackFrameLimit = ErrorPayload.MAX_CRASH_STACK_FRAMES,
            )

        assertThat(result["stack"] as String).contains("crashFrame5")
    }

    @Test
    fun `null exception message falls back to the exception class name`() {
        val result =
            ErrorPayload.build(
                error = IllegalStateException(),
                data = emptyMap(),
                stackFrameLimit = 3,
            )

        assertThat(result["error"]).isEqualTo("IllegalStateException")
    }

    private fun frames(count: Int): Array<StackTraceElement> =
        Array(count) { index ->
            StackTraceElement("com.example.Crash", "crashFrame$index", "Crash$index.kt", index + 1)
        }
}
