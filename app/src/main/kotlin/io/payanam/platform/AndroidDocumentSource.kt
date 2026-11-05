//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.shared.platform.COPY_NOT_ATTEMPTED
import io.payanam.shared.platform.DocumentEntry
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.platform.SupportState
import java.io.OutputStream

/**
 * Android's answer to "give me the file the user picked", over SAF.
 *
 * The counterpart of the desktop's `AwtDocumentSource`, and the reason the shared import support has no
 * `Uri` in it: a `content://` URI is just a [String] token here, and a `DocumentsContract` tree walk, a
 * `ContentResolver` query and an `InputStream` are all the platform's private business again.
 *
 * WHY IT IS CONSTRUCTED WITH A [Context] RATHER THAN INJECTED: the SAF calls cannot be made without one,
 * and the desktop's implementation needs nothing at all. A constructor parameter is therefore the only
 * honest common shape, and the app constructs this where a `Context` already lives — the same convention
 * the settings import path already uses for its `AndroidDatabaseEncryptionProbe`. Wiring it through Hilt
 * belongs with the change that gives the import ViewModels their whole constructor.
 *
 * The tree-walk answers are depth ONE, matching [DocumentSource.childrenOf]: a recursive walk would
 * descend into nested backup folders and offer the user a database from somewhere they did not choose.
 */
class AndroidDocumentSource(
    private val context: Context,
) : DocumentSource {
    private val logger = UnifiedLogger.getInstance()

    override val fileAccess: SupportState = SupportState.SUPPORTED

    /**
     * SAF trees are the platform's real folder capability, so this is SUPPORTED rather than
     * NOT_APPLICABLE — claiming Android has no folders would make shared UI hide a route that works.
     */
    override val containerAccess: SupportState = SupportState.SUPPORTED

    override suspend fun requestSource(suggestedName: String?): String? = null

    override suspend fun requestDestination(suggestedName: String): String? = null

    override fun displayNameOf(token: String): String? = queryDisplayName(Uri.parse(token))

    override fun kindOf(token: String): DocumentKind = if (DocumentsContract.isTreeUri(Uri.parse(token))) DocumentKind.CONTAINER else DocumentKind.FILE

    @Suppress("TooGenericExceptionCaught") // Intentional: any ContentProvider may throw when opening a stream
    override fun copyInto(
        token: String,
        destination: OutputStream,
    ): Long {
        val uri = Uri.parse(token)
        val inputStream =
            try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                logger.w(
                    "AndroidDocumentSource.copyInto",
                    "Could not open picked source stream",
                    mapOf("error" to (e.message ?: "Unknown error")),
                )
                return COPY_NOT_ATTEMPTED
            } ?: return COPY_NOT_ATTEMPTED
        return inputStream.use { stream -> stream.copyTo(destination) }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: any ContentProvider may throw when opening a stream
    override fun readLeadingBytes(
        token: String,
        byteCount: Int,
    ): ByteArray? {
        val inputStream =
            try {
                context.contentResolver.openInputStream(Uri.parse(token))
            } catch (e: Exception) {
                logger.w(
                    "AndroidDocumentSource.readLeadingBytes",
                    "Could not open picked source for header read",
                    mapOf("error" to (e.message ?: "Unknown error")),
                )
                return null
            } ?: return null
        val header = ByteArray(byteCount)
        val read = inputStream.use { stream -> stream.read(header) }
        return if (read <= 0) null else header
    }

    override fun childrenOf(token: String): List<DocumentEntry> {
        val treeUri = Uri.parse(token)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val entries = mutableListOf<DocumentEntry>()

        context.contentResolver.query(
            childrenUri,
            CHILD_DOCUMENT_PROJECTION,
            null,
            null,
            null,
        )?.use { cursor: Cursor ->
            val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (
                documentIdIndex == NO_SUCH_COLUMN ||
                displayNameIndex == NO_SUCH_COLUMN ||
                mimeTypeIndex == NO_SUCH_COLUMN
            ) {
                return emptyList()
            }
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(documentIdIndex) ?: continue
                val displayName = cursor.getString(displayNameIndex) ?: continue
                val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                entries.add(
                    DocumentEntry(
                        name = displayName,
                        token = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
                        kind =
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            DocumentKind.CONTAINER
                        } else {
                            DocumentKind.FILE
                        },
                    ),
                )
            }
        }

        logger.i(
            "AndroidDocumentSource.childrenOf",
            "Listed child documents from import tree",
            mapOf(
                "entryCount" to entries.size,
                "fileCount" to entries.count { it.kind == DocumentKind.FILE },
                "directoryCount" to entries.count { it.kind == DocumentKind.CONTAINER },
            ),
        )
        CrashBreadcrumbs.record(
            source = "AndroidDocumentSource.childrenOf",
            stage = "listed",
            data = mapOf("entryCount" to entries.size),
        )
        return entries
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: a ContentResolver query may throw any provider's fault
    private fun queryDisplayName(uri: Uri): String? =
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index == NO_SUCH_COLUMN || !cursor.moveToFirst()) {
                    null
                } else {
                    cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            logger.w(
                "AndroidDocumentSource.queryDisplayName",
                "Failed to read display name from uri",
                mapOf("error" to (e.message ?: "Unknown error")),
            )
            null
        }

    private companion object {
        /**
         * What `Cursor.getColumnIndex` answers for a column the provider did not return. Named rather
         * than written as a literal because it is a value the platform defines, not one chosen here.
         */
        const val NO_SUCH_COLUMN = -1

        val CHILD_DOCUMENT_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
    }
}
