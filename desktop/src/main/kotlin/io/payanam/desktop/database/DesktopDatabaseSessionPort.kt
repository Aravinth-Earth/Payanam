//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.database.WalCheckpointRule.FoldReport
import kotlinx.coroutines.flow.StateFlow

/**
 * The desktop's implementation of the shared [io.payanam.domain.port.DatabaseSessionManager], over
 * the session controller.
 *
 * Two decisions live here and nowhere else:
 *
 * - **The sealed outcome becomes a [Result].** The controller answers [DatabaseOpenResult] because
 *   it alone decides what gets installed; a shared caller asks a question with a conventional
 *   answer, so the mapping belongs at the edge of the platform.
 * - **A refusal is THINNED to its issue type.** `issue.detailMessage` can carry a filesystem path —
 *   the controller's refusal branch explains why that detail must not escape its own log line — and
 *   a failure message travels to screens, logs, and bug reports. The issue TYPE names which class of
 *   database problem occurred without leaking where the database lives.
 *
 * [sessionOpenState] is the controller's own state, set only where a session exists — not the UI's
 * optimistic flag, whose early `true` produced the recorded desktop session flip-flop.
 */
internal class DesktopDatabaseSessionPort(
    private val controller: DesktopDatabaseSessionController,
) : io.payanam.domain.port.DatabaseSessionManager {
    /**
     * Opens through the controller: an accepted open is success; a refusal becomes a failure
     * carrying ONLY the issue type's name, never the detail (it can hold a filesystem path).
     */
    override suspend fun openDatabase(passphrase: String): Result<Unit> =
        when (val result = controller.openAndInstall(passphrase.toCharArray())) {
            is DatabaseOpenResult.Opened -> {
                controller.closeInFlightForRekey = false
                Result.success(Unit)
            }

            is DatabaseOpenResult.Refused -> {
                Result.failure(IllegalStateException("Desktop database not opened: " + result.issue.type.name))
            }
        }

    /**
     * The controller's fold, awaited, and its honest report either way.
     *
     * The controller answers `null` when nothing is open; the shared contract asks for a report that
     * cannot read as a flushed database, so that absence is mapped to [WalCheckpointRule.NO_ROW] - which
     * is non-empty by construction. A caller that must flush after closing therefore still gets a real
     * fold: this port opens nothing itself and says so by reporting nothing, rather than inventing a
     * flush it did not perform.
     */
    override suspend fun flushPendingChanges(passphrase: String): FoldReport = controller.checkpoint() ?: WalCheckpointRule.NO_ROW

    /** Closes the session; the controller owns the close-and-uninstall ordering. */
    override fun closeDatabase() {
        // The shared change flow closes before its rekey and reopens (or restarts) right after; the
        // gate follower must not arm from this close or it will yank the change screen mid-submit.
        controller.closeInFlightForRekey = true
        controller.closeAndUninstall()
    }

    /**
     * The passphrase the open session was established with, as a String for the shared contract.
     *
     * @throws IllegalStateException when no session is open — failing here is what stops an import
     * from proceeding against the wrong database.
     */
    override fun requireOpenPassphrase(): String =
        controller.passphraseForTransfer()?.concatToString()
            ?: error("DesktopDatabaseSessionPort: no passphrase available — DB not open")

    /** True while the controller holds a session. */
    override fun isSessionOpen(): Boolean = controller.isOpen

    /** The controller's honest state — owner-backed, never the UI's optimistic flag. */
    override val sessionOpenState: StateFlow<Boolean> = controller.sessionOpenState
}
