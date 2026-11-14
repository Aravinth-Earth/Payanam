//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The biometric enable prompt launch: it runs on the dispatcher it is given, and a prompt that throws
 * is answered as a failure instead of escaping the coroutine.
 *
 * WHY THE HELPER IS TESTED RATHER THAN THE WHOLE VIEWMODEL: reaching the prompt inside
 * `enableBiometricUnlockWithVerification` needs a valid passphrase, a passing `BiometricManager`
 * check and a real `FragmentActivity` host, so a whole-flow test would pin the platform's biometric
 * plumbing instead of this launch contract. The regression being pinned is narrow and exact: the
 * prompt call must be dispatched, not run on whatever background thread the settings coroutine
 * happens to hold, and a throw must become `false`.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsBiometricPromptLaunchTest {
    private lateinit var logger: UnifiedLogger

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        logger = UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    fun `the prompt is invoked on the supplied dispatcher, not on the calling thread`() {
        val promptThreadName = "settings-biometric-prompt-test"
        var promptThread: Thread? = null
        val executor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, promptThreadName).also { promptThread = it }
            }
        try {
            var invokedOn: Thread? = null
            val launched =
                runBlocking {
                    launchBiometricPrompt(executor.asCoroutineDispatcher(), logger) {
                        invokedOn = Thread.currentThread()
                    }
                }

            assertThat(launched).isTrue()
            assertThat(invokedOn).isNotNull()
            // Identity, not the name string: kotlinx-coroutines appends " @coroutine#N" to a thread's name
            // whenever coroutine debug mode is on, which another test in the same JVM can switch on. The
            // contract is "ran on the supplied dispatcher", and the thread the factory made is exactly that.
            assertThat(invokedOn).isSameInstanceAs(promptThread)
            assertThat(invokedOn?.name).startsWith(promptThreadName)
            assertThat(invokedOn).isNotSameInstanceAs(Thread.currentThread())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a prompt that throws is reported as a failure instead of escaping the coroutine`() {
        val outcome =
            runCatching {
                runBlocking {
                    launchBiometricPrompt(Dispatchers.Unconfined, logger) {
                        // The exact exception the reported crash carried.
                        throw IllegalStateException("Must be called from main thread of fragment host")
                    }
                }
            }

        assertThat(outcome.isFailure).isFalse()
        assertThat(outcome.getOrNull()).isFalse()
    }

    @Test
    fun `a cancelled prompt is rethrown so structured concurrency still works`() {
        val outcome =
            runCatching {
                runBlocking {
                    launchBiometricPrompt(Dispatchers.Unconfined, logger) {
                        throw CancellationException("host torn down")
                    }
                }
            }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}
