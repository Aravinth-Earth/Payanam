//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.payanam.R
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import java.io.File
import java.io.FileOutputStream

internal data class DatabaseImportCopyResult(
    /** Source kind. */
    val sourceKind: String,
    /** Primary file name. */
    val primaryFileName: String,
    /** Bytes copied. */
    val bytesCopied: Long,
    /** Companion files copied. */
    val companionFilesCopied: Int,
)

internal object DatabaseImportSupport {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Copy database artifacts.
     */
    fun copyDatabaseArtifacts(
        /** Context. */
        context: Context,
        /** Source uri. */
        sourceUri: Uri,
        /** Target database file. */
        targetDatabaseFile: File,
    ): DatabaseImportCopyResult {
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabaseImportSupport.copyDatabaseArtifacts",
            stage = "started",
            data = mapOf(
                "sourceUri" to sourceUri.toString(),
                "targetPath" to targetDatabaseFile.absolutePath,
            ),
        )
        /** Source. */
        val source = resolveSource(context, sourceUri)
        logger.i(
            "DatabaseImportSupport.copyDatabaseArtifacts",
            "Resolved import source",
            /** Map of. */
            mapOf(
                "sourceKind" to source.sourceKind,
                "primaryFileName" to source.primaryFileName,
                "hasWal" to (source.walUri != null),
                "hasShm" to (source.shmUri != null),
                "targetPath" to targetDatabaseFile.absolutePath,
            ),
        )
        /** Copy mappings. */
        val copyMappings = mutableListOf(
            /** Copy mapping. */
            CopyMapping(
                label = "db",
                sourceUri = source.dbUri,
                targetFile = targetDatabaseFile,
            ),
        )

        source.walUri?.let {
            copyMappings.add(
                /** Copy mapping. */
                CopyMapping(
                    label = "wal",
                    sourceUri = it,
                    targetFile = File(targetDatabaseFile.parent, "${targetDatabaseFile.name}-wal"),
                ),
            )
        }
        source.shmUri?.let {
            copyMappings.add(
                /** Copy mapping. */
                CopyMapping(
                    label = "shm",
                    sourceUri = it,
                    targetFile = File(targetDatabaseFile.parent, "${targetDatabaseFile.name}-shm"),
                ),
            )
        }

        /** Bytes copied. */
        var bytesCopied = 0L
        /** Companion files copied. */
        var companionFilesCopied = 0
        copyMappings.forEachIndexed { index, mapping ->
            /** Current bytes. */
            val currentBytes = context.contentResolver.openInputStream(mapping.sourceUri)?.use { inputStream ->
                /** File output stream. */
                FileOutputStream(mapping.targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IllegalStateException(
                context.getString(R.string.settings_import_error_source_stream_open),
            )

            /** If. */
            if (index == 0) {
                bytesCopied = currentBytes
            } else {
                companionFilesCopied++
            }

            logger.i(
                "DatabaseImportSupport.copyDatabaseArtifacts",
                "Copied import artifact",
                /** Map of. */
                mapOf(
                    "artifact" to mapping.label,
                    "sourceKind" to source.sourceKind,
                    "target" to mapping.targetFile.name,
                    "sizeKB" to (currentBytes / 1024),
                ),
            )
            CrashSafeBreadcrumbs.record(
                context = context,
                source = "DatabaseImportSupport.copyDatabaseArtifacts",
                stage = "artifact_copied",
                data = mapOf(
                    "artifact" to mapping.label,
                    "target" to mapping.targetFile.name,
                    "sizeKB" to (currentBytes / 1024),
                ),
            )
        }
        logger.i(
            "DatabaseImportSupport.copyDatabaseArtifacts",
            "Import artifact copy phase completed",
            /** Map of. */
            mapOf(
                "sourceKind" to source.sourceKind,
                "primaryFileName" to source.primaryFileName,
                "copiedArtifacts" to copyMappings.size,
                "companionFilesCopied" to companionFilesCopied,
                "primaryBytesCopiedKB" to (bytesCopied / 1024),
            ),
        )

        return DatabaseImportCopyResult(
            sourceKind = source.sourceKind,
            primaryFileName = source.primaryFileName,
            bytesCopied = bytesCopied,
            companionFilesCopied = companionFilesCopied,
        ).also {
            CrashSafeBreadcrumbs.record(
                context = context,
                source = "DatabaseImportSupport.copyDatabaseArtifacts",
                stage = "completed",
                data = mapOf(
                    "sourceKind" to source.sourceKind,
                    "primaryFileName" to source.primaryFileName,
                    "copiedArtifacts" to copyMappings.size,
                ),
            )
        }
    }

    private fun readPlaintextDatabaseUserVersion(databaseFile: File, logTag: String): Int? = try {
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            /** Null. */
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.version
        }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        logger.w(
            /** Log tag. */
            logTag,
            "Failed to read SQLite user_version from imported database",
            /** Map of. */
            mapOf("error" to (e.message ?: "Unknown error")),
        )
        /** Null. */
        null
    }

