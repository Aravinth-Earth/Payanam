//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ThrowsCount", "UseCheckOrError", "ktlint:standard:max-line-length", "LongMethod", "LargeClass")

package io.payanam.database.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.payanam.common.logging.UnifiedLogger
import java.io.File
import net.sqlcipher.database.SQLiteDatabase as SqlCipherDatabase

/**
 * DatabaseEncryptionMigrationSupport.
 */
object DatabaseEncryptionMigrationSupport {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Export database snapshot.
     */
    fun exportDatabaseSnapshot(
        /** Context. */
        context: Context,
        /** Source database. */
        sourceDatabase: File,
        /** Destination database. */
        destinationDatabase: File,
        passphrase: String?,
        /** Export plaintext. */
        exportPlaintext: Boolean,
        /** Log tag. */
        logTag: String,
    ) {
        /** If. */
        if (!sourceDatabase.exists()) {
            throw IllegalStateException("Source database does not exist.")
        }
        /** If. */
        if (destinationDatabase.exists()) {
            destinationDatabase.delete()
        }
        /** If. */
        if (!exportPlaintext) {
            sourceDatabase.copyTo(destinationDatabase, overwrite = true)
            logger.i(logTag, "Database snapshot copied as-is")
            /** Return. */
            return
        }

        /** If. */
        if (canOpenWithFramework(sourceDatabase)) {
            sourceDatabase.copyTo(destinationDatabase, overwrite = true)
            logger.i(logTag, "Database snapshot copied in plaintext mode from plaintext source")
            /** Return. */
            return
        }

        /** Effective passphrase. */
        val effectivePassphrase =
            passphrase?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Passphrase is required for plaintext export from encrypted database.")
        /** If. */
        if (!canOpenWithSqlCipher(context, sourceDatabase, effectivePassphrase)) {
            throw IllegalStateException("Database could not be opened for plaintext export.")
        }

        /** Export sql cipher to plaintext. */
        exportSqlCipherToPlaintext(
            context = context,
            sourceDatabase = sourceDatabase,
            destinationDatabase = destinationDatabase,
            passphrase = effectivePassphrase,
        )
        logger.i(logTag, "Database snapshot exported as plaintext one-time payload")
    }

    /**
     * Ensure encrypted with passphrase.
     */
    fun ensureEncryptedWithPassphrase(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Passphrase. */
        passphrase: String,
        /** Log tag. */
        logTag: String,
    ): Boolean {
        /** If. */
        if (!databaseFile.exists()) {
            logger.w(logTag, "Encryption migration skipped because database file does not exist")
            return false
        }
        /** Is already encrypted with key. */
        val isAlreadyEncryptedWithKey = canOpenWithSqlCipher(context, databaseFile, passphrase)
        /** If. */
        if (isAlreadyEncryptedWithKey) {
            logger.i(logTag, "Database already encrypted with configured passphrase")
            return false
        }

        /** Is plaintext. */
        val isPlaintext = canOpenWithFramework(databaseFile)
        /** If. */
        if (!isPlaintext) {
            throw IllegalStateException("Imported database is encrypted with a different key or unreadable.")
        }

        /** Migrate plaintext to sql cipher. */
        migratePlaintextToSqlCipher(
            context = context,
            sourceDatabase = databaseFile,
            passphrase = passphrase,
            logTag = logTag,
        )
        logger.i(logTag, "Converted plaintext database into SQLCipher format")
        return true
    }

    /**
     * Migrate encrypted database with different key.
     */
    fun migrateEncryptedDatabaseWithDifferentKey(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Imported passphrase. */
        importedPassphrase: String,
        /** Target passphrase. */
        targetPassphrase: String,
        /** Log tag. */
        logTag: String,
    ): Boolean {
        /** If. */
        if (!databaseFile.exists()) {
            logger.w(logTag, "Encrypted migration skipped because database file does not exist")
            return false
        }

        /** If. */
        if (importedPassphrase == targetPassphrase) {
            logger.i(logTag, "Imported and target passphrases are identical; no re-encryption needed")
            return false
        }

        // Verify imported DB can be opened with the provided passphrase
        /** If. */
        if (!canOpenWithSqlCipher(context, databaseFile, importedPassphrase)) {
            throw IllegalStateException("Cannot decrypt imported database with provided passphrase.")
        }

        // Re-encrypt with the target passphrase
        /** Rekey encrypted database. */
        rekeyEncryptedDatabase(
            context = context,
            databaseFile = databaseFile,
            currentPassphrase = importedPassphrase,
            newPassphrase = targetPassphrase,
            logTag = logTag,
        )

        // Verify re-encryption succeeded
        /** If. */
        if (!canOpenWithSqlCipher(context, databaseFile, targetPassphrase)) {
            throw IllegalStateException("Re-encryption verification failed after passphrase change.")
        }

        logger.i(logTag, "Successfully migrated encrypted database to target passphrase")
        return true
    }

