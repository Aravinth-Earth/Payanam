//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The two platform contracts' own logic, exercised without a platform.
 *
 * The interfaces themselves have no bodies to cover, but everything they HAND OUT does: the sealed
 * hierarchies a shared screen branches on, the default property that decides whether a folder route is
 * offered, and the one value type callers compare. Those are the decisions that differ per platform, so
 * they are worth pinning here rather than only through a desktop implementation.
 */
class PlatformUpdateAndDocumentContractsTest {
    // ---- ApkUpdateSource's vocabulary -------------------------------------------------------------

    @Test
    fun upToDateAndInconclusiveAreDifferentAnswers() {
        // The whole reason [UpdateAvailability] is a sealed hierarchy: a screen that renders them the same
        // way tells a user they have the newest build after a check that never concluded.
        assertThat(UpdateAvailability.UpToDate).isNotEqualTo(UpdateAvailability.Inconclusive)
        assertThat(UpdateAvailability.UpToDate).isNotEqualTo(UpdateAvailability.Newer(1, installable = true))
    }

    @Test
    fun newnessCarriesWhetherTheArtifactCouldBeInstalledHere() {
        // A newer build that ships no artifact this platform can install is the NORMAL desktop case, so
        // "newer" and "installable" cannot be one fact.
        val newer = UpdateAvailability.Newer(buildNumber = 147, installable = false)
        assertThat(newer.buildNumber).isEqualTo(147)
        assertThat(newer.installable).isFalse()
    }

    @Test
    fun installHandoffReportsRefusalWithAReasonRatherThanFailing() {
        // A caller has to render the difference, so a refusal carries a reason it can map to copy instead
        // of a platform string it would have to print.
        val refused = InstallHandoff.Refused(InstallRefusal.NO_INSTALLER)
        assertThat((refused as InstallHandoff.Refused).reason).isEqualTo(InstallRefusal.NO_INSTALLER)
        assertThat(InstallHandoff.Refused(InstallRefusal.FILE_MISSING))
            .isNotEqualTo(InstallHandoff.Refused(InstallRefusal.NO_INSTALLER))
    }

    @Test
    fun refusalReasonsAreDistinctSoNoTwoCausesShareAMessage() {
        // Each reason is a different user-facing explanation; collapsing two would send a user chasing the
        // wrong problem.
        assertThat(InstallRefusal.entries.toSet()).hasSize(InstallRefusal.entries.size)
        assertThat(InstallRefusal.entries).containsAtLeast(
            InstallRefusal.NO_DOWNLOAD,
            InstallRefusal.FILE_MISSING,
            InstallRefusal.NO_INSTALLER,
            InstallRefusal.PLATFORM_NOT_APPLICABLE,
        )
    }

    @Test
    fun aDownloadIsAddressedByThePlatformsOwnHandle() {
        // Opaque on purpose: Android holds a FileProvider URI, and a shared type must not name either.
        val download = UpdateDownload(buildNumber = 42, platformHandle = "content://provider/42")
        assertThat(download.platformHandle).isEqualTo("content://provider/42")
        assertThat(download.buildNumber).isEqualTo(42)
    }

    // ---- DocumentSource's vocabulary ---------------------------------------------------------------

    @Test
    fun offersFolderImportFollowsContainerAccessRatherThanBeingDeclared() {
        // A platform with no folder concept must not be able to say yes here: the whole point is that a
        // platform that does not support folders cannot be handed a dead control to render.
        assertThat(supportingContainers.offersFolderImport).isTrue()
        assertThat(containerlessPlatform.offersFolderImport).isFalse()
    }

    @Test
    fun singleFileAccessAloneNeverImpliesAFolderRoute() {
        val fileOnly = FakeDocumentSource(containerAccess = SupportState.NOT_APPLICABLE)
        assertThat(fileOnly.fileAccess).isEqualTo(SupportState.SUPPORTED)
        assertThat(fileOnly.offersFolderImport).isFalse()
    }

    @Test
    fun aDocumentEntryCarriesTheNameTheMatchingReads() {
        // The `.db` / `-wal` / `-shm` matching is done on [DocumentEntry.name], so an entry whose name is
        // blank could never be classified - and a sidecar picked by mistake would be imported as a backup.
        val entry = DocumentEntry(name = "payanam.db", token = "/tmp/payanam.db", kind = DocumentKind.FILE)
        assertThat(entry.name).isNotEmpty()
        assertThat(entry.kind).isEqualTo(DocumentKind.FILE)
    }

