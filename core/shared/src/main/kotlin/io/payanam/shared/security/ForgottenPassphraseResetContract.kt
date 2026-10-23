//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

/**
 * The destructive recovery path, named for what it does.
 *
 * WHY THIS IS A SEPARATE PORT AND NOT A VAULT METHOD: [PassphraseVault.clearPassphrase] is documented as
 * never touching the database, and that documentation is load-bearing — it is what lets a caller treat a
 * reset as routine. The unlock gate's "I forgot my passphrase" path does the opposite: it deletes the whole
 * artifact family and wipes the encryption state, so the user starts over with an empty app. Two operations
 * that share a name and differ in blast radius cannot share a type. Folding them into one method with a
 * boolean, or naming them "reset" and "clear" in the same interface, is how a future caller picks the
 * cheaper-sounding one on a screen where the expensive one was meant.
 *
 * WHY IT IS A PORT AND NOT A VIEWMODEL METHOD: deleting files is platform work — Android's database path
 * comes from the platform, the desktop's from its own resolver — and the shared state layer may name
 * neither. The contract also fixes the ORDER, which is the part that is easy to get wrong: the data goes
 * first and the credential second, so a failure part-way leaves the user with a wiped app and a stale
 * credential rather than a live credential and no way to use it.
 *
 * WHAT A CALLER MAY ASSUME: on [ForgottenPassphraseReset.Report.Succeeded], the database artifact family
 * named by this platform's own path authority is gone and the unlock credential no longer verifies. On any
 * other outcome, do NOT tell the user it worked, and do not assume the two halves either both happened or
 * both did not — [Report.ArtifactsDeleted] is reported separately for exactly that reason.
 */
interface ForgottenPassphraseReset {
    /**
     * Deletes the platform's database artifact family, then clears the unlock credential.
     *
     * Blocking and destructive. Never call it to "try" anything.
     */
    fun reset(report: (Report) -> Unit)
}

/**
 * What a [ForgottenPassphraseReset] actually did.
 *
 * A data class rather than a boolean because the two halves fail independently and the user is owed the
 * difference: "your data is gone but the app still thinks it is locked" is a support ticket, and it is
 * invisible if the result is a single flag.
 */
data class Report(
    /** How many artifact files were actually removed. Zero is legitimate on a fresh install. */
    val artifactsDeleted: Int,
    /** Whether the unlock credential was cleared. */
    val credentialCleared: Boolean,
) {
    /** Both halves completed. The only outcome a caller may report as success. */
    val succeeded: Boolean
        get() = credentialCleared
}
