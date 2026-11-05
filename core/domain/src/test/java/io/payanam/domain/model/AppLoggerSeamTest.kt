//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.NoOpUnifiedLogger
import io.payanam.shared.logging.UnifiedLogger
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Guards the [AppLogger] seam that lets platform-neutral modules log without reaching for a platform
 * singleton.
 *
 * This is a regression test for a real defect: the seam was first implemented with an `install()`
 * function that **no production entry point ever called**, so `AppLogger.current` stayed at the no-op
 * default and every diagnostic from `core/domain` and `core/scoring` was discarded silently. The unit
 * tests all passed, because a no-op logger is exactly what a test expects. Only an assertion that a
 * real installed logger RECEIVES the call can catch that class of bug.
 */
class AppLoggerSeamTest {
    private class RecordingLogger : UnifiedLogger {
        val sources = mutableListOf<String>()

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            sources += "D:$source"
        }

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            sources += "I:$source"
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            sources += "W:$source"
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            sources += "E:$source"
        }

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            sources += "ES:$source"
        }
    }

    // Reset in BOTH @Before and @After: every test class in this module shares one JVM (`forkEvery` is
    // unset in this build), so a logger leaked by an earlier class could make the assertion below pass
    // for the wrong reason, and this class must not leak into the next one either.
    @Before
    fun resetLoggerBefore() {
        AppLogger.resetForTests()
    }

    @After
    fun resetLoggerAfter() {
        AppLogger.resetForTests()
    }

    @Test
    fun installedLoggerReceivesLogsFromDomainCode() {
        val recording = RecordingLogger()
        AppLogger.install(recording)

        // A legacy id is rejected and logged at WARN - a deterministic, real call path in this module.
        DimensionTaxonomyCatalog.fromAnyId("dim_health_wellness")

        assertThat(recording.sources).isNotEmpty()
    }

    @Test
    fun logsGoNowhereButNeverCrashWhenNoLoggerIsInstalled() {
        // No install() call: reaching the assertion without throwing is the point, because this is
        // what a plain JVM consumer (and the desktop before its startup path runs) actually sees.
        DimensionTaxonomyCatalog.fromAnyId("dim_health_wellness")

        assertThat(AppLogger.current).isSameInstanceAs(NoOpUnifiedLogger)
    }

    @Test
    fun resetForTestsRestoresTheNoOpDefault() {
        AppLogger.install(RecordingLogger())
        AppLogger.resetForTests()

        assertThat(AppLogger.current).isSameInstanceAs(NoOpUnifiedLogger)
    }
}
