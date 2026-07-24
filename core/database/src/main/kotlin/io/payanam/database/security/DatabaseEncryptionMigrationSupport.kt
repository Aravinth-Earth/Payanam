//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ThrowsCount", "UseCheckOrError", "ktlint:standard:max-line-length", "LongMethod", "LargeClass")

package io.payanam.database.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.payanam.common.logging.UnifiedLogger
import java.io.File
import net.sqlcipher.database.SQLiteDatabase as SqlCipherDatabase

object DatabaseEncryptionMigrationSupport {
    private val logger = UnifiedLogger.getInstance()

    fun exportDatabaseSnapshot(
        context: Context,
        sourceDatabase: File,
        destinationDatabase: File,
        passphrase: String?,
        exportPlaintext: Boolean,
        logTag: String,
    ) {
        if (!sourceDatabase.exists()) {
            throw IllegalStateException("Source database does not exist.")
        }
        if (destinationDatabase.exists()) {
            destinationDatabase.delete()
        }
        if (!exportPlaintext) {
            sourceDatabase.copyTo(destinationDatabase, overwrite = true)
            logger.i(logTag, "Database snapshot copied as-is")
            return
        }

        if (canOpenWithFramework(sourceDatabase)) {
            sourceDatabase.copyTo(destinationDatabase, overwrite = true)
            logger.i(logTag, "Database snapshot copied in plaintext mode from plaintext source")
            return
        }

        val effectivePassphrase =
            passphrase?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Passphrase is required for plaintext export from encrypted database.")
        if (!canOpenWithSqlCipher(context, sourceDatabase, effectivePassphrase)) {
            throw IllegalStateException("Database could not be opened for plaintext export.")
        }

        exportSqlCipherToPlaintext(
            context = context,
            sourceDatabase = sourceDatabase,
            destinationDatabase = destinationDatabase,
            passphrase = effectivePassphrase,
        )
        logger.i(logTag, "Database snapshot exported as plaintext one-time payload")
    }

    fun ensureEncryptedWithPassphrase(
        context: Context,
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ): Boolean {
        if (!databaseFile.exists()) {
            logger.w(logTag, "Encryption migration skipped because database file does not exist")
            return false
        }
        val isAlreadyEncryptedWithKey = canOpenWithSqlCipher(context, databaseFile, passphrase)
        if (isAlreadyEncryptedWithKey) {
            logger.i(logTag, "Database already encrypted with configured passphrase")
            return false
        }

        val isPlaintext = canOpenWithFramework(databaseFile)
        if (!isPlaintext) {
            throw IllegalStateException("Imported database is encrypted with a different key or unreadable.")
        }

        migratePlaintextToSqlCipher(
            context = context,
            sourceDatabase = databaseFile,
            passphrase = passphrase,
            logTag = logTag,
        )
        logger.i(logTag, "Converted plaintext database into SQLCipher format")
        return true
    }

    fun migrateEncryptedDatabaseWithDifferentKey(
        context: Context,
        databaseFile: File,
        importedPassphrase: String,
        targetPassphrase: String,
        logTag: String,
    ): Boolean {
        if (!databaseFile.exists()) {
            logger.w(logTag, "Encrypted migration skipped because database file does not exist")
            return false
        }

        if (importedPassphrase == targetPassphrase) {
            logger.i(logTag, "Imported and target passphrases are identical; no re-encryption needed")
            return false
        }

        // Verify imported DB can be opened with the provided passphrase
        if (!canOpenWithSqlCipher(context, databaseFile, importedPassphrase)) {
            throw IllegalStateException("Cannot decrypt imported database with provided passphrase.")
        }

        // Re-encrypt with the target passphrase
        rekeyEncryptedDatabase(
            context = context,
            databaseFile = databaseFile,
            currentPassphrase = importedPassphrase,
            newPassphrase = targetPassphrase,
            logTag = logTag,
        )

        // Verify re-encryption succeeded
        if (!canOpenWithSqlCipher(context, databaseFile, targetPassphrase)) {
            throw IllegalStateException("Re-encryption verification failed after passphrase change.")
        }

        logger.i(logTag, "Successfully migrated encrypted database to target passphrase")
        return true
    }