    /*
     * After copying db+wal+shm from an export, merges WAL data into the DB and removes all
     * WAL artefacts so the health check can open a clean single-file database.
     *
     * Design constraints discovered across builds 991–994:
     *  - Calling SQLiteDatabase.openDatabase() on the PRODUCTION db path while a WAL is
     *    co-located destroys files at the Android native layer regardless of DatabaseErrorHandler.
     *  - Keeping the WAL and letting the health check open it fails even when the WAL magic
     *    is valid, because the WAL header checksum/salt may be inconsistent when the backup was
     *    taken while the app was running (partially-written WAL header at copy time).
     *
     * Strategy — temp-copy checkpoint:
     * 1. Always delete the SHM immediately (process-local index; must be recreated per-process).
     * 2. No WAL → done.
     * 3. Copy DB + WAL to temp paths in the same directory.
     * 4. Open the TEMP DB (not the production path) with OPEN_READWRITE + no-op error handler.
     *    Any native-layer file destruction from a bad WAL hits only the temp copy.
     * 5. Run PRAGMA wal_checkpoint(TRUNCATE) on the temp DB to fold WAL frames into it.
     * 6. Delete the production WAL.
     * 7. On checkpoint success: overwrite the production DB with the consolidated temp copy.
     *    On checkpoint failure: leave production DB unchanged (committed/checkpointed state).
     * 8. Clean up temp files in finally.
     *
     * Either path leaves the production DB path with no WAL present for the health check.
     */

    /**
     * Reads the first 16 bytes of the database file and logs them for diagnostic purposes.
     * Returns true if the header matches the standard SQLite magic ("SQLite format 3\0...").
     * Returns false if the file appears to be SQLCipher-encrypted (random salt header) or corrupt.
     */
    fun isStandardSqliteFile(databaseFile: File, logTag: String): Boolean {
        /** If. */
        if (!databaseFile.exists() || databaseFile.length() == 0L) {
            logger.w(
                /** Log tag. */
                logTag,
                "Database format check: file missing or empty",
                /** Map of. */
                mapOf(
                    "file" to databaseFile.name,
                    "exists" to databaseFile.exists(),
                    "sizeBytes" to databaseFile.length(),
                ),
            )
            return false
        }
        return try {
            /** Header. */
            val header = ByteArray(16)
            /** Bytes read. */
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            /** Hex header. */
            val hexHeader = header.take(bytesRead.coerceAtLeast(0)).joinToString("") { "%02X".format(it) }
            // Standard SQLite magic: "SQLite format 3\0" (16 bytes)
            /** Sqlite magic. */
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
            /** Is standard. */
            val isStandard = bytesRead >= sqliteMagic.size &&
                header.copyOf(sqliteMagic.size).contentEquals(sqliteMagic)
            logger.i(
                /** Log tag. */
                logTag,
                "Database file format check",
                /** Map of. */
                mapOf(
                    "file" to databaseFile.name,
                    "sizeKB" to (databaseFile.length() / 1024),
                    "headerHex" to hexHeader,
                    "isStandardSqlite" to isStandard,
                ),
            )
            /** Is standard. */
            isStandard
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.w(logTag, "Failed to read database file header for format check", mapOf("error" to (e.message ?: "Unknown")))
            /** False. */
            false
        }
    }

