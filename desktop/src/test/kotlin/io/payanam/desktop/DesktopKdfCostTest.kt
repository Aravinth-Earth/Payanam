//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.security.PassphraseKdf
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The desktop's key-derivation COST, asserted on the verifier the desktop itself writes.
 *
 * **Why this exists next to the shared KDF test.** `PassphraseKdfTest` pins the shared configuration and
 * its fixed vector, but it cannot show that the DESKTOP PATH reaches that configuration: the store was
 * the side that quietly derived at 10,000 iterations while the shared object said 210,000, and a test of
 * the shared object alone would have stayed green through the whole drift. This reads the verifier file
 * the store actually wrote and re-derives the same passphrase and salt at the agreed cost (and at the
 * former cost), so the assertion is about what the desktop ships, not about what a constant says.
 *
 * **The mutants this catches:**
 *
 * 1. the store's derivation drops back to 10,000 iterations (or any other count) - the 210k equality
 *    fails;
 * 2. the store stops calling the shared KDF and grows a private derivation - the 210k equality fails
 *    unless the private copy happens to be byte-identical, and the anti-regression case still proves the
 *    cost was not lowered;
 * 3. the salt stops being 32 bytes - the stored salt is decoded and used, so a shorter salt changes the
 *    derivation and both assertions fail.
 */
class DesktopKdfCostTest {
    @Test
    fun `the verifier the desktop stores was derived at the shared 210k cost, not the former 10k`() {
        val directory = Files.createTempDirectory("payanam-kdf-cost")
        val store = DesktopSecurityStore(securityDirectory = directory)
        val result = store.configurePassphrase("Passw0rd!123")
        assertThat(result).isEqualTo(DesktopPassphraseActionResult.Success)

        val file = directory.resolve("desktop-security.properties")
        assertThat(Files.exists(file)).isTrue()
        val properties =
            Properties().apply {
                Files.newInputStream(file).use(::load)
            }
        val salt = Base64.getDecoder().decode(properties.getProperty("saltBase64"))
        val storedHash = Base64.getDecoder().decode(properties.getProperty("hashBase64"))
        assertThat(salt).hasLength(PassphraseKdf.SALT_BYTES)

        // The desktop's own cost, re-derived through the SHARED KDF: it must reproduce the stored verifier.
        val atAgreedCost = PassphraseKdf.derive("Passw0rd!123".toCharArray(), salt)
        assertThat(atAgreedCost).isEqualTo(storedHash)

        // The anti-regression: at the desktop's FORMER 10,000 iterations the same inputs give a different
        // verifier, so this fails if the stored cost ever drops back - the exact drift the shared object
        // was created to make impossible.
        val atFormerCost = deriveAt("Passw0rd!123", salt, 10_000)
        assertThat(atFormerCost).isNotEqualTo(storedHash)
    }

    private fun deriveAt(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, PassphraseKdf.KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(PassphraseKdf.ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    @Test
    fun `a verifier written at the former cost is accepted once and re-hashed at the shared cost`() {
        // The re-hash path. A verifier on disk at 10,000 iterations can never match a 210,000-iteration
        // derivation, so without this path its owner is locked out of their own database and the lockout
        // counter climbs on every correct passphrase. The test fabricates that on-disk state directly -
        // the only way to produce a legacy verifier now - and then asserts BOTH halves: the unlock
        // succeeds, and the stored verifier is at the shared cost afterwards.
        val directory = Files.createTempDirectory("payanam-kdf-rehash")
        val store = DesktopSecurityStore(securityDirectory = directory)
        assertThat(store.configurePassphrase("Passw0rd!123")).isEqualTo(DesktopPassphraseActionResult.Success)

        val file = directory.resolve("desktop-security.properties")
        val properties =
            Properties().apply {
                Files.newInputStream(file).use(::load)
            }
        val salt = Base64.getDecoder().decode(properties.getProperty("saltBase64"))
        val legacyHash = deriveAt("Passw0rd!123", salt, 10_000)
        properties.setProperty("hashBase64", Base64.getEncoder().encodeToString(legacyHash))
        Files.newOutputStream(file).use { stream -> properties.store(stream, "legacy verifier") }

        // The unlock succeeds against the LEGACY verifier...
        val result = store.verifyPassphrase("Passw0rd!123")
        assertThat(result).isEqualTo(DesktopPassphraseActionResult.Success)

        // ...and the file no longer holds it: the stored hash is now the shared-cost derivation, so the
        // cheap artifact survives no longer than the one unlock it bought.
        val after =
            Properties().apply {
                Files.newInputStream(file).use(::load)
            }
        val storedAfter = Base64.getDecoder().decode(after.getProperty("hashBase64"))
        assertThat(storedAfter).isEqualTo(PassphraseKdf.derive("Passw0rd!123".toCharArray(), salt))
        assertThat(storedAfter).isNotEqualTo(legacyHash)

        // A second unlock proves the rewritten verifier is the one in force.
        assertThat(store.verifyPassphrase("Passw0rd!123")).isEqualTo(DesktopPassphraseActionResult.Success)
    }
}