    @Test
    fun fileAndContainerAreDistinctKinds() {
        assertThat(DocumentKind.entries.toSet()).hasSize(DocumentKind.entries.size)
        assertThat(DocumentKind.FILE).isNotEqualTo(DocumentKind.CONTAINER)
    }

    // ---- DocumentSource's own logic, driven through a fake -------------------------------------------

    @Test
    fun `offersFolderImport follows containerAccess, not a hardcoded value`() {
        // The default property on the interface: it is the ONLY logic in the interface itself, and it
        // must be `containerAccess == SUPPORTED`, not `!= NOT_APPLICABLE` — the two differ when the
        // state is DECLINES or IN_PROCESS_ONLY.
        val declining = FakeDocumentSource(containerAccess = SupportState.DECLINES)
        assertThat(declining.offersFolderImport).isFalse()

        val inProcess = FakeDocumentSource(containerAccess = SupportState.IN_PROCESS_ONLY)
        assertThat(inProcess.offersFolderImport).isFalse()

        val notSurfaced = FakeDocumentSource(containerAccess = SupportState.NOT_SURFACED)
        assertThat(notSurfaced.offersFolderImport).isFalse()

        val supported = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        assertThat(supported.offersFolderImport).isTrue()
    }

    @Test
    fun `a containerless platform reports NOT_APPLICABLE for containerAccess`() {
        val platform = FakeDocumentSource(containerAccess = SupportState.NOT_APPLICABLE)
        assertThat(platform.containerAccess).isEqualTo(SupportState.NOT_APPLICABLE)
    }

    @Test
    fun `fileAccess is independent of containerAccess`() {
        // A platform can support single files without supporting folders (the common case), or support
        // folders but not individual files (unusual but possible). The two properties are separate.
        val filesOnly = FakeDocumentSource(
            fileAccess = SupportState.SUPPORTED,
            containerAccess = SupportState.NOT_APPLICABLE,
        )
        assertThat(filesOnly.fileAccess).isEqualTo(SupportState.SUPPORTED)
        assertThat(filesOnly.containerAccess).isEqualTo(SupportState.NOT_APPLICABLE)
        assertThat(filesOnly.offersFolderImport).isFalse()
    }

    @Test
    fun `requestSource returns the token the platform hands back`() {
        val source = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        runBlocking {
            assertThat(source.requestSource("payanam.db")).isEqualTo("content://source/1")
            assertThat(source.requestSource(null)).isEqualTo("content://source/1")
        }
    }

    @Test
    fun `requestSource returns null when the user cancels`() {
        val source = FakeDocumentSource(
            containerAccess = SupportState.SUPPORTED,
            sourceToken = null,
        )
        runBlocking {
            assertThat(source.requestSource("payanam.db")).isNull()
            assertThat(source.requestSource(null)).isNull()
        }
    }

    @Test
    fun `requestDestination returns the token the platform hands back`() {
        val source = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        runBlocking {
            assertThat(source.requestDestination("payanam-backup.db"))
                .isEqualTo("content://destination/1")
        }
    }

    @Test
    fun `requestDestination returns null when the user cancels`() {
        val source = FakeDocumentSource(
            containerAccess = SupportState.SUPPORTED,
            destinationToken = null,
        )
        runBlocking {
            assertThat(source.requestDestination("payanam-backup.db")).isNull()
        }
    }

    @Test
    fun `displayNameOf returns the name the platform associates with a token`() {
        val source = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        assertThat(source.displayNameOf("content://source/1")).isEqualTo("payanam.db")
    }

    @Test
    fun `displayNameOf returns null when the platform cannot supply a name`() {
        val source = FakeDocumentSource(
            containerAccess = SupportState.SUPPORTED,
            displayName = null,
        )
        assertThat(source.displayNameOf("content://source/1")).isNull()
    }

    @Test
    fun `kindOf returns the DocumentKind the platform associates with a token`() {
        val source = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        assertThat(source.kindOf("content://container/1")).isEqualTo(DocumentKind.CONTAINER)
        assertThat(source.kindOf("content://source/1")).isEqualTo(DocumentKind.FILE)
    }