    /**
     * Consolidate wal after import.
     */
    fun consolidateWalAfterImport(dbFile: File, logTag: String): Boolean {
        /** Wal file. */
        val walFile = File(dbFile.parent, "${dbFile.name}-wal")
        /** Shm file. */
        val shmFile = File(dbFile.parent, "${dbFile.name}-shm")

        logger.i(
            /** Log tag. */
            logTag,
            "WAL consolidation: initial state",
            /** Map of. */
            mapOf(
                "db" to dbFile.name,
                "dbExists" to dbFile.exists(),
                "dbSizeKB" to (dbFile.length() / 1024),
                "walExists" to walFile.exists(),
                "walSizeKB" to (walFile.length() / 1024),
                "shmExists" to shmFile.exists(),
            ),
        )

        /** If. */
        if (!walFile.exists()) {
            logger.i(logTag, "WAL consolidation: no WAL present, nothing to merge")
            return true
        }

        /** Has standard header. */
        val hasStandardHeader = hasStandardSqliteHeader(dbFile)
        /** If. */
        if (!hasStandardHeader) {
            logger.w(
                /** Log tag. */
                logTag,
                "WAL consolidation skipped for non-standard DB header; preserving DB/WAL/SHM",
                /** Map of. */
                mapOf(
                    "dbSizeKB" to (dbFile.length() / 1024),
                    "walSizeKB" to (walFile.length() / 1024),
                    "shmExists" to shmFile.exists(),
                ),
            )
            return false
        }

        // SHM is process-local and can be safely rebuilt for standard SQLite imports.
        shmFile.delete()

        /** Temp db. */
        val tempDb = File(dbFile.parent, "${dbFile.name}.wal_merge_tmp")
        /** Temp wal. */
        val tempWal = File(dbFile.parent, "${dbFile.name}.wal_merge_tmp-wal")
        /** Temp shm. */
        val tempShm = File(dbFile.parent, "${dbFile.name}.wal_merge_tmp-shm")

        return try {
            dbFile.copyTo(tempDb, overwrite = true)
            walFile.copyTo(tempWal, overwrite = true)
            // No SHM copy — SQLite creates a fresh one for tempDb on open.

            logger.i(
                /** Log tag. */
                logTag,
                "WAL consolidation: temp copies created",
                /** Map of. */
                mapOf(
                    "tempDbSizeKB" to (tempDb.length() / 1024),
                    "tempWalSizeKB" to (tempWal.length() / 1024),
                ),
            )

            /** Checkpointed. */
            val checkpointed = try {
                SQLiteDatabase.openDatabase(
                    tempDb.absolutePath,
                    /** Null. */
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    DatabaseErrorHandler { /* no-op: temp file only, not production DB */ },
                ).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        /** If. */
                        if (cursor.moveToFirst()) {
                            logger.i(
                                /** Log tag. */
                                logTag,
                                "WAL checkpoint via temp copy",
                                /** Map of. */
                                mapOf(
                                    "busy" to cursor.getInt(0),
                                    "log" to cursor.getInt(1),
                                    "ckpt" to cursor.getInt(2),
                                ),
                            )
                        }
                    }
                }
                /** Survived. */
                val survived = tempDb.exists() && tempDb.length() > 0
                logger.i(
                    /** Log tag. */
                    logTag,
                    "WAL checkpoint temp open succeeded",
                    /** Map of. */
                    mapOf("tempDbSurvived" to survived, "tempDbSizeKB" to (tempDb.length() / 1024)),
                )
                /** Survived. */
                survived
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.w(
                    /** Log tag. */
                    logTag,
                    "WAL checkpoint on temp copy failed; preserving WAL to avoid data loss",
                    /** Map of. */
                    mapOf(
                        "error" to (e.message ?: "Unknown"),
                        "exception" to e.javaClass.simpleName,
                        "tempDbExists" to tempDb.exists(),
                        "tempDbSizeKB" to (tempDb.length() / 1024),
                    ),
                )
                /** False. */
                false
            }

