//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

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
    override fun captureCheckpoint(): EncryptionCheckpointOutcome =
        encryptionManager.backupEncryptionPrefs().asOutcome()

    override fun restoreCheckpoint(): EncryptionCheckpointOutcome =
        encryptionManager.restoreEncryptionPrefs().asOutcome()

    /**
     * The manager's own method returns `Unit` — it commits, then logs whether the commit took, and discards the
     * answer. That is the one operation on this platform whose result is genuinely unknowable afterwards: the
     * write is fire-and-forget, and reporting [EncryptionCheckpointOutcome.CONFIRMED] would be claiming a
     * verification that never happened. So the contract's own uncertainty case is returned rather than a
     * confidence the platform does not have.
     */
    override fun discardCheckpoint(): EncryptionCheckpointOutcome = EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED

    override fun wipeEncryptionState(): EncryptionCheckpointOutcome =
        encryptionManager.resetEncryptionState().asOutcome()

    /**
     * The manager distinguishes exactly one thing — did the write stick — which the contract reports as
     * [EncryptionCheckpointOutcome.CONFIRMED] or [EncryptionCheckpointOutcome.STORE_UNAVAILABLE].
     *
     * [EncryptionCheckpointOutcome.NOT_SUPPORTED] and [EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED] are
     * unreachable through this mapping, and that is a fact about Android rather than an oversight: this platform
     * always has at-rest encryption metadata to act on, and every call it can make reports whether it stuck.
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
