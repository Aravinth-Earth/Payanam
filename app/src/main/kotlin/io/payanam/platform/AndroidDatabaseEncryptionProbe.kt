//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.shared.database.DatabaseEncryptionProbe
import java.io.File

/**
 * Android's answer to [DatabaseEncryptionProbe], over Room.
 *
 * A thin adapter, not a second implementation: every rule stays in
 * [DatabaseEncryptionMigrationSupport], which the desktop cannot see and this class does not duplicate. Only
 * the HANDLE differs — a `Context` where the desktop has a JDBC connection.
 */
class AndroidDatabaseEncryptionProbe(
    private val context: Context,
) : DatabaseEncryptionProbe {
    override fun isDetectablyEncrypted(
        databaseFile: File,
        logTag: String,
    ): Boolean = DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(
        context = context,
        databaseFile = databaseFile,
        logTag = logTag,
    )

    override fun canOpen(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ): Boolean = // No context here either: opening a file with a key is a driver operation, not an Android one.
        DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher(
            databaseFile = databaseFile,
            passphrase = passphrase,
            logTag = logTag,
        )

    override fun ensureEncrypted(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ) {
        // Android is the platform that HAS this path, so it runs the real conversion rather than refusing.
        // A false return is a refusal the caller must see, so it throws rather than being ignored: a silent
        // no-op would let the import compare counts across a conversion that never happened and report
        // success on a plaintext file.
        val converted =
            DatabaseEncryptionMigrationSupport.ensureEncryptedWithPassphrase(
                context = context,
                databaseFile = databaseFile,
                passphrase = passphrase,
                logTag = logTag,
            )
        check(converted) { "Re-encryption of " + databaseFile.name + " did not complete" }
    }

    override fun readTableCounts(
        databaseFile: File,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int> = // No context on this one: counting rows never touches the Android framework, so the rule needs no
        // platform handle at all — which is why the parameter used to be here has been removed.
        DatabaseEncryptionMigrationSupport.readTableCounts(
            databaseFile = databaseFile,
            passphrase = passphrase,
            tableNames = tableNames,
        )
}
