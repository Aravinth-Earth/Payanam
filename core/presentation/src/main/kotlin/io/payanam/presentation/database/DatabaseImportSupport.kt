//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.presentation.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.EncryptedFileClassifier
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.shared.platform.COPY_NOT_ATTEMPTED
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.platform.DocumentSource
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * What one import actually copied, in terms a screen can render.
 *
 * PROMOTED FROM `:app` (`ui/viewmodel/DatabaseImportSupport.kt`, 730 lines). Every Android type it used
 * is gone rather than relocated:
 *
 *  - **`Uri` → a `String` token.** The 48 `Uri` sites now read and write one opaque token through
 *    [DocumentSource]; SAF's `content://` string and the desktop's filesystem path are indistinguishable
 *    here, which is the point — the caller resolves the platform handle and this code never learns which
 *    platform it is on. `DocumentsContract`, `OpenableColumns` and `ContentResolver` queries left with
 *    the `Uri` they were reading.
 *  - **`Context` → the two ports that need one.** [DocumentSource] opens and copies, and the
 *    [io.payanam.shared.database.DatabaseHealthCheck] opens a database file to read its schema version.
 *    The `getString(R.string.x)` calls became [UiStrings] reads, supplied by the caller, because a
 *    shared module has no `Context` to resolve an id with.
 *  - **`SQLiteDatabase`/`DatabaseErrorHandler` → [DatabaseInitWalConsolidator].** Running
 *    `PRAGMA wal_checkpoint(TRUNCATE)` is a Room/SQLCipher question and stays on the platform, which is
 *    the port the DB-init flow already had; this file keeps the temp-copy ORCHESTRATION that decides
 *    whether the production `-wal` may be deleted, and that decision is safety-critical, so it stays here.
 *  - **`UnifiedLogger` → [AppLogger].** A plain-JVM module cannot reach the app's logger singleton.
 *
 * The plaintext-magic probe no longer restates the SQLite header: it asks
 * [EncryptedFileClassifier], which is the one place that rule is written down.
 */
data class DatabaseImportCopyResult(
    val sourceKind: String,
    val primaryFileName: String,
    val bytesCopied: Long,
    val companionFilesCopied: Int,
)

/**
 * The schema range an imported plaintext database must fall in, read from the ONE place that owns it.
 *
 * This used to be `DatabaseHealthChecker.MIN_MIGRATABLE_VERSION` / `.CURRENT_VERSION`, which are
 * themselves aliases of [PayanamDatabaseSchema] — an Android library in `:core:database`, invisible to a
 * `kotlin.jvm` module. Naming the shared constant directly keeps the same number and removes a hop.
 */
object DatabaseImportSchemaRange {
    /** Oldest schema this build can migrate an import forward from. */
    val minimumSupported: Int = PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION

    /** The schema this build writes, and therefore the newest an import may be. */
    val currentSupported: Int = PayanamDatabaseSchema.VERSION
}

/** Copying an import's artifact family out of a picked document, and gating what may be picked. */
object DatabaseImportSupport {
    private val logger get() = AppLogger.current

