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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The install gate of the pre-unlock hatch, exercised one verdict at a time.
 *
 * WHY THIS EXISTS: `install()` refuses on three of the four [ChecksumVerdict] values and proceeds
 * only on [ChecksumVerdict.Verified], but while the gate was a Kotlin `object` no unit test could
 * choose the verdict - the decision was a network fetch plus a real file hash. The gate is a
 * constructor dependency now, so each branch is driven by a scripted [FakeChecksumVerifier] and the
 * assertions are about the caller's behaviour, not the hashing.
 *
 * The file really exists on disk (a real temp APK with real bytes): "the APK is the user's only
 * copy, so it is left in place" is a claim about a file that would have been deleted if the gate
 * were written the other way, and a non-existent fixture would pass that assertion for free.
 *
 * `_downloadState` has no producer reachable offline (only the DownloadManager poll loop writes
 * [DownloadUiState.Downloaded]), so it is seeded reflectively. That seeds *state*, not logic - the
 * same seam `PreUnlockUpdateViewModelFailurePathTest` uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreUnlockUpdateViewModelInstallGateTest {

    private lateinit var baseContext: android.content.Context

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(baseContext, "test", 0)
        }
        // The gate runs inside `viewModelScope.launch`; an unconfined main dispatcher makes that
        // launch run to completion on this thread, so the assertions can be made immediately.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `install launches the installer when the download verifies`() {
        val installer = FakeApkInstaller()
        val apk = downloadedApk()
        val vm = viewModel(verdict = ChecksumVerdict.Verified, apk = apk, apkInstaller = installer)

        vm.install()

        assertEquals(1, installer.installCount)
        assertEquals(listOf(apk.absolutePath), installer.installedPaths)
        // Nothing failed: the gate's success path does not touch the download state.
        assertEquals(DownloadUiState.Downloaded(apk.name, apk.absolutePath), vm.downloadState.value)
    }

    @Test
    fun `install refuses a mismatch, reports its own key and leaves the file in place`() {
        val installer = FakeApkInstaller()
        val apk = downloadedApk()
        val bytesBefore = apk.readBytes()
        val vm = viewModel(verdict = ChecksumVerdict.Mismatch, apk = apk, apkInstaller = installer)

        vm.install()

        assertEquals(0, installer.installCount)
        assertEquals(DownloadUiState.Failed("checksum_mismatch"), vm.downloadState.value)
        assertTrue("the downloaded APK must be left on disk", apk.exists())
        assertArrayEquals(bytesBefore, apk.readBytes())
    }

    @Test
    fun `install refuses a download whose release published no digest, reporting checksum_absent`() {
        val installer = FakeApkInstaller()
        val apk = downloadedApk()
        val vm = viewModel(
            verdict = ChecksumVerdict.Skipped("no_sha256_asset"),
            apk = apk,
            apkInstaller = installer,
        )

        vm.install()

        assertEquals(0, installer.installCount)
        assertEquals(DownloadUiState.Failed("checksum_absent"), vm.downloadState.value)
        assertTrue("the downloaded APK must be left on disk", apk.exists())
    }

    @Test
    fun `install refuses a download that could not be verified, reporting checksum_unavailable`() {
        val installer = FakeApkInstaller()
        val apk = downloadedApk()
        val vm = viewModel(
            verdict = ChecksumVerdict.Unavailable("sidecar_unavailable"),
            apk = apk,
            apkInstaller = installer,
        )

        vm.install()

        assertEquals(0, installer.installCount)
        assertEquals(DownloadUiState.Failed("checksum_unavailable"), vm.downloadState.value)
        assertTrue("the downloaded APK must be left on disk", apk.exists())
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * A real APK-shaped fixture in the app's cache dir, seeded as the completed download.
     *
     * A real file rather than a path string: "left untouched" is then an assertion about bytes on
     * disk, which is the property the gate's comment promises.
     */
    private fun downloadedApk(): File {
        val apk = File(baseContext.cacheDir, "payanam-install-gate-${System.nanoTime()}.apk")
        apk.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x2a))
        return apk
    }

    private fun viewModel(
        verdict: ChecksumVerdict,
        apk: File,
        apkInstaller: ApkInstaller = FakeApkInstaller(),
    ): PreUnlockUpdateViewModel {
        val vm = PreUnlockUpdateViewModel(
            installSourceReader = FakeInstallSourceReader(),
            apkDownloader = FakeApkDownloader(),
            apkInstaller = apkInstaller,
            checksumVerifier = FakeChecksumVerifier(verdict),
        )
        seedDownloadState(vm, DownloadUiState.Downloaded(apk.name, apk.absolutePath))
        return vm
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedDownloadState(vm: PreUnlockUpdateViewModel, state: DownloadUiState) {
        val field = vm.javaClass.getDeclaredField("_downloadState")
        field.isAccessible = true
        (field.get(vm) as MutableStateFlow<DownloadUiState>).value = state
    }

    // ── fakes ───────────────────────────────────────────────────────────

    private class FakeChecksumVerifier(
        private val verdict: ChecksumVerdict,
    ) : ApkChecksumVerifier {
        override suspend fun verify(
            apkFile: File,
            sha256Url: String?,
        ): ChecksumVerdict = verdict
    }

    private class FakeInstallSourceReader : InstallSourceReader {
        override fun isFDroidBuild(): Boolean = false
    }

    private class FakeApkDownloader : ApkDownloader {
        override fun enqueue(url: String, fileName: String, wifiOnly: Boolean): String? = null

        override fun queryProgress(downloadId: String): DownloadUiState = DownloadUiState.Idle
    }

    private class FakeApkInstaller : ApkInstaller {
        var installCount = 0
            private set
        val installedPaths = mutableListOf<String>()

        override suspend fun install(
            apkPath: String,
            sha256Url: String?,
        ): Boolean {
            installCount++
            installedPaths += apkPath
            return true
        }
    }
}
