//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseOpenOutcome
import io.payanam.shared.database.PayanamDatabaseHandle
import io.payanam.shared.database.PayanamDatabaseOpener
import io.payanam.shared.security.BiometricAuthentication
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricRefusalReason
import io.payanam.shared.security.BiometricUnavailableReason
import io.payanam.shared.security.BiometricUnlockPrompt
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.Report
import io.payanam.shared.security.VaultAvailability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * The unlock gate over its real collaborators' contracts: a fake [PassphraseVault], a fake
 * [BiometricUnlockPrompt] and a fake [PayanamDatabaseOpener].
 *
 * WHY THE SUBJECT CHANGED: this used to be a test of [io.payanam.database.security.DatabaseEncryptionManager]
 * and of one function that matched English exception messages. The gate now asks a [PassphraseVault] whether
 * a passphrase is right, asks a [BiometricUnlockPrompt] to prove the user, and opens through
 * [PayanamDatabaseOpener] - so a test whose subject is any one of those would be testing a dependency. These
 * assertions are about the gate's own behaviour, and the three ways it can be wrong in production are:
 * a refusal that unlocks anyway, a success that opens with the WRONG passphrase, and a lockout that reports
 * a countdown it did not get from the vault.
 */
class DatabasePassphraseUnlockViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(testDispatcher)

    private lateinit var vault: FakePassphraseVault
    private lateinit var prompt: FakeBiometricUnlockPrompt
    private lateinit var opener: FakeDatabaseOpener
    private lateinit var databaseFile: File

    /** Named rather than built per call, because the reset path's assertions are about what IT was asked. */
    private val reset = FakeForgottenPassphraseReset()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    @Before
    fun setUp() {
        vault = FakePassphraseVault(configured = "correct horse")
        prompt = FakeBiometricUnlockPrompt()
        opener = FakeDatabaseOpener()
        databaseFile = File.createTempFile("payanam-unlock-test", ".db")
    }

    @After
    fun tearDown() {
        databaseFile.delete()
    }

    /**
     * The gate with every blocking hop pointed at the test scheduler, so virtual time governs the whole
     * thing. Without this, `advanceUntilIdle` returns while a `Dispatchers.IO` hop is still in flight and
     * the assertions read a state the gate has not reached yet.
     */
    private fun viewModel() =
        DatabasePassphraseUnlockViewModel(
            vault = vault,
            biometricPrompt = prompt,
            forgottenPassphraseReset = reset,
            databaseOpener = opener,
            databaseFile = databaseFile,
            strings = strings,
            scope = scope,
            ioDispatcher = testDispatcher,
            // The prompt must run on the dispatcher this platform supplies; point it at the test scheduler
            // so `advanceUntilIdle` governs that hop too (the default is a real background pool).
            promptDispatcher = testDispatcher,
        )

    // ------------------------------------------------------------------ the three production risks

    @Test
    fun `a refused prompt does not unlock`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            prompt.availability = BiometricAvailability.Available
            prompt.answer = BiometricAuthentication.Refused(BiometricRefusalReason.FAILED)
            var unlocked = false

            viewModel.startBiometricUnlock { unlocked = true }
            advanceUntilIdle()

            assertThat(unlocked).isFalse()
            assertThat(opener.openedWith).isNull()
            assertThat(viewModel.uiState.value.isUnlocking).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isNotNull()
            viewModel.dispose()
        }

    @Test
    fun `a dismissal is not reported as a failure`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            prompt.availability = BiometricAvailability.Available
            prompt.answer = BiometricAuthentication.Refused(BiometricRefusalReason.CANCELLED)
            var unlocked = false

            viewModel.startBiometricUnlock { unlocked = true }
            advanceUntilIdle()

            assertThat(unlocked).isFalse()
            // The one behaviour a cancellation must not have: an error on screen for a user who backed out.
            assertThat(viewModel.uiState.value.errorReasonCode).isNull()
            assertThat(viewModel.uiState.value.isUnlocking).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `an authenticated prompt opens the session with THAT passphrase`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            prompt.availability = BiometricAvailability.Available
            prompt.answer = BiometricAuthentication.Authenticated("unwrapped-by-the-platform")
            var unlocked = false

            viewModel.startBiometricUnlock { unlocked = true }
            advanceUntilIdle()

            assertThat(unlocked).isTrue()
            // The pass that matters: the passphrase the platform handed back is the one the session opened
            // with, and it is not the vault's own value.
            assertThat(opener.openedWith).isEqualTo("unwrapped-by-the-platform")
            assertThat(vault.resetAttemptsCalled).isTrue()
            assertThat(viewModel.uiState.value.errorReasonCode).isNull()
            viewModel.dispose()
        }

    @Test
    fun `the prompt is invoked on the injected prompt dispatcher`() =
        runTest(testDispatcher) {
            // THE REGRESSION THIS TEST EXISTS FOR: `startBiometricUnlock` used to call the platform prompt
            // directly inside the ViewModel's background scope. On Android that reaches
            // `BiometricPrompt.authenticate(...)`, which is main-thread-only; off the main thread it threw
            // and the exception killed the process on launch. The fix is that the prompt runs on the
            // dispatcher the platform supplies, so that is exactly what is asserted here.
            val promptDispatcher = StandardTestDispatcher(testScheduler)
            prompt.availability = BiometricAvailability.Available
            prompt.answer = BiometricAuthentication.Authenticated("unwrapped-by-the-platform")
            val viewModel =
                DatabasePassphraseUnlockViewModel(
                    vault = vault,
                    biometricPrompt = prompt,
                    forgottenPassphraseReset = reset,
                    databaseOpener = opener,
                    databaseFile = databaseFile,
                    strings = strings,
                    scope = scope,
                    ioDispatcher = testDispatcher,
                    promptDispatcher = promptDispatcher,
                )

            viewModel.startBiometricUnlock { }
            advanceUntilIdle()

            assertThat(prompt.authenticationRequests).isEqualTo(1)
            // The dispatcher the prompt actually observed is the one that was injected - not the scope's.
            assertThat(prompt.requestDispatcher).isSameInstanceAs(promptDispatcher)
            viewModel.dispose()
        }

    @Test
    fun `a prompt that throws keeps the app open and reports an error`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            prompt.availability = BiometricAvailability.Available
            prompt.failure = IllegalStateException("prompt exploded")
            var unlocked = false

            viewModel.startBiometricUnlock { unlocked = true }
            advanceUntilIdle()

            // The whole point of the guard: a misbehaving platform prompt must surface as state, not as an
            // uncaught coroutine exception that takes the process down.
            assertThat(unlocked).isFalse()
            assertThat(viewModel.uiState.value.isUnlocking).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("biometric_error")
            viewModel.dispose()
        }

    @Test
    fun `a typed passphrase opens the session with that same passphrase`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            var unlocked = false

            viewModel.unlock("correct horse") { unlocked = true }
            advanceUntilIdle()

            assertThat(unlocked).isTrue()
            assertThat(opener.openedWith).isEqualTo("correct horse")
            viewModel.dispose()
        }

    @Test
    fun `a lockout reports the vault's remaining time and does not try the passphrase`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            vault.lockoutSecondsRemaining = 42L
            var unlocked = false

            viewModel.unlock("correct horse") { unlocked = true }
            // NOT advanceUntilIdle: this path starts the lockout ticker, which is a `while (true)` over
            // `delay(1000)` - idling to completion would run a minute of virtual time to no end. The gate
            // publishes its state synchronously before the ticker starts, so a single yield is enough.
            testScheduler.runCurrent()

            assertThat(unlocked).isFalse()
            // The countdown is the VAULT's number, restated - never a locally re-derived one.
            assertThat(viewModel.uiState.value.lockoutSecondsRemaining).isEqualTo(42L)
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("locked")
            assertThat(vault.verifyCalls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `an unavailable platform names the reason instead of prompting`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            prompt.availability = BiometricAvailability.Unavailable(BiometricUnavailableReason.NO_HARDWARE)
            var unlocked = false

            viewModel.startBiometricUnlock { unlocked = true }
            advanceUntilIdle()

            assertThat(unlocked).isFalse()
            assertThat(prompt.authenticationRequests).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("biometric_unavailable")
            viewModel.dispose()
        }

    @Test
    fun `a refused database open becomes the boot issue's own reason code`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            opener.refusal = DatabaseOpenOutcome.Refused(DatabaseBootIssue(DatabaseBootIssueType.DB_TOO_NEW))
            var unlocked = false

            viewModel.unlock("correct horse") { unlocked = true }
            advanceUntilIdle()

            assertThat(unlocked).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("db_too_new")
            viewModel.dispose()
        }

    @Test
    fun `a wrong passphrase reports the failure and lets the vault answer the lockout`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            vault.recordedFailureLockout = 30L
            var unlocked = false

            viewModel.unlock("wrong") { unlocked = true }
            // runCurrent, not advanceUntilIdle: an escalated failure starts the ticker, which never idles.
            testScheduler.runCurrent()

            assertThat(unlocked).isFalse()
            // Exactly ONE record call. The stores count inside their own verification, so a second
            // increment here would escalate the schedule twice as fast and lock the owner out early.
            assertThat(vault.recordCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("locked")
            viewModel.dispose()
        }

    @Test
    fun `a wrong passphrase below the threshold reports invalid rather than a lockout`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            vault.recordedFailureLockout = 0L
            var unlocked = false

            viewModel.unlock("wrong") { unlocked = true }
            testScheduler.runCurrent()

            assertThat(unlocked).isFalse()
            assertThat(vault.recordCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("invalid")
            assertThat(viewModel.uiState.value.lockoutSecondsRemaining).isEqualTo(0L)
            viewModel.dispose()
        }

    @Test
    fun `a blank passphrase is refused without consulting the vault at all`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()

            viewModel.unlock("   ") { }
            advanceUntilIdle()

            assertThat(vault.verifyCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("invalid")
            viewModel.dispose()
        }

    @Test
    fun `the storage label follows the vault's encryption state`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.storageModeLabelKey).isEqualTo("plaintext")

            vault.encryptionEnabled = true
            val enabled = viewModel()
            advanceUntilIdle()
            assertThat(enabled.uiState.value.storageModeLabelKey).isEqualTo("encrypted")
            viewModel.dispose()
            enabled.dispose()
        }

    @Test
    fun `the reason code resolves to the shared copy table's own sentence`() {
        val viewModel = viewModel()

        assertThat(viewModel.messageForReasonCode("invalid")).isEqualTo(strings.dbPassphraseUnlockErrorInvalid)
        assertThat(viewModel.messageForReasonCode("db_too_new")).isEqualTo(strings.dbPassphraseUnlockErrorDbTooNew)
        assertThat(viewModel.messageForReasonCode(null)).isNull()
        // A code this class does not raise resolves to nothing rather than to a wrong sentence.
        assertThat(viewModel.messageForReasonCode("biometric_lockout")).isNull()
        viewModel.dispose()
    }

    // ---------------------------------------------------------------------------- classification

    @Test
    fun `each boot issue maps to the reason code its own copy expects`() {
        fun reasonFor(type: DatabaseBootIssueType) = classifyDatabaseOpenOutcome(DatabaseOpenOutcome.Refused(DatabaseBootIssue(type)))

        assertThat(reasonFor(DatabaseBootIssueType.DB_TOO_NEW)).isEqualTo("db_too_new")
        assertThat(reasonFor(DatabaseBootIssueType.DB_TOO_OLD)).isEqualTo("db_too_old")
        assertThat(reasonFor(DatabaseBootIssueType.SCHEMA_INVALID)).isEqualTo("schema_invalid")
        assertThat(reasonFor(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING)).isEqualTo("storage_incomplete")
        assertThat(reasonFor(DatabaseBootIssueType.OPEN_FAILED)).isEqualTo("open_failed")
        assertThat(reasonFor(DatabaseBootIssueType.REPAIRABLE_GENERIC)).isEqualTo("open_failed")
    }

    // ------------------------------------------------------------------------------ the fakes

    /**
     * A [PassphraseVault] that counts the calls the gate must not double up.
     *
     * [recordCalls] is the assertion the contract's KDoc turns on: the desktop's store counts the failure
     * inside `verifyPassphrase`, so this fake does too, and a gate that recorded it again would show a
     * count of 2 for one wrong passphrase.
     */
    @Test
    fun `a successful reset clears the busy flag and calls back`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            reset.report = Report(artifactsDeleted = 3, artifactsAttempted = 3, credentialCleared = true)
            var resetDone = false

            viewModel.forgotPassphraseReset { resetDone = true }
            advanceUntilIdle()

            assertThat(resetDone).isTrue()
            assertThat(viewModel.uiState.value.isUnlocking).isFalse()
            // A successful reset is not an error state: the user asked for it, so the screen that follows is
            // the setup gate, not a message about what just happened.
            assertThat(viewModel.uiState.value.errorReasonCode).isNull()
            assertThat(viewModel.uiState.value.lockoutSecondsRemaining).isEqualTo(0L)
            viewModel.dispose()
        }

    @Test
    fun `a reset that did not clear the credential is reported and does NOT call back`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            reset.report =
                Report(artifactsDeleted = 0, artifactsAttempted = 3, credentialCleared = false)
            var resetDone = false

            viewModel.forgotPassphraseReset { resetDone = true }
            advanceUntilIdle()

            // The port's `succeeded` is exactly this promise: a half-done reset must not walk the user past a
            // gate whose credential is still in place, or they reach a database they cannot open and no
            // screen that says why.
            assertThat(resetDone).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("reset_failed")
            assertThat(viewModel.uiState.value.isUnlocking).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `a PARTIAL wipe with a cleared credential does NOT report success`() =
        runTest(testDispatcher) {
            // THE ARM THE PORT'S RESULT TYPE EXISTS TO PROVE. Both halves are individually fine: the
            // credential cleared, and most of the artifact family went. What did not happen is the whole
            // family going, and a user told their data was destroyed while a `-wal` of their own database
            // is still beside the wiped main file is the one lie this path must not tell. So the answer the
            // gate walks past on has to be `succeeded`, and only `succeeded` unlocks the next gate.
            val viewModel = viewModel()
            reset.report = Report(artifactsDeleted = 3, artifactsAttempted = 4, credentialCleared = true)
            var resetDone = false

            viewModel.forgotPassphraseReset { resetDone = true }
            advanceUntilIdle()

            assertThat(resetDone).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("reset_failed")
            // The credential half is still true, and it is reported as such rather than folded away: a
            // caller that wants to tell the user "we cleared your lock but a file survived" needs the two
            // halves separately, which is why the report is a data class and not a boolean.
            assertThat(reset.report.credentialCleared).isTrue()
            assertThat(viewModel.uiState.value.isUnlocking).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `one request reaches the port exactly once`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            var resetDone = false

            viewModel.forgotPassphraseReset { resetDone = true }
            advanceUntilIdle()

            assertThat(resetDone).isTrue()
            // A second ask would be a second deletion of a user's data for one tap, and the callback shape
            // this port replaced made a double answer something a platform could cause by resuming twice.
            assertThat(reset.resetCalls).isEqualTo(1)
            viewModel.dispose()
        }

    private class FakePassphraseVault(
        private val configured: String,
    ) : PassphraseVault {
        var encryptionEnabled = false
        var lockoutSecondsRemaining = 0L
        var recordedFailureLockout = 0L
        var verifyCalls = 0
        var recordCalls = 0
        var resetAttemptsCalled = false

        override fun hasPassphraseConfigured(): Boolean = true

        override fun availability(): VaultAvailability = VaultAvailability.AVAILABLE

        override fun unlockLockoutSecondsRemaining(): Long = lockoutSecondsRemaining

        override fun verifyPassphrase(passphrase: CharArray): Boolean {
            verifyCalls++
            val valid = passphrase.concatToString() == configured
            if (!valid && recordedFailureLockout > 0L) {
                lockoutSecondsRemaining = recordedFailureLockout
            }
            return valid
        }

        /** The re-read the desktop does: it reports the store's own verdict and does not increment. */
        override fun recordFailedUnlockAttempt(): Long {
            recordCalls++
            return lockoutSecondsRemaining
        }

        override fun resetUnlockAttempts() {
            resetAttemptsCalled = true
        }

        override fun isEncryptionEnabled(): Boolean = encryptionEnabled

        override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome = PassphraseConfigureOutcome.CONFIGURED

        override fun clearPassphrase() = Unit

        override fun updatePassphrase(
            current: CharArray,
            new: CharArray,
        ): PassphraseUpdateOutcome = PassphraseUpdateOutcome.UPDATED
    }

    private class FakeBiometricUnlockPrompt : BiometricUnlockPrompt {
        var availability: BiometricAvailability = BiometricAvailability.Available
        var answer: BiometricAuthentication = BiometricAuthentication.Refused(BiometricRefusalReason.FAILED)
        var authenticationRequests = 0

        /** The dispatcher this prompt was actually resumed on, captured to prove where it was invoked. */
        var requestDispatcher: CoroutineDispatcher? = null

        /** When set, [requestAuthentication] throws it - the misbehaving-platform case. */
        var failure: Throwable? = null

        override fun availability(): BiometricAvailability = availability

        override suspend fun requestAuthentication(): BiometricAuthentication {
            authenticationRequests++
            requestDispatcher = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            failure?.let { throw it }
            return answer
        }
    }

    private class FakeDatabaseOpener : PayanamDatabaseOpener {
        var openedWith: String? = null
        var refusal: DatabaseOpenOutcome? = null

        override suspend fun verifyPassphrase(
            databasePath: String,
            passphrase: CharArray,
        ): Boolean = true

        override suspend fun open(
            databasePath: String,
            passphrase: CharArray,
            readOnly: Boolean,
        ): DatabaseOpenOutcome {
            openedWith = passphrase.concatToString()
            return refusal ?: DatabaseOpenOutcome.Opened(OpenHandle)
        }
    }

    private object OpenHandle : PayanamDatabaseHandle {
        override val schemaVersion: Int = 1
        override val isOpen: Boolean = true

        override fun close() = Unit
    }

    private class FakeForgottenPassphraseReset : DatabaseForgotPassphraseReset {
        var report = Report(artifactsDeleted = 3, artifactsAttempted = 3, credentialCleared = true)

        /** How many times the port was asked; a second ask would be a second reset of a user's data. */
        var resetCalls = 0
            private set

        override fun reset(): Report {
            resetCalls++
            return report
        }
    }
}
