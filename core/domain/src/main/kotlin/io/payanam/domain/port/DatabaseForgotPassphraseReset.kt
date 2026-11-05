//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.shared.security.Report

/**
 * Destroys the database and the credential behind it: the "I forgot my passphrase" recovery path, on
 * either platform.
 *
 * ## What this port is for
 *
 * [io.payanam.shared.security.PassphraseVault.clearPassphrase] is documented as never touching the
 * database, and that documentation is load-bearing — it is what lets a caller treat a credential clear as
 * routine. This port does the opposite: it deletes the whole artifact family and THEN wipes the unlock
 * credential, so the user starts over with an empty app. Two operations that share a name and differ in
 * blast radius cannot share a type, and this one is named for the expensive half deliberately, so a
 * screen that reaches for it by name is reaching for the destructive one.
 *
 * WHY A PORT RATHER THAN A SHARED HELPER: the path authority is the platform's. Android's database path
 * comes from `Context.getDatabasePath` and the desktop's from its own store, so a shared implementation
 * that spelled either one out would create a second answer that can drift from the path the database is
 * actually opened at — and a reset that deletes a family the app never opened reports success and destroys
 * nothing.
 *
 * ## WHY THE ANSWER IS RETURNED AND NOT PUSHED THROUGH A CALLBACK
 *
 * A callback-shaped reset leaves the caller defenceless against two failures and leaves the platform free
 * to cause either: a reset that never invokes its argument hangs the caller forever with its busy flag
 * still set and nothing on screen to explain it, and one that invokes it twice resumes one continuation
 * twice. Returning [Report] makes both impossible to EXPRESS — the platform owes exactly one call, and the
 * caller reads one value off the return — so a screen a user reached while locked out cannot hang them on
 * a spinner nothing will ever clear. This is the same reason [DatabaseRekey] answers with an outcome
 * rather than promising nothing.
 *
 * ## What an implementation may not do
 *
 * Report success it did not measure. [Report.succeeded] is a roll-up of BOTH halves — the credential no
 * longer verifies AND every artifact the platform found is gone — and the credential clears reliably on
 * both platforms, so a delete that removed some members and then failed on one would otherwise report
 * success while a surviving `-wal` still holds committed rows from a database the user was told no longer
 * exists.
 *
 * To make that roll-up derivable at all, an implementation MUST enumerate the artifact members that
 * EXIST before it deletes anything and report both counts. A survivors-only count cannot tell an absent
 * member from one this process failed to remove, and those two are opposite answers: the absent member is
 * a fresh install, not a failure.
 *
 * THE ORDER IS PART OF THE CONTRACT: artifacts first, credential second. A reset that cleared the
 * credential first and then failed to delete would leave the user holding a working unlock for a database
 * they cannot open — locked out of data that is still on disk. Clearing second leaves them an empty app
 * they can set up, which is the state this path exists to produce.
 */
fun interface DatabaseForgotPassphraseReset {
    /**
     * Deletes the platform's database artifact family, then clears the unlock credential, and reports what
     * actually happened.
     *
     * Blocking and destructive. Never call it to "try" anything.
     *
     * @return the honest [Report]. [Report.succeeded] is the only answer a caller may show as a reset
     *   that worked; every other outcome means the user is NOT told their data was destroyed.
     */
    fun reset(): Report
}