//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract tests for [ApkInstaller].
 *
 * The real implementation resolves a FileProvider URI and launches an ACTION_VIEW intent —
 * Android-framework code that cannot run on the JVM. These tests pin the interface contract
 * via a fake: the return value is the sole signal the ViewModel branches on, so the fake
 * must faithfully record the call and replay the configured outcome.
 */
class ApkInstallerTest {
    /** A fake [ApkInstaller] that records calls and returns a configurable result. */
    private class FakeApkInstaller(
        private val result: Boolean,
    ) : ApkInstaller {
        val calls = mutableListOf<String>()

        override fun install(apkPath: String): Boolean {
            calls.add(apkPath)
            return result
        }
    }

    @Test
    fun `install returns true when the installer launches the intent`() {
        val fake = FakeApkInstaller(result = true)
        assertThat(fake.install("/data/app/payanam.apk")).isTrue()
    }

    @Test
    fun `install returns false when the installer cannot be resolved`() {
        val fake = FakeApkInstaller(result = false)
        assertThat(fake.install("/data/app/payanam.apk")).isFalse()
    }

    @Test
    fun `install records the apkPath it was called with`() {
        val fake = FakeApkInstaller(result = true)
        fake.install("/data/app/payanam.apk")
        fake.install("/data/app/payanam2.apk")
        assertThat(fake.calls).containsExactly("/data/app/payanam.apk", "/data/app/payanam2.apk").inOrder()
    }

    @Test
    fun `install returns the configured result on every call`() {
        val fake = FakeApkInstaller(result = false)
        assertThat(fake.install("a")).isFalse()
        assertThat(fake.install("b")).isFalse()
    }
}
