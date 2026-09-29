//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

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
