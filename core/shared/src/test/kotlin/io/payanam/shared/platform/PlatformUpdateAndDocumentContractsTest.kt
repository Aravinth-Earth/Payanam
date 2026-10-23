//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

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

    // ---- fakes ------------------------------------------------------------------------------------

    private val supportingContainers =
        FakeDocumentSource(containerAccess = SupportState.SUPPORTED)

    private val containerlessPlatform =
        FakeDocumentSource(containerAccess = SupportState.NOT_APPLICABLE)

    /** A platform that does nothing, so these tests exercise the CONTRACT rather than an implementation. */
    private class FakeDocumentSource(
        override val containerAccess: SupportState,
    ) : DocumentSource {
        override val fileAccess: SupportState = SupportState.SUPPORTED

        override suspend fun requestSource(suggestedName: String?): String? = null

        override suspend fun requestDestination(suggestedName: String): String? = null

        override fun displayNameOf(token: String): String? = null

        override fun kindOf(token: String): DocumentKind = DocumentKind.FILE

        override fun copyInto(
            token: String,
            destination: java.io.OutputStream,
        ): Long = COPY_NOT_ATTEMPTED

        override fun readLeadingBytes(
            token: String,
            byteCount: Int,
        ): ByteArray? = null

        override fun childrenOf(token: String): List<DocumentEntry> = emptyList()
    }
}
