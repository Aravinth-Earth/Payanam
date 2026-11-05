//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.jvm.schema.PayanamDatabaseSchemaDdl
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * Opens the encrypted Payanam database on the JVM.
 *
 * Every guard here encodes a behaviour **measured** against SQLCipher JDBC driver `0.2.13`, not assumed:
 *
 * - `?mode=ro` on the JDBC URL is **silently ignored** — it connects without error, `PRAGMA query_only`
 *   stays `0`, and a write succeeds. A design trusting it would ship a false read-only guarantee, so it
 *   is never used.
 * - `Connection.setReadOnly(true)` **throws** `SQLFeatureNotSupportedException`, as does
 *   `getTransactionIsolation()`. `PRAGMA query_only = ON` is the only enforced read-only mechanism, and
 *   it is read back and required to be `1`.
 * - `PRAGMA foreign_keys` is **OFF on every new connection** and is never stored in the file, so a
 *   raw driver open enforces nothing: every declared `ON DELETE CASCADE` / `SET NULL` is inert and a
 *   delete leaves its children behind in silence. Android never meets this, because Room's generated
 *   open callback issues `PRAGMA foreign_keys = ON` for it; the JDBC driver does not, so the same file
 *   behaves differently on the two platforms. [enforceForeignKeys] issues it on every [open] and
 *   requires it to read back as `1`, which is what makes a delete cascade exactly as it does on
 *   Android — parity with Room's delegate, not a product choice.
 * - `Connection.autoCommit` is `true` on open, so a caller need not commit explicitly after a write.
 * - `PRAGMA user_version` **can** be written through this driver, but only on a path this driver does
 *   not execute: `execute`, `executeUpdate` and a trailing semicolon all persist `0` (it stores
 *   query-shaped SQL without stepping it). The one shape that works is
 *   `createStatement().executeQuery(sql).use { it.next() }` — a query whose [java.sql.ResultSet] is
 *   DRAINED. [writePragma] is the only place that does so, and [create] is what depends on it: a file
 *   whose `user_version` persisted `0` would be refused by its own [classifySchemaVersion].
 * - `PRAGMA journal_mode = WAL` is the same shape (query + drain) — plain `execute` silently leaves it
 *   `delete` — but [create] deliberately leaves the default `delete` journal alone. A WAL database is
 *   only self-contained once its last connection checkpoints and removes the `-wal`/`-shm` sidecars,
 *   and [create] MOVES a single file into place; carrying sidecars across that move would strand
 *   committed data in a log file the guards then refuse as a sign of an unclean copy.
 * - The driver **scrubs the key** out of the [Properties] object it is handed, so a reused instance
 *   makes the SECOND connection fail `file is not a database`. [connect] therefore builds a fresh
 *   [Properties] per connection rather than hoisting one.
 * - A wrong passphrase **connects** without error; the failure surfaces at the first statement, when
 *   the key is first needed to read a page. So "connected" is never evidence the key is right, and
 *   [verifyPassphrase] proves the key by reading through it rather than by connecting.
 * - A `-wal`/`-shm`/`-journal` beside the database is refused: `query_only` is applied after the
 *   connection opens, so SQLite may replay the log into the main file before any guard can act.
 *
 * The passphrase is passed in and never derived, logged, or measured. The absolute database path is
 * never logged either — it would leak the user's home-directory layout into a log they might paste into
 * a public issue.
 */
object PayanamDatabaseOpener {
    private const val SOURCE = "PayanamDatabaseOpener"

    private const val DRIVER_CLASS = "io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherDriver"

    /** Internal, not private: the desktop's health check opens the same URL with the same key property, and a
     * second copy of either string is a second convention to drift. */
    internal const val URL_PREFIX = "jdbc:sqlcipher:"
    internal const val KEY_PROPERTY = "key"

    /**
     * Milliseconds a blocked write waits instead of failing immediately with `SQLITE_BUSY`.
     *
     * The WRITE paths wait this long - the opener's own, the create path's and the rekey's - so a lock
     * that is about to clear is waited out rather than turned into a failure.
     */
    private const val BUSY_TIMEOUT_MS = 5000

    /** Sidecars that mean the file was not cleanly checkpointed before it was copied. */
    private val SIDECAR_SUFFIXES = DatabaseArtifacts.sidecarSuffixes

