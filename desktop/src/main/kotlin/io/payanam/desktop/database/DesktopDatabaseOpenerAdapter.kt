//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.DatabaseOpenMode
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.shared.database.DatabaseOpenOutcome
import java.nio.file.Path
import io.payanam.shared.database.PayanamDatabaseOpener as DatabaseOpenerContract

/**
 * The desktop's implementation of the shared database-opener contract, over the JDBC/SQLCipher opener.
 *
 * The opener itself is platform-specific (`:core:database-jvm`), so this adapter is where the desktop side
 * of the seam lives: it is the only place that knows both the JDBC result type and the shared one.
 *
 * The mapping is deliberately total rather than defensive. [DatabaseOpenResult] has exactly two cases today
 * and the shared outcome has exactly two, so every case is spelled out; a future third case is a compile
 * error here instead of a refusal that silently reports the wrong thing.
 */
internal class DesktopDatabaseOpenerAdapter : DatabaseOpenerContract {
    override suspend fun verifyPassphrase(
        databasePath: String,
        passphrase: CharArray,
    ): Boolean = PayanamDatabaseOpener.verifyPassphrase(Path.of(databasePath), passphrase.concatToString())

    override suspend fun open(
        databasePath: String,
        passphrase: CharArray,
        readOnly: Boolean,
    ): DatabaseOpenOutcome =
        when (val result = openSession(databasePath, passphrase, readOnly)) {
            is DatabaseOpenResult.Opened -> DatabaseOpenOutcome.Opened(result.session)
            is DatabaseOpenResult.Refused -> DatabaseOpenOutcome.Refused(result.issue)
        }

    /**
     * The same open, in the JDBC module's own result type.
     *
     * Exists so the desktop's startup path can hold the CONCRETE session it needs to build repositories,
     * without casting a `PayanamDatabaseHandle` back down. Shared callers use [open] and never see this;
     * the desktop uses this and never casts.
     */
    suspend fun openSession(
        databasePath: String,
        passphrase: CharArray,
        readOnly: Boolean = false,
    ): DatabaseOpenResult =
        PayanamDatabaseOpener.open(
            databasePath = Path.of(databasePath),
            passphrase = passphrase.concatToString(),
            mode = if (readOnly) DatabaseOpenMode.READ_ONLY else DatabaseOpenMode.READ_WRITE,
        )
}
