//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tests for the shared transfer contract.
 *
 * There is no production implementation in this module — Android's is SAF-backed and the desktop's is
 * path-backed — so what is asserted here is that the contract is satisfiable and that its vocabulary is
 * complete and distinguishable. That last part is the substance: each refusal case exists because it calls
 * for different copy, and two cases collapsing into one is exactly the defect this enum prevents.
 */
class DatabaseTransferContractTest {
    /** The smallest honest gateway: it moves bytes and reports why it could not. */
    private class FakeGateway(
        private val refuseWith: TransferRefusal? = null,
    ) : DatabaseTransferGateway {
        var importCalls = 0
            private set

        override suspend fun exportTo(destinationToken: String): TransferOutcome =
            refuseWith?.let { TransferOutcome.Refused(it) } ?: TransferOutcome.Succeeded(byteCount = 42)

        override suspend fun importFrom(
            sourceToken: String,
            passphrase: CharArray,
        ): TransferOutcome {
            importCalls++
            return refuseWith?.let { TransferOutcome.Refused(it) } ?: TransferOutcome.Succeeded(byteCount = 7)
        }
    }

    @Test
    fun `a successful transfer reports the size of what moved`(): Unit =
        runBlocking {
            val gateway = FakeGateway()

            val outcome = gateway.exportTo("somewhere")

            assertThat((outcome as TransferOutcome.Succeeded).byteCount).isEqualTo(42)
        }

    @Test
    fun `every refusal reason is a distinct answer`() {
        // Asserted through the enum so a new case is exercised the moment it is added: a reason with no
        // mapping on a platform would otherwise be a silent fallthrough.
        val reasons = TransferRefusal.entries.map { TransferOutcome.Refused(it) }

        assertThat(reasons.map { (it as TransferOutcome.Refused).reason })
            .containsExactlyElementsIn(TransferRefusal.entries)

        assertThat(TransferRefusal.entries.toSet()).hasSize(TransferRefusal.entries.size)
        assertThat(TransferRefusal.valueOf("PASSPHRASE_REJECTED"))
            .isEqualTo(TransferRefusal.PASSPHRASE_REJECTED)
    }

    @Test
    fun `the refusals a user must be able to tell apart are in fact separate`() {
        // The four that call for different copy and different user action. Collapsing any pair sends the
        // user to fix the wrong thing, which is why they are not one "failed" case.
        assertThat(TransferRefusal.entries)
            .containsAtLeast(
                TransferRefusal.PASSPHRASE_REJECTED,
                TransferRefusal.NOT_A_PAYANAM_DATABASE,
                TransferRefusal.INCOMPATIBLE_SCHEMA,
                TransferRefusal.SAME_FILE,
            )
    }

    @Test
    fun `a refused import is reported without the passphrase being retained`(): Unit =
        runBlocking {
            // The passphrase arrives as a CharArray so the CALLER owns its lifetime; the contract must not
            // offer a way for an implementation to hold on to it beyond the call, which is why nothing here
            // returns or stores one.
            val gateway = FakeGateway(refuseWith = TransferRefusal.PASSPHRASE_REJECTED)

            val outcome = gateway.importFrom("somewhere", "Str0ng-Passphrase!".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.PASSPHRASE_REJECTED)
            assertThat(gateway.importCalls).isEqualTo(1)
        }
}