    /**
     * Suffix of the sibling file [create] builds before moving it into place.
     *
     * Fixed rather than a random/temp name so the file a failed create leaves behind is identifiable
     * in a bug report — and, more importantly, so it carries NONE of the sidecar suffixes the guards
     * refuse. A `createTempFile` name that ended in `-wal` would make the moved-in database look like
     * an unclean copy to the very next `open`.
     */
    private const val TEMP_SUFFIX = ".payanam-creating"

    // Loaded once, before any thread can connect: the driver extracts its native library on first use,
    // and two threads racing that extraction is a documented failure mode. Class.forName is
    // thread-safe, so doing it here makes the race impossible rather than merely unlikely.
    private val driverLoaded: Boolean by lazy {
        try {
            Class.forName(DRIVER_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /**
     * Opens [databasePath] with [passphrase].
     *
     * @param mode the contract to open under.
     * @return [DatabaseOpenResult.Opened] with a session the caller owns and must close, or
     *   [DatabaseOpenResult.Refused] carrying the same issue categories Android's boot screen uses.
     */
    fun open(
        databasePath: Path,
        passphrase: String,
        mode: DatabaseOpenMode = DatabaseOpenMode.READ_WRITE,
    ): DatabaseOpenResult {
        preflight(databasePath)?.let { return it }

        val connection =
            try {
                connect(databasePath, passphrase)
            } catch (error: SQLException) {
                return refuse(
                    DatabaseBootIssueType.OPEN_FAILED,
                    detail = "${RefusalDetail.OPEN_THREW}:${error.javaClass.simpleName}",
                    error = error,
                )
            }

        try {
            applyBusyTimeout(connection)

            // Before anything reads or writes a row: a declared CASCADE is inert until this is on, and
            // this is the same invariant Android gets from Room's open callback. Refused rather than
            // logged, because an open that silently enforces nothing looks identical to one that does.
            if (!enforceForeignKeys(connection)) {
                connection.close()
                return refuse(
                    DatabaseBootIssueType.OPEN_FAILED,
                    RefusalDetail.FOREIGN_KEYS_NOT_ENFORCED,
                )
            }

            val version = readUserVersion(connection)
            classifySchemaVersion(version)?.let { problem ->
                connection.close()
                return refuse(problem, detectedVersion = version)
            }

            val missing = findMissingCriticalTables(connection)
            if (missing.isNotEmpty()) {
                connection.close()
                return refuse(
                    DatabaseBootIssueType.SCHEMA_INVALID,
                    detail = missing.joinToString(","),
                    detectedVersion = version,
                )
            }

            if (mode == DatabaseOpenMode.READ_ONLY && !enforceReadOnly(connection)) {
                connection.close()
                return refuse(
                    DatabaseBootIssueType.OPEN_FAILED,
                    RefusalDetail.READ_ONLY_NOT_ENFORCED,
                    detectedVersion = version,
                )
            }

            logOpened(version, mode)
            return DatabaseOpenResult.Opened(PayanamDatabaseSession(connection, version, mode))
        } catch (error: SQLException) {
            runCatching { connection.close() }
            return refuse(
                DatabaseBootIssueType.OPEN_FAILED,
                detail = "${RefusalDetail.GUARD_THREW}:${error.javaClass.simpleName}",
                error = error,
            )
        }
    }

    /**
     * Creates an encrypted Payanam database at [databasePath], on the same driver/key/URL path as
     * [open]. This is what makes a first run on a machine that has never had Payanam possible: until
     * it exists there is nothing to open, and a Linux user with no Android export to import was stuck
     * at the gate.
     *
     * **Failure-atomic, by construction.** The database is built at a temporary sibling path and is
     * only [Files.move]d into place once EVERY step below has succeeded. A half-built file cannot
     * reach [databasePath], and a failure deletes the temporary file, so the user's directory is left
     * exactly as it was found. The move carries **no** `REPLACE_EXISTING`: an existing database is
     * refused, never overwritten — a user who can no longer open their own database has no recovery,
     * so overwriting one is the one outcome this must never produce.
     *
     * **The temporary file is proved, not assumed.** Before the move, the built database is read back
     * through the same connection: `user_version`, the critical tables and Room's identity hash must
     * all be what [PayanamDatabaseSchemaDdl] says they are. A create that "succeeded" but produced a
     * file this build would then refuse is a failed create, caught while it is still temporary.
     *
     * **What this deliberately does NOT do**, because the app does it on both platforms exactly as
     * Android does: it does not write the `database_init_completed` flag and it does not seed life
     * dimensions. Seeding them here would mean a second, divergent definition of the default set.
     *
     * @return [DatabaseOpenResult.Opened] on a session the caller owns, or
     *   [DatabaseOpenResult.Refused] carrying the same issue categories [open] uses.
     */
    fun create(
        databasePath: Path,
        passphrase: String,
    ): DatabaseOpenResult {
        preflightForCreate(databasePath)?.let { return it }

        val target = databasePath.toAbsolutePath()
        val temporary = temporarySiblingFor(target)
        // If a temporary path was ALREADY there, it is not ours and a failure must not delete it —
        // the cleanup below exists to undo this call, not to tidy the user's directory.
        val temporaryPreexisted = Files.exists(temporary)
        var moved = false
        // `Exception`, not `Throwable`, and deliberately: the three things that can go wrong here are
        // a driver SQLException, an IOException from the move, and the IllegalArgumentException the
        // read-back `require`s raise — but which one a given failure arrives as is the driver's and
        // the JVM's business, not this method's, and a create path that propagated a raw
        // SQLException out of its own rollback would leave callers with an exception instead of the
        // refusal the rest of this class returns. Anything worse (an Error) is a bug or an exhausted
        // JVM, and swallowing it would be the greater dishonesty; it propagates, and the worst it
        // leaves behind is the fixed-name temporary file the next create overwrites.
        try {
            buildAt(temporary, passphrase)
            // No REPLACE_EXISTING: an existing file must be refused, not clobbered. (A race that
            // creates the target between the preflight and here fails this move rather than winning.)
            Files.move(temporary, target)
            moved = true
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            // A failure AFTER the move deletes the database this call just created, so the directory
            // returns to the state it was in before the call rather than to a file nobody can open.
            if (moved) {
                deleteQuietly(target)
            } else if (!temporaryPreexisted) {
                deleteQuietly(temporary)
            }
            return refuse(
                DatabaseBootIssueType.OPEN_FAILED,
                detail = "${RefusalDetail.CREATE_THREW}:${error.javaClass.simpleName}",
                error = error,
            )
        }

        // Re-open through the real guards so the caller gets a session proven the same way any other
        // database is, and so a created file that cannot be opened by `open` is a refusal, not a
        // silent success.
        val result = open(target, passphrase)
        if (result is DatabaseOpenResult.Refused) {
            deleteQuietly(target)
        } else {
            AppLogger.current.i(SOURCE, "Created encrypted database", mapOf("schemaVersion" to PayanamDatabaseSchemaDdl.SCHEMA_VERSION))
        }
        return result
    }

    /**
     * Builds the whole database at [temporary] and reads it back before returning, so every way this
     * can go wrong happens while the target path is still untouched.
     */
    private fun buildAt(
        temporary: Path,
        passphrase: String,
    ) {
        connect(temporary, passphrase).use { connection ->
            connection.createStatement().use { statement ->
                PayanamDatabaseSchemaDdl.TABLES.values.forEach(statement::execute)
                PayanamDatabaseSchemaDdl.INDICES.values.forEach(statement::execute)
                PayanamDatabaseSchemaDdl.SETUP_QUERIES.forEach(statement::execute)
            }
            applyBusyTimeout(connection)
            // The DRAINED-query path — see the class KDoc. `execute` here would persist 0 and the
            // file this call produces would be refused by its own classifySchemaVersion.
            writePragma(connection, "PRAGMA user_version = ${PayanamDatabaseSchemaDdl.SCHEMA_VERSION}")

            val version = readUserVersion(connection)
            require(version == PayanamDatabaseSchemaDdl.SCHEMA_VERSION) {
                "Created database reports user_version $version, not ${PayanamDatabaseSchemaDdl.SCHEMA_VERSION}"
            }
            val missing = findMissingCriticalTables(connection)
            require(missing.isEmpty()) { "Created database is missing critical table(s) ${missing.joinToString(",")}" }
            val identityHash = readIdentityHash(connection)
            require(identityHash == PayanamDatabaseSchemaDdl.IDENTITY_HASH) {
                "Created database reports identity hash '$identityHash', not '${PayanamDatabaseSchemaDdl.IDENTITY_HASH}'"
            }
        }
    }

    /**
     * A temporary path beside the target — same directory, so the move cannot cross a filesystem and
     * degrade into a copy, and same directory so the guards' sidecar rules apply to it too.
     */
    private fun temporarySiblingFor(target: Path): Path = target.resolveSibling("${target.fileName}$TEMP_SUFFIX")

    private fun readIdentityHash(connection: Connection): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT identity_hash FROM room_master_table WHERE id = 42").use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }

    /**
     * Issues a pragma that must take effect in the file through this driver.
     *
     * [java.sql.Statement.execute] and `executeUpdate` both persist `0` for `PRAGMA user_version` on
     * SQLCipher JDBC 0.2.13 (measured) — the driver records the SQL without stepping it. Running it
     * as a QUERY and draining the [java.sql.ResultSet] is the shape that does execute, so the single
     * `next()` is the load-bearing part of this function and must not be "simplified" away.
     */
    private fun writePragma(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                while (rows.next()) {
                    // Drained deliberately: see above.
                }
            }
        }
    }

    private fun deleteQuietly(path: Path) {
        SIDECAR_SUFFIXES.forEach { suffix -> runCatching { Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + suffix)) } }
        runCatching { Files.deleteIfExists(path) }
    }

    /**
     * Whether [passphrase] is the key [databasePath] was encrypted with.
     *
     * Mirrors Android's `canOpenWithSqlCipher` probe: the import step must verify the passphrase+file
     * pair BEFORE copying anything, so a wrong passphrase is reported as such rather than accepted and
     * failing later.
     *
     * Separate from [open] deliberately — "is this the right passphrase" is a different question from
     * "may we operate on this database", and Android keeps the two apart as well. A missing file or a
     * sidecar is not a passphrase verdict, so both return `false` without claiming one.
     */
    fun verifyPassphrase(
        databasePath: Path,
        passphrase: String,
    ): Boolean {
        if (!Files.exists(databasePath) || !Files.isReadable(databasePath)) return false
        if (findSidecar(databasePath) != null) return false
        if (!driverLoaded) return false
        return try {
            connect(databasePath, passphrase).use { connection ->
                // Reading through the key forces page decryption; a wrong key fails here.
                readUserVersion(connection)
                true
            }
        } catch (_: SQLException) {
            false
        }
    }

    /**
     * Path-level guards for [open], where the database is expected to already exist.
     *
     * Kept separate from [preflightForCreate] on purpose. The two paths ask opposite questions of the
     * filesystem — "may I operate on this file" versus "may I create this file" — and a missing file
     * is the SUCCESS case for create and the refusal case for open, so a shared guard could not be
     * written without one of the two being wrong.
     */
    private fun preflight(databasePath: Path): DatabaseOpenResult.Refused? {
        if (!Files.exists(databasePath)) {
            return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.NOT_FOUND)
        }
        if (!Files.isReadable(databasePath)) {
            return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.NOT_READABLE)
        }
        if (!driverAvailable()) return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.NATIVE_UNAVAILABLE)
        findSidecar(databasePath)?.let { sidecar ->
            // Android's category is "sidecars without a clean main file". The desktop extends it to
            // "sidecars beside a main file", because the danger — a replayed log mutating the file
            // before query_only applies — is identical.
            return refuse(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING, detail = sidecar)
        }
        return null
    }

    /**
     * Path-level guards for [create], which is where a fresh install lives.
     *
     * **The parent directory is the thing checked, not the target file.** A file that does not exist
     * yet is not readable, so reusing [preflight] here would refuse EVERY fresh install and the
     * create path could never run — the bug this split exists to prevent. What create needs to know
     * is whether the DIRECTORY it must write into exists and accepts writes.
     *
     * An existing file at the target is refused rather than replaced: see [create].
     */
    private fun preflightForCreate(databasePath: Path): DatabaseOpenResult.Refused? {
        if (Files.exists(databasePath)) {
            return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.ALREADY_EXISTS)
        }
        val directory =
            databasePath.toAbsolutePath().parent
                ?: return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.NO_PARENT_DIRECTORY)
        if (!Files.isDirectory(directory)) {
            return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.PARENT_NOT_FOUND)
        }
        if (!Files.isWritable(directory)) {
            return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.PARENT_NOT_WRITABLE)
        }
        // A sidecar beside a non-existent target can only be a leftover, and the guards would refuse
        // the file we are about to move in beside it.
        findSidecar(databasePath)?.let { sidecar ->
            return refuse(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING, detail = sidecar)
        }
        if (!driverAvailable()) return refuse(DatabaseBootIssueType.OPEN_FAILED, RefusalDetail.NATIVE_UNAVAILABLE)
        return null
    }

    private fun driverAvailable(): Boolean = driverLoaded

    private fun connect(
        databasePath: Path,
        passphrase: String,
    ): Connection {
        // A FRESH Properties per connection, deliberately: the driver scrubs the key out of the object
        // it is handed, so reusing one instance makes the second connection fail 'file is not a
        // database'. A shared, hoisted instance looks like a harmless optimisation and is a latent
        // single-connection bug.
        val properties =
            Properties().apply {
                // Android passes the raw UTF-8 passphrase bytes as the key; this property carries the
                // same secret as text and the driver encodes it identically. That equivalence is what
                // lets the two platforms open the same file at all.
                setProperty(KEY_PROPERTY, passphrase)
            }
        return DriverManager.getConnection("$URL_PREFIX${databasePath.toAbsolutePath()}", properties)
    }

    /**
     * Issues `PRAGMA busy_timeout`, so a blocked write WAITS instead of failing instantly.
     *
     * Internal, not private: the desktop's REKEY connection is opened by [DesktopDatabaseEncryptionProbe]
     * beside this one, and a rekey that meets a transient lock has to wait exactly as every other
     * connection in this module does. The pragma text and the number stay in this file, so there is ONE
     * convention rather than two - the same reason [URL_PREFIX] and [KEY_PROPERTY] are internal.
     */
    internal fun applyBusyTimeout(connection: Connection) {
        connection.createStatement().use { it.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS") }
    }

    private fun readUserVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }

    /** Sets `PRAGMA query_only = ON` and returns whether it read back as enforced. */
    private fun enforceReadOnly(connection: Connection): Boolean {
        connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
        return connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA query_only").use { rows ->
                if (rows.next()) rows.getInt(1) == 1 else false
            }
        }
    }

    /**
     * Sets `PRAGMA foreign_keys = ON` and returns whether it read back as enforced.
     *
     * The default is OFF and the setting is per-connection, never stored in the file — so this is not
     * an optimisation or a preference, it is the difference between a declared `ON DELETE CASCADE`
     * firing and not firing at all. Room issues this for Android; nothing issues it for a raw JDBC
     * connection, which is why the same database behaved differently on the two platforms.
     *
     * Read back rather than assumed, like [enforceReadOnly]: a driver that reported success while
     * leaving the pragma at `0` would make the guarantee false in exactly the silent way this guards
     * against, so a failure to enforce is a refusal rather than a logged warning.
     */
    private fun enforceForeignKeys(connection: Connection): Boolean {
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        return connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_keys").use { rows ->
                if (rows.next()) rows.getInt(1) == 1 else false
            }
        }
    }

    private fun findMissingCriticalTables(connection: Connection): List<String> {
        val present = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                while (rows.next()) {
                    rows.getString(1)?.let(present::add)
                }
            }
        }
        return PayanamDatabaseSchema.CRITICAL_TABLES.filterNot(present::contains).sorted()
    }

    private fun findSidecar(databasePath: Path): String? =
        SIDECAR_SUFFIXES.firstOrNull { suffix ->
            Files.exists(databasePath.resolveSibling(databasePath.fileName.toString() + suffix))
        }

    private fun logOpened(
        version: Int,
        mode: DatabaseOpenMode,
    ) {
        AppLogger.current.i(
            SOURCE,
            "Opened encrypted database",
            mapOf("schemaVersion" to version, "mode" to mode.name),
        )
    }

    /**
     * Records the refusal and returns it. The path is never logged, and neither is the passphrase nor
     * its length — a helpful log must not become the leak.
     */
    private fun refuse(
        type: DatabaseBootIssueType,
        detail: String? = null,
        detectedVersion: Int = 0,
        error: Throwable? = null,
    ): DatabaseOpenResult.Refused {
        val issue = DatabaseBootIssue(type, detail, detectedVersion)
        val payload =
            buildMap<String, Any?> {
                put("issue", type.name)
                if (detail != null) put("detail", detail)
                put("schemaVersion", detectedVersion)
            }
        // A refusal is the operator-visible signal of a failed open: WARN (expected, actionable)
        // rather than ERROR, with the exception attached when there is one so the cause survives.
        AppLogger.current.w(SOURCE, "Refused to open encrypted database", payload)
        if (error != null) {
            AppLogger.current.e(SOURCE, "Database open failure", error, payload)
        }
        return DatabaseOpenResult.Refused(issue)
    }
}
