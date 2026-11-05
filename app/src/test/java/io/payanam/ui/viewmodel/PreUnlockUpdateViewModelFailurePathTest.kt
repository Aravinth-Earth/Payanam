//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.ApkChecksumVerifier
import io.payanam.feature.settings.ChecksumVerdict
import io.payanam.presentation.download.DownloadUiState
import io.payanam.presentation.updates.ApkDownloader
import io.payanam.presentation.updates.ApkInstaller
import io.payanam.presentation.updates.InstallSourceReader
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Failure-path coverage for the pre-unlock update hatch (the DB-locked rescue
 * screen). Every branch asserted here is a *refusal* or an *error surface* —
 * the happy path (update found → downloaded → installer launched) is not the
 * subject of this class.
 *
 * Two properties make these tests possible without touching production code:
 *
 * 1. All four guards run *synchronously*, before any `viewModelScope.launch`,
 *    so no test dispatcher / `Dispatchers.setMain` setup is needed.
 * 2. The F-Droid gate is the only framework fact the constructor reads, so a
 *    fake [InstallSourceReader] is enough to select the branch.
 *
 * `_downloadState` is private with no producer that works offline (only the
 * DownloadManager poll loop writes [DownloadUiState.Downloaded]), so the two
 * install-after-download tests seed it reflectively. That seeds *state*, not
 * production code.
 */
@RunWith(RobolectricTestRunner::class)
class PreUnlockUpdateViewModelFailurePathTest {

