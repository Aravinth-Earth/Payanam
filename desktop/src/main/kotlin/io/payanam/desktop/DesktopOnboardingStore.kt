//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.io.StringWriter
import java.nio.file.Path
import java.util.Properties

/** The onboarding marker's file, beside the security store's own entry in the same directory. */
private const val DESKTOP_ONBOARDING_FILE_NAME = "desktop-onboarding.properties"

/** The one property this store holds. */
private const val KEY_ONBOARDING_COMPLETED = "onboardingCompleted"

/**
 * Whether THIS INSTALLATION has been through the shared onboarding tour.
 *
 * DEVICE-LEVEL, and deliberately not a database row. The fact it replaces lived in `app_settings`, inside
 * the encrypted database - so the reset, which deletes that file, answered "never onboarded" to the one
 * user who had demonstrably used the app long enough to want it wiped. The owner's ruling (2026-10-14):
 * *"When a user is using a reset option which means user already know how an app works only then they will
 * be using the reset option. So in that case we don't need to show them the onboarding flow once more."*
 * A genuinely fresh install has no file here and still sees the tour.
 *
 * It lives NEXT TO [DesktopSecurityStore] rather than inside it, and that is the point: the security store's
 * payload is rewritten from a snapshot on every passphrase change and CLEARED by
 * [DesktopSecurityStore.resetSecurityState], which is exactly the operation this marker must survive. It
 * therefore uses the same [DesktopKeyValueEntry] file discipline - a 0600 file under the user's own data
 * root, written atomically - and none of the security store's lifecycle.
 */
internal class DesktopOnboardingStore(
    securityDirectory: Path = DesktopAppPaths.resolveSecurityDirectory(),
    private val entry: DesktopKeyValueEntry =
        DesktopKeyValueEntry(securityDirectory.resolve(DESKTOP_ONBOARDING_FILE_NAME)),
) {
    /**
     * True once the tour has been finished on this installation.
     *
     * An unreadable or absent payload is `false`, which is the first-install answer: the alternative would
     * be to suppress the tour for a user who has never seen it, and a file this store cannot read is not
     * evidence that they have.
     */
    fun hasCompletedOnboarding(): Boolean =
        runCatching {
            val payload = entry.readEntry() ?: return false
            val properties = Properties()
            properties.load(payload.reader())
            properties.getProperty(KEY_ONBOARDING_COMPLETED)?.toBoolean() ?: false
        }.getOrDefault(false)

    /**
     * Records that the tour has been finished.
     *
     * Written through the entry, so the write is atomic and the file is owner-only - the same two
     * properties the security store's own writes have.
     */
    fun markOnboardingCompleted() {
        val properties = Properties()
        properties.setProperty(KEY_ONBOARDING_COMPLETED, "true")
        // A real writer rather than `buildString`: `Properties.store` writes to a `Writer`, and passing a
        // `StringBuilder` there would be a type error the compiler catches (it did).
        val writer = StringWriter()
        properties.store(writer, null)
        entry.writeEntry(writer.toString())
    }
}