    /**
     * Copies the selected database (plus any `-wal`/`-shm`/`-journal` companions) out of
     * [sourceToken] over the target files, returning what was copied.
     *
     * [sourceToken] is whatever [documentSource] handed out — a `content://` URI on Android, a path on
     * the desktop — passed back unchanged. [strings] supplies the localized refusals, because this module
     * cannot resolve a resource id.
     */
    fun copyDatabaseArtifacts(
        documentSource: DocumentSource,
        sourceToken: String,
        targetDatabaseFile: File,
        strings: UiStrings,
    ): DatabaseImportCopyResult {
        CrashBreadcrumbs.record(
            source = "DatabaseImportSupport.copyDatabaseArtifacts",
            stage = "started",
            data =
                mapOf(
                    "sourceToken" to sourceToken,
                    "targetPath" to targetDatabaseFile.absolutePath,
                ),
        )
        val source = resolveSource(documentSource, sourceToken, strings)
        logger.i(
            "DatabaseImportSupport.copyDatabaseArtifacts",
            "Resolved import source",
            mapOf(
                "sourceKind" to source.sourceKind,
                "primaryFileName" to source.primaryFileName,
                "companionCount" to source.companionTokens.size,
                "targetPath" to targetDatabaseFile.absolutePath,
            ),
        )
        // The artifact family is written INTO the user's database directory, and nothing here may assume that
        // directory exists. Android's platform creates its `databases/` dir; the DESKTOP does not - the init
        // gate's import is the FIRST writer on a fresh profile, and `FileOutputStream` never creates parents.
        // Measured 2026-10-02, the desktop's first successful pick died with
        // `.../Payanam/database/payanam.db.enc (No such file or directory)` - an OS message naming the APP's
        // target while the user's own file had not even been opened, which reads as "it is refusing a path I
        // never chose". Creating the directory is idempotent, so the platforms that already have it are
        // unaffected.
        targetDatabaseFile.absoluteFile.parentFile?.let { Files.createDirectories(it.toPath()) }
        val copyMappings =
            mutableListOf(
                CopyMapping(
                    label = "db",
                    sourceToken = source.dbToken,
                    targetFile = targetDatabaseFile,
                ),
            )
        // Built from the shared artifact family rather than a hand-typed pair: `-journal` is a file
        // SQLite really creates next to a database, and a list that silently omits it is a list that
        // silently drops a committed transaction.
        DatabaseArtifacts.sidecarSuffixes.forEach { suffix ->
            val companion = source.companionTokens[suffix] ?: return@forEach
            copyMappings.add(
                CopyMapping(
                    label = suffix.removePrefix("-"),
                    sourceToken = companion,
                    targetFile = File(targetDatabaseFile.parent, targetDatabaseFile.name + suffix),
                ),
            )
        }
        var bytesCopied = 0L
        var companionFilesCopied = 0
        copyMappings.forEachIndexed { index, mapping ->
            val currentBytes = copyOne(documentSource, mapping, strings)
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
            CrashBreadcrumbs.record(
                source = "DatabaseImportSupport.copyDatabaseArtifacts",
                stage = "artifact_copied",
                data =
                    mapOf(
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
            CrashBreadcrumbs.record(
                source = "DatabaseImportSupport.copyDatabaseArtifacts",
                stage = "completed",
                data =
                    mapOf(
                        "sourceKind" to source.sourceKind,
                        "primaryFileName" to source.primaryFileName,
                        "copiedArtifacts" to copyMappings.size,
                    ),
            )
        }
    }

    private fun copyOne(
        documentSource: DocumentSource,
        mapping: CopyMapping,
        strings: UiStrings,
    ): Long =
        FileOutputStream(mapping.targetFile).use { outputStream ->
            val written = documentSource.copyInto(mapping.sourceToken, outputStream)
            if (written == COPY_NOT_ATTEMPTED) {
                throw IllegalStateException(strings.settingsImportErrorSourceStreamOpen)
            }
            written
        }

    /**
     * Reads the first bytes of [databaseFile] and reports whether they are the standard SQLite header.
     *
     * RE-POINTED, not reimplemented: this used to build `"SQLite format 3\0"` in a local `ByteArray(16)`
     * and compare, which was a second copy of the encrypted-file rule and one that could disagree with
     * [io.payanam.shared.database.DatabaseEncryptionProbe.isDetectablyEncrypted] about the same file. The
     * verdict now comes from [EncryptedFileClassifier], so "not encrypted" means the same thing here as
     * it does on the other side of the import flow.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: a picked file can fail with SecurityException, IOException or a provider fault
    fun isStandardSqliteFile(
        databaseFile: File,
        logTag: String,
    ): Boolean {
        logger.i(
            logTag,
            "IMPORT_PROBE_FORMAT_ENTER",
            mapOf(
                "file" to databaseFile.name,
                "exists" to databaseFile.exists(),
                "sizeBytes" to databaseFile.length(),
            ),
        )
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
            val header = ByteArray(EncryptedFileClassifier.MINIMUM_PROBE_BYTES)
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            val hexHeader = header.take(bytesRead.coerceAtLeast(0)).joinToString("") { "%02X".format(it) }
            val verdict =
                EncryptedFileClassifier.classify(
                    header = header,
                    totalLength = databaseFile.length(),
                    readSucceeded = bytesRead > 0,
                )
            logger.i(
                logTag,
                "Database file format check",
                mapOf(
                    "file" to databaseFile.name,
                    "sizeKB" to (databaseFile.length() / 1024),
                    "headerHex" to hexHeader,
                    "isStandardSqlite" to verdict.hasPlaintextMagic,
                    "verdictReason" to verdict.reason.name,
                ),
            )
            verdict.hasPlaintextMagic
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(
                logTag,
                "Failed to read database file header for format check",
                mapOf("error" to (e.message ?: "Unknown")),
            )
            false
        }
    }

    /*
     * After copying db+wal+shm from an export, merges WAL data into the DB and removes all
     * WAL artefacts so the health check can open a clean single-file database.
     *
     * Design constraints discovered across builds 991–994:
     *  - Opening a database on the PRODUCTION path while a WAL is co-located destroys files at the
     *    Android native layer, which is why the fold runs on a TEMP copy.
     *  - Keeping the WAL and letting the health check open it fails even when the WAL magic is valid,
     *    because the WAL header checksum/salt may be inconsistent when the backup was taken while the
     *    app was running.
     *
     * Strategy — temp-copy checkpoint (this orchestration is platform-neutral; the fold is not):
     * 1. Always delete the SHM immediately (process-local index; must be recreated per-process).
     * 2. No WAL → done.
     * 3. Copy DB + WAL to temp paths in the same directory.
     * 4. Ask [walConsolidator] to fold the TEMP file, never the production path, so any native-layer
     *    file destruction from a bad WAL hits only the temp copy.
     * 5. On fold success: delete the production WAL and overwrite the production DB with the
     *    consolidated temp copy. On failure: leave production DB unchanged.
     * 6. Clean up temp files in finally.
     *
     * Either path leaves the production DB path with no WAL present for the health check.
     */

    /**
     * Merges an imported WAL into its database via a temp-copy checkpoint and
     * removes WAL artifacts (false when consolidation was skipped/failed).
     *
     * [walConsolidator] runs `PRAGMA wal_checkpoint(TRUNCATE)` against the TEMP copy and reports whether
     * the log was actually emptied — not merely whether the file still existed, which proves nothing
     * about frames that existed only in the file this method is about to delete.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; broad catch intentional
    fun consolidateWalAfterImport(
        dbFile: File,
        logTag: String,
        walConsolidator: DatabaseInitWalConsolidator,
    ): Boolean {
        val walFile = File(dbFile.parent, dbFile.name + DatabaseArtifacts.WAL_SUFFIX)
        val shmFile = File(dbFile.parent, dbFile.name + DatabaseArtifacts.SHM_SUFFIX)

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
        if (!isStandardSqliteFile(dbFile, logTag)) {
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
        val tempDb = File(dbFile.parent, dbFile.name + "." + DatabaseArtifacts.WAL_MERGE_TEMP_SUFFIX)
        val tempWal = File(dbFile.parent, tempDb.name + DatabaseArtifacts.WAL_SUFFIX)
        val tempShm = File(dbFile.parent, tempDb.name + DatabaseArtifacts.SHM_SUFFIX)

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
            val checkpointed =
                try {
                    val folded = walConsolidator.consolidateWalAfterImport(tempDb, logTag)
                    logger.i(
                        logTag,
                        "WAL checkpoint via temp copy",
                        mapOf(
                            "folded" to folded,
                            "tempDbSurvived" to tempDb.exists(),
                            "tempDbSizeKB" to (tempDb.length() / 1024),
                        ),
                    )
                    folded
                } catch (e: CancellationException) {
                    throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(logTag, "WAL consolidation failed unexpectedly; leaving DB/WAL/SHM unchanged", e)
            false
        } finally {
            tempDb.delete()
            tempWal.delete()
            tempShm.delete()
        }
    }

    private fun resolveSource(
        documentSource: DocumentSource,
        sourceToken: String,
        strings: UiStrings,
    ): ResolvedSource {
        val kind = documentSource.kindOf(sourceToken)
        logger.i(
            "DatabaseImportSupport.resolveSource",
            "Resolving database import source",
            mapOf("token" to sourceToken, "kind" to kind.name),
        )
        return if (kind == DocumentKind.CONTAINER) {
            resolveFromContainer(documentSource, sourceToken, strings)
        } else {
            resolveFromSingleFile(documentSource, sourceToken, strings)
        }
    }

    private fun resolveFromContainer(
        documentSource: DocumentSource,
        containerToken: String,
        strings: UiStrings,
    ): ResolvedSource {
        val childDocuments = documentSource.childrenOf(containerToken).filterNot { it.kind == DocumentKind.CONTAINER }
        val preferredDb =
            childDocuments.firstOrNull {
                it.name.equals(PayanamDatabaseSchema.DATABASE_NAME, ignoreCase = true)
            }
        val dbDocument =
            preferredDb ?: run {
                val dbCandidates = childDocuments.filter { isImportableDatabaseFileName(it.name) }
                when (dbCandidates.size) {
                    0 -> throw IllegalStateException(strings.settingsImportErrorFolderMissingDb)
                    1 -> dbCandidates.first()
                    else -> throw IllegalStateException(strings.settingsImportErrorFolderMultipleDb)
                }
            }
        // Every sidecar SQLite can place beside the main file, matched by name in the SHARED family order
        // — `-wal`, `-shm` and `-journal`. Naming them one by one is how `-journal` went missing once.
        val companions =
            DatabaseArtifacts.sidecarSuffixes
                .mapNotNull { suffix ->
                    val match = childDocuments.firstOrNull { it.name.equals(dbDocument.name + suffix, ignoreCase = true) }
                    match?.let { suffix to it.token }
                }.toMap()
        val resolved =
            ResolvedSource(
                sourceKind = "folder",
                primaryFileName = dbDocument.name,
                dbToken = dbDocument.token,
                companionTokens = companions,
            )
        logger.i(
            "DatabaseImportSupport.resolveFromContainer",
            "Resolved database import container source",
            mapOf(
                "primaryFileName" to resolved.primaryFileName,
                "fileDocumentCount" to childDocuments.size,
                "companions" to resolved.companionTokens.keys.joinToString(","),
            ),
        )
        return resolved
    }

    /**
     * True when [fileName] names a database file this importer accepts: a `.db` file, never a companion.
     * Pure, so the acceptance rule is unit-testable without a platform document handle. It is the single
     * gate for the file picker, which is launched with a wildcard MIME filter because Android has no
     * dependable MIME mapping for `.db`.
     *
     * The companion test reads [DatabaseArtifacts.sidecarSuffixes] rather than a hand-typed pair: a
     * `-journal` picked by mistake is not a database, and the only way to be sure of that is to ask the
     * one list that already knows every companion.
     */
    fun isImportableDatabaseFileName(fileName: String): Boolean =
        DatabaseArtifacts.sidecarSuffixes.none { fileName.endsWith(it, ignoreCase = true) } &&
            fileName.endsWith(DB_EXTENSION, ignoreCase = true)

    private fun resolveFromSingleFile(
        documentSource: DocumentSource,
        sourceToken: String,
        strings: UiStrings,
    ): ResolvedSource {
        val fileName =
            documentSource.displayNameOf(sourceToken)
                ?: sourceToken.substringAfterLast('/')
                ?: PayanamDatabaseSchema.DATABASE_NAME
        if (!isImportableDatabaseFileName(fileName)) {
            throw IllegalStateException(strings.settingsImportErrorSelectMainDb)
        }
        val resolved =
            ResolvedSource(
                sourceKind = "file",
                primaryFileName = fileName,
                dbToken = sourceToken,
                companionTokens = emptyMap(),
            )
        logger.i(
            "DatabaseImportSupport.resolveFromSingleFile",
            "Resolved single-file import source",
            mapOf("primaryFileName" to resolved.primaryFileName, "token" to sourceToken),
        )
        return resolved
    }

    private data class ResolvedSource(
        val sourceKind: String,
        val primaryFileName: String,
        val dbToken: String,
        /** Companion suffix (`-wal`, `-shm`, `-journal`) to that companion's token. */
        val companionTokens: Map<String, String>,
    )

    private data class CopyMapping(
        val label: String,
        val sourceToken: String,
        val targetFile: File,
    )

    private const val DB_EXTENSION = ".db"

    // The `-wal` and `-shm` names are read from DatabaseArtifacts (DatabaseArtifacts.WAL_SUFFIX,
    // DatabaseArtifacts.SHM_SUFFIX) and are not restated in this file.
    // A sidecar this file does not know about would be copied into the temp fold, left behind after a
    // successful checkpoint, or never deleted — and each of those loses a committed write silently.
}