    private lateinit var baseContext: android.content.Context

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(baseContext, "test", 0)
        }
    }

    /**
     * Wiring sanity check, not a hatch behaviour: if the mocked installer were
     * mis-stubbed the refusal tests below would still pass (an F-Droid build
     * refuses everything), so the non-F-Droid baseline is pinned first.
     */
    @Test
    fun `isFDroidBuild is false for a non-F-Droid installer`() {
        val vm = viewModel(isFDroid = false)
        assertFalse(vm.isFDroidBuild)
    }

    /**
     * F-Droid guard wiring: the flag must actually flip when the installer is
     * F-Droid, otherwise the guard tests below prove nothing.
     */
    @Test
    fun `isFDroidBuild is true when installed from F-Droid`() {
        val vm = viewModel(isFDroid = true)
        assertTrue(vm.isFDroidBuild)
    }

    // ── download() failure paths ────────────────────────────────────────

    /**
     * No APK url yet (user tapped Download before/instead of a successful
     * check). `latestApkUrl ?: return` must abandon the tap: state untouched
     * and — the part that matters — the downloader is never contacted, so no
     * orphaned download row can be left behind.
     */
    @Test
    fun `download refuses and leaves state Idle when no APK URL is available`() {
        val downloader = FakeApkDownloader()
        val vm = viewModel(isFDroid = false, apkDownloader = downloader)

        vm.download()

        assertEquals(DownloadUiState.Idle, vm.downloadState.value)
        assertEquals(0, downloader.enqueueCount)
    }

    /**
     * F-Droid installs are updated by the F-Droid client. The hatch must
     * refuse before touching the downloader.
     */
    @Test
    fun `download is refused on an F-Droid build`() {
        val downloader = FakeApkDownloader()
        val vm = viewModel(isFDroid = true, apkDownloader = downloader)

        vm.download()

        assertTrue(vm.isFDroidBuild)
        assertEquals(DownloadUiState.Idle, vm.downloadState.value)
        assertEquals(0, downloader.enqueueCount)
    }

    /**
     * Same F-Droid guard on the check step: no result message, no checking
     * flag, no framework work — the hatch reports nothing at all.
     */
    @Test
    fun `checkForUpdate is refused on an F-Droid build`() {
        val downloader = FakeApkDownloader()
        val vm = viewModel(isFDroid = true, apkDownloader = downloader)

        vm.checkForUpdate()

        assertTrue(vm.isFDroidBuild)
        assertNull(vm.checkResultMessage.value)
        assertFalse(vm.checking.value)
        assertEquals(0, downloader.enqueueCount)
    }

    // ── install() failure paths ─────────────────────────────────────────

    /**
     * Nothing downloaded yet: the tap must be abandoned silently-but-safely,
     * with no state mutation and, above all, no installer handoff.
     */
    @Test
    fun `install refuses and leaves state Idle when nothing has been downloaded`() {
        val installer = FakeApkInstaller()
        val vm = viewModel(isFDroid = false, apkInstaller = installer)

        vm.install()

        assertEquals(DownloadUiState.Idle, vm.downloadState.value)
        assertEquals(0, installer.installCount)
    }

    /**
     * `DownloadUiState.Downloaded.localPath` is nullable and the downloader
     * really can emit `null` when no local uri is available, so the null path
     * must refuse rather than hand a null File to the installer.
     */
    @Test
    fun `install refuses when a downloaded record carries no local path`() {
        val installer = FakeApkInstaller()
        val vm = viewModel(isFDroid = false, apkInstaller = installer)
        seedDownloadState(vm, DownloadUiState.Downloaded(fileName = "unknown", localPath = null))

        vm.install()

        assertEquals(DownloadUiState.Downloaded(fileName = "unknown", localPath = null), vm.downloadState.value)
        assertEquals(0, installer.installCount)
    }

    /**
     * The APK was downloaded but is gone by install time (cleaned up, storage
     * unmounted, retention pass). This branch *does* surface a failure:
     * `Failed("file_missing")`, and still no installer handoff.
     */
    @Test
    fun `install reports file_missing when the downloaded APK is gone at install time`() {
        val installer = FakeApkInstaller()
        val vm = viewModel(isFDroid = false, apkInstaller = installer)
        val missing = File(baseContext.cacheDir, "payanam-missing.apk")
        check(!missing.exists()) { "test fixture must not exist on disk" }
        seedDownloadState(vm, DownloadUiState.Downloaded(fileName = missing.name, localPath = missing.absolutePath))

        vm.install()

        assertEquals(DownloadUiState.Failed("file_missing"), vm.downloadState.value)
        assertEquals(0, installer.installCount)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun viewModel(
        isFDroid: Boolean,
        apkDownloader: ApkDownloader = FakeApkDownloader(),
        apkInstaller: ApkInstaller = FakeApkInstaller(),
    ): PreUnlockUpdateViewModel = PreUnlockUpdateViewModel(
        installSourceReader = FakeInstallSourceReader(isFDroid),
        apkDownloader = apkDownloader,
        apkInstaller = apkInstaller,
        // None of these cases reach the checksum gate (each refuses earlier), so the verdict here is
        // never consulted; the branch coverage for it lives in
        // `PreUnlockUpdateViewModelInstallGateTest`.
        checksumVerifier = FakeChecksumVerifier(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun seedDownloadState(vm: PreUnlockUpdateViewModel, state: DownloadUiState) {
        val field = vm.javaClass.getDeclaredField("_downloadState")
        field.isAccessible = true
        (field.get(vm) as MutableStateFlow<DownloadUiState>).value = state
    }

    // ── fakes ───────────────────────────────────────────────────────────

    private class FakeInstallSourceReader(private val isFDroid: Boolean) : InstallSourceReader {
        var callCount = 0
            private set
        override fun isFDroidBuild(): Boolean {
            callCount++
            return isFDroid
        }
    }

    private class FakeApkDownloader : ApkDownloader {
        var enqueueCount = 0
            private set
        override fun enqueue(url: String, fileName: String, wifiOnly: Boolean): String? {
            enqueueCount++
            return null
        }
        override fun queryProgress(downloadId: String): DownloadUiState = DownloadUiState.Idle
    }

    private class FakeApkInstaller : ApkInstaller {
        var installCount = 0
            private set
        override suspend fun install(
            apkPath: String,
            sha256Url: String?,
        ): Boolean {
            installCount++
            return true
        }
    }

    private class FakeChecksumVerifier : ApkChecksumVerifier {
        override suspend fun verify(
            apkFile: File,
            sha256Url: String?,
        ): ChecksumVerdict = ChecksumVerdict.Verified
    }
}
