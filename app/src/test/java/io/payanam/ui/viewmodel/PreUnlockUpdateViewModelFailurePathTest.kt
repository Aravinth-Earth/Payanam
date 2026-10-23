//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.presentation.download.DownloadUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
 * 2. The installer package (F-Droid gate) is the only framework fact the
 *    constructor reads, so a mocked [Context] is enough to select the branch.
 *
 * `_downloadState` is private with no producer that works offline (only the
 * DownloadManager poll loop writes [DownloadUiState.Downloaded]), so the two
 * install-after-download tests seed it reflectively. That seeds *state*, not
 * production code.
 */
@RunWith(RobolectricTestRunner::class)
class PreUnlockUpdateViewModelFailurePathTest {

    private lateinit var baseContext: Context

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
        val vm = viewModel(installerPackage = "com.android.vending")
        assertFalse(vm.isFDroidBuild)
    }

    /**
     * F-Droid guard wiring: the flag must actually flip when the installer is
     * F-Droid, otherwise the guard tests below prove nothing.
     */
    @Test
    fun `isFDroidBuild is true when installed from F-Droid`() {
        val vm = viewModel(installerPackage = FDROID_INSTALLER)
        assertTrue(vm.isFDroidBuild)
    }

    // ── download() failure paths ────────────────────────────────────────

    /**
     * No APK url yet (user tapped Download before/instead of a successful
     * check). `latestApkUrl ?: return` must abandon the tap: state untouched
     * and — the part that matters — DownloadManager never contacted, so no
     * orphaned download row can be left behind.
     */
    @Test
    fun `download refuses and leaves state Idle when no APK URL is available`() {
        val context = contextWithInstaller("com.android.vending")
        val vm = viewModel(context)

        vm.download()

        assertEquals(DownloadUiState.Idle, vm.downloadState.value)
        verify(context, never()).getSystemService(any())
    }

    /**
     * F-Droid installs are updated by the F-Droid client. The hatch must
     * refuse before touching DownloadManager.
     */
    @Test
    fun `download is refused on an F-Droid build`() {
        val context = contextWithInstaller(FDROID_INSTALLER)
        val vm = viewModel(context)

        vm.download()

        assertTrue(vm.isFDroidBuild)
        assertEquals(DownloadUiState.Idle, vm.downloadState.value)
        verify(context, never()).getSystemService(any())
    }

    /**
     * Same F-Droid guard on the check step: no result message, no checking
     * flag, no framework work — the hatch reports nothing at all.
     */
    @Test
    fun `checkForUpdate is refused on an F-Droid build`() {
        val context = contextWithInstaller(FDROID_INSTALLER)
        val vm = viewModel(context)

        vm.checkForUpdate()

        assertTrue(vm.isFDroidBuild)
        assertNull(vm.checkResultMessage.value)
        assertFalse(vm.checking.value)
        verify(context, never()).getSystemService(any())
    }

    // ── install() failure paths ─────────────────────────────────────────

    /**
     * Nothing downloaded yet: the tap must be abandoned silently-but-safely,
     * with no state mutation and, above all, no installer intent.
     */
    @Test
    fun `install refuses and leaves state Idle when nothing has been downloaded`() {
        val context = contextWithInstaller("com.android.vending")
        val vm = viewModel(context)

        vm.install()

        assertEquals(DownloadUiState.Idle, vm.downloadState.value)
        verify(context, never()).startActivity(any<Intent>())
    }

    /**
     * `DownloadUiState.Downloaded.localPath` is nullable and AutoDownloadManager
     * really does emit `null` when DownloadManager reports no local uri, so the
     * null path must refuse rather than hand a null File to the installer.
     */
    @Test
    fun `install refuses when a downloaded record carries no local path`() {
        val context = contextWithInstaller("com.android.vending")
        val vm = viewModel(context)
        seedDownloadState(vm, DownloadUiState.Downloaded(fileName = "unknown", localPath = null))

        vm.install()

        assertEquals(DownloadUiState.Downloaded(fileName = "unknown", localPath = null), vm.downloadState.value)
        verify(context, never()).startActivity(any<Intent>())
    }

    /**
     * The APK was downloaded but is gone by install time (cleaned up, storage
     * unmounted, retention pass). This branch *does* surface a failure:
     * `Failed("file_missing")`, and still no installer intent.
     */
    @Test
    fun `install reports file_missing when the downloaded APK is gone at install time`() {
        val context = contextWithInstaller("com.android.vending")
        val vm = viewModel(context)
        val missing = File(baseContext.cacheDir, "payanam-missing.apk")
        check(!missing.exists()) { "test fixture must not exist on disk" }
        seedDownloadState(vm, DownloadUiState.Downloaded(fileName = missing.name, localPath = missing.absolutePath))

        vm.install()

        assertEquals(DownloadUiState.Failed("file_missing"), vm.downloadState.value)
        verify(context, never()).startActivity(any<Intent>())
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun viewModel(installerPackage: String?): PreUnlockUpdateViewModel =
        viewModel(contextWithInstaller(installerPackage))

    private fun viewModel(context: Context): PreUnlockUpdateViewModel =
        PreUnlockUpdateViewModel(context)

    /**
     * Real application context with only the installer package faked, so every
     * other framework call behaves normally while the F-Droid gate is selected.
     */
    private fun contextWithInstaller(installerPackage: String?): Context {
        val packageManager = mock<PackageManager>()
        whenever(packageManager.getInstallerPackageName(any())).thenReturn(installerPackage)
        return mock<Context>().also {
            whenever(it.packageName).thenReturn(baseContext.packageName)
            whenever(it.packageManager).thenReturn(packageManager)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedDownloadState(vm: PreUnlockUpdateViewModel, state: DownloadUiState) {
        val field = vm.javaClass.getDeclaredField("_downloadState")
        field.isAccessible = true
        (field.get(vm) as MutableStateFlow<DownloadUiState>).value = state
    }

    private companion object {
        const val FDROID_INSTALLER = "org.fdroid.fdroid"
    }
}