    @Suppress("UnusedParameter")
    fun isDetectablyEncrypted(
        context: Context,
        databaseFile: File,
        logTag: String,
    ): Boolean {
        if (!databaseFile.exists()) {
            return false
        }

        val isPlaintext = canOpenWithFramework(databaseFile)
        if (isPlaintext) {
            return false
        }

        // If it's not plaintext, check if it has SQLCipher characteristics
        // We can't open it directly, but if it's not plaintext SQLite, assume encrypted
        val magicBytes =
            databaseFile.inputStream().use { stream ->
                ByteArray(16).apply { stream.read(this) }
            }

        // SQLite magic is "SQLite format 3\0"
        val sqliteMagic = "SQLite format 3\u0000".toByteArray()
        val hasPlaintextMagic = magicBytes.take(16) == sqliteMagic.take(16)

        val result = !hasPlaintextMagic && databaseFile.length() > 0
        logger.i(
            logTag,
            "Database encryption detection",
            mapOf(
                "isPlaintext" to isPlaintext,
                "hasPlaintextMagic" to hasPlaintextMagic,
                "isDetectablyEncrypted" to result,
                "fileSizeBytes" to databaseFile.length(),
            ),
        )
        return result
    }

    fun rekeyEncryptedDatabase(
        context: Context,
        databaseFile: File,
        currentPassphrase: String,
        newPassphrase: String,
        logTag: String,
    ) {
        if (!databaseFile.exists()) {
            throw IllegalStateException("Database file does not exist for passphrase update.")
        }
        SqlCipherDatabase.loadLibs(context)
        val db =
            SqlCipherDatabase.openDatabase(
                databaseFile.absolutePath,
                currentPassphrase,
                null,
                SqlCipherDatabase.OPEN_READWRITE,
            )
        try {
            db.rawExecSQL("PRAGMA rekey = '${escapeSql(newPassphrase)}';")
        } finally {
            db.close()
        }
        if (!canOpenWithSqlCipher(context, databaseFile, newPassphrase)) {
            throw IllegalStateException("Rekey verification failed for database.")
        }
        logger.i(logTag, "Database rekey completed")
    }

