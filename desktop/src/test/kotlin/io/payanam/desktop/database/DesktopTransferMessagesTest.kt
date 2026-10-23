//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.transfer.TransferOutcome
import io.payanam.shared.transfer.TransferRefusal
import org.junit.Test

/**
 * What the user is told, tested for the property that matters rather than for wording.
 *
 * That property is: **when the live database was not replaced, the message says so** — and when it WAS
 * replaced, the message must not claim otherwise. Every other choice here is copy, which the shared string
 * table will own once the screen moves to `:core:ui`; this one is a fact the user acts on.
 */
class DesktopTransferMessagesTest {
    private fun export(
        outcome: DesktopTransferOutcome,
        destination: String = "/tmp/backup.db",
    ) = DesktopTransferMessages.describe(outcome, DesktopTransferMessages.TransferDirection.EXPORT, destination)

    private fun import(outcome: DesktopTransferOutcome) =
        DesktopTransferMessages.describe(outcome, DesktopTransferMessages.TransferDirection.IMPORT)

    private fun refused(reason: TransferRefusal) = DesktopTransferOutcome.Gateway(TransferOutcome.Refused(reason))

    @Test
    fun `every refusal that left the database alone says nothing was replaced`() {
        val undoneRefusals =
            listOf(
                TransferRefusal.PASSPHRASE_REJECTED,
                TransferRefusal.NOT_A_PAYANAM_DATABASE,
                TransferRefusal.INCOMPATIBLE_SCHEMA,
                TransferRefusal.SOURCE_UNREADABLE,
                TransferRefusal.DESTINATION_UNWRITABLE,
                TransferRefusal.SAME_FILE,
            )

        undoneRefusals.forEach { reason ->
            val message = import(refused(reason))
            assertThat(message).contains("Nothing was replaced.")
        }
    }

    @Test
    fun `a refused replace says the previous database is still live`() {
        // Its own wording because it is not "nothing happened to the file" - the copy DID land, and what
        // failed is the swap. Telling the user "nothing was replaced" would be true but unusable; what they
        // need to know is which database they are on.
        assertThat(import(refused(TransferRefusal.REPLACE_FAILED)))
            .contains("previous database is still the live one")
    }

    @Test
    fun `an incomplete fold does not claim the user's file was fine or that nothing was replaced`() {
        val message = export(refused(TransferRefusal.CHECKPOINT_INCOMPLETE))

        // Nothing was WRITTEN, and the user's fix is to try again - not to pick a different destination.
        assertThat(message).contains("nothing was written")
        assertThat(message).doesNotContain("Nothing was replaced")
    }

    @Test
    fun `an import that landed but cannot be opened says the file is already in place`() {
        val message =
            import(DesktopTransferOutcome.ImportedButUnopenable("DB_TOO_NEW"))

        // The distinction that stops the user re-picking a file that is already their database.
        assertThat(message).contains("already your database file")
        assertThat(message).contains("newer version of the app")
    }

    @Test
    fun `an unknown open failure does not leak an enum name into the message`() {
        val message = import(DesktopTransferOutcome.ImportedButUnopenable("SOMETHING_NEW"))

        assertThat(message).doesNotContain("SOMETHING_NEW")
        assertThat(message).contains("this build could not read it")
    }

    @Test
    fun `a successful export names the destination and a successful import does not`() {
        val succeeded = DesktopTransferOutcome.Gateway(TransferOutcome.Succeeded(2048L))

        assertThat(export(succeeded, "/tmp/backup.db")).contains("/tmp/backup.db")
        assertThat(export(succeeded, "/tmp/backup.db")).contains("2 KB")
        // An import's destination is the live database, which the user already knows; naming it would be
        // noise.
        assertThat(import(succeeded)).doesNotContain("/tmp")
        assertThat(import(succeeded)).contains("This is your database now")
    }

    @Test
    fun `exporting with nothing open points at the session rather than at a file`() {
        val message = export(DesktopTransferOutcome.NothingToExport)

        assertThat(message).contains("Unlock first")
    }
}
