//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.domain.port.DatabaseRekey
import io.payanam.shared.database.DatabaseRekeySequence
import java.io.File

/**
 * Android's answer to the rekey port: the shared sequence, run over SQLCipher's own handle.
 *
 * A separate class from [AndroidDatabaseEncryptionProbe] rather than a second capability on it, because a
 * passphrase change needs a rekey and nothing else on this platform does — so a caller that only has to
 * answer whether a file is encrypted is never handed a rekey capability.
 *
 * ## The one place a passphrase stops being erasable on this platform
 *
 * The passphrases arrive as [CharArray] and are converted once, here, because a `PRAGMA rekey` statement is
 * the only form the driver takes. That is the port's security-relevant boundary, and it is a whole file
 * rather than a line so it can be found by looking for the conversion. The desktop has its own, and there
 * are exactly two.
 */
class AndroidDatabaseRekey(
    private val context: Context,
) : DatabaseRekey {
    override fun rekeyEncryptedDatabase(
        databaseFile: File,
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
        logTag: String,
    ): DatabaseRekeySequence.Outcome =
        DatabaseEncryptionMigrationSupport.rekeyEncryptedDatabase(
            context = context,
            databaseFile = databaseFile,
            currentPassphrase = currentPassphrase.concatToString(),
            newPassphrase = newPassphrase.concatToString(),
            logTag = logTag,
        )
}
