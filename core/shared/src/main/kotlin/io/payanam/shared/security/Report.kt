//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

/**
 * What the forgot-passphrase reset actually did — the answer
 * [io.payanam.domain.port.DatabaseForgotPassphraseReset] returns.
 *
 * WHY THE OPERATION ITSELF IS NOT DECLARED HERE: the destructive path is a port, because the path
 * authority over the artifact family is the platform's and this module cannot name either. What lives here
 * is only the SHAPE OF THE ANSWER, because both platforms produce it and the rule that decides success is
 * the same rule in both places.
 *
 * WHY IT IS NOT A VAULT METHOD, restated where the report lives: [PassphraseVault.clearPassphrase] is
 * documented as never touching the database, and that documentation is load-bearing — it is what lets a
 * caller treat a reset as routine. The unlock gate's "I forgot my passphrase" path does the opposite: it
 * deletes the whole artifact family and wipes the encryption state, so the user starts over with an empty
 * app. Two operations that share a name and differ in blast radius cannot share a type.
 *
 * A data class rather than a boolean because the two halves fail independently and the user is owed the
 * difference: "your data is gone but the app still thinks it is locked" is a support ticket, and it is
 * invisible if the result is a single flag.
 */
data class Report(
    /** How many artifact files were actually removed. */
    val artifactsDeleted: Int,
    /**
     * How many artifact files this platform tried to remove — the members of the shared artifact family that
     * EXISTED, counted BEFORE any delete is attempted.
     *
     * A count of ATTEMPTS rather than of the family the path authority always enumerates, and of the
     * members that existed rather than of all of them: a fresh install has no artifacts, so counting the
     * four it never had would report zero of four as a failed reset on every first run. With the members
     * that were really there, an absent member is never mistaken for a failed delete and a fresh install's
     * legitimate zero is still a success — neither is derivable from a successes-only count, because an
     * implementation cannot tell an absent member from one it failed to remove without counting first.
     */
    val artifactsAttempted: Int,
    /** Whether the unlock credential was cleared. */
    val credentialCleared: Boolean,
) {
    /**
     * Every artifact the platform found is gone AND the credential no longer verifies. The only outcome a
     * caller may report as success.
     *
     * A roll-up of BOTH halves, deliberately. Reporting this from the credential alone would be the one lie
     * this port exists to make impossible: the credential clears reliably, so a partial delete — a `-wal`
     * held open by another process, a read-only mount — would report success while the user's rows remain
     * readable from the sidecar. With [artifactsAttempted] on the report, that case is a false here rather
     * than a user told their data is gone.
     */
    val succeeded: Boolean
        get() = credentialCleared && artifactsDeleted >= artifactsAttempted
}
