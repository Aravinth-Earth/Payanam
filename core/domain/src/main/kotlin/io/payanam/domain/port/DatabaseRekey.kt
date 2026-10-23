//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.shared.database.DatabaseRekeySequence
import java.io.File

/**
 * Rekeys an already-encrypted database from one passphrase to another, on either platform.
 *
 * ## What this port is for
 *
 * A passphrase change has two halves that cannot be written down in a shared file: the HANDLE the rekey
 * statement runs against, and the sequence it runs. The sequence is already shared
 * ([DatabaseRekeySequence]) because both platforms can run the identical `PRAGMA rekey` against an
 * encrypted file, and what remains unshared is the handle — a `net.zetetic.database.sqlcipher` database
 * on Android, a JDBC `Connection` on the desktop. This port is the named seam between the two, so a shared
 * caller that has a passphrase in hand can ask for a rekey without naming either platform's driver.
 *
 * ## The passphrase boundary — the one thing here that is security-relevant
 *
 * The passphrases are [CharArray] here and `String` in [DatabaseRekeySequence], and that is deliberate in
 * this direction only. A caller that obtained the passphrase from a vault holds it as a [CharArray] and
 * can clear it; a `String` is immutable, lives until the garbage collector takes it, and cannot be wiped.
 * So the [CharArray] stays on the port — the boundary a caller touches — and each implementation converts
 * once, at the point where a PRAGMA literal has to be built, because the statement text is the only form
 * both drivers accept. That conversion is confined to an implementation on purpose: it is the one place
 * where a passphrase stops being erasable, and having exactly two such places is what makes it auditable.
 *
 * A passphrase containing a single quote is a real case (`it's mine`), and the shared sequence escapes it
 * for the same reason.
 *
 * ## What an implementation may not do
 *
 * Report success it did not measure. The sequence's own gate requires the file to open under the new
 * passphrase AND fail under the old one, and every failure of that gate throws — a half-applied rekey
 * locks the user out of their own database, so "it might have worked" is not an answer this port has.
 */
fun interface DatabaseRekey {
    /**
     * Rekeys [databaseFile] from [currentPassphrase] to [newPassphrase] and verifies the result.
     *
     * @return [DatabaseRekeySequence.Outcome.ALREADY_ON_TARGET_PASSPHRASE] when the two passphrases match,
     *   in which case no statement is issued and the file is not touched.
     * @throws IllegalStateException when the file is missing, the driver refuses to open it under
     *   [currentPassphrase], or the result fails the shared gate. The file's encryption is then UNVERIFIED,
     *   and the caller is responsible for restoring whatever it backed up first.
     */
    fun rekeyEncryptedDatabase(
        databaseFile: File,
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
        logTag: String,
    ): DatabaseRekeySequence.Outcome
}
