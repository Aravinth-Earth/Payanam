//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A published digest, upper-case hex, exactly as `build-android.ps1` writes it. */
private const val DIGEST_UPPER = "AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12"

/** The same digest, lower-case hex, as a digester would normally produce it. */
private const val DIGEST_LOWER = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12"

/** The artifact name a sidecar line names after the digest. */
private const val ARTIFACT_NAME = "payanam_1.0.0_debug_arm64-v8a.apk"

/**
 * The `.sha256` sidecar policy: parse the published format, compare digests case-insensitively.
 *
 * The fixture is the real shape rather than a convenient one — upper-case hex, TWO spaces, then
 * the bare artifact filename, plus a trailing newline — because that is what `build-android.ps1`
 * produces and what `publish-release.ps1` verifies. The property pinned here is fail-closed
 * parsing: a token that is not exactly 64 hex characters yields null, never a best guess.
 */
class ChecksumSidecarTest {
    @Test
    fun `a published sidecar line parses to a lowercase digest`() {
        val published = "$DIGEST_UPPER  $ARTIFACT_NAME\n"

        assertThat(parseSha256Sidecar(published)).isEqualTo(DIGEST_LOWER)
    }

    @Test
    fun `a utf8 byte order mark is tolerated`() {
        assertThat(parseSha256Sidecar("\uFEFF$DIGEST_UPPER  $ARTIFACT_NAME\n")).isEqualTo(DIGEST_LOWER)
    }

    @Test
    fun `crlf line endings are tolerated`() {
        assertThat(parseSha256Sidecar("$DIGEST_UPPER  $ARTIFACT_NAME\r\n")).isEqualTo(DIGEST_LOWER)
        assertThat(parseSha256Sidecar("$DIGEST_UPPER\r\n")).isEqualTo(DIGEST_LOWER)
    }

    @Test
    fun `blank lines and surrounding whitespace are tolerated`() {
        assertThat(parseSha256Sidecar("\n\n$DIGEST_UPPER  $ARTIFACT_NAME\n\n")).isEqualTo(DIGEST_LOWER)
        assertThat(parseSha256Sidecar("   \n  $DIGEST_UPPER  $ARTIFACT_NAME  \n  \n")).isEqualTo(DIGEST_LOWER)
        assertThat(parseSha256Sidecar("  $DIGEST_UPPER  ")).isEqualTo(DIGEST_LOWER)
    }

    @Test
    fun `a tab or a single space separates the digest from the filename`() {
        assertThat(parseSha256Sidecar("$DIGEST_UPPER\t$ARTIFACT_NAME")).isEqualTo(DIGEST_LOWER)
        assertThat(parseSha256Sidecar("$DIGEST_UPPER $ARTIFACT_NAME")).isEqualTo(DIGEST_LOWER)
        assertThat(parseSha256Sidecar("$DIGEST_UPPER     $ARTIFACT_NAME")).isEqualTo(DIGEST_LOWER)
    }

    @Test
    fun `a digest alone on the line parses`() {
        assertThat(parseSha256Sidecar(DIGEST_UPPER)).isEqualTo(DIGEST_LOWER)
    }

    @Test
    fun `a lowercase digest parses unchanged`() {
        assertThat(parseSha256Sidecar("$DIGEST_LOWER  $ARTIFACT_NAME")).isEqualTo(DIGEST_LOWER)
    }

    @Test
    fun `a digest of the wrong length is rejected`() {
        assertThat(parseSha256Sidecar(DIGEST_UPPER.dropLast(1))).isNull()
        assertThat(parseSha256Sidecar(DIGEST_UPPER + "A")).isNull()
        assertThat(parseSha256Sidecar(DIGEST_UPPER + "A  $ARTIFACT_NAME")).isNull()
    }

    @Test
    fun `non hex characters are rejected`() {
        // 'G' is outside a-f, and a Unicode digit is not an ASCII hex digit.
        assertThat(parseSha256Sidecar("G" + DIGEST_UPPER.drop(1))).isNull()
        assertThat(parseSha256Sidecar(DIGEST_UPPER.drop(1) + "g")).isNull()
        assertThat(parseSha256Sidecar(DIGEST_UPPER.dropLast(1) + "\u0661")).isNull()
        // An internal separator makes the first token too short.
        assertThat(parseSha256Sidecar("${DIGEST_UPPER.take(32)} ${DIGEST_UPPER.drop(32)}")).isNull()
    }

    @Test
    fun `text without a digest is rejected`() {
        assertThat(parseSha256Sidecar("")).isNull()
        assertThat(parseSha256Sidecar("   ")).isNull()
        assertThat(parseSha256Sidecar("\n\r\n\t\n")).isNull()
        assertThat(parseSha256Sidecar("""{"sha256":"$DIGEST_LOWER"}""")).isNull()
        assertThat(parseSha256Sidecar("Not Found")).isNull()
    }

    @Test
    fun `only the first meaningful line is read`() {
        // A malformed first line is not skipped in favour of a valid later one: publishing reads
        // the first line too, so a file that needs hunting past is a file that fails closed.
        assertThat(parseSha256Sidecar("garbage\n$DIGEST_UPPER  $ARTIFACT_NAME")).isNull()
    }

    @Test
    fun `a digest and its equal compare true regardless of case`() {
        assertThat(sha256Matches(DIGEST_UPPER, DIGEST_UPPER)).isTrue()
        assertThat(sha256Matches(DIGEST_LOWER, DIGEST_LOWER)).isTrue()
        assertThat(sha256Matches(DIGEST_UPPER, DIGEST_LOWER)).isTrue()
        assertThat(sha256Matches(DIGEST_LOWER, DIGEST_UPPER)).isTrue()
        // Surrounding whitespace is trimmed, as the parser does.
        assertThat(sha256Matches("  $DIGEST_UPPER\n", DIGEST_LOWER)).isTrue()
    }

    @Test
    fun `a different or malformed digest compares false`() {
        assertThat(sha256Matches(DIGEST_UPPER, DIGEST_LOWER.dropLast(1) + "0")).isFalse()
        assertThat(sha256Matches(DIGEST_UPPER.dropLast(1), DIGEST_LOWER)).isFalse()
        assertThat(sha256Matches(DIGEST_UPPER, DIGEST_LOWER + "0")).isFalse()
        assertThat(sha256Matches("Not a digest", DIGEST_LOWER)).isFalse()
        assertThat(sha256Matches(DIGEST_UPPER, "")).isFalse()
        assertThat(sha256Matches("", "")).isFalse()
    }
}