    fun readTableCounts(
        context: Context,
        databaseFile: File,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int> {
        if (!databaseFile.exists()) {
            return tableNames.associateWith { 0 }
        }

        if (!passphrase.isNullOrBlank() && canOpenWithSqlCipher(context, databaseFile, passphrase)) {
            return readCountsWithSqlCipher(context, databaseFile, passphrase, tableNames)
        }
        if (canOpenWithFramework(databaseFile)) {
            return readCountsWithFramework(databaseFile, tableNames)
        }
        return tableNames.associateWith { 0 }
    }

    private fun migratePlaintextToSqlCipher(
        context: Context,
        sourceDatabase: File,
        passphrase: String,
        logTag: String,
    ) {
        SqlCipherDatabase.loadLibs(context)
        val tempEncrypted =
            File(
                context.cacheDir,
                "${sourceDatabase.name}.${System.currentTimeMillis()}.enc.tmp",
            )
        if (tempEncrypted.exists()) {
            tempEncrypted.delete()
        }
        // Normalize pending WAL pages into the main db before copy+rekey.
        runCatching {
            SQLiteDatabase
                .openDatabase(
                    sourceDatabase.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { frameworkDb ->
                    frameworkDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).close()
                }
        }.onFailure { checkpointError ->
            logger.w(
                logTag,
                "WAL checkpoint before encryption migration failed; continuing with best effort",
                mapOf("error" to (checkpointError.message ?: "Unknown error")),
            )
        }

        sourceDatabase.copyTo(tempEncrypted, overwrite = true)

        val encryptedDb =
            SqlCipherDatabase.openDatabase(
                tempEncrypted.absolutePath,
                "",
                null,
                SqlCipherDatabase.OPEN_READWRITE,
            )
        try {
            val sourceVersion = encryptedDb.version
            encryptedDb.rawExecSQL("PRAGMA rekey = '${escapeSql(passphrase)}';")
            encryptedDb.rawExecSQL("PRAGMA user_version = $sourceVersion;")
            logger.i(
                logTag,
                "SQLCipher rekey conversion completed for plaintext source",
                mapOf("userVersion" to sourceVersion),
            )
        } finally {
            encryptedDb.close()
        }

        if (!tempEncrypted.exists() || tempEncrypted.length() == 0L) {
            throw IllegalStateException("Encrypted migration output was unreadable.")
        }

        var encryptedOutput = tempEncrypted
        if (!canOpenWithSqlCipher(context, encryptedOutput, passphrase)) {
            logger.w(
                logTag,
                "Rekey path did not produce SQLCipher-readable output; attempting sqlcipher_export fallback",
            )
            encryptedOutput =
                runCatching {
                    exportPlaintextToEncryptedViaAttach(
                        context = context,
                        plaintextDatabase = tempEncrypted,
                        passphrase = passphrase,
                        logTag = logTag,
                    )
                }.getOrElse { attachError ->
                    logger.w(
                        logTag,
                        "sqlcipher_export fallback failed; attempting row-copy fallback",
                        mapOf(
                            "error" to (attachError.message ?: "Unknown error"),
                            "exception" to attachError.javaClass.simpleName,
                        ),
                    )
                    copyPlaintextToSqlCipherByRow(
                        context = context,
                        plaintextDatabase = tempEncrypted,
                        passphrase = passphrase,
                        logTag = logTag,
                    )
                }
        }

        replaceWithEncryptedSnapshot(
            sourceDatabase = sourceDatabase,
            tempEncrypted = encryptedOutput,
        )

        if (!canOpenWithSqlCipher(context, sourceDatabase, passphrase)) {
            val frameworkReadable = canOpenWithFramework(sourceDatabase)
            logger.w(
                logTag,
                "Post-migration encrypted-open verification failed",
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
        context: Context,
        plaintextDatabase: File,
        passphrase: String,
        logTag: String,
    ): File {
        val exportedEncrypted =
            File(
                context.cacheDir,
                "${plaintextDatabase.name}.${System.currentTimeMillis()}.export.enc.tmp",
            )
        if (exportedEncrypted.exists()) {
            exportedEncrypted.delete()
        }
        SqlCipherDatabase.loadLibs(context)
        val plainDb =
            SqlCipherDatabase.openDatabase(
                plaintextDatabase.absolutePath,
                "",
                null,
                SqlCipherDatabase.OPEN_READWRITE,
            )
        try {
            val sourceVersion = plainDb.version
            logger.i(
                logTag,
                "Starting sqlcipher_export fallback",
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
                logTag,
                "sqlcipher_export fallback completed",
                mapOf("userVersion" to sourceVersion),
            )
        } finally {
            plainDb.close()
        }
        if (!exportedEncrypted.exists() || exportedEncrypted.length() == 0L ||
            !canOpenWithSqlCipher(context, exportedEncrypted, passphrase)
        ) {
            throw IllegalStateException("Encrypted migration output was unreadable.")
        }
        if (plaintextDatabase.exists()) {
            plaintextDatabase.delete()
        }
        return exportedEncrypted
    }

    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private fun copyPlaintextToSqlCipherByRow(
        context: Context,
        plaintextDatabase: File,
        passphrase: String,
        logTag: String,
    ): File {
        val encryptedOutput =
            File(
                context.cacheDir,
                "${plaintextDatabase.name}.${System.currentTimeMillis()}.rowcopy.enc.tmp",
            )
        if (encryptedOutput.exists()) {
            encryptedOutput.delete()
        }

        val sourceDb =
            SQLiteDatabase.openDatabase(
                plaintextDatabase.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        SqlCipherDatabase.loadLibs(context)
        val destDb =
            SqlCipherDatabase.openOrCreateDatabase(
                encryptedOutput,
                passphrase,
                null,
            )
        try {
            destDb.rawExecSQL("PRAGMA foreign_keys = OFF;")

            val tableDefinitions = mutableListOf<Pair<String, String>>()
            sourceDb
                .rawQuery(
                    "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY name",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val tableName = cursor.getString(0) ?: continue
                        val createSql = cursor.getString(1) ?: continue
                        tableDefinitions.add(tableName to createSql)
                    }
                }

            tableDefinitions.forEach { (_, createSql) ->
                destDb.rawExecSQL(createSql)
            }

            tableDefinitions.forEach { (tableName, _) ->
                val escapedTable = tableName.replace("\"", "\"\"")
                sourceDb.rawQuery("SELECT * FROM \"$escapedTable\"", null).use { cursor ->
                    val columns = cursor.columnNames
                    if (columns.isEmpty()) return@use
                    val columnSql = columns.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
                    val valueSql = columns.joinToString(",") { "?" }
                    val statement =
                        destDb.compileStatement(
                            "INSERT INTO \"$escapedTable\" ($columnSql) VALUES ($valueSql)",
                        )
                    destDb.beginTransaction()
                    try {
                        while (cursor.moveToNext()) {
                            statement.clearBindings()
                            for (index in columns.indices) {
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

            sourceDb
                .rawQuery(
                    "SELECT sql FROM sqlite_master WHERE type IN ('index','trigger','view') AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY type, name",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val ddl = cursor.getString(0) ?: continue
                        runCatching { destDb.rawExecSQL(ddl) }
                    }
                }

            destDb.rawExecSQL("PRAGMA user_version = ${sourceDb.version};")
            logger.i(
                logTag,
                "Row-copy fallback completed",
                mapOf(
                    "tableCount" to tableDefinitions.size,
                    "targetPath" to encryptedOutput.absolutePath,
                ),
            )
        } finally {
            runCatching { sourceDb.close() }
            runCatching { destDb.close() }
        }

        if (!encryptedOutput.exists() || encryptedOutput.length() == 0L || !canOpenWithSqlCipher(context, encryptedOutput, passphrase)) {
            throw IllegalStateException("Encrypted migration output was unreadable.")
        }
        if (plaintextDatabase.exists()) {
            plaintextDatabase.delete()
        }
        return encryptedOutput
    }

    private fun replaceWithEncryptedSnapshot(
        sourceDatabase: File,
        tempEncrypted: File,
    ) {
        deleteCompanionFiles(sourceDatabase)
        if (!sourceDatabase.delete()) {
            throw IllegalStateException("Could not replace plaintext database during encryption migration.")
        }
        if (!tempEncrypted.renameTo(sourceDatabase)) {
            tempEncrypted.copyTo(sourceDatabase, overwrite = true)
            tempEncrypted.delete()
        }
    }

    private fun exportSqlCipherToPlaintext(
        context: Context,
        sourceDatabase: File,
        destinationDatabase: File,
        passphrase: String,
    ) {
        SqlCipherDatabase.loadLibs(context)
        val sourceDb =
            SqlCipherDatabase.openDatabase(
                sourceDatabase.absolutePath,
                passphrase,
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
        if (!destinationDatabase.exists() || destinationDatabase.length() == 0L) {
            throw IllegalStateException("Plaintext export output was empty.")
        }
    }

    private fun canOpenWithFramework(databaseFile: File): Boolean =
        runCatching {
            SQLiteDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    db.version >= 0
                }
        }.getOrDefault(false)

    private fun readCountsWithFramework(
        databaseFile: File,
        tableNames: List<String>,
    ): Map<String, Int> =
        runCatching {
            SQLiteDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    tableNames.associateWith { tableName ->
                        db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getInt(0) else 0
                        }
                    }
                }
        }.getOrElse {
            tableNames.associateWith { 0 }
        }

    private fun readCountsWithSqlCipher(
        context: Context,
        databaseFile: File,
        passphrase: String,
        tableNames: List<String>,
    ): Map<String, Int> =
        runCatching {
            SqlCipherDatabase.loadLibs(context)
            SqlCipherDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    passphrase,
                    null,
                    SqlCipherDatabase.OPEN_READONLY,
                ).use { db ->
                    tableNames.associateWith { tableName ->
                        db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getInt(0) else 0
                        }
                    }
                }
        }.getOrElse {
            tableNames.associateWith { 0 }
        }

    fun canOpenWithSqlCipher(
        context: Context,
        databaseFile: File,
        passphrase: String,
        logTag: String = "DatabaseEncryptionMigrationSupport",
    ): Boolean =
        runCatching {
            SqlCipherDatabase.loadLibs(context)
            SqlCipherDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    passphrase,
                    null,
                    SqlCipherDatabase.OPEN_READONLY,
                ).use { db ->
                    db.version >= 0
                }
        }.getOrElse { error ->
            logger.w(
                logTag,
                "SQLCipher open check failed",
                mapOf(
                    "dbPath" to databaseFile.absolutePath,
                    "error" to (error.message ?: "Unknown error"),
                    "exception" to error.javaClass.simpleName,
                ),
            )
            false
        }

    private fun deleteCompanionFiles(databaseFile: File) {
        val wal = File(databaseFile.parentFile, "${databaseFile.name}-wal")
        val shm = File(databaseFile.parentFile, "${databaseFile.name}-shm")
        val journal = File(databaseFile.parentFile, "${databaseFile.name}-journal")
        listOf(wal, shm, journal).forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun escapeSql(value: String): String = value.replace("'", "''")

    // Writes database_init_completed = true directly into app_settings, bypassing Room.
    // Uses SQLCipher if passphrase non-null, plain SQLite otherwise.
    fun markDatabaseInitCompleted(
        context: Context,
        databaseFile: File,
        passphrase: String?,
        logTag: String = "DatabaseEncryptionMigrationSupport",
    ) {
        val updatedAt =
            java.time.LocalDateTime
                .now()
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val sql = "INSERT OR REPLACE INTO app_settings(`key`, value, updatedAt) VALUES (?, ?, ?)"
        val args = arrayOf("database_init_completed", "true", updatedAt)
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
