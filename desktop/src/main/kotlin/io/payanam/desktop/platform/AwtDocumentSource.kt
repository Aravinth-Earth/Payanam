//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.COPY_NOT_ATTEMPTED
import io.payanam.shared.platform.DocumentEntry
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.platform.SupportState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The dispatcher the OS file dialog is driven on - deliberately NOT the caller's.
 *
 * Compose dispatches a click handler's coroutine inline, on the AWT event thread and INSIDE the
 * pointer-event dispatch. A modal shown there blocks that thread while AWT pumps events, the pump
 * re-enters Compose, and Compose resumes a continuation the coroutine machinery had already completed:
 * `CoroutinesInternalError: ... CompletedContinuation cannot be cast to DispatchedContinuation`, followed
 * by a `ClassCastException` out of the same resume. Measured 2026-10-02 on the desktop's first import
 * attempt, with both stacks ending at [choose].
 *
 * Hopping off the caller's thread suspends the UI coroutine, hands the event thread back to AWT, and lets
 * the dialog run its own event loop where blocking is harmless. `Dispatchers.IO` rather than a private
 * thread because the thread is BLOCKED for as long as the dialog is open, which is exactly what that pool
 * is for.
 */
private val DIALOG_DISPATCHER: CoroutineDispatcher = Dispatchers.IO

/**
 * The desktop's answer to "give me the file the user picked", over a filesystem.
 *
 * Replaces the desktop's own `DesktopFilePicker`, which was internal to this module and therefore could
 * not be the shared contract: it returned a bare path String and knew nothing about reading the file back.
 * Folding it in here means the token the picker hands out is openable by the SAME contract that produced
 * it, which is what lets a shared ViewModel drive an import without knowing which platform it is on.
 *
 * AWT rather than a Compose-side file browser because Compose Desktop runs on AWT, this needs no extra
 * dependency, and an OS file dialog is what a desktop user expects for choosing a file on disk.
 */
internal class AwtDocumentSource(
    private val parent: () -> Frame? = { null },
) : DocumentSource {
    private val logger = AppLogger.current

    override val fileAccess: SupportState = SupportState.SUPPORTED

    /**
     * A desktop directory IS a container, so this is SUPPORTED rather than NOT_APPLICABLE — claiming the
     * platform has no folders would make shared UI hide a route that works. Depth-one listing, matching
     * the SAF tree walk it replaces.
     */
    override val containerAccess: SupportState = SupportState.SUPPORTED

    /**
     * Both entry points hop to [DIALOG_DISPATCHER] before touching AWT: the hop is the whole reason these
     * are `suspend`, and without it the caller's thread - the UI's own - is the one that blocks.
     */
    override suspend fun requestSource(suggestedName: String?): String? =
        withContext(DIALOG_DISPATCHER) { choose(FileDialog.LOAD, suggestedName) }

    override suspend fun requestDestination(suggestedName: String): String? =
        withContext(DIALOG_DISPATCHER) { choose(FileDialog.SAVE, suggestedName) }

    private fun choose(
        mode: Int,
        suggestedFileName: String?,
    ): String? {
        // Logged BEFORE the modal opens, with the thread it runs on: a dialog that never appears and a
        // dialog that appears on the event thread are otherwise indistinguishable from the outside, and the
        // second one is a crash (see [DIALOG_DISPATCHER]).
        logger.d(
            "AwtDocumentSource.choose",
            "Opening the file dialog",
            mapOf("mode" to mode, "thread" to Thread.currentThread().name),
        )
        val dialog = FileDialog(parent(), "Payanam", mode)
        if (suggestedFileName != null) dialog.file = suggestedFileName
        dialog.isVisible = true
        val directory = dialog.directory
        val file = dialog.file
        // Both parts are required: the dialog answers them separately, and a directory without a file is a
        // cancelled dialog rather than a choice.
        if (directory == null || file == null) {
            logger.d(
                "AwtDocumentSource.choose",
                "File dialog dismissed without a selection",
                mapOf("mode" to mode),
            )
            return null
        }
        return Path.of(directory, file).toString()
    }

    override fun displayNameOf(token: String): String? =
        runCatching { Path.of(token).fileName?.toString() }
            .getOrElse { error ->
                logger.w(
                    "AwtDocumentSource.displayNameOf",
                    "Could not read a file name from the chosen path",
                    mapOf("error" to (error.message ?: "Unknown error")),
                )
                null
            }

    override fun kindOf(token: String): DocumentKind =
        if (runCatching { Files.isDirectory(Path.of(token)) }.getOrDefault(false)) {
            DocumentKind.CONTAINER
        } else {
            DocumentKind.FILE
        }

    override fun copyInto(
        token: String,
        destination: OutputStream,
    ): Long {
        val source = Path.of(token)
        if (!Files.isRegularFile(source)) {
            // -1 rather than a throw: the caller already handles "could not open this" as its own branch,
            // and a missing file is an expected outcome of a cancelled or moved picker, not a defect.
            logger.w(
                "AwtDocumentSource.copyInto",
                "Chosen source is not a readable file; nothing copied",
                mapOf("file" to source.fileName?.toString()),
            )
            return COPY_NOT_ATTEMPTED
        }
        // Streamed, not read whole: a Payanam database is tens of megabytes and a ByteArray would be an
        // out-of-memory crash rather than a copy.
        return Files.newInputStream(source).use { input -> input.copyTo(destination) }
    }

    override fun readLeadingBytes(
        token: String,
        byteCount: Int,
    ): ByteArray? =
        runCatching {
            Files.newInputStream(Path.of(token)).use { it.readNBytes(byteCount) }
        }.getOrElse { error ->
            logger.w(
                "AwtDocumentSource.readLeadingBytes",
                "Could not read the file header",
                mapOf("error" to (error.message ?: "Unknown error")),
            )
            null
        }

    override fun childrenOf(token: String): List<DocumentEntry> {
        val directory = Path.of(token)
        if (!Files.isDirectory(directory)) {
            logger.w(
                "AwtDocumentSource.childrenOf",
                "Chosen source is not a folder, so it has no children to list",
                mapOf("file" to directory.fileName?.toString()),
            )
            return emptyList()
        }
        return runCatching {
            Files.list(directory).use { stream ->
                stream.toList().map { child ->
                    DocumentEntry(
                        name = child.fileName.toString(),
                        token = child.toString(),
                        // A directory the user can descend into is still a FILE here: the import only ever
                        // wants database files, and treating a folder as a candidate would offer one.
                        kind = DocumentKind.FILE,
                    )
                }
            }
        }.getOrElse { error ->
            logger.w(
                "AwtDocumentSource.childrenOf",
                "Could not list the chosen folder",
                mapOf("error" to (error.message ?: "Unknown error")),
            )
            emptyList()
        }
    }
}
