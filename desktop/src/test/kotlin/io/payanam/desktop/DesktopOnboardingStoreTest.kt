//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop's onboarding marker: a device-level fact that must survive the wipe it exists for.
 *
 * The defect this store replaces was one of HOME: the fact lived in the encrypted database, so the reset
 * that deletes that database answered "never onboarded" to the one user who had used the app long enough to
 * ask for it to be wiped. These cases pin the two halves of the fix - the marker is durable, and it is NOT
 * the security store's state, because that store's own reset is exactly what runs alongside the wipe.
 */
class DesktopOnboardingStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val directory: Path get() = temporaryFolder.root.toPath().resolve("security")

    @Test
    fun `an installation with no marker has not completed onboarding`() {
        assertThat(store().hasCompletedOnboarding()).isFalse()
    }

    @Test
    fun `marking is durable and read back by a second instance`() {
        store().markOnboardingCompleted()

        // A SECOND instance, so the claim is about the file rather than about an in-memory copy: this is
        // the same object graph a restart builds.
        assertThat(store().hasCompletedOnboarding()).isTrue()
    }

    @Test
    fun `the security store's own reset leaves the marker alone`() {
        store().markOnboardingCompleted()

        // The desktop's security reset - the one the refusal state offers, and the same store's state the
        // passphrase lives in - rewrites its own entry. The marker is a different file in the same
        // directory precisely so this cannot clear it.
        DesktopSecurityStore(securityDirectory = directory).resetSecurityState()

        assertThat(store().hasCompletedOnboarding()).isTrue()
    }

    @Test
    fun `an unreadable payload reads as not completed rather than throwing`() {
        val file = directory.resolve("desktop-onboarding.properties")
        Files.createDirectories(directory)
        Files.writeString(file, "this is not a properties payload\u0000")

        // False, not an exception: a file this store cannot read is not evidence that the user has seen the
        // tour, and refusing to render the tour for such a user is the worse of the two answers.
        assertThat(store().hasCompletedOnboarding()).isFalse()
    }

    private fun store(): DesktopOnboardingStore = DesktopOnboardingStore(securityDirectory = directory)
}
