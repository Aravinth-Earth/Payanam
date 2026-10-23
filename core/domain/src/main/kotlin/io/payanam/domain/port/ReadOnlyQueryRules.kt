//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * The one question [DatabaseSchemaReader] answers, written down once for both implementations to ask.
 *
 * ## WHY THE STATEMENT AND ITS BOUND LIVE HERE RATHER THAN IN EACH IMPLEMENTATION
 *
 * [DatabaseSchemaReader] takes no SQL argument: it is a port that reads the live schema on its own
 * terms, so the text of that read is not something a caller supplies, varies per platform, or may
 * legitimately disagree about. Both answers to it - the desktop's read over a verified read-only
 * handle and Android's read over Room's `openHelper.readableDatabase` - were carrying a private copy
 * of the same statement and the same row bound. Two copies of a security-relevant text is exactly
 * the shape that drifts: one platform's exclusion of the table holding the API key gets edited, the
 * other keeps handing a model a DDL that names the key's column, and both still read plausibly.
 * Shared, the two platforms cannot disagree about what a model is shown.
 *
 * The bound belongs here for the same reason, and not out of tidiness. [SCHEMA_MAX_ROWS] is a property
 * of the QUESTION, not of the platform: it is not the model's context budget - that is the caller's
 * `maxRows` on [ReadOnlyDatabaseQuery.query] - and it is not a measurement of any one schema. It is a
 * limit the port takes on itself, and a number one implementation invents is a number the other
 * implementation is free to disagree with.
 *
 * ## WHAT IS DELIBERATELY NOT HERE
 *
 * The desktop's leading-keyword allow-list is not shared, and Android does not get one. The desktop
 * keeps its own `SELECT`/`VALUES`/`EXPLAIN` set, deliberately without `WITH`, because SQLite permits
 * `WITH ... INSERT`; Android keeps none, because the shared [io.payanam.domain.assistant.ReadOnlySqlGuard]
 * runs before either port and admits `^(select|with)\b` on both, so Android accepts a leading
 * `WITH ... SELECT` today and a shared allow-list would newly refuse a statement that works. What
 * differs between the platforms is how each is defended, not what it may read.
 */
object ReadOnlyQueryRules {
    /**
     * The schema read: every user table's name and its `CREATE TABLE` text, ordered by name.
     *
     * `app_settings` is excluded because it is where the API key lives: a `CREATE TABLE` statement
     * names the column, and a model that can read that table's rows can read the key, so the
     * exclusion belongs where the SQL is written rather than in a caller's good intentions. The
     * `sqlite_%` and `room_%` families are the engine's and Room's own bookkeeping, which describes
     * no user data and changes between releases.
     *
     * The text below is carried over byte-for-byte from both implementations it replaces. This is the
     * statement a model is shown, so an edit here is a change to what the assistant knows about the
     * user's database, not a cleanup.
     */
    const val SCHEMA_QUERY =
        "SELECT name, sql FROM sqlite_master WHERE type='table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
            "AND name != 'app_settings' ORDER BY name"

    /** Far above the number of tables in any schema; a bound, not a guess about this one. */
    const val SCHEMA_MAX_ROWS = 512
}
