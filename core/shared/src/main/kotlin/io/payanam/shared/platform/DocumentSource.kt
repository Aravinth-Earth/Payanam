//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import java.io.OutputStream

/**
 * Whether a picked thing is one file or a folder of them.
 *
 * Named by what the user chose, not by which platform asked: Android's SAF hands back a tree URI, the
 * desktop a directory, and shared code must not learn which.
 */
enum class DocumentKind {
    /** One file. Every platform can produce this. */
    FILE,

    /** A folder whose immediate children can be listed. */
    CONTAINER,
}

/**
 * One entry found by listing a [DocumentKind.CONTAINER].
 *
 * The token is the platform's own handle, opaque here for the same reason the transfer gateway takes one:
 * Android yields a `content://` URI, the desktop a filesystem path, and no shared signature can name one
 * without excluding the other platform.
 */
data class DocumentEntry(
    /** The name the user sees — what the `.db` / `-wal` / `-shm` matching reads. */
    val name: String,

    /** The token this entry reads as, passed back to [DocumentSource] unchanged. */
    val token: String,

    val kind: DocumentKind,
)

/**
 * [DocumentSource.copyInto]'s answer when the token could not be opened at all, named once so both
 * platforms return the same sentinel instead of each inventing a literal.
 */
const val COPY_NOT_ATTEMPTED: Long = -1L

/**
 * Reading and writing the ONE file a user picks.
 *
 * An export is the encrypted database file and an import is handed one, so what both flows need is access
 * to a CHOSEN FILE. Export/import semantics are not here on purpose: they stay in the transfer layer and in
 * the data layer's passphrase handling, which is the only place that knows what a Payanam backup is.
 *
 * **No stream is ever handed out.** A Payanam database is tens of megabytes, so a `ByteArray` answer is an
 * out-of-memory crash on a phone, and a returned `InputStream` is a descriptor leak waiting for the one
 * caller that forgets to close it — on Android that leak is a held content provider. [copyInto] and
 * [readLeadingBytes] are the only two ways to get bytes out, and neither can be left open.
 */
interface DocumentSource {
    /** Picking and reading a single file. Supported wherever a user can choose one file. */
    val fileAccess: SupportState

    /**
     * Reading a folder's contents. This is the only capability that genuinely differs between the
     * platforms — Android has SAF trees, the desktop has a directory on disk.
     */
    val containerAccess: SupportState

    /**
     * True when a picked folder can really be walked, so shared UI can offer or hide the folder route
     * instead of presenting a control that leads nowhere.
     */
    val offersFolderImport: Boolean get() = containerAccess == SupportState.SUPPORTED

    /**
     * Asks the user for a file to read, or null when they cancelled.
     *
     * Suspend because a platform picker is a blocking modal — the desktop's AWT dialog must not be driven
     * from a coroutine dispatcher thread. Cancelled is null and never an empty string, so "the user
     * cancelled" cannot be confused with "the user chose something unusable".
     */
    suspend fun requestSource(suggestedName: String? = null): String?

    /** Asks the user where to write, or null when they cancelled. */
    suspend fun requestDestination(suggestedName: String): String?

    /**
     * The name the user sees for [token], or null when the platform cannot supply one.
     *
     * Null stays possible rather than being derived from the token: this is what rejects a `-wal` or `-shm`
     * companion picked by mistake, and a name invented from a path would defeat the check.
     */
    fun displayNameOf(token: String): String?

    /** What [token] is. A platform with no folder concept reports every token as [DocumentKind.FILE]. */
    fun kindOf(token: String): DocumentKind

    /**
     * Copies [token]'s bytes into [destination] and returns how many were written.
     *
     * Blocking rather than suspend: every caller already sits inside its own IO dispatcher, and making
     * this suspend would imply the implementation moves threads — which a desktop file dialog genuinely
     * cannot do. The caller's dispatcher stays the caller's business.
     *
     * Returns -1 when the token cannot be opened at all. A copy that starts and then fails throws, leaving
     * [destination] to the caller's cleanup — a truncated file is never reported as a success.
     */
    fun copyInto(
        token: String,
        destination: OutputStream,
    ): Long

    /**
     * The first [byteCount] bytes of [token], or null when it cannot be read.
     *
     * Separate from [copyInto] so the "is this a standard SQLite file" probe — which reads a 16-byte magic
     * number — does not have to stage a whole database to look at its header.
     */
    fun readLeadingBytes(
        token: String,
        byteCount: Int,
    ): ByteArray?

    /**
     * The immediate children of a [DocumentKind.CONTAINER] [token], empty when it has none.
     *
     * Depth ONE, deliberately: a recursive walk would descend into nested backup folders and offer the user
     * a database from somewhere they did not choose. When [containerAccess] is not
     * [SupportState.SUPPORTED] this returns empty and the platform logs why — a missing capability is
     * never a silent empty list.
     */
    fun childrenOf(token: String): List<DocumentEntry>
}