            /** If. */
            if (checkpointed) {
                /** Wal deleted. */
                val walDeleted = walFile.delete()
                logger.i(
                    /** Log tag. */
                    logTag,
                    "WAL consolidation: production WAL removed",
                    /** Map of. */
                    mapOf(
                        "walDeleted" to walDeleted,
                        "dbSizeKB" to (dbFile.length() / 1024),
                    ),
                )
                tempDb.copyTo(dbFile, overwrite = true)
                logger.i(
                    /** Log tag. */
                    logTag,
                    "WAL merged into DB via temp copy; production DB consolidated",
                    /** Map of. */
                    mapOf("dbSizeKB" to (dbFile.length() / 1024)),
                )
            } else {
                logger.w(
                    /** Log tag. */
                    logTag,
                    "WAL retained after consolidation failure; caller must continue with DB/WAL",
                    /** Map of. */
                    mapOf(
                        "dbSizeKB" to (dbFile.length() / 1024),
                        "walExists" to walFile.exists(),
                        "walSizeKB" to (walFile.length() / 1024),
                    ),
                )
            }

            /** Checkpointed. */
            checkpointed
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(logTag, "WAL consolidation failed unexpectedly; leaving DB/WAL/SHM unchanged", e)
            /** False. */
            false
        } finally {
            tempDb.delete()
            tempWal.delete()
            tempShm.delete()
        }
    }

    private fun hasStandardSqliteHeader(databaseFile: File): Boolean {
        /** If. */
        if (!databaseFile.exists() || databaseFile.length() < 16L) return false
        return try {
            /** Header. */
            val header = ByteArray(16)
            /** Bytes read. */
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            /** Sqlite magic. */
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
            bytesRead >= sqliteMagic.size &&
                header.copyOf(sqliteMagic.size).contentEquals(sqliteMagic)
        } catch (_: Exception) {
            /** False. */
            false
        }
    }

    /**
     * Decrypts a SQLCipher-encrypted database file in-place using the provided passphrase.
     * On success, [databaseFile] is replaced with a standard plaintext SQLite database.
     * Throws if the passphrase is incorrect or the file cannot be opened.
     */
    fun decryptEncryptedImport(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Passphrase. */
        passphrase: String,
        /** Log tag. */
        logTag: String,
    ) {
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabaseImportSupport.decryptEncryptedImport",
            stage = "started",
            data = mapOf(
                "dbFile" to databaseFile.absolutePath,
                "passphraseLength" to passphrase.length,
            ),
        )
        /** Temp decrypted. */
        val tempDecrypted = File(databaseFile.parent, "${databaseFile.name}.import_decrypt.tmp")
        logger.i(
            /** Log tag. */
            logTag,
            "Starting encrypted import decrypt",
            /** Map of. */
            mapOf(
                "dbFile" to databaseFile.absolutePath,
                "dbSizeKB" to (databaseFile.length() / 1024),
                "passphraseLength" to passphrase.length,
            ),
        )
        try {
            DatabaseEncryptionMigrationSupport.exportDatabaseSnapshot(
                context = context,
                sourceDatabase = databaseFile,
                destinationDatabase = tempDecrypted,
                passphrase = passphrase,
                exportPlaintext = true,
                logTag = logTag,
            )
            databaseFile.delete()
            /** If. */
            if (!tempDecrypted.renameTo(databaseFile)) {
                tempDecrypted.copyTo(databaseFile, overwrite = true)
                tempDecrypted.delete()
            }
            logger.i(
                /** Log tag. */
                logTag,
                "Encrypted import decrypted to plaintext successfully",
                /** Map of. */
                mapOf("sizeKB" to (databaseFile.length() / 1024)),
            )
            CrashSafeBreadcrumbs.record(
                context = context,
                source = "DatabaseImportSupport.decryptEncryptedImport",
                stage = "completed",
                data = mapOf("sizeKB" to (databaseFile.length() / 1024)),
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            tempDecrypted.delete()
            logger.e(logTag, "Encrypted import decrypt failed", e)
            CrashSafeBreadcrumbs.record(
                context = context,
                source = "DatabaseImportSupport.decryptEncryptedImport",
                stage = "failed",
                data = mapOf("error" to (e.message ?: "unknown")),
            )
            throw e
        }
    }

    /**
     * Validate supported plaintext import schema.
     */
    fun validateSupportedPlaintextImportSchema(
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        /** Log tag. */
        logTag: String,
    ): Int {
        /** User version. */
        val userVersion = readPlaintextDatabaseUserVersion(
            databaseFile = databaseFile,
            logTag = "DatabaseImportSupport.readPlaintextDatabaseUserVersion",
        ) ?: throw IllegalStateException(
            context.getString(R.string.settings_import_error_unreadable_db),
        )

        /** If. */
        if (userVersion < DatabaseHealthChecker.MIN_MIGRATABLE_VERSION) {
            throw IllegalStateException(
                context.getString(
                    R.string.settings_import_error_schema_too_old,
                    /** User version. */
                    userVersion,
                    DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
                ),
            )
        }

        /** If. */
        if (userVersion > DatabaseHealthChecker.CURRENT_VERSION) {
            throw IllegalStateException(
                context.getString(
                    R.string.settings_import_error_schema_too_new,
                    /** User version. */
                    userVersion,
                    DatabaseHealthChecker.CURRENT_VERSION,
                ),
            )
        }

        logger.i(
            /** Log tag. */
            logTag,
            "Validated imported plaintext database schema",
            /** Map of. */
            mapOf(
                "dbVersion" to userVersion,
                "minimumSupported" to DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
                "currentSupported" to DatabaseHealthChecker.CURRENT_VERSION,
            ),
        )
        return userVersion
    }

    private fun resolveSource(context: Context, sourceUri: Uri): ResolvedSource {
        logger.i(
            "DatabaseImportSupport.resolveSource",
            "Resolving database import source",
            /** Map of. */
            mapOf(
                "uri" to sourceUri.toString(),
                "isTreeUri" to DocumentsContract.isTreeUri(sourceUri),
            ),
        )
        /** If. */
        if (DocumentsContract.isTreeUri(sourceUri)) {
            return resolveFromTree(context, sourceUri)
        }
        return resolveFromSingleFile(context, sourceUri)
    }

    private fun resolveFromTree(context: Context, sourceTreeUri: Uri): ResolvedSource {
        /** Child documents. */
        val childDocuments = listChildDocuments(context, sourceTreeUri)
        /** File documents. */
        val fileDocuments = childDocuments.filterNot { it.isDirectory }

        /** Preferred db. */
        val preferredDb = fileDocuments.firstOrNull {
            it.name.equals(PayanamDatabase.DATABASE_NAME, ignoreCase = true)
        }
        /** Db document. */
        val dbDocument = preferredDb ?: run {
            /** Db candidates. */
            val dbCandidates = fileDocuments.filter { it.name.endsWith(DB_EXTENSION, ignoreCase = true) }
            /** When. */
            when (dbCandidates.size) {
                0 -> throw IllegalStateException(
                    context.getString(R.string.settings_import_error_folder_missing_db),
                )

                1 -> dbCandidates.first()

                else -> throw IllegalStateException(
                    context.getString(R.string.settings_import_error_folder_multiple_db),
                )
            }
        }

        /** Wal uri. */
        val walUri = fileDocuments.firstOrNull {
            it.name.equals("${dbDocument.name}-wal", ignoreCase = true)
        }?.uri
        /** Shm uri. */
        val shmUri = fileDocuments.firstOrNull {
            it.name.equals("${dbDocument.name}-shm", ignoreCase = true)
        }?.uri

        /** Resolved. */
        val resolved = ResolvedSource(
            sourceKind = "folder",
            primaryFileName = dbDocument.name,
            dbUri = dbDocument.uri,
            walUri = walUri,
            shmUri = shmUri,
        )
        logger.i(
            "DatabaseImportSupport.resolveFromTree",
            "Resolved database import tree source",
            /** Map of. */
            mapOf(
                "primaryFileName" to resolved.primaryFileName,
                "fileDocumentCount" to fileDocuments.size,
                "hasWal" to (resolved.walUri != null),
                "hasShm" to (resolved.shmUri != null),
            ),
        )
        return resolved
    }

    private fun resolveFromSingleFile(
        /** Context. */
        context: Context,
        /** Source uri. */
        sourceUri: Uri,
    ): ResolvedSource {
        /** File name. */
        val fileName = queryDisplayName(context, sourceUri)
            ?: sourceUri.lastPathSegment?.substringAfterLast('/')
            ?: PayanamDatabase.DATABASE_NAME

        /** If. */
        if (fileName.endsWith(WAL_SUFFIX, ignoreCase = true) ||
            fileName.endsWith(SHM_SUFFIX, ignoreCase = true) ||
            !fileName.endsWith(DB_EXTENSION, ignoreCase = true)
        ) {
            throw IllegalStateException(
                context.getString(R.string.settings_import_error_select_main_db),
            )
        }

        /** Resolved. */
        val resolved = ResolvedSource(
            sourceKind = "file",
            primaryFileName = fileName,
            dbUri = sourceUri,
            walUri = null,
            shmUri = null,
        )
        logger.i(
            "DatabaseImportSupport.resolveFromSingleFile",
            "Resolved single-file import source",
            /** Map of. */
            mapOf("primaryFileName" to resolved.primaryFileName, "uri" to sourceUri.toString()),
        )
        return resolved
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            /** Uri. */
            uri,
            /** Array of. */
            arrayOf(OpenableColumns.DISPLAY_NAME),
            /** Null. */
            null,
            /** Null. */
            null,
            /** Null. */
            null,
        )?.use { cursor ->
            /** Index. */
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            /** If. */
            if (index == -1 || !cursor.moveToFirst()) {
                /** Null. */
                null
            } else {
                cursor.getString(index)
            }
        }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        logger.w(
            "DatabaseImportSupport.queryDisplayName",
            "Failed to read display name from uri",
            /** Map of. */
            mapOf("error" to (e.message ?: "Unknown error")),
        )
        /** Null. */
        null
    }

    private fun listChildDocuments(
        /** Context. */
        context: Context,
        /** Source tree uri. */
        sourceTreeUri: Uri,
    ): List<TreeDocumentEntry> {
        /** Tree document id. */
        val treeDocumentId = DocumentsContract.getTreeDocumentId(sourceTreeUri)
        /** Children uri. */
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(sourceTreeUri, treeDocumentId)
        /** Child documents. */
        val childDocuments = mutableListOf<TreeDocumentEntry>()

        context.contentResolver.query(
            /** Children uri. */
            childrenUri,
            /** Child document projection. */
            CHILD_DOCUMENT_PROJECTION,
            /** Null. */
            null,
            /** Null. */
            null,
            /** Null. */
            null,
        )?.use { cursor ->
            /** Document id index. */
            val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            /** Display name index. */
            val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            /** Mime type index. */
            val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            /** If. */
            if (documentIdIndex == -1 || displayNameIndex == -1 || mimeTypeIndex == -1) {
                return emptyList()
            }

            /** While. */
            while (cursor.moveToNext()) {
                /** Document id. */
                val documentId = cursor.getString(documentIdIndex) ?: continue
                /** Display name. */
                val displayName = cursor.getString(displayNameIndex) ?: continue
                /** Mime type. */
                val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                /** Child uri. */
                val childUri = DocumentsContract.buildDocumentUriUsingTree(sourceTreeUri, documentId)
                childDocuments.add(
                    /** Tree document entry. */
                    TreeDocumentEntry(
                        name = displayName,
                        uri = childUri,
                        isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    ),
                )
            }
        }

        logger.i(
            "DatabaseImportSupport.listChildDocuments",
            "Listed child documents from import tree",
            /** Map of. */
            mapOf(
                "treeUri" to sourceTreeUri.toString(),
                "entryCount" to childDocuments.size,
                "fileCount" to childDocuments.count { !it.isDirectory },
                "directoryCount" to childDocuments.count { it.isDirectory },
            ),
        )
        return childDocuments
    }

    private data class ResolvedSource(
        /** Source kind. */
        val sourceKind: String,
        /** Primary file name. */
        val primaryFileName: String,
        /** Db uri. */
        val dbUri: Uri,
        /** Wal uri. */
        val walUri: Uri?,
        /** Shm uri. */
        val shmUri: Uri?,
    )

    private data class CopyMapping(
        /** Label. */
        val label: String,
        /** Source uri. */
        val sourceUri: Uri,
        /** Target file. */
        val targetFile: File,
    )

    private data class TreeDocumentEntry(
        /** Name. */
        val name: String,
        /** Uri. */
        val uri: Uri,
        /** Is directory. */
        val isDirectory: Boolean,
    )

    private const val DB_EXTENSION = ".db"
    private const val WAL_SUFFIX = "-wal"
    private const val SHM_SUFFIX = "-shm"

    private val CHILD_DOCUMENT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
}
