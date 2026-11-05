//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.security.EncryptionCheckpointOutcome
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [DesktopEncryptionStateCheckpoint] under test: all four operations refuse with
 * [EncryptionCheckpointOutcome.NOT_SUPPORTED], and every refusal says which operation it refused and why.
 *
 * WHY THIS FILE EXISTS. The class was WIRED into the import loop's collaborators by
 * `DesktopImportLoopHarnessTest` but nothing ever ASKED it anything, so its four answers had no test.
 * `NOT_SUPPORTED` is the load-bearing value in the whole contract here: the desktop opens an unencrypted
 * JDBC file, so it holds no at-rest encryption metadata, and a member that answered `CONFIRMED` would let
 * an import flow tell a user their plaintext database "can be rolled back" — or claim a passphrase was
 * wiped that the import never touched. The shared contract test proves a fake plaintext implementation
 * behaves this way; this file proves the real desktop class does, at the wiring site that uses it.
 *
 * **HOW THE LOG IS CAPTURED.** `AppLogger.install` with a [UnifiedLogger] that keeps WARN entries in
 * memory, exactly as `DesktopStartupRecoveryTest` does — an in-repo precedent, so no log-file directory,
 * no initialisation side effects and no filesystem are involved, and this file needs no `TemporaryFolder`
 * because nothing here touches one. Every WARN entry is recorded as `"<source> :: <message>"`, so one
 * assertion can name the source tag exactly and read the reason text out of the same entry.
 *
 * **WHY THE LOGGER IS INSTALLED BEFORE THE SUBJECT IS BUILT.** [DesktopEncryptionStateCheckpoint] reads
 * `AppLogger.current` once, into a constructor `val`, so the logger it will use for its whole life is
 * whichever one was installed at construction. A checkpoint built while `AppLogger` still holds the default
 * `NoOpUnifiedLogger` would discard all four refusals, and every log assertion below would then pass
 * vacuously on an empty capture. The `@Before` ordering is what makes those assertions mean anything.
 *
 * **WHY `AppLogger` IS RESET IN BOTH DIRECTIONS.** Test classes in this module share one JVM
 * (`forkEvery` is unset), and `AppLogger` is a process-wide `object`. The `@Before` guard stops this file
 * from asserting on a recorder another class installed; the `@After` guard stops this file's recorder from
 * swallowing the next class's diagnostics.
 */
class DesktopEncryptionStateCheckpointTest {
    /** The WARN entries the installed logger saw, each as `"<source> :: <message>"`. */
    private val warnings = mutableListOf<String>()

    /** Built in [setUp] AFTER the logger is installed — see the class KDoc for why that order is load-bearing. */
    private lateinit var checkpoint: DesktopEncryptionStateCheckpoint

    @Before
    fun setUp() {
        AppLogger.resetForTests()
        warnings.clear()
        AppLogger.install(recordingLogger())
        checkpoint = DesktopEncryptionStateCheckpoint()
    }

    @After
    fun tearDown() {
        AppLogger.resetForTests()
    }

    /**
     * The first of the four members, and the one a caller trusts before it deletes anything.
     *
     * A `CONFIRMED` here is the specific lie that matters most: it is the answer a flow reads as "there is
     * now a copy of the encryption state", which is what it would tell the user it can roll back to.
     */
    @Test
    fun `capturing a checkpoint is refused, and the refusal is logged`() {
        val outcome = checkpoint.captureCheckpoint()

        assertRefusedAndExplained(outcome, operation = "captureCheckpoint")
    }

    /**
     * The second member, which the shared contract only permits after a `CONFIRMED` capture.
     *
     * Because no capture can ever be confirmed on this platform, the safe restore is unreachable by
     * construction here — and that is worth pinning, since a restore that reported success on a platform
     * holding nothing is indistinguishable at the call site from one that reported a real rollback.
     */
    @Test
    fun `restoring a checkpoint is refused, and the refusal is logged`() {
        val outcome = checkpoint.restoreCheckpoint()

        assertRefusedAndExplained(outcome, operation = "restoreCheckpoint")
    }

    /**
     * The third member, and the irreversible one: it drops the only recovery path a caller has.
     *
     * The desktop's copy is never taken, so there is nothing to drop; saying so beats a `CONFIRMED` that
     * reads as "your checkpoint was discarded", which on a platform that took none describes no event.
     */
    @Test
    fun `discarding a checkpoint is refused, and the refusal is logged`() {
        val outcome = checkpoint.discardCheckpoint()

        assertRefusedAndExplained(outcome, operation = "discardCheckpoint")
    }

    /**
     * The fourth member, and the one with the most dangerous wrong answer.
     *
     * `wipeEncryptionState` collides with the vault's `clearPassphrase` on at least one platform, and both
     * point at a real method here: [io.payanam.desktop.DesktopSecurityStore] holds a verifier and the
     * vault's `resetSecurityState()` would work. Reporting `CONFIRMED` would therefore be claiming a wipe
     * of a passphrase the import flow never asked about, and it would do it to something that was never
     * encrypted. This case is the one that fails if a future change reaches for the vault to make the
     * refusal look like a result.
     */
    @Test
    fun `wiping encryption state is refused, and the refusal is logged`() {
        val outcome = checkpoint.wipeEncryptionState()

        assertRefusedAndExplained(outcome, operation = "wipeEncryptionState")
    }

