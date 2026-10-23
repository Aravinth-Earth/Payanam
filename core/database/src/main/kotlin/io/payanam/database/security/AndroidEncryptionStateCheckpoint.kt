//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.security.EncryptionCheckpointOutcome
import io.payanam.shared.security.EncryptionStateCheckpoint

/**
 * The Android side of the encryption-checkpoint contract, over [DatabaseEncryptionManager].
 *
 * An adapter and not a second implementation: every member is a mapping of one manager call, and the manager
 * remains the only thing that touches the preferences blob or the Keystore. The adapter exists because the
 * manager's booleans are lossy in the way this contract cares about — it cannot tell a platform with nothing
 * to save from a platform whose write did not stick, and it reports [clearEncryptionPrefsBackup]'s unconfirmable
 * write as silence rather than as a result.
 *
 * WHY [wipeEncryptionState] IS NOT [PassphraseVault]'s [io.payanam.shared.security.PassphraseVault.clearPassphrase]
 * EVEN THOUGH THEY ARE THE SAME MANAGER CALL HERE: the shared contract names them as separate members because
 * their intents differ, and an adapter that quietly aliased them would make a caller that picked the wrong one
 * unable to tell. On Android the two do happen to be the same write — the manager has no way to forget the
 * verifier without clearing the whole preferences blob — and that coincidence is stated here rather than
 * hidden, so a future reader does not go looking for a second implementation that does not exist.
 */
class AndroidEncryptionStateCheckpoint(
    private val encryptionManager: DatabaseEncryptionManager,
) : EncryptionStateCheckpoint {
    private val logger = UnifiedLogger.getInstance()

    override fun captureCheckpoint(): EncryptionCheckpointOutcome =
        encryptionManager.backupEncryptionPrefs().asOutcome()

    override fun restoreCheckpoint(): EncryptionCheckpointOutcome =
        encryptionManager.restoreEncryptionPrefs().asOutcome()

    /**
     * Performs the manager's own delete of the `_bak_` side-car family, so the captured copy really goes away
     * instead of being described as gone.
     *
     * The manager's method returns `Unit` — it commits, logs whether the commit took, and discards the answer —
     * so on success the contract's uncertainty case is returned rather than a confidence the platform does not
     * have.
     *
     * THE FAILURE CASE IS MAPPED, NOT SWALLOWED. This call is the only *commit-based* preferences writer in the
     * manager without its own `runCatching` — the `apply()`-based setters are unprotected too, but they cannot
     * report a result anyway. A throw from here would reach a caller's recovery path: at
     * `DatabaseInitViewModel.resumeImportWithPassphrase` the discard sits inside a `try` whose `catch` (for any
     * non-wrong-passphrase error) runs `restoreCheckpoint()`; a restore with nothing captured then removes the LIVE
     * keys and reports [EncryptionCheckpointOutcome.CONFIRMED] — a lockout on a database that was just imported
     * successfully. So the call cannot propagate: a failure reports [EncryptionCheckpointOutcome.STORE_UNAVAILABLE]
     * (the contract's failure case, explicitly harmless for a discard) and is logged rather than silent.
     */
    override fun discardCheckpoint(): EncryptionCheckpointOutcome =
        runCatching { encryptionManager.clearEncryptionPrefsBackup() }.fold(
            onSuccess = { EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED },
            onFailure = { error ->
                logger.w(
                    "AndroidEncryptionStateCheckpoint.discardCheckpoint",
                    "Failed to clear the encryption preference backup; backup keys may survive",
                    mapOf("error" to (error.message ?: error::class.simpleName)),
                )
                EncryptionCheckpointOutcome.STORE_UNAVAILABLE
            },
        )

    override fun wipeEncryptionState(): EncryptionCheckpointOutcome =
        encryptionManager.resetEncryptionState().asOutcome()

    /**
     * The manager distinguishes exactly one thing — did the write stick — which the contract reports as
     * [EncryptionCheckpointOutcome.CONFIRMED] or [EncryptionCheckpointOutcome.STORE_UNAVAILABLE].
     *
     * [EncryptionCheckpointOutcome.NOT_SUPPORTED] and [EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED] are
     * unreachable through **this boolean mapping** — but [discardCheckpoint] returns
     * [EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED] directly, so that case is reachable from the adapter even
     * though no boolean can produce it. The unreachability below is a fact about Android rather than an oversight:
     * this platform always has at-rest encryption metadata to act on, and every call it can make reports whether it
     * stuck.
     * Both cases are still handled below so that a future manager method that cannot report cannot silently
     * inherit a "confirmed" it never earned.
     */
    private fun Boolean.asOutcome(): EncryptionCheckpointOutcome =
        if (this) {
            EncryptionCheckpointOutcome.CONFIRMED
        } else {
            EncryptionCheckpointOutcome.STORE_UNAVAILABLE
        }
}
