//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.transaction

/**
 * Runs a block as ONE database transaction: the whole block commits together, and any failure rolls
 * it back.
 *
 * WHY THIS IS A SHARED CONTRACT: the repositories used to reach for the platform's own transaction
 * helper directly - Android's code called Room's `withTransaction` at every call site, while the JVM
 * data layer had its own [io.payanam.database.jvm.JdbcQueries.transaction]. Two mechanisms meant two
 * chances for the atomicity rules to drift, and neither platform could name what it needed without
 * naming its database library. Callers now depend on this interface; each platform supplies one
 * implementation whose internals nobody else can see.
 *
 * Both implementations serialise on the session's own lock, so a transaction and a plain read or
 * write never interleave on the single connection - the Atomicity rule and the [synchronized] rule
 * from the session types hold together, not separately.
 */
interface DatabaseTransactionRunner {
    /**
     * Runs [block] inside one transaction and returns its result.
     *
     * Must not be called concurrently with a plain read/write on the same session: both go through
     * the session's lock, so they queue, but a caller holding this must also not open a second
     * transaction inside [block] on a different session for the same file.
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
