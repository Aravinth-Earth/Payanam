//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.EncryptionCheckpointOutcome
import io.payanam.shared.security.EncryptionStateCheckpoint

/**
 * The desktop's [EncryptionStateCheckpoint]: every member refuses, and says why.
 *
 * THIS IS AN HONEST ABSENCE RATHER THAN A MISSING IMPLEMENTATION, and the difference is the whole point of the
 * contract's [EncryptionCheckpointOutcome.NOT_SUPPORTED] case. The desktop has no at-rest encryption
 * METADATA: no mode flag, no passphrase verifier on the file, and no biometric wrapping. (Whether the file's
 * bytes are encrypted is a different question, and [DesktopPassphraseVault] answers it about the FILE, through
 * the same probe every other file reader uses - this checkpoint is about the metadata around the file, not
 * about the bytes.) A checkpoint of nothing is not a checkpoint, so each member reports
 * [EncryptionCheckpointOutcome.NOT_SUPPORTED] instead of returning a success that would let a shared import
 * flow tell a user their plaintext database "can be rolled back".
 *
 * WHY IT IS A REAL CLASS AND NOT A `DesktopUnsupportedPorts` ENTRY: the refusals there are for capabilities
 * the desktop lacks but Android has, and they are inert until something asks. This one is wired in Stage 5 and
 * a caller that asks is mid-way through deleting a user's database — the answer is load-bearing at runtime, so
 * it belongs where the reasoning about this platform's storage can be read next to it.
 *
 * WHY IT DOES NOT DELEGATE TO THE VAULT, WHICH IS THE TEMPTING SHORTCUT: the desktop's
 * [io.payanam.desktop.DesktopSecurityStore] does hold a passphrase verifier, and
 * [DesktopPassphraseVault.clearPassphrase] does have a working `resetSecurityState()` to call. Mapping
 * `wipeEncryptionState` onto it would return
 * [EncryptionCheckpointOutcome.CONFIRMED] for a platform that holds no encryption state — claiming a wipe of
 * something that does not exist, and doing it to a passphrase the import flow never touched. The shared
 * contract forbids that mapping in so many words, and this class is where that rule is paid for: a desktop
 * import that abandons mid-way forgets nothing, because there was never anything to forget.
 *
 * They log rather than returning a bare constant, matching the rest of the desktop's refusals: a session that
 * silently skipped a rollback checkpoint is legible in its own log instead of only in the user's data.
 */
internal class DesktopEncryptionStateCheckpoint : EncryptionStateCheckpoint {
    private val logger = AppLogger.current

    override fun captureCheckpoint(): EncryptionCheckpointOutcome = notApplicable("captureCheckpoint")

    override fun restoreCheckpoint(): EncryptionCheckpointOutcome = notApplicable("restoreCheckpoint")

    override fun discardCheckpoint(): EncryptionCheckpointOutcome = notApplicable("discardCheckpoint")

    override fun wipeEncryptionState(): EncryptionCheckpointOutcome = notApplicable("wipeEncryptionState")

    private fun notApplicable(operation: String): EncryptionCheckpointOutcome {
        logger.w(
            "DesktopEncryptionStateCheckpoint.$operation",
            "This platform stores the database unencrypted, so there is no encryption state to " +
                "capture, restore, discard or wipe; the request was not performed",
        )
        return EncryptionCheckpointOutcome.NOT_SUPPORTED
    }
}
