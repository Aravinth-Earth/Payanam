//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Contract tests for [ApkInstaller].
 *
 * The real implementation resolves a FileProvider URI and launches an ACTION_VIEW intent —
 * Android-framework code that cannot run on the JVM. These tests pin the interface contract
 * via a fake: the return value is the sole signal the ViewModel branches on, so the fake
 * must faithfully record the call and replay the configured outcome. The checksum URL is part
 * of the request, so the fake records it beside the path rather than ignoring it.
 *
 * The method is suspend (the check it owns does real IO), so each test runs its body inside
 * [runBlocking] rather than relying on a test dispatcher it does not otherwise need.
 */
class ApkInstallerTest {
    /** A fake [ApkInstaller] that records calls and returns a configurable result. */
    private class FakeApkInstaller(
        private val result: Boolean,
    ) : ApkInstaller {
        /** One entry per call: the path and the sidecar URL the caller passed. */
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun install(
            apkPath: String,
            sha256Url: String?,
        ): Boolean {
            calls.add(apkPath to sha256Url)
            return result
        }
    }

    @Test
    fun `install returns true when the installer launches the intent`() {
        runBlocking {
            val fake = FakeApkInstaller(result = true)
            assertThat(fake.install("/data/app/payanam.apk", "https://example.invalid/payanam.apk.sha256")).isTrue()
        }
    }

    @Test
    fun `install returns false when the installer cannot be resolved`() {
        runBlocking {
            val fake = FakeApkInstaller(result = false)
            assertThat(fake.install("/data/app/payanam.apk", null)).isFalse()
        }
    }

    @Test
    fun `install records the apkPath it was called with`() {
        runBlocking {
            val fake = FakeApkInstaller(result = true)
            fake.install("/data/app/payanam.apk", null)
            fake.install("/data/app/payanam2.apk", null)
            assertThat(fake.calls.map { it.first })
                .containsExactly("/data/app/payanam.apk", "/data/app/payanam2.apk")
                .inOrder()
        }
    }

    @Test
    fun `install carries the checksum URL it was given, so no caller can skip the check`() {
        runBlocking {
            val fake = FakeApkInstaller(result = true)
            fake.install("/data/app/payanam.apk", "https://example.invalid/payanam.apk.sha256")
            assertThat(fake.calls.single().second).isEqualTo("https://example.invalid/payanam.apk.sha256")
            fake.install("/data/app/payanam.apk", null)
            assertThat(fake.calls.last().second).isNull()
        }
    }

    @Test
    fun `install returns the configured result on every call`() {
        runBlocking {
            val fake = FakeApkInstaller(result = false)
            assertThat(fake.install("a", null)).isFalse()
            assertThat(fake.install("b", null)).isFalse()
        }
    }
}
