//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract tests for [InstallSourceReader].
 *
 * The real implementation answers from the Android build configuration, which is not on the JVM test
 * classpath here. These tests pin the interface contract via a fake:
 * the ViewModel reads [InstallSourceReader.isFDroidBuild] once at construction and branches
 * on it for every user action, so the fake must return a stable boolean.
 */
class InstallSourceReaderTest {
    /** A fake [InstallSourceReader] that returns a configurable result. */
    private class FakeInstallSourceReader(
        private val result: Boolean,
    ) : InstallSourceReader {
        var callCount = 0

        override fun isFDroidBuild(): Boolean {
            callCount++
            return result
        }
    }

    @Test
    fun `isFDroidBuild returns true when installed from F-Droid`() {
        val fake = FakeInstallSourceReader(result = true)
        assertThat(fake.isFDroidBuild()).isTrue()
    }

    @Test
    fun `isFDroidBuild returns false when installed from another source`() {
        val fake = FakeInstallSourceReader(result = false)
        assertThat(fake.isFDroidBuild()).isFalse()
    }

    @Test
    fun `isFDroidBuild returns the same value on every call`() {
        val fake = FakeInstallSourceReader(result = true)
        assertThat(fake.isFDroidBuild()).isTrue()
        assertThat(fake.isFDroidBuild()).isTrue()
        assertThat(fake.callCount).isEqualTo(2)
    }
}
