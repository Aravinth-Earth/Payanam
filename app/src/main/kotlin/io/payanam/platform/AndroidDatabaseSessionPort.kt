//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.database.WalCheckpointRule.FoldReport
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's implementation of the shared [io.payanam.domain.port.DatabaseSessionManager].
 *
 * Pure delegation: Android's session capability already exists — the wrapped manager owns the Room
 * session, the passphrase, and the open state — so this port adds only the SHARED SHAPE (the same
 * five members the desktop answers) plus an entry breadcrumb on the two state-changing calls, making
 * a shared caller's open/close visible in the same log stream as the platform's own lines. The
 * wrapped manager's `isOpen` flow IS this port's session state: owner-backed, never a UI flag.
 *
 * Both `DatabaseSessionManager`s are named in full in the header because importing either would make
 * the other unreadable.
 */
@Singleton
class AndroidDatabaseSessionPort
    @Inject
    constructor(
        private val delegate: io.payanam.database.session.DatabaseSessionManager,
    ) : io.payanam.domain.port.DatabaseSessionManager {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Breadcrumb first, then the wrapped manager's own open — the data keys mirror its entry log
         * so a request through this port reads as one sequence with the manager's outcome lines.
         */
        override suspend fun openDatabase(passphrase: String): Result<Unit> {
            logger.i(
                "AndroidDatabaseSessionPort.openDatabase",
                "DB open requested via the shared port",
                mapOf(
                    "alreadyOpen" to delegate.isDbOpen(),
                    "passphraseLength" to passphrase.length,
                ),
            )
            return delegate.openDatabase(passphrase)
        }

        /**
         * Breadcrumb first, then the wrapped manager's close; `wasOpen` is read before the close so
         * it reports what the request found, like the manager's own close log.
         */
        override fun closeDatabase() {
            logger.i(
                "AndroidDatabaseSessionPort.closeDatabase",
                "DB close requested via the shared port",
                mapOf("wasOpen" to delegate.isDbOpen()),
            )
            delegate.closeDatabase()
        }

        /**
         * Delegates the awaitable fold to the wrapped manager, which folds through the open session or
         * opens its own handle under [passphrase] when none is open.
         */
        override suspend fun flushPendingChanges(passphrase: String): FoldReport =
            delegate.flushPendingChanges(passphrase)

        /** The wrapped manager's passphrase contract — same IllegalStateException when closed. */
        override fun requireOpenPassphrase(): String = delegate.requireOpenPassphrase()

        /** True while the wrapped manager holds a Room session. */
        override fun isSessionOpen(): Boolean = delegate.isDbOpen()

        /** The wrapped manager's own flow — session-owner state, never a UI/Compose flag. */
        override val sessionOpenState: StateFlow<Boolean> = delegate.isOpen
    }
