//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseOpenOutcome
import io.payanam.shared.database.PayanamDatabaseHandle
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.database.PayanamDatabaseOpener as DatabaseOpenerContract

/**
 * The shared unlock ViewModel's opener, routed through the desktop's ONE session controller.
 *
 * The defect this replaces: the collaborators held a bare [DesktopDatabaseOpenerAdapter], so a
 * shared-screen unlock opened its OWN session through `:core:database-jvm` and then dropped the handle.
 * `LocalDataLayer` stayed UNINSTALLED, so an unlocked desktop shell read empty lists. Opening through
 * [DesktopDatabaseSessionController.openAndInstall] is what leaves the shared data layer in force.
 *
 * ## THE PATH ARGUMENT IS IGNORED, AND THAT IS LOGGED
 *
 * [DatabaseOpenerContract.open] takes the caller's `databasePath`; the controller does not - it opens the
 * store's own file. The shared ViewModel passes the same file this desktop resolves, but the two are not
 * the same CONTRACT, so a caller that ever handed this adapter a second path would silently have the other
 * one opened. The divergence is therefore reported on every call rather than treated as a detail.
 *
 * ## AN ALREADY-OPEN SESSION SHORT-CIRCUITS TO A NO-OP HANDLE
 *
 * `openAndInstall` begins by closing and uninstalling. Calling it on the re-auth path
 * (`onSilentUnlockRequested` -> `PASSPHRASE_UNLOCK`) would therefore close and reinstall underneath live
 * retained ViewModels still holding the closed connection. When [DesktopDatabaseSessionController.isOpen]
 * is already true the controller is left untouched and [OpenedSessionHandle] is returned: the session is in
 * force, so an `Opened` outcome is true and the shared screen's success callback fires.
 *
 * ## `readOnly` IS REFUSED, NOT DOWNGRADED AND NOT IGNORED
 *
 * The contract makes `readOnly` a request a platform must refuse rather than quietly honour as a
 * read-write open, and `openAndInstall` has no read-only mode. The unlock ViewModel only ever passes
 * `false`; a `true` here means some future caller changed, and that is the case that must fail loudly.
 *
 * ## NO RE-VERIFICATION
 *
 * The typed unlock path verifies through the vault before it opens, and that verdict is what cleared the
 * lockout. Verifying again here would re-derive the same answer through a different code path.
 * [verifyPassphrase] exists because the contract requires it, and delegates to [DesktopDatabaseOpenerAdapter]
 * for callers that need a path-based probe.
 */
internal class DesktopSessionControllerOpenerAdapter(
    private val sessionController: DesktopDatabaseSessionController,
) : DatabaseOpenerContract {
    override suspend fun verifyPassphrase(
        databasePath: String,
        passphrase: CharArray,
    ): Boolean = DesktopDatabaseOpenerAdapter().verifyPassphrase(databasePath, passphrase)

    override suspend fun open(
        databasePath: String,
        passphrase: CharArray,
        readOnly: Boolean,
    ): DatabaseOpenOutcome {
        if (readOnly) {
            AppLogger.current.w(
                "DesktopSessionControllerOpenerAdapter.open",
                "Refusing a read-only open: the desktop session controller installs a read-write layer only",
            )
            return DatabaseOpenOutcome.Refused(
                DatabaseBootIssue(type = DatabaseBootIssueType.NON_REPAIRABLE_GENERIC),
            )
        }
        if (sessionController.isOpen) {
            AppLogger.current.i(
                "DesktopSessionControllerOpenerAdapter.open",
                "Session already open; the controller is left in force and no reopen is attempted",
            )
            return DatabaseOpenOutcome.Opened(OpenedSessionHandle)
        }
        AppLogger.current.w(
            "DesktopSessionControllerOpenerAdapter.open",
            "Opening through the session controller, which uses the store's own file rather than the path " +
                "the caller supplied",
            mapOf("callerSuppliedFileName" to databasePath.substringAfterLast('/')),
        )
        return when (val result = sessionController.openAndInstall(passphrase)) {
            is DatabaseOpenResult.Opened -> DatabaseOpenOutcome.Opened(result.session)
            is DatabaseOpenResult.Refused -> DatabaseOpenOutcome.Refused(result.issue)
        }
    }
}

/**
 * Stands in for the session the controller already holds, and is not the caller's to close.
 *
 * [close] is deliberately a no-op: the controller owns the real handle, and a screen that closed this would
 * close nothing rather than the live session.
 *
 * **Honest limit:** [schemaVersion] reports the version this build writes, because the controller exposes no
 * way to read the open session's reported version. The unlock path only classifies the outcome and never
 * reads the handle, so nothing depends on this value today - but a caller that starts reading it needs the
 * controller to expose the session first.
 */
private object OpenedSessionHandle : PayanamDatabaseHandle {
    override val schemaVersion: Int = PayanamDatabaseSchema.VERSION
    override val isOpen: Boolean = true

    override fun close() = Unit
}