    @Suppress("UnusedParameter")
    /**
     * Is detectably encrypted.
     */
    fun isDetectablyEncrypted(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Log tag. */
        logTag: String,
    ): Boolean {
        /** If. */
        if (!databaseFile.exists()) {
            return false
        }

        /** Is plaintext. */
        val isPlaintext = canOpenWithFramework(databaseFile)
        /** If. */
        if (isPlaintext) {
            return false
        }

        // If it's not plaintext, check if it has SQLCipher characteristics
        // We can't open it directly, but if it's not plaintext SQLite, assume encrypted
        /** Magic bytes. */
        val magicBytes =
            databaseFile.inputStream().use { stream ->
                /** Byte array. */
                ByteArray(16).apply { stream.read(this) }
            }

        // SQLite magic is "SQLite format 3\0"
        /** Sqlite magic. */
        val sqliteMagic = "SQLite format 3\u0000".toByteArray()
        /** Has plaintext magic. */
        val hasPlaintextMagic = magicBytes.take(16) == sqliteMagic.take(16)

        /** Result. */
        val result = !hasPlaintextMagic && databaseFile.length() > 0
        logger.i(
            /** Log tag. */
            logTag,
            "Database encryption detection",
            /** Map of. */
            mapOf(
                "isPlaintext" to isPlaintext,
                "hasPlaintextMagic" to hasPlaintextMagic,
                "isDetectablyEncrypted" to result,
                "fileSizeBytes" to databaseFile.length(),
            ),
        )
        return result
    }

    /**
     * Rekey encrypted database.
     */
    fun rekeyEncryptedDatabase(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Current passphrase. */
        currentPassphrase: String,
        /** New passphrase. */
        newPassphrase: String,
        /** Log tag. */
        logTag: String,
    ) {
        /** If. */
        if (!databaseFile.exists()) {
            throw IllegalStateException("Database file does not exist for passphrase update.")
        }
        SqlCipherDatabase.loadLibs(context)
        /** Db. */
        val db =
            SqlCipherDatabase.openDatabase(
                databaseFile.absolutePath,
                /** Current passphrase. */
                currentPassphrase,
                /** Null. */
                null,
                SqlCipherDatabase.OPEN_READWRITE,
            )
        try {
            db.rawExecSQL("PRAGMA rekey = '${escapeSql(newPassphrase)}';")
        } finally {
            db.close()
        }
        /** If. */
        if (!canOpenWithSqlCipher(context, databaseFile, newPassphrase)) {
            throw IllegalStateException("Rekey verification failed for database.")
        }
        logger.i(logTag, "Database rekey completed")
    }