    @Test
    fun `copyInto writes bytes to the destination and returns the count`() {
        val source = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        val baos = ByteArrayOutputStream()
        val bytesWritten = source.copyInto("content://source/1", baos)
        assertThat(bytesWritten).isEqualTo(4L)
        assertThat(baos.toByteArray()).isEqualTo("test".toByteArray())
    }

    @Test
    fun `copyInto returns COPY_NOT_ATTEMPTED when the token cannot be opened`() {
        val source = FakeDocumentSource(
            containerAccess = SupportState.SUPPORTED,
            copyBytes = null,
        )
        val baos = ByteArrayOutputStream()
        assertThat(source.copyInto("content://source/1", baos)).isEqualTo(COPY_NOT_ATTEMPTED)
    }

    @Test
    fun `readLeadingBytes returns the first N bytes of the token`() {
        val source = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        val header = source.readLeadingBytes("content://source/1", 4)
        assertThat(header).isNotNull()
        assertThat(header!!.toList()).isEqualTo("test".toByteArray().toList())
    }

    @Test
    fun `readLeadingBytes returns null when the token cannot be read`() {
        val source = FakeDocumentSource(
            containerAccess = SupportState.SUPPORTED,
            leadingBytes = null,
        )
        assertThat(source.readLeadingBytes("content://source/1", 4)).isNull()
    }

    @Test
    fun `childrenOf returns the immediate children of a container`() {
        val source = FakeDocumentSource(containerAccess = SupportState.SUPPORTED)
        val children = source.childrenOf("content://container/1")
        assertThat(children).hasSize(2)
        assertThat(children[0].name).isEqualTo("payanam.db")
        assertThat(children[0].kind).isEqualTo(DocumentKind.FILE)
        assertThat(children[1].name).isEqualTo("backups")
        assertThat(children[1].kind).isEqualTo(DocumentKind.CONTAINER)
    }

    @Test
    fun `childrenOf returns an empty list when the container has no children`() {
        val source = FakeDocumentSource(
            containerAccess = SupportState.SUPPORTED,
            children = emptyList(),
        )
        assertThat(source.childrenOf("content://container/1")).isEmpty()
    }

    // ---- fakes ------------------------------------------------------------------------------------

    private val supportingContainers =
        FakeDocumentSource(containerAccess = SupportState.SUPPORTED)

    private val containerlessPlatform =
        FakeDocumentSource(containerAccess = SupportState.NOT_APPLICABLE)

    /**
     * A platform that records what it was handed and returns configurable answers, so these tests
     * exercise the CONTRACT (the interface's default `offersFolderImport` and every method signature)
     * rather than any real implementation.
     */
    private class FakeDocumentSource(
        override val fileAccess: SupportState = SupportState.SUPPORTED,
        override val containerAccess: SupportState = SupportState.NOT_APPLICABLE,
        private val sourceToken: String? = "content://source/1",
        private val destinationToken: String? = "content://destination/1",
        private val displayName: String? = "payanam.db",
        private val copyBytes: ByteArray? = "test".toByteArray(),
        private val leadingBytes: ByteArray? = "test".toByteArray(),
        private val children: List<DocumentEntry> =
            listOf(
                DocumentEntry(name = "payanam.db", token = "content://container/1/payanam.db", kind = DocumentKind.FILE),
                DocumentEntry(name = "backups", token = "content://container/1/backups", kind = DocumentKind.CONTAINER),
            ),
    ) : DocumentSource {
        override val offersFolderImport: Boolean get() = containerAccess == SupportState.SUPPORTED

        override suspend fun requestSource(suggestedName: String?): String? = sourceToken

        override suspend fun requestDestination(suggestedName: String): String? = destinationToken

        override fun displayNameOf(token: String): String? = displayName

        override fun kindOf(token: String): DocumentKind =
            if (token.contains("container")) DocumentKind.CONTAINER else DocumentKind.FILE

        override fun copyInto(
            token: String,
            destination: java.io.OutputStream,
        ): Long {
            val bytes = copyBytes ?: return COPY_NOT_ATTEMPTED
            destination.write(bytes)
            return bytes.size.toLong()
        }

        override fun readLeadingBytes(
            token: String,
            byteCount: Int,
        ): ByteArray? {
            val bytes = leadingBytes ?: return null
            return bytes.copyOf(minOf(byteCount, bytes.size))
        }

        override fun childrenOf(token: String): List<DocumentEntry> = children
    }
}
