//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.jetbrains.skia.Image
import org.junit.Test

/**
 * Guards the wiring of the shared brand assets in `assets/branding`.
 *
 * [loadBrandIcon] is deliberately tolerant - it logs and returns null rather than failing startup - so a
 * broken resource wiring degrades to the platform-default window icon with only a log line to show for
 * it. That is exactly the kind of break that survives a review and a build, which is why it is asserted
 * here instead: the packaged installer icon and the runtime window icon must both come from the one
 * checked-in file.
 *
 * The test runs against the main source set's resources, so it fails if `resources.srcDir("assets/branding")`
 * is removed or the asset is renamed - not just if the file is missing from the repo.
 */
class BrandIconAssetTest {
    private fun readBrandIconBytes(): ByteArray? =
        Thread.currentThread().contextClassLoader?.getResourceAsStream(BRAND_ICON_RESOURCE)?.use {
            it.readBytes()
        }

    @Test
    fun `the shared brand icon is on the classpath`() {
        assertThat(readBrandIconBytes()).isNotNull()
    }

    @Test
    fun `the shared brand icon decodes as the 512px launcher artwork`() {
        val bytes = readBrandIconBytes()
        assertThat(bytes).isNotNull()

        val image = Image.makeFromEncoded(bytes!!)
        // 512 is the size Android's launcher icon and the F-Droid listing are rendered from; a silent
        // swap to some other file would change this and be caught.
        assertThat(image.width).isEqualTo(512)
        assertThat(image.height).isEqualTo(512)
    }

    @Test
    fun `the windows icon is not shipped inside the application jar`() {
        // The .ico is a Windows packaging input read from assets/branding, not a runtime resource. It is
        // ~370 KB of uncompressed BMP entries, so leaking it into every platform's jar is the regression.
        val icoStream =
            Thread.currentThread().contextClassLoader?.getResourceAsStream("payanam-icon.ico")
        assertThat(icoStream).isNull()
    }
}