    /**
     * Read table counts.
     */
    fun readTableCounts(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int> {
        /** If. */
        if (!databaseFile.exists()) {
            return tableNames.associateWith { 0 }
        }

        /** If. */
        if (!passphrase.isNullOrBlank() && canOpenWithSqlCipher(context, databaseFile, passphrase)) {
            return readCountsWithSqlCipher(context, databaseFile, passphrase, tableNames)
        }
        /** If. */
        if (canOpenWithFramework(databaseFile)) {
            return readCountsWithFramework(databaseFile, tableNames)
        }
        return tableNames.associateWith { 0 }
    }

    private fun migratePlaintextToSqlCipher(
        /** Context. */
        context: Context,
        /** Source database. */
        sourceDatabase: File,
        /** Passphrase. */
        passphrase: String,
        /** Log tag. */
        logTag: String,
    ) {
        SqlCipherDatabase.loadLibs(context)
        /** Temp encrypted. */
        val tempEncrypted =
            /** File. */
            File(
                context.cacheDir,
                "${sourceDatabase.name}.${System.currentTimeMillis()}.enc.tmp",
            )
        /** If. */
        if (tempEncrypted.exists()) {
            tempEncrypted.delete()
        }
        // Normalize pending WAL pages into the main db before copy+rekey.
        runCatching {
            /** Sqlite database. */
            SQLiteDatabase
                .openDatabase(
                    sourceDatabase.absolutePath,
                    /** Null. */
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { frameworkDb ->
                    frameworkDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).close()
                }
        }.onFailure { checkpointError ->
            logger.w(
                /** Log tag. */
                logTag,
                "WAL checkpoint before encryption migration failed; continuing with best effort",
                /** Map of. */
                mapOf("error" to (checkpointError.message ?: "Unknown error")),
            )
        }

        sourceDatabase.copyTo(tempEncrypted, overwrite = true)

        /** Encrypted db. */
        val encryptedDb =
            SqlCipherDatabase.openDatabase(
                tempEncrypted.absolutePath,
                "",
                /** Null. */
                null,
                SqlCipherDatabase.OPEN_READWRITE,
            )
        try {
            /** Source version. */
            val sourceVersion = encryptedDb.version
            encryptedDb.rawExecSQL("PRAGMA rekey = '${escapeSql(passphrase)}';")
            encryptedDb.rawExecSQL("PRAGMA user_version = $sourceVersion;")
            logger.i(
                /** Log tag. */
                logTag,
                "SQLCipher rekey conversion completed for plaintext source",
                /** Map of. */
                mapOf("userVersion" to sourceVersion),
            )
        } finally {
            encryptedDb.close()
        }

        /** If. */
        if (!tempEncrypted.exists() || tempEncrypted.length() == 0L) {
            throw IllegalStateException("Encrypted migration output was unreadable.")
        }

        /** Encrypted output. */
        var encryptedOutput = tempEncrypted
        /** If. */
        if (!canOpenWithSqlCipher(context, encryptedOutput, passphrase)) {
            logger.w(
                /** Log tag. */
                logTag,
                "Rekey path did not produce SQLCipher-readable output; attempting sqlcipher_export fallback",
            )
            encryptedOutput =
                runCatching {
                    /** Export plaintext to encrypted via attach. */
                    exportPlaintextToEncryptedViaAttach(
                        context = context,
                        plaintextDatabase = tempEncrypted,
                        passphrase = passphrase,
                        logTag = logTag,
                    )
                }.getOrElse { attachError ->
                    logger.w(
                        /** Log tag. */
                        logTag,
                        "sqlcipher_export fallback failed; attempting row-copy fallback",
                        /** Map of. */
                        mapOf(
                            "error" to (attachError.message ?: "Unknown error"),
                            "exception" to attachError.javaClass.simpleName,
                        ),
                    )
                    /** Copy plaintext to sql cipher by row. */
                    copyPlaintextToSqlCipherByRow(
                        context = context,
                        plaintextDatabase = tempEncrypted,
                        passphrase = passphrase,
                        logTag = logTag,
                    )
                }
        }

        /** Replace with encrypted snapshot. */
        replaceWithEncryptedSnapshot(
            sourceDatabase = sourceDatabase,
            tempEncrypted = encryptedOutput,
        )

        /** If. */
        if (!canOpenWithSqlCipher(context, sourceDatabase, passphrase)) {
            /** Framework readable. */
            val frameworkReadable = canOpenWithFramework(sourceDatabase)
            logger.w(
                /** Log tag. */
                logTag,
                "Post-migration encrypted-open verification failed",
                /** Map of. */
                mapOf(
                    "dbPath" to sourceDatabase.absolutePath,
                    "fileExists" to sourceDatabase.exists(),
                    "sizeBytes" to sourceDatabase.length(),
                    "frameworkReadableAfterSwap" to frameworkReadable,
                ),
            )
            throw IllegalStateException("Encrypted migration output was unreadable.")
        }
    }

    private fun exportPlaintextToEncryptedViaAttach(
        /** Context. */
        context: Context,
        /** Plaintext database. */
        plaintextDatabase: File,
        /** Passphrase. */
        passphrase: String,
        /** Log tag. */
        logTag: String,
    ): File {
        /** Exported encrypted. */
        val exportedEncrypted =
            /** File. */
            File(
                context.cacheDir,
                "${plaintextDatabase.name}.${System.currentTimeMillis()}.export.enc.tmp",
            )
        /** If. */
        if (exportedEncrypted.exists()) {
            exportedEncrypted.delete()
        }
        SqlCipherDatabase.loadLibs(context)
        /** Plain db. */
        val plainDb =
            SqlCipherDatabase.openDatabase(
                plaintextDatabase.absolutePath,
                "",
                /** Null. */
                null,
                SqlCipherDatabase.OPEN_READWRITE,
            )
        try {
            /** Source version. */
            val sourceVersion = plainDb.version
            logger.i(
                /** Log tag. */
                logTag,
                "Starting sqlcipher_export fallback",
                /** Map of. */
                mapOf(
                    "sourcePath" to plaintextDatabase.absolutePath,
                    "targetPath" to exportedEncrypted.absolutePath,
                ),
            )
            plainDb.rawExecSQL(
                "ATTACH DATABASE '${escapeSql(exportedEncrypted.absolutePath)}' AS encrypted KEY '${escapeSql(passphrase)}';",
            )
            plainDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            plainDb.rawExecSQL("PRAGMA encrypted.user_version = $sourceVersion;")
            plainDb.rawExecSQL("DETACH DATABASE encrypted;")
            logger.i(
                /** Log tag. */
                logTag,
                "sqlcipher_export fallback completed",
                /** Map of. */
                mapOf("userVersion" to sourceVersion),
            )
        } finally {
            plainDb.close()
        }
        /** If. */
        if (!exportedEncrypted.exists() || exportedEncrypted.length() == 0L ||
            !canOpenWithSqlCipher(context, exportedEncrypted, passphrase)
        ) {
            throw IllegalStateException("Encrypted migration output was unreadable.")
        }
        /** If. */
        if (plaintextDatabase.exists()) {
            plaintextDatabase.delete()
        }
        return exportedEncrypted
    }

    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private fun copyPlaintextToSqlCipherByRow(
        /** Context. */
        context: Context,
        /** Plaintext database. */
        plaintextDatabase: File,
        /** Passphrase. */
        passphrase: String,
        /** Log tag. */
        logTag: String,
    ): File {
        /** Encrypted output. */
        val encryptedOutput =
            /** File. */
            File(
                context.cacheDir,
                "${plaintextDatabase.name}.${System.currentTimeMillis()}.rowcopy.enc.tmp",
            )
        /** If. */
        if (encryptedOutput.exists()) {
            encryptedOutput.delete()
        }

        /** Source db. */
        val sourceDb =
            SQLiteDatabase.openDatabase(
                plaintextDatabase.absolutePath,
                /** Null. */
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        SqlCipherDatabase.loadLibs(context)
        /** Dest db. */
        val destDb =
            SqlCipherDatabase.openOrCreateDatabase(
                /** Encrypted output. */
                encryptedOutput,
                /** Passphrase. */
                passphrase,
                /** Null. */
                null,
            )
        try {
            destDb.rawExecSQL("PRAGMA foreign_keys = OFF;")

            /** Table definitions. */
            val tableDefinitions = mutableListOf<Pair<String, String>>()
            /** Source db. */
            sourceDb
                .rawQuery(
                    "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY name",
                    /** Null. */
                    null,
                ).use { cursor ->
                    /** While. */
                    while (cursor.moveToNext()) {
                        /** Table name. */
                        val tableName = cursor.getString(0) ?: continue
                        /** Create sql. */
                        val createSql = cursor.getString(1) ?: continue
                        tableDefinitions.add(tableName to createSql)
                    }
                }

            tableDefinitions.forEach { (_, createSql) ->
                destDb.rawExecSQL(createSql)
            }

            tableDefinitions.forEach { (tableName, _) ->
                /** Escaped table. */
                val escapedTable = tableName.replace("\"", "\"\"")
                sourceDb.rawQuery("SELECT * FROM \"$escapedTable\"", null).use { cursor ->
                    /** Columns. */
                    val columns = cursor.columnNames
                    /** If. */
                    if (columns.isEmpty()) return@use
                    /** Column sql. */
                    val columnSql = columns.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
                    /** Value sql. */
                    val valueSql = columns.joinToString(",") { "?" }
                    /** Statement. */
                    val statement =
                        destDb.compileStatement(
                            "INSERT INTO \"$escapedTable\" ($columnSql) VALUES ($valueSql)",
                        )
                    destDb.beginTransaction()
                    try {
                        /** While. */
                        while (cursor.moveToNext()) {
                            statement.clearBindings()
                            /** For. */
                            for (index in columns.indices) {
                                /** When. */
                                when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> statement.bindNull(index + 1)
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(index + 1, cursor.getLong(index))
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> statement.bindDouble(index + 1, cursor.getDouble(index))
                                    android.database.Cursor.FIELD_TYPE_STRING -> statement.bindString(index + 1, cursor.getString(index))
                                    android.database.Cursor.FIELD_TYPE_BLOB -> statement.bindBlob(index + 1, cursor.getBlob(index))
                                    else -> statement.bindNull(index + 1)
                                }
                            }
                            statement.executeInsert()
                        }
                        destDb.setTransactionSuccessful()
                    } finally {
                        destDb.endTransaction()
                        statement.close()
                    }
                }
            }

            /** Source db. */
            sourceDb
                .rawQuery(
                    "SELECT sql FROM sqlite_master WHERE type IN ('index','trigger','view') AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY type, name",
                    /** Null. */
                    null,
                ).use { cursor ->
                    /** While. */
                    while (cursor.moveToNext()) {
                        /** Ddl. */
                        val ddl = cursor.getString(0) ?: continue
                        runCatching { destDb.rawExecSQL(ddl) }
                    }
                }

            destDb.rawExecSQL("PRAGMA user_version = ${sourceDb.version};")
            logger.i(
                /** Log tag. */
                logTag,
                "Row-copy fallback completed",
                /** Map of. */
                mapOf(
                    "tableCount" to tableDefinitions.size,
                    "targetPath" to encryptedOutput.absolutePath,
                ),
            )
        } finally {
            runCatching { sourceDb.close() }
            runCatching { destDb.close() }
        }

        /** If. */
        if (!encryptedOutput.exists() || encryptedOutput.length() == 0L || !canOpenWithSqlCipher(context, encryptedOutput, passphrase)) {
            throw IllegalStateException("Encrypted migration output was unreadable.")
        }
        /** If. */
        if (plaintextDatabase.exists()) {
            plaintextDatabase.delete()
        }
        return encryptedOutput
    }

    private fun replaceWithEncryptedSnapshot(
        /** Source database. */
        sourceDatabase: File,
        /** Temp encrypted. */
        tempEncrypted: File,
    ) {
        /** Delete companion files. */
        deleteCompanionFiles(sourceDatabase)
        /** If. */
        if (!sourceDatabase.delete()) {
            throw IllegalStateException("Could not replace plaintext database during encryption migration.")
        }
        /** If. */
        if (!tempEncrypted.renameTo(sourceDatabase)) {
            tempEncrypted.copyTo(sourceDatabase, overwrite = true)
            tempEncrypted.delete()
        }
    }

    private fun exportSqlCipherToPlaintext(
        /** Context. */
        context: Context,
        /** Source database. */
        sourceDatabase: File,
        /** Destination database. */
        destinationDatabase: File,
        /** Passphrase. */
        passphrase: String,
    ) {
        SqlCipherDatabase.loadLibs(context)
        /** Source db. */
        val sourceDb =
            SqlCipherDatabase.openDatabase(
                sourceDatabase.absolutePath,
                /** Passphrase. */
                passphrase,
                /** Null. */
                null,
                SqlCipherDatabase.OPEN_READWRITE,
            )
        try {
            sourceDb.rawExecSQL(
                "ATTACH DATABASE '${escapeSql(destinationDatabase.absolutePath)}' AS plaintext KEY '';",
            )
            sourceDb.rawExecSQL("SELECT sqlcipher_export('plaintext');")
            sourceDb.rawExecSQL("PRAGMA plaintext.user_version = ${sourceDb.version};")
            sourceDb.rawExecSQL("DETACH DATABASE plaintext;")
        } finally {
            sourceDb.close()
        }
        /** If. */
        if (!destinationDatabase.exists() || destinationDatabase.length() == 0L) {
            throw IllegalStateException("Plaintext export output was empty.")
        }
    }

    private fun canOpenWithFramework(databaseFile: File): Boolean =
        runCatching {
            /** Sqlite database. */
            SQLiteDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    /** Null. */
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    db.version >= 0
                }
        }.getOrDefault(false)

