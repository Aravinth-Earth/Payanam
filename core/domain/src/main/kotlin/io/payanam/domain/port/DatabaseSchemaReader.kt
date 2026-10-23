//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * Reads the live schema — the `CREATE TABLE` text of every user table — for the assistant's prompt.
 *
 * ## Why this is a port with NO SQL argument
 *
 * The schema is read from the live database on every call rather than cached, because a cached schema
 * would go stale after an import or a media swap and the model would then be reasoning about columns
 * that no longer exist. That is a real reason for a live read, and it is NOT a reason to expose raw SQL:
 * this use case asks exactly one question and cannot usefully ask another. A port whose only argument is
 * a table name it chooses itself cannot be handed a model's SQL string by mistake, which is the same
 * boundary [ReadOnlyDatabaseQuery] draws with its verified handle. The two are implemented together
 * because one verified read-only handle answers both.
 */
fun interface DatabaseSchemaReader {
    /**
     * Reads the current schema, or the reason it could not be read.
     *
     * Implementation-owned tables (`sqlite_%`, `room_%`) and the settings table that holds the API key
     * are excluded by the implementation, not by convention at the call site.
     */
    fun readSchema(): DatabaseSchemaResult
}

/** One table's name and the `CREATE` statement that defines it. */
data class DatabaseTableSchema(
    val name: String,
    val ddl: String,
)

/** The schema as read, or the reason it was not read. */
sealed interface DatabaseSchemaResult {
    /** The tables read, in the implementation's own order. */
    data class Tables(val tables: List<DatabaseTableSchema>) : DatabaseSchemaResult

    /** No schema was read. Carries the same [ReadOnlyRefusal] vocabulary a query refusal does. */
    data class Refused(
        val reason: ReadOnlyRefusal,
        val detail: String,
    ) : DatabaseSchemaResult
}
