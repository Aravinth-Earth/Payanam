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
import io.payanam.shared.security.BiometricUnavailableReason
import io.payanam.shared.security.BiometricUnlockPrompt
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.Report
import io.payanam.shared.security.VaultAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import java.io.File

/**
 * The unlock gate's two translation tables, END TO END across every case - [classifyDatabaseOpenOutcome]
 * from a boot issue, and [io.payanam.presentation.database.DatabasePassphraseUnlockViewModel.messageForReasonCode]
 * from the reason code it produces.
 *
 * WHY THE WHOLE TABLE RATHER THAN THE SAMPLED ROWS: the existing test asserts three of eleven reason codes.
 * That is enough to prove the table is wired up and not enough to catch the failure this table actually has -
 * a row that maps a boot issue to a code with NO copy, or to the wrong code's copy. Both render as a blank or
 * a mismatched sentence on a screen that is otherwise working, and neither throws. So this walks every
 * [DatabaseBootIssueType] to the string the screen would render, and separately asserts that the codes this
 * class raises on its own (invalid / locked / reset_failed / migration_required / loc_database_needs_repair)
 * each have their own distinct copy rather than falling through to a neighbour's sentence.
 *
 * The pairing is asserted by DISTINCTNESS as well as by membership: if two codes resolved to the same
 * sentence, one assertion would still pass, so the distinctness check is what makes "each row has its own
 * copy" a claim rather than a hope.
 */
class DatabasePassphraseUnlockReasonCodeTableTest {
    private val viewModel by lazy {
        DatabasePassphraseUnlockViewModel(
            vault = FakeVault(),
            biometricPrompt = FakePrompt(),
            forgottenPassphraseReset = FakeReset(),
            databaseOpener = FakeOpener(),
            databaseFile = File(System.getProperty("java.io.tmpdir"), "payanam-unlock-reasons.db"),
            strings = strings,
            scope = CoroutineScope(StandardTestDispatcher()),
            ioDispatcher = StandardTestDispatcher(),
        )
    }

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    /**
     * The collaborators are stubs because nothing under test ASKS them anything: both functions in this
     * class are pure translations over their argument. A fake with real behaviour would be a test of the
     * fake, so each one refuses loudly if a path under test ever starts calling it.
     */
    private class FakeVault : PassphraseVault {
        private fun refuse(): Nothing = throw AssertionError("the reason-code table must not verify or configure a passphrase")

        /** [DatabasePassphraseUnlockViewModel.init] reads these two; answering them is not an assertion. */
        override fun unlockLockoutSecondsRemaining(): Long = 0L

        override fun isEncryptionEnabled(): Boolean = true

        override fun hasPassphraseConfigured(): Boolean = true

        override fun availability(): VaultAvailability = VaultAvailability.AVAILABLE

        override fun verifyPassphrase(passphrase: CharArray): Boolean = refuse()

        override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome = refuse()

        override fun clearPassphrase() = refuse()

        override fun recordFailedUnlockAttempt(): Long = refuse()

        override fun resetUnlockAttempts() = refuse()

        override fun updatePassphrase(
            current: CharArray,
            new: CharArray,
        ): PassphraseUpdateOutcome = refuse()
    }

    private class FakePrompt : BiometricUnlockPrompt {
        override fun availability(): BiometricAvailability = BiometricAvailability.Unavailable(BiometricUnavailableReason.NO_HARDWARE)

        override suspend fun requestAuthentication(): BiometricAuthentication =
            throw AssertionError("the reason-code table must not ask for a fingerprint")
    }

    private class FakeReset : DatabaseForgotPassphraseReset {
        override fun reset(): Report = throw AssertionError("the table must not reset")
    }

    private class FakeOpener : PayanamDatabaseOpener {
        private fun refuse(): Nothing = throw AssertionError("the reason-code table must not open a database")

        override suspend fun verifyPassphrase(
            databasePath: String,
            passphrase: CharArray,
        ): Boolean = refuse()

        override suspend fun open(
            databasePath: String,
            passphrase: CharArray,
            readOnly: Boolean,
        ): DatabaseOpenOutcome = refuse()
    }

    private class FakeHandle : PayanamDatabaseHandle {
        override val schemaVersion: Int = 1
        override val isOpen: Boolean = true

        override fun close() = Unit
    }

    /** Every boot issue type, with the reason code this module's mapper gives it. */
    private val bootIssueMappings =
        listOf(
            DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING to "storage_incomplete",
            DatabaseBootIssueType.DB_TOO_OLD to "db_too_old",
            DatabaseBootIssueType.DB_TOO_NEW to "db_too_new",
            DatabaseBootIssueType.SCHEMA_INVALID to "schema_invalid",
            DatabaseBootIssueType.OPEN_FAILED to "open_failed",
            DatabaseBootIssueType.REPAIRABLE_GENERIC to "open_failed",
            DatabaseBootIssueType.NON_REPAIRABLE_GENERIC to "open_failed",
        )

    @Test
    fun every_boot_issue_type_reaches_its_own_reason_code() {
        bootIssueMappings.forEach { (type, expected) ->
            val classified =
                classifyDatabaseOpenOutcome(DatabaseOpenOutcome.Refused(DatabaseBootIssue(type)))

            assertThat(classified).isEqualTo(expected)
        }
    }

    @Test
    fun every_reason_code_a_boot_issue_can_produce_has_its_own_message() {
        // The failure this catches: a boot issue classified to a code, and the screen then finding no copy
        // for that code and rendering nothing at all - a failed unlock with an empty error line.
        bootIssueMappings.map { it.second }.distinct().forEach { reasonCode ->
            assertThat(viewModel.messageForReasonCode(reasonCode)).isNotNull()
        }
    }

    @Test
    fun the_four_distinct_boot_failure_codes_get_four_different_sentences() {
        val messages =
            listOf("storage_incomplete", "db_too_old", "db_too_new", "schema_invalid").map { code ->
                viewModel.messageForReasonCode(code)
            }

        assertThat(messages).containsNoDuplicates()
        assertThat(messages).doesNotContain(null)
    }

    @Test
    fun the_codes_this_class_raises_on_its_own_all_resolve_to_distinct_copy() {
        // invalid (wrong passphrase), locked (vault lockout), reset_failed (the reset path),
        // migration_required and loc_database_needs_repair are all reachable from unlock() itself, so a
        // caller can hit every one of them without the database ever being opened.
        val codes = listOf("invalid", "locked", "reset_failed", "migration_required", "loc_database_needs_repair")

        val messages = codes.map { viewModel.messageForReasonCode(it) }

        assertThat(messages).containsNoDuplicates()
        assertThat(messages).doesNotContain(null)
    }

    @Test
    fun an_opened_database_is_not_a_failure_to_classify() {
        // `Opened` is not a `Refused`, so there is no issue to read and the safe cast yields null. Production
        // never calls the mapper on a success, which is exactly why a test has to pin what it would answer:
        // the generic code, not a crash on the cast and not a bogus "storage_incomplete".
        val opened = DatabaseOpenOutcome.Opened(FakeHandle())

        assertThat(classifyDatabaseOpenOutcome(opened)).isEqualTo("open_failed")
    }

    @Test
    fun a_code_this_class_never_raises_resolves_to_nothing() {
        assertThat(viewModel.messageForReasonCode("biometric_lockout")).isNull()
        assertThat(viewModel.messageForReasonCode("")).isNull()
        assertThat(viewModel.messageForReasonCode(null)).isNull()
    }
}
