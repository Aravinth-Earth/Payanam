//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

import io.payanam.core.ui.text.UiStrings

/**
 * Resolves the passphrase ViewModels' error vocabulary to copy, or null when there is nothing to show.
 *
 * The reason CODES are the ViewModel's contract - they are what crosses the layer boundary - and this is
 * the only place they turn into words, so a code can never render as a blank error. Unknown codes map to
 * null rather than to a guess, which matches the screens' behaviour before they were shared.
 *
 * It lives here rather than in the generated [UiStrings] because it is logic, not copy: the generator only
 * ever emits text. Shared by the change, setup and unlock screens, which read overlapping codes.
 */
fun UiStrings.passphraseErrorMessage(reasonCode: String?): String? =
    when (reasonCode) {
        "current_invalid" -> dbPassphraseChangeErrorCurrentInvalid

        "min_length" -> dbPassphraseErrorMinLength

        "missing_uppercase" -> dbPassphraseErrorUppercase

        "missing_lowercase" -> dbPassphraseErrorLowercase

        "missing_digit" -> dbPassphraseErrorDigit

        "missing_symbol" -> dbPassphraseErrorSymbol

        "mismatch" -> dbPassphraseErrorMismatch

        "generic" -> dbPassphraseErrorGeneric

        // The four SETUP-only codes. Each names a failure that happened after the passphrase was accepted,
        // which is why none of them has a policy-shaped message like the arms above: the user cannot fix
        // any of them by typing a different password.
        //
        //  - migration_incompatible: an existing database is already sealed with a different key, so the
        //    user must reset rather than retype. The screen's reset affordance is keyed off this code.
        //  - migration_failed: the re-encryption threw. The data was left unchanged, which is what this
        //    message says, because a user who retried on the assumption it was half-done would be worse
        //    off than one who knows their data is intact.
        //  - persist_failed: the vault could not write an accepted passphrase. The exact predecessor of the
        //    old `!configured` arm, kept as its own code for the same reason.
        //  - reset_failed: the local-data reset could not complete. Deliberately the UNLOCK screen's
        //    existing string: both codes describe one operation - wiping local data - and giving the two
        //    screens different sentences for it would be the drift this module exists to end. No new key.
        "migration_incompatible" -> dbPassphraseErrorMigrationIncompatible

        "migration_failed" -> dbPassphraseErrorMigrationFailed

        "persist_failed" -> dbPassphraseErrorPersistFailed

        "reset_failed" -> dbPassphraseUnlockResetFailed

        // `rejected_by_policy` is NOT given an arm, and that is the ViewModel's own documented expectation
        // rather than an omission here: DatabasePassphraseSetupViewModel states that no screen copy exists
        // for it and that a rendering screen should fall through to its generic arm - vague, but not false.
        // Writing a message would mean inventing platform copy in a shared module. The setup screen's
        // wrapper below is what makes that fallthrough render the generic arm instead of nothing.
        else -> null
    }

/**
 * The setup gate's error copy: [passphraseErrorMessage], with a generic fallback for any code it does not
 * know.
 *
 * WHY A WRAPPER RATHER THAN A SECOND `else` ARM: the two are not the same contract, and conflating them
 * would quietly change what the CHANGE screen renders. [passphraseErrorMessage] returns null for an
 * unknown code, and the change screen then draws no error at all - correct there, because its own
 * vocabulary is the policy codes and anything else is a bug worth rendering as silence. The SETUP screen
 * behaved differently for its whole life: it showed the generic "could not save" line for anything it did
 * not recognise. Preserving that is the requirement of a move, so the difference is expressed as the
 * wrapper the setup screen calls rather than as a change to the shared map every caller reads.
 *
 * The distinction that matters is `null` input versus an unknown CODE, and this function keeps them
 * apart: a `null` reason code means there is no error to show and returns null, so the screen's existing
 * `?.let` guard stays the only thing deciding whether an error renders. Only a NON-null code this table
 * has no arm for reaches the generic sentence.
 *
 * @param reasonCode the ViewModel's code, or null when no error is surfaced.
 * @return the copy to render, or null when there is no error to describe.
 */
fun UiStrings.passphraseSetupErrorMessage(reasonCode: String?): String? =
    if (reasonCode == null) {
        null
    } else {
        passphraseErrorMessage(reasonCode) ?: dbPassphraseErrorGeneric
    }
