//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.shared.platform.BackupBackgroundCapability
import io.payanam.shared.platform.HapticFeedback
import io.payanam.shared.platform.SupportState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for Android's platform seams.
 *
 * What is asserted here is the platform's OWN answer — which capabilities it claims and which it refuses —
 * rather than a hardcoded list, because the answer legitimately varies by device and SDK. The one refusal
 * that must not vary is a non-positive backup interval: it is rejected BEFORE `WorkManager` is touched, so
 * the test needs no work-testing infrastructure and proves the validation is not decorative.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidPlatformSeamsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a non-positive backup interval is rejected rather than treated as off`(): Unit =
        runBlocking {
            val scheduler = AndroidBackupScheduler(context)

            // Silently treating a bad interval as "disabled" would leave the user believing backups run.
            val zero = runCatching { scheduler.schedule(0) }
            val negative = runCatching { scheduler.schedule(-15) }

            assertThat(zero.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(negative.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(scheduler.intervalMinutes).isNull()
        }

    @Test
    fun `Android reports a surviving schedule, not a foreground one`() {
        // `WorkManager` keeps the schedule after the app is closed, which is the real difference from the
        // desktop's in-process timer.
        assertThat(AndroidBackupScheduler(context).capability)
            .isEqualTo(BackupBackgroundCapability.BACKGROUND_CAPABLE)
    }

    @Test
    fun `Android claims reminders, because it has a notification channel to deliver them on`() {
        assertThat(androidCapabilities(context).reminders)
            .isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    @Config(sdk = [31])
    fun `dynamic colour is claimed only from the SDK that can supply it`() {
        assertThat(androidCapabilities(context).supportsDynamicColor).isTrue()
    }

    @Test
    @Config(sdk = [30])
    fun `dynamic colour is not claimed below that SDK`() {
        assertThat(androidCapabilities(context).supportsDynamicColor).isFalse()
    }

    @Test
    fun `every haptic request is harmless whether or not the device can vibrate`() {
        val sink = AndroidHapticSink(context)

        // Robolectric may or may not provide a vibrator; either way, decorative feedback must not throw.
        HapticFeedback.entries.forEach { sink.perform(it) }

        // Compared against the platform's own answer, computed independently here rather than restated, so
        // this cannot pass by asserting the sink against itself.
        val vibratorPresent =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(android.os.VibratorManager::class.java)
                    ?.defaultVibrator
                    ?.hasVibrator() == true
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.os.Vibrator::class.java)?.hasVibrator() == true
            }
        assertThat(sink.isActive).isEqualTo(vibratorPresent)
    }

    @Test
    fun `debug logging follows the build flag by default and reports the value in force`() {
        val flags = AndroidDebugFlags()
        val initial = flags.debugLoggingEnabled

        assertThat(flags.setDebugLoggingEnabled(true)).isTrue()
        assertThat(flags.debugLoggingEnabled).isTrue()
        assertThat(flags.setDebugLoggingEnabled(initial)).isEqualTo(initial)
    }
}