    /**
     * All four members, one checkpoint, one answer each — the drift test for the enumeration itself.
     *
     * Four named values rather than a size or an `all {}`: `containsExactly` fails if any member reports
     * something else, and fails just as loudly if the list stops covering all four, which a `hasSize(4)`
     * plus a set comparison would let pass.
     */
    @Test
    fun `every operation refuses, and none of them claims the state changed`() {
        val outcomes =
            listOf(
                checkpoint.captureCheckpoint(),
                checkpoint.restoreCheckpoint(),
                checkpoint.discardCheckpoint(),
                checkpoint.wipeEncryptionState(),
            )

        assertThat(outcomes)
            .containsExactly(
                EncryptionCheckpointOutcome.NOT_SUPPORTED,
                EncryptionCheckpointOutcome.NOT_SUPPORTED,
                EncryptionCheckpointOutcome.NOT_SUPPORTED,
                EncryptionCheckpointOutcome.NOT_SUPPORTED,
            )
        // Named here so the reason a caller can proceed is legible in the failure output: `NOT_SUPPORTED` is
        // an honest absence, not a store failure and not an unconfirmed write, so the import flow must not
        // treat it as a reason to abort. Asserting it against those two cases explicitly is what stops the
        // distinction from being collapsed into a boolean later.
        assertThat(checkpoint.captureCheckpoint())
            .isNotEqualTo(EncryptionCheckpointOutcome.STORE_UNAVAILABLE)
        assertThat(checkpoint.captureCheckpoint())
            .isNotEqualTo(EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED)
    }

    /**
     * The other half of the gate, which the outcome assertions cannot see: each refusal is legible in the
     * session's own log, tagged with the operation that was refused.
     *
     * Without this, a member that returned `NOT_SUPPORTED` silently would pass every outcome assertion and
     * still leave an import loop that skipped a rollback with nothing to explain it — which is exactly the
     * failure the class's own KDoc says it avoids by logging instead of returning a bare constant.
     */
    @Test
    fun `every refusal logs the operation it refused`() {
        checkpoint.captureCheckpoint()
        checkpoint.restoreCheckpoint()
        checkpoint.discardCheckpoint()
        checkpoint.wipeEncryptionState()

        // `containsExactly` rather than `contains`: it forbids a fifth entry and a repeated tag, so a
        // double-logged, skipped or mis-tagged refusal fails here instead of looking like four clean answers.
        assertThat(warnings.map { it.substringBefore(LOG_SEPARATOR) })
            .containsExactly(
                sourceTag("captureCheckpoint"),
                sourceTag("restoreCheckpoint"),
                sourceTag("discardCheckpoint"),
                sourceTag("wipeEncryptionState"),
            )
    }

    /**
     * Every refusal carries the same reason, and that reason is the platform's missing encryption state.
     *
     * Fragments rather than the whole sentence, so a reworded reason that still says the right thing does
     * not break a gate about refusal semantics — while a truncated, empty or substituted message still
     * fails, which is the part that would leave a log line claiming a refusal for no stated reason.
     */
    @Test
    fun `every refusal logs that this platform stores the database unencrypted`() {
        checkpoint.captureCheckpoint()
        checkpoint.restoreCheckpoint()
        checkpoint.discardCheckpoint()
        checkpoint.wipeEncryptionState()

        val messages = warnings.map { it.substringAfter(LOG_SEPARATOR) }
        // One distinct reason across four members: a member that logged its own excuse instead of the
        // shared one would make this two, which is how a per-member rationalisation would first show up.
        assertThat(messages.distinct()).hasSize(1)
        messages.forEach { message ->
            assertThat(message).contains("stores the database unencrypted")
            assertThat(message).contains("there is no encryption state to")
            assertThat(message).contains("the request was not performed")
        }
    }

    /**
     * The gate as one assertion: the answer is [EncryptionCheckpointOutcome.NOT_SUPPORTED], and exactly one
     * WARN entry went out, tagged with the operation that was attempted.
     *
     * Both halves are checked together because either can be lost without the other, and each loss passes
     * a test that only covers its neighbour: an outcome-only test is satisfied by a silent refusal, and a
     * log-only test is satisfied by a refusal that logged the wrong answer. So the source tag is compared
     * for equality — proving the operation name reached the log — and the capture is pinned to a single
     * entry, so a member that logged the refusal twice does not read as thorough.
     */
    private fun assertRefusedAndExplained(
        outcome: EncryptionCheckpointOutcome,
        operation: String,
    ) {
        assertThat(outcome).isEqualTo(EncryptionCheckpointOutcome.NOT_SUPPORTED)
        assertThat(warnings).hasSize(1)
        val entry = warnings.single()
        assertThat(entry.substringBefore(LOG_SEPARATOR)).isEqualTo(sourceTag(operation))
        val message = entry.substringAfter(LOG_SEPARATOR)
        assertThat(message).contains("stores the database unencrypted")
        assertThat(message).contains("the request was not performed")
    }

    /**
     * The source tag the implementation builds for [operation], transcribed from
     * `"DesktopEncryptionStateCheckpoint.$operation"` rather than composed here from the class name, so
     * this file cannot agree with the implementation by construction: change the tag the class logs and
     * these expectations stop matching.
     */
    private fun sourceTag(operation: String): String = "DesktopEncryptionStateCheckpoint.$operation"

    /** Keeps every WARN entry in [warnings] and drops every other level, so only refusals are asserted on. */
    private fun recordingLogger(): UnifiedLogger =
        object : UnifiedLogger {
            override fun d(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) = Unit

            override fun i(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) = Unit

            override fun w(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                warnings += "$source$LOG_SEPARATOR$message"
            }

            override fun e(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit

            override fun eSync(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit
        }

    private companion object {
        /** The [warnings] entry separator: `"<source> :: <message>"`, split back apart by the assertions. */
        const val LOG_SEPARATOR = " :: "
    }
}
