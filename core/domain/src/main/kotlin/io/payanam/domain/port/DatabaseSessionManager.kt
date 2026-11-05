//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.shared.database.WalCheckpointRule
import kotlinx.coroutines.flow.StateFlow

/**
 * Opening, closing and observing the one encrypted database session.
 *
 * This capability is SUPPORTED on both platforms — not an Android path with a desktop stub. Android
 * delegates to its Room/SQLCipher session manager, the desktop to its JDBC session controller; both
 * answer every member here for real, so a shared caller asks either platform the same question and
 * gets the same shape of answer.
 *
 * [sessionOpenState] MUST be backed by the session owner's own state — never by a UI/Compose state.
 * An optimistic UI flag that fired before the open completed caused a recorded desktop session
 * flip-flop: the screen claimed an open the session had not reached, and everything reading that flag
 * acted on a database that was not there. The owner flips its state only once the session exists; the
 * UI observes.
 */
interface DatabaseSessionManager {
    /**
     * Opens the session with [passphrase], or reports why it could not.
     *
     * A [Result] rather than a throw because "the database would not open" (wrong passphrase, missing
     * file) is an outcome a screen must present, not a crash.
     */
    suspend fun openDatabase(passphrase: String): Result<Unit>

    /**
     * Folds the write-ahead log so the MAIN database file holds every pending write, under [passphrase],
     * and reports what the fold actually did.
     *
     * **Awaits the flush rather than reporting one.** [closeDatabase] deliberately does not fold, so a
     * caller that re-keys or copies the file after closing would take a main file that is a snapshot from
     * whenever the last fold happened: the most recent commits live only in the `-wal` sidecar, still
     * encrypted under the CURRENT key. Re-keying then rewrites the key on one file alone, and those
     * frames are left under a key nothing will ever present again — unreadable, and nothing says so. So
     * the passphrase change waits here and proceeds only on an empty log; there is no path where the
     * change happens while writes are still unsaved.
     *
     * **Why the passphrase travels with the call.** Folding needs a handle opened under the database's
     * key, and the caller is the one that holds it. While a session is open the platform folds through
     * that session; with no session open the platform opens its own handle under [passphrase] for the
     * fold alone. So this member can complete on a platform whose session is not the one holding the
     * pending frames, rather than reporting a fold that never ran.
     *
     * A non-empty [WalCheckpointRule.FoldReport] — including [WalCheckpointRule.NO_ROW] — means the main
     * file may not hold every write, and the caller does not proceed. Never throws for that; throwing is
     * reserved for nothing here, so a caller reads ONE shape of answer on both platforms.
     */
    suspend fun flushPendingChanges(passphrase: String): WalCheckpointRule.FoldReport

    /**
     * Releases the session, so a read after shutdown fails loudly instead of succeeding against a
     * stale handle.
     */
    fun closeDatabase()

    /**
     * The passphrase the current session was established with.
     *
     * @throws IllegalStateException when no session is open: the import and re-key flows that need
     * it must fail here rather than proceed against the wrong database.
     */
    fun requireOpenPassphrase(): String

    /** True only while a session is genuinely open. */
    fun isSessionOpen(): Boolean

    /**
     * Hot stream of the session's open state, for a screen that reacts rather than polls.
     *
     * Its current value always agrees with [isSessionOpen], because both come from the session
     * owner's own state — see the interface note: never a UI/Compose flag.
     */
    val sessionOpenState: StateFlow<Boolean>
}