    private fun readCountsWithFramework(
        /** Database file. */
        databaseFile: File,
        tableNames: List<String>,
    ): Map<String, Int> =
        runCatching {
            /** Sqlite database. */
            SQLiteDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    /** Null. */
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    tableNames.associateWith { tableName ->
                        db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
                            /** If. */
                            if (cursor.moveToFirst()) cursor.getInt(0) else 0
                        }
                    }
                }
        }.getOrElse {
            tableNames.associateWith { 0 }
        }

    private fun readCountsWithSqlCipher(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Passphrase. */
        passphrase: String,
        tableNames: List<String>,
    ): Map<String, Int> =
        runCatching {
            SqlCipherDatabase.loadLibs(context)
            /** Sql cipher database. */
            SqlCipherDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    /** Passphrase. */
                    passphrase,
                    /** Null. */
                    null,
                    SqlCipherDatabase.OPEN_READONLY,
                ).use { db ->
                    tableNames.associateWith { tableName ->
                        db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
                            /** If. */
                            if (cursor.moveToFirst()) cursor.getInt(0) else 0
                        }
                    }
                }
        }.getOrElse {
            tableNames.associateWith { 0 }
        }

    /**
     * Can open with sql cipher.
     */
    fun canOpenWithSqlCipher(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Passphrase. */
        passphrase: String,
        logTag: String = "DatabaseEncryptionMigrationSupport",
    ): Boolean =
        runCatching {
            SqlCipherDatabase.loadLibs(context)
            /** Sql cipher database. */
            SqlCipherDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    /** Passphrase. */
                    passphrase,
                    /** Null. */
                    null,
                    SqlCipherDatabase.OPEN_READONLY,
                ).use { db ->
                    db.version >= 0
                }
        }.getOrElse { error ->
            logger.w(
                /** Log tag. */
                logTag,
                "SQLCipher open check failed",
                /** Map of. */
                mapOf(
                    "dbPath" to databaseFile.absolutePath,
                    "error" to (error.message ?: "Unknown error"),
                    "exception" to error.javaClass.simpleName,
                ),
            )
            /** False. */
            false
        }

    private fun deleteCompanionFiles(databaseFile: File) {
        /** Wal. */
        val wal = File(databaseFile.parentFile, "${databaseFile.name}-wal")
        /** Shm. */
        val shm = File(databaseFile.parentFile, "${databaseFile.name}-shm")
        /** Journal. */
        val journal = File(databaseFile.parentFile, "${databaseFile.name}-journal")
        /** List of. */
        listOf(wal, shm, journal).forEach { file ->
            /** If. */
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun escapeSql(value: String): String = value.replace("'", "''")

    // Writes database_init_completed = true directly into app_settings, bypassing Room.
    // Uses SQLCipher if passphrase non-null, plain SQLite otherwise.
    /**
     * Mark database init completed.
     */
    fun markDatabaseInitCompleted(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        passphrase: String?,
        logTag: String = "DatabaseEncryptionMigrationSupport",
    ) {
        /** Updated at. */
        val updatedAt =
            java.time.LocalDateTime
                .now()
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        /** Sql. */
        val sql = "INSERT OR REPLACE INTO app_settings(`key`, value, updatedAt) VALUES (?, ?, ?)"
        /** Args. */
        val args = arrayOf("database_init_completed", "true", updatedAt)
        /** If. */
        if (passphrase != null) {
            SqlCipherDatabase.loadLibs(context)
            SqlCipherDatabase.openDatabase(databaseFile.absolutePath, passphrase, null, SqlCipherDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(sql, args)
            }
        } else {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.execSQL(sql, args) }
        }
        logger.i(logTag, "Database init completed flag written directly to DB")
    }
}
