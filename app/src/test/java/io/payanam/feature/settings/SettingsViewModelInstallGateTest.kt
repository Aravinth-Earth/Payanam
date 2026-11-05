//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import io.payanam.presentation.download.DownloadUiState
import io.payanam.service.BackupStatusStore
import io.payanam.service.DatabaseBackupCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Settings half of the install gate: `onInstallNow()` refuses every verdict but
 * [ChecksumVerdict.Verified], and each refusal surfaces its own key.
 *
 * WHY THE WHOLE VIEWMODEL IS BUILT: the branch under test is inside `onInstallNow`, behind a file
 * existence check and an `appSettings` read. Calling [ChecksumVerdict.installBlockKey] directly
 * would prove nothing about the caller - that the *state* the UI renders carries the refusal is the
 * change worth pinning, and it only happens in this coroutine.
 *
 * WHY THE HARNESS MIRRORS `SettingsPlaintextImportRefusalTest`: `SettingsViewModel` is not
 * Hilt-injected - it is built at its one call site - so a unit test has to supply the same real
 * collaborators, and that test already establishes which of them must be real. Only the four
 * repositories `init` touches are mocks, and the checksum gate is the fake under test.
 *
 * WHY THE VERIFIED CASE ASSERTS "NOT REFUSED" RATHER THAN "INSTALLER STARTED": the handoff goes
 * through `FileProvider` and `Context.startActivity`, which is platform plumbing this test tier does
 * not arrange; asserting on it would make the test a fixture of the manifest's provider paths rather
 * than of the gate. What the gate decides is asserted exactly: none of the three refusal keys, and
 * the pending install cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelInstallGateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        // The ViewModel reads its repositories from the installed data layer at construction time, so
        // the layer must be installed first - the same override shape the import test uses.
        LocalDataLayer.install(
            object : PayanamDataLayer by UninstalledDataLayer {
                override val tasks: TaskRepository = mock()
                override val timeEntries: TimeEntryRepository = mock()
                override val notes: NoteRepository = mock()
                override val appSettings: AppSettingsRepository = mock()
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onInstallNow reaches the installer only for a verified download`() {
        val apk = downloadedApk()
        val viewModel = viewModelSeededWith(ChecksumVerdict.Verified, apk)

        viewModel.onInstallNow()

        awaitUntil("the verified download to leave the gate") {
            viewModel.uiState.value.pendingInstallPath == null
        }
        val refusal = (viewModel.uiState.value.downloadState as? DownloadUiState.Failed)?.message
        assertThat(REFUSAL_KEYS).doesNotContain(refusal)
        assertThat(apk.exists()).isTrue()
    }

    @Test
    fun `onInstallNow reports checksum_mismatch and keeps the file`() {
        val apk = downloadedApk()
        val viewModel = viewModelSeededWith(ChecksumVerdict.Mismatch, apk)

        viewModel.onInstallNow()

        awaitUntil("the mismatch refusal to surface") {
            viewModel.uiState.value.downloadState == DownloadUiState.Failed("checksum_mismatch")
        }
        assertThat(viewModel.uiState.value.pendingInstallPath).isNull()
        assertThat(apk.exists()).isTrue()
    }

    @Test
    fun `onInstallNow reports checksum_absent when the release published no digest`() {
        val apk = downloadedApk()
        val viewModel = viewModelSeededWith(ChecksumVerdict.Skipped("no_sha256_asset"), apk)

        viewModel.onInstallNow()

        awaitUntil("the absent-digest refusal to surface") {
            viewModel.uiState.value.downloadState == DownloadUiState.Failed("checksum_absent")
        }
        assertThat(viewModel.uiState.value.pendingInstallPath).isNull()
        assertThat(apk.exists()).isTrue()
    }

    @Test
    fun `onInstallNow reports checksum_unavailable when the download could not be verified`() {
        val apk = downloadedApk()
        val viewModel = viewModelSeededWith(ChecksumVerdict.Unavailable("sidecar_unavailable"), apk)

        viewModel.onInstallNow()

        awaitUntil("the unverifiable refusal to surface") {
            viewModel.uiState.value.downloadState == DownloadUiState.Failed("checksum_unavailable")
        }
        assertThat(viewModel.uiState.value.pendingInstallPath).isNull()
        assertThat(apk.exists()).isTrue()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** A real file on disk, so "the APK is left in place" is a claim about something that exists. */
    private fun downloadedApk(): File =
        File(context.cacheDir, "payanam-settings-gate-${System.nanoTime()}.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x2a))
        }

    /**
     * The ViewModel with the scripted verdict, its update state seeded as a completed download.
     *
     * The production `scope` is left in place: this path's work is a real coroutine on a background
     * dispatcher by design (the Settings screen's instance outlives the tap), so the test waits for
     * the state instead of swapping the dispatcher the ViewModel actually runs on.
     */
    private fun viewModelSeededWith(
        verdict: ChecksumVerdict,
        apk: File,
    ): SettingsViewModel {
        val encryptionManager = DatabaseEncryptionManager(context)
        val viewModel =
            SettingsViewModel(
                context = context,
                sessionManager = DatabaseSessionManager(context, encryptionManager),
                databaseEncryptionManager = encryptionManager,
                databaseBackupCoordinator = DatabaseBackupCoordinator(context, mock(), BackupStatusStore(context)),
                checksumVerifier = FakeChecksumVerifier(verdict),
            )
        viewModel.updateUiState {
            it.copy(
                downloadState = DownloadUiState.Downloaded(fileName = apk.name, localPath = apk.absolutePath),
                pendingInstallPath = apk.absolutePath,
            )
        }
        return viewModel
    }

    private fun awaitUntil(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for $description")
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    /** The gate under test, scripted: every call returns the verdict the case is about. */
    private class FakeChecksumVerifier(
        private val verdict: ChecksumVerdict,
    ) : ApkChecksumVerifier {
        override suspend fun verify(
            apkFile: File,
            sha256Url: String?,
        ): ChecksumVerdict = verdict
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 20L

        /** Exactly the keys a refusal may surface; a verified download must not produce one. */
        val REFUSAL_KEYS = setOf("checksum_mismatch", "checksum_absent", "checksum_unavailable")
    }
}
