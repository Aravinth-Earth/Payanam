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
    val sourceKind: String,
    val primaryFileName: String,
    val bytesCopied: Long,
    val companionFilesCopied: Int,
)

internal object DatabaseImportSupport {
    private val logger = UnifiedLogger.getInstance()
    /**
     * Performs the copy database artifacts.
     */
    fun copyDatabaseArtifacts(
        context: Context,
        sourceUri: Uri,
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
        val source = resolveSource(context, sourceUri)
        logger.i(
            "DatabaseImportSupport.copyDatabaseArtifacts",
            "Resolved import source",
            mapOf(
                "sourceKind" to source.sourceKind,
                "primaryFileName" to source.primaryFileName,
                "hasWal" to (source.walUri != null),
                "hasShm" to (source.shmUri != null),
                "targetPath" to targetDatabaseFile.absolutePath,
            ),
        )
        val copyMappings = mutableListOf(
            CopyMapping(
                label = "db",
                sourceUri = source.dbUri,
                targetFile = targetDatabaseFile,
            ),
        )

        source.walUri?.let {
            copyMappings.add(
                CopyMapping(
                    label = "wal",
                    sourceUri = it,
                    targetFile = File(targetDatabaseFile.parent, "${targetDatabaseFile.name}-wal"),
                ),
            )
        }
        source.shmUri?.let {
            copyMappings.add(
                CopyMapping(
                    label = "shm",
                    sourceUri = it,
                    targetFile = File(targetDatabaseFile.parent, "${targetDatabaseFile.name}-shm"),
                ),
            )
        }
        var bytesCopied = 0L
        var companionFilesCopied = 0
        copyMappings.forEachIndexed { index, mapping ->
            val currentBytes = context.contentResolver.openInputStream(mapping.sourceUri)?.use { inputStream ->
                FileOutputStream(mapping.targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IllegalStateException(
                context.getString(R.string.settings_import_error_source_stream_open),
            )
            if (index == 0) {
                bytesCopied = currentBytes
            } else {
                companionFilesCopied++
            }

            logger.i(
                "DatabaseImportSupport.copyDatabaseArtifacts",
                "Copied import artifact",
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

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readPlaintextDatabaseUserVersion(databaseFile: File, logTag: String): Int? = try {
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.version
        }
    } catch (e: Exception) {
        logger.w(
            logTag,
            "Failed to read SQLite user_version from imported database",
            mapOf("error" to (e.message ?: "Unknown error")),
        )
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
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun isStandardSqliteFile(databaseFile: File, logTag: String): Boolean {
        if (!databaseFile.exists() || databaseFile.length() == 0L) {
            logger.w(
                logTag,
                "Database format check: file missing or empty",
                mapOf(
                    "file" to databaseFile.name,
                    "exists" to databaseFile.exists(),
                    "sizeBytes" to databaseFile.length(),
                ),
            )
            return false
        }
        return try {
            val header = ByteArray(16)
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            val hexHeader = header.take(bytesRead.coerceAtLeast(0)).joinToString("") { "%02X".format(it) }
            // Standard SQLite magic: "SQLite format 3\0" (16 bytes)
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
            val isStandard = bytesRead >= sqliteMagic.size &&
                header.copyOf(sqliteMagic.size).contentEquals(sqliteMagic)
            logger.i(
                logTag,
                "Database file format check",
                mapOf(
                    "file" to databaseFile.name,
                    "sizeKB" to (databaseFile.length() / 1024),
                    "headerHex" to hexHeader,
                    "isStandardSqlite" to isStandard,
                ),
            )
            isStandard
        } catch (e: Exception) {
            logger.w(logTag, "Failed to read database file header for format check", mapOf("error" to (e.message ?: "Unknown")))
            false
        }
    }
    /**
     * Performs the consolidate wal after import.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun consolidateWalAfterImport(dbFile: File, logTag: String): Boolean {
        val walFile = File(dbFile.parent, "${dbFile.name}-wal")
        val shmFile = File(dbFile.parent, "${dbFile.name}-shm")

        logger.i(
            logTag,
            "WAL consolidation: initial state",
            mapOf(
                "db" to dbFile.name,
                "dbExists" to dbFile.exists(),
                "dbSizeKB" to (dbFile.length() / 1024),
                "walExists" to walFile.exists(),
                "walSizeKB" to (walFile.length() / 1024),
                "shmExists" to shmFile.exists(),
            ),
        )
        if (!walFile.exists()) {
            logger.i(logTag, "WAL consolidation: no WAL present, nothing to merge")
            return true
        }
        val hasStandardHeader = hasStandardSqliteHeader(dbFile)
        if (!hasStandardHeader) {
            logger.w(
                logTag,
                "WAL consolidation skipped for non-standard DB header; preserving DB/WAL/SHM",
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
        val tempDb = File(dbFile.parent, "${dbFile.name}.wal_merge_tmp")
        val tempWal = File(dbFile.parent, "${dbFile.name}.wal_merge_tmp-wal")
        val tempShm = File(dbFile.parent, "${dbFile.name}.wal_merge_tmp-shm")

        return try {
            dbFile.copyTo(tempDb, overwrite = true)
            walFile.copyTo(tempWal, overwrite = true)
            // No SHM copy — SQLite creates a fresh one for tempDb on open.

            logger.i(
                logTag,
                "WAL consolidation: temp copies created",
                mapOf(
                    "tempDbSizeKB" to (tempDb.length() / 1024),
                    "tempWalSizeKB" to (tempWal.length() / 1024),
                ),
            )
            val checkpointed = try {
                SQLiteDatabase.openDatabase(
                    tempDb.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    DatabaseErrorHandler { /* no-op: temp file only, not production DB */ },
                ).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            logger.i(
                                logTag,
                                "WAL checkpoint via temp copy",
                                mapOf(
                                    "busy" to cursor.getInt(0),
                                    "log" to cursor.getInt(1),
                                    "ckpt" to cursor.getInt(2),
                                ),
                            )
                        }
                    }
                }
                val survived = tempDb.exists() && tempDb.length() > 0
                logger.i(
                    logTag,
                    "WAL checkpoint temp open succeeded",
                    mapOf("tempDbSurvived" to survived, "tempDbSizeKB" to (tempDb.length() / 1024)),
                )
                survived
            } catch (e: Exception) {
                logger.w(
                    logTag,
                    "WAL checkpoint on temp copy failed; preserving WAL to avoid data loss",
                    mapOf(
                        "error" to (e.message ?: "Unknown"),
                        "exception" to e.javaClass.simpleName,
                        "tempDbExists" to tempDb.exists(),
                        "tempDbSizeKB" to (tempDb.length() / 1024),
                    ),
                )
                false
            }
            if (checkpointed) {
                val walDeleted = walFile.delete()
                logger.i(
                    logTag,
                    "WAL consolidation: production WAL removed",
                    mapOf(
                        "walDeleted" to walDeleted,
                        "dbSizeKB" to (dbFile.length() / 1024),
                    ),
                )
                tempDb.copyTo(dbFile, overwrite = true)
                logger.i(
                    logTag,
                    "WAL merged into DB via temp copy; production DB consolidated",
                    mapOf("dbSizeKB" to (dbFile.length() / 1024)),
                )
            } else {
                logger.w(
                    logTag,
                    "WAL retained after consolidation failure; caller must continue with DB/WAL",
                    mapOf(
                        "dbSizeKB" to (dbFile.length() / 1024),
                        "walExists" to walFile.exists(),
                        "walSizeKB" to (walFile.length() / 1024),
                    ),
                )
            }
            checkpointed
        } catch (e: Exception) {
            logger.e(logTag, "WAL consolidation failed unexpectedly; leaving DB/WAL/SHM unchanged", e)
            false
        } finally {
            tempDb.delete()
            tempWal.delete()
            tempShm.delete()
        }
    }

    private fun hasStandardSqliteHeader(databaseFile: File): Boolean {
        if (!databaseFile.exists() || databaseFile.length() < 16L) return false
        return try {
            val header = ByteArray(16)
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
            bytesRead >= sqliteMagic.size &&
                header.copyOf(sqliteMagic.size).contentEquals(sqliteMagic)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Decrypts a SQLCipher-encrypted database file in-place using the provided passphrase.
     * On success, [databaseFile] is replaced with a standard plaintext SQLite database.
     * Throws if the passphrase is incorrect or the file cannot be opened.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun decryptEncryptedImport(
        context: Context,
        databaseFile: File,
        passphrase: String,
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
        val tempDecrypted = File(databaseFile.parent, "${databaseFile.name}.import_decrypt.tmp")
        logger.i(
            logTag,
            "Starting encrypted import decrypt",
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
            if (!tempDecrypted.renameTo(databaseFile)) {
                tempDecrypted.copyTo(databaseFile, overwrite = true)
                tempDecrypted.delete()
            }
            logger.i(
                logTag,
                "Encrypted import decrypted to plaintext successfully",
                mapOf("sizeKB" to (databaseFile.length() / 1024)),
            )
            CrashSafeBreadcrumbs.record(
                context = context,
                source = "DatabaseImportSupport.decryptEncryptedImport",
                stage = "completed",
                data = mapOf("sizeKB" to (databaseFile.length() / 1024)),
            )
        } catch (e: Exception) {
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
     * Returns true when the validate supported plaintext import schema.
     */
    fun validateSupportedPlaintextImportSchema(
        context: Context,
        databaseFile: File,
        logTag: String,
    ): Int {
        val userVersion = readPlaintextDatabaseUserVersion(
            databaseFile = databaseFile,
            logTag = "DatabaseImportSupport.readPlaintextDatabaseUserVersion",
        ) ?: throw IllegalStateException(
            context.getString(R.string.settings_import_error_unreadable_db),
        )
        if (userVersion < DatabaseHealthChecker.MIN_MIGRATABLE_VERSION) {
            throw IllegalStateException(
                context.getString(
                    R.string.settings_import_error_schema_too_old,
                    userVersion,
                    DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
                ),
            )
        }
        if (userVersion > DatabaseHealthChecker.CURRENT_VERSION) {
            throw IllegalStateException(
                context.getString(
                    R.string.settings_import_error_schema_too_new,
                    userVersion,
                    DatabaseHealthChecker.CURRENT_VERSION,
                ),
            )
        }

        logger.i(
            logTag,
            "Validated imported plaintext database schema",
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
            mapOf(
                "uri" to sourceUri.toString(),
                "isTreeUri" to DocumentsContract.isTreeUri(sourceUri),
            ),
        )
        if (DocumentsContract.isTreeUri(sourceUri)) {
            return resolveFromTree(context, sourceUri)
        }
        return resolveFromSingleFile(context, sourceUri)
    }

    private fun resolveFromTree(context: Context, sourceTreeUri: Uri): ResolvedSource {
        val childDocuments = listChildDocuments(context, sourceTreeUri)
        val fileDocuments = childDocuments.filterNot { it.isDirectory }
        val preferredDb = fileDocuments.firstOrNull {
            it.name.equals(PayanamDatabase.DATABASE_NAME, ignoreCase = true)
        }
        val dbDocument = preferredDb ?: run {
            val dbCandidates = fileDocuments.filter { it.name.endsWith(DB_EXTENSION, ignoreCase = true) }
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
        val walUri = fileDocuments.firstOrNull {
            it.name.equals("${dbDocument.name}-wal", ignoreCase = true)
        }?.uri
        val shmUri = fileDocuments.firstOrNull {
            it.name.equals("${dbDocument.name}-shm", ignoreCase = true)
        }?.uri
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
        context: Context,
        sourceUri: Uri,
    ): ResolvedSource {
        val fileName = queryDisplayName(context, sourceUri)
            ?: sourceUri.lastPathSegment?.substringAfterLast('/')
            ?: PayanamDatabase.DATABASE_NAME
        if (fileName.endsWith(WAL_SUFFIX, ignoreCase = true) ||
            fileName.endsWith(SHM_SUFFIX, ignoreCase = true) ||
            !fileName.endsWith(DB_EXTENSION, ignoreCase = true)
        ) {
            throw IllegalStateException(
                context.getString(R.string.settings_import_error_select_main_db),
            )
        }
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
            mapOf("primaryFileName" to resolved.primaryFileName, "uri" to sourceUri.toString()),
        )
        return resolved
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index == -1 || !cursor.moveToFirst()) {
                null
            } else {
                cursor.getString(index)
            }
        }
    } catch (e: Exception) {
        logger.w(
            "DatabaseImportSupport.queryDisplayName",
            "Failed to read display name from uri",
            mapOf("error" to (e.message ?: "Unknown error")),
        )
        null
    }

    private fun listChildDocuments(
        context: Context,
        sourceTreeUri: Uri,
    ): List<TreeDocumentEntry> {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(sourceTreeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(sourceTreeUri, treeDocumentId)
        val childDocuments = mutableListOf<TreeDocumentEntry>()

        context.contentResolver.query(
            childrenUri,
            CHILD_DOCUMENT_PROJECTION,
            null,
            null,
            null,
        )?.use { cursor ->
            val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (documentIdIndex == -1 || displayNameIndex == -1 || mimeTypeIndex == -1) {
                return emptyList()
            }
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(documentIdIndex) ?: continue
                val displayName = cursor.getString(displayNameIndex) ?: continue
                val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                val childUri = DocumentsContract.buildDocumentUriUsingTree(sourceTreeUri, documentId)
                childDocuments.add(
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
        val sourceKind: String,
        val primaryFileName: String,
        val dbUri: Uri,
        val walUri: Uri?,
        val shmUri: Uri?,
    )

    private data class CopyMapping(
        val label: String,
        val sourceUri: Uri,
        val targetFile: File,
    )

    private data class TreeDocumentEntry(
        val name: String,
        val uri: Uri,
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
