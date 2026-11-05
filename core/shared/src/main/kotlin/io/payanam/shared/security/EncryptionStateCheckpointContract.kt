//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

/**
 * The safety net that surrounds a destructive, whole-database replacement: the at-rest encryption metadata a
 * caller must be able to put back when the replacement fails.
 *
 * WHY IT IS A CONTRACT AND NOT A VAULT METHOD: replacing the database means deleting every artifact and copying
 * a different file family in their place. The encryption metadata that describes those artifacts — the mode
 * flag, the passphrase verifier, the biometric wrapping — is NOT in the database file, so it survives a
 * replacement untouched while the data it describes is replaced. A replacement that fails half way therefore
 * leaves the two disagreeing: a live verifier for an openable new file, or a wiped verifier for a file that is
 * still the old one. This port is the checkpoint that makes that recoverable.
 *
 * WHY IT IS NAMED FOR THE JOB: the three checkpoint members are one operation seen from three moments —
 * take a copy before the destructive step, put it back if that step failed, drop it once the new database is
 * confirmed good. They are named as one family so a caller reading a log line can see which moment it is in.
 * [wipeEncryptionState] is deliberately NOT in that family, because it is not a checkpoint operation at all: it
 * is the abandon path, and a name like "discard the checkpoint" for something that destroys the live state is
 * how a future caller picks it on a screen where the cheaper-sounding one was meant.
 *
 * WHAT A CALLER MAY ASSUME, ON ANY PLATFORM:
 * - The four members are ordered by the flow below and a caller must follow it.
 * - [restoreCheckpoint] is only safe AFTER a [captureCheckpoint] on the same platform. This is not a style
 *   preference: at least one implementation treats "no backup present" as "remove the live value", so a
 *   restore that finds nothing to restore is a wipe that reports itself as a success.
 * - No member touches the database file, and none is a substitute for
 *   [io.payanam.domain.port.DatabaseForgotPassphraseReset], which is
 *   named for the data it destroys. This port cannot delete a user's data.
 *
 * WHAT A CALLER MAY NOT ASSUME:
 * - That a [discardCheckpoint] took effect. Some platforms cannot confirm their own write, and this contract
 *   makes them say so rather than report a success nobody verified.
 * - That a refused operation (see [EncryptionCheckpointOutcome.NOT_SUPPORTED]) left anything changed. A
 *   platform that does not keep at-rest encryption metadata has nothing to checkpoint, and MUST NOT answer by
 *   reaching for the passphrase vault to make the call look like it did something.
 */
interface EncryptionStateCheckpoint {
    /**
     * Copies the current encryption metadata aside so it can be put back. Call BEFORE the destructive step.
     *
     * A caller that proceeds without a [EncryptionCheckpointOutcome.CONFIRMED] answer has no way back, and the
     * first thing it must then stop saying is "your import can be rolled back".
     */
    fun captureCheckpoint(): EncryptionCheckpointOutcome

    /**
     * Puts the captured metadata back, replacing whatever the failed step left behind.
     *
     * MUST NOT be called unless [captureCheckpoint] reported [EncryptionCheckpointOutcome.CONFIRMED] on this
     * platform. With nothing captured, at least one implementation removes the live keys instead of leaving
     * them, so the safe reading of this method is "I put back what I copied", never "make the state match".
     */
    fun restoreCheckpoint(): EncryptionCheckpointOutcome

    /**
     * Drops the captured copy, because the replacement is now confirmed good.
     *
     * This is irreversible: once the copy is gone there is no way to undo the replacement through this port.
     * A caller that discards before the new database has been opened is deleting its own recovery path, so a
     * platform that cannot confirm its own write MUST say so here instead of returning a confident answer.
     */
    fun discardCheckpoint(): EncryptionCheckpointOutcome

    /**
     * Abandons an in-progress replacement: forgets the live encryption metadata so the caller can start over.
     *
     * NAMED SEPARATELY FROM [PassphraseVault.clearPassphrase] BECAUSE THEY COLLIDE ON AT LEAST ONE PLATFORM AND
     * MUST NOT BE USED INTERCHANGEABLY: they may be the same underlying write there, but they answer different
     * intents. The vault's verb is the unlock gate's "I forgot my passphrase" — the caller is the user and
     * the data is staying. This one is the import flow's "this attempt is not finishing" — the caller still owns
     * the data and is about to restore or discard it itself. A caller that reaches for this one because it
     * sounds more thorough will forget a working passphrase for no gain, and on a platform where the database
     * is not encrypted at all it will do the same thing to a passphrase that was never involved.
     */
    fun wipeEncryptionState(): EncryptionCheckpointOutcome
}

/**
 * What an [EncryptionStateCheckpoint] operation actually did.
 *
 * A named result rather than a boolean for the two reasons the rest of this package already uses them. First,
 * [NOT_SUPPORTED] and [PERFORMED_UNCONFIRMED] are both `false` as booleans, and they call for opposite copy:
 * one is "this platform has no such thing", the other is "it probably happened". Second, a platform is not
 * allowed to hide its own uncertainty, so a member it cannot verify has a case of its own to return.
 */
enum class EncryptionCheckpointOutcome {
    /**
     * The operation took effect and the platform confirmed it.
     *
     * The only outcome on which a caller may tell the user the state changed, or may rely on being able to
     * reverse what just happened.
     */
    CONFIRMED,

    /**
     * The platform does not keep at-rest encryption metadata, so there is nothing to capture, restore,
     * discard or wipe.
     *
     * An honest absence, and explicitly NOT a failure: a desktop build that opens a plaintext database has no
     * encryption state to lose, and reporting anything else would be claiming a protection the platform does
     * not have. An implementation MUST NOT reach into the passphrase vault to make this look like a result.
     */
    NOT_SUPPORTED,

    /**
     * The platform performed the operation but cannot confirm that it stuck.
     *
     * A caller must treat the state as unknown, not as changed and not as unchanged: the honest handling is to
     * say the operation could not be verified rather than to report either outcome as fact.
     */
    PERFORMED_UNCONFIRMED,

    /**
     * The platform tried and could not write.
     *
     * For [EncryptionStateCheckpoint.captureCheckpoint] this means the destructive step must not proceed
     * without saying so; for [EncryptionStateCheckpoint.discardCheckpoint] it is harmless, because the copy
     * surviving is the recoverable state.
     */
    STORE_UNAVAILABLE,
}
