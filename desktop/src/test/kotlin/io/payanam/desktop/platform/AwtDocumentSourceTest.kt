//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.platform.COPY_NOT_ATTEMPTED
import io.payanam.shared.platform.DocumentEntry
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.platform.SupportState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop `DocumentSource` against REAL files, which is the QA gate for these seams asks for.
 *
 * The file-dialog half cannot be tested — an AWT modal blocks and no test can drive it — so these cover
 * everything that reads or classifies a path, because that is where a wrong answer silently corrupts an
 * import: a truncated copy, a header read that returns the wrong bytes, a folder listing that invents a
 * name. Each test writes an actual temp file and reads it back.
 */
class AwtDocumentSourceTest {
    private lateinit var workspace: Path
    private val source = AwtDocumentSource()

    @Before
    fun createWorkspace() {
        workspace = Files.createTempDirectory("payanam-document-source")
    }

    @After
    fun deleteWorkspace() {
        Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun reportsBothFileAndContainerAccessAsSupported() {
        // NOT_APPLICABLE here would make shared UI hide a folder route that genuinely works on this
        // platform, so the honest answer is SUPPORTED for both.
        assertThat(source.fileAccess).isEqualTo(SupportState.SUPPORTED)
        assertThat(source.containerAccess).isEqualTo(SupportState.SUPPORTED)
        assertThat(source.offersFolderImport).isTrue()
    }

    @Test
    fun copiesARealFileByteForByte() {
        // A Payanam database is tens of megabytes, so the copy must stream. A truncating or
        // charset-mangling copy here would produce a database that opens but has lost rows.
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val file = Files.write(workspace.resolve("payanam.db"), payload)

        val sink = ByteArrayOutputStream()
        val written = source.copyInto(file.toString(), sink)

        assertThat(written).isEqualTo(payload.size.toLong())
        assertThat(sink.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun streamsALargeFileWithoutEverHoldingMoreThanOneBufferAtOnce() {
        // The gate's peak-bytes clause, observed at the only place the public API exposes it: the sink.
        // `copyTo` reads into one buffer and writes each chunk straight out, so the largest single write
        // the sink ever sees is that buffer - a copy that read the file whole would arrive as ONE write
        // the size of the payload, and a copy that accumulated chunks would show a growing maximum.
        val payloadSize = 256 * 1024
        val payload = ByteArray(payloadSize) { (it % 251).toByte() }
        val file = Files.write(workspace.resolve("large.db"), payload)

        val recordingSink = LargestWriteRecordingSink()
        val written = source.copyInto(file.toString(), recordingSink)

        assertThat(written).isEqualTo(payloadSize.toLong())
        assertThat(recordingSink.bytes.toByteArray()).isEqualTo(payload)
        // One buffer, generously bounded: the JDK's `copyTo` default is 8 KiB, and asserting against a
        // literal 8192 would pin the JDK's choice rather than the property. The payload is 32 buffers
        // larger, so any non-streaming implementation fails this immediately.
        assertThat(recordingSink.largestWrite).isAtMost(64 * 1024)
        assertThat(recordingSink.largestWrite).isGreaterThan(0)
    }

    @Test
    fun reportsMinusOneRatherThanThrowingForAMissingFile() {
        // The caller already has a "could not open this" branch; throwing would force a second path for an
        // outcome that is ordinary when a picker is cancelled or the file moves.
        val sink = ByteArrayOutputStream()
        val written = source.copyInto(workspace.resolve("absent.db").toString(), sink)
        assertThat(written).isEqualTo(COPY_NOT_ATTEMPTED)
        assertThat(sink.size()).isEqualTo(0)
    }

    @Test
    fun readsOnlyTheRequestedHeaderBytes() {
        // The SQLite-magic probe reads 16 bytes. Reading the whole file to do it would stage tens of
        // megabytes for a header check. The fixture is the app's OWN magic constant
        // (DatabaseImportSupport.kt:233 — "SQLite format 3\0"), not an approximation of it: a header read
        // that dropped or reordered the trailing NUL would classify a real database as unreadable.
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        val file = Files.write(workspace.resolve("header.db"), magic + "trailing bytes".toByteArray())

        val header = source.readLeadingBytes(file.toString(), magic.size)

        assertThat(header).isNotNull()
        assertThat(header).isEqualTo(magic)
        assertThat(header!!.size).isEqualTo(16)
        // The point of the method: the tail is NOT returned, so a 40 MB database costs 16 bytes here.
        assertThat(source.readLeadingBytes(file.toString(), 8)!!.size).isEqualTo(8)
    }

    @Test
    fun returnsNullForAHeaderItCannotRead() {
        assertThat(source.readLeadingBytes(workspace.resolve("nope.db").toString(), 16)).isNull()
    }

    @Test
    fun namesAFileFromItsPath() {
        val file = Files.write(workspace.resolve("payanam.db"), byteArrayOf(1))
        assertThat(source.displayNameOf(file.toString())).isEqualTo("payanam.db")
    }

    @Test
    fun distinguishesAFolderFromAFile() {
        val file = Files.write(workspace.resolve("payanam.db"), byteArrayOf(1))
        assertThat(source.kindOf(file.toString())).isEqualTo(DocumentKind.FILE)
        assertThat(source.kindOf(workspace.toString())).isEqualTo(DocumentKind.CONTAINER)
    }

    @Test
    fun listsAFoldersImmediateChildrenWithUsableTokens() {
        // The tokens must be readable straight back, because the tree resolver hands them to copyInto
        // without translating. A token that needed re-derivation would be a second source of truth.
        val database = Files.write(workspace.resolve("payanam.db"), byteArrayOf(1))
        val wal = Files.write(workspace.resolve("payanam.db-wal"), byteArrayOf(2))
        Files.createDirectory(workspace.resolve("nested"))

        val children = source.childrenOf(workspace.toString())

        val byName = children.associateBy { it.name }
        assertThat(byName.keys).containsExactly("payanam.db", "payanam.db-wal", "nested")
        assertThat(byName.getValue("payanam.db").token).isEqualTo(database.toString())
        assertThat(byName.getValue("payanam.db-wal").token).isEqualTo(wal.toString())
        // Every child is a FILE: the import wants database files, so a folder must not be offered as one.
        assertThat(children.map { it.kind }).containsExactly(DocumentKind.FILE, DocumentKind.FILE, DocumentKind.FILE)
    }

    @Test
    fun doesNotRecurseIntoNestedFolders() {
        // A recursive walk would offer a database from somewhere the user never chose.
        Files.createDirectory(workspace.resolve("inner"))
        Files.write(workspace.resolve("inner").resolve("deep.db"), byteArrayOf(1))

        val children = source.childrenOf(workspace.toString())

        assertThat(children.map { it.name }).containsExactly("inner")
    }

    @Test
    fun aListedChildCanBeCopiedStraightBack() {
        // The end-to-end property the whole contract exists for: pick a folder, list it, read what you
        // found. If any step re-derives the token differently, this fails.
        val payload = "SQLite format 3\u0000".toByteArray()
        Files.write(workspace.resolve("payanam.db"), payload)

        val entry: DocumentEntry = source.childrenOf(workspace.toString()).single()
        val sink = ByteArrayOutputStream()
        val written = source.copyInto(entry.token, sink)

        assertThat(written).isEqualTo(payload.size.toLong())
        assertThat(sink.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun returnsEmptyRatherThanThrowingWhenAskedForChildrenOfAFile() {
        // Honest emptiness, not an exception: a user who picks a file instead of a folder is an ordinary
        // mistake, and the folder route must degrade quietly while the platform logs it.
        val file = Files.write(workspace.resolve("payanam.db"), byteArrayOf(1))
        assertThat(source.childrenOf(file.toString())).isEmpty()
    }

    /** Records the largest single write, which is the observable form of "one buffer held at once". */
    private class LargestWriteRecordingSink : java.io.OutputStream() {
        val bytes = java.io.ByteArrayOutputStream()

        var largestWrite: Int = 0
            private set

        override fun write(b: Int) {
            bytes.write(b)
            largestWrite = maxOf(largestWrite, 1)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            bytes.write(b, off, len)
            largestWrite = maxOf(largestWrite, len)
        }
    }
}
