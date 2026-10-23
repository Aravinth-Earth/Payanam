//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.presentation.updates

/**
 * A SHA-256 digest written as hex: 32 bytes, two hex characters each.
 *
 * The one magic number in this file, so nothing below repeats it.
 */
const val SHA256_HEX_LENGTH = 64

/** Byte-order mark a PowerShell `Set-Content -Encoding UTF8` write can leave at the start of the file. */
private const val BOM = '\uFEFF'

/** Separates the digest token from the filename token on a sidecar line. */
private val SIDECAR_SEPARATOR = Regex("""\s+""")

/**
 * Parse a release's `.sha256` sidecar into its digest, or null when it carries none.
 *
 * THE FORMAT, pinned to what this repo actually publishes. `build-android.ps1` writes one line of
 * `<64 uppercase hex>` + two spaces + the bare artifact filename + a newline, through PowerShell
 * `Set-Content -Encoding UTF8`. `publish-release.ps1` verifies it by taking the FIRST line, then
 * the FIRST whitespace-separated token, upper-casing it and ignoring the filename entirely.
 * This parser mirrors that read exactly, and additionally tolerates a UTF-8 BOM, CRLF, tabs, one
 * or more spaces, and blank lines around the payload.
 *
 * The filename token is DELIBERATELY ignored, because publishing ignores it: the digest is checked
 * against the bytes that were actually downloaded, so a sidecar naming a different file is not a
 * reason to accept a digest, nor to reject one. Only the digest token is validated.
 *
 * Fail closed: only the first non-blank line is read, its first token must be exactly
 * [SHA256_HEX_LENGTH] hex characters after trimming, and anything else yields null. A later line
 * is never consulted, because hunting past a malformed first line would accept more shapes than
 * the publisher's own reader does. Never throws.
 */
fun parseSha256Sidecar(text: String): String? {
    val body = text.removePrefix(BOM.toString())
    for (line in body.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val token = trimmed.split(SIDECAR_SEPARATOR).firstOrNull()
        return normaliseHexDigest(token)
    }
    return null
}

/**
 * True when [expectedHex] and [actualHex] are the same SHA-256 digest.
 *
 * Comparison is case-insensitive because builds publish upper-case hex while digesting code
 * usually produces lower-case. Surrounding whitespace is trimmed, and anything else that is not
 * exactly [SHA256_HEX_LENGTH] hex characters on either side is false — never a throw, and never a
 * partial or prefix match.
 */
fun sha256Matches(expectedHex: String, actualHex: String): Boolean {
    val expected = normaliseHexDigest(expectedHex) ?: return false
    val actual = normaliseHexDigest(actualHex) ?: return false
    return expected == actual
}

/** Trim, validate as a full-width hex digest, and lower-case it; null when it is anything else. */
private fun normaliseHexDigest(candidate: String?): String? {
    val digits = candidate?.trim().orEmpty()
    if (digits.length != SHA256_HEX_LENGTH) return null
    if (!digits.all { it.isHexDigit() }) return null
    return digits.lowercase()
}

/** ASCII hex only: a Unicode decimal digit or full-width character is not a valid digest char. */
private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
