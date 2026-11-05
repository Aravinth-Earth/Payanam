//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one table every install gate reads: which verdict may reach the installer, and which failure
 * key each refusal reports.
 *
 * Asserted directly as well as through the ViewModels because this is the exact place a future
 * verdict could be added without a branch: the `when` would stop compiling, and this test would fail
 * alongside it. The three refusal keys are asserted to be DISTINCT - one shared key would make a
 * tampered download indistinguishable from an unconfirmable one in the state the UI renders.
 */
class ChecksumVerdictMappingTest {

    @Test
    fun `only a verified verdict may be installed`() {
        assertNull(ChecksumVerdict.Verified.installBlockKey())
    }

    @Test
    fun `each non-verified verdict maps to its own failure key`() {
        assertEquals("checksum_mismatch", ChecksumVerdict.Mismatch.installBlockKey())
        assertEquals("checksum_absent", ChecksumVerdict.Skipped("no_sha256_asset").installBlockKey())
        assertEquals("checksum_unavailable", ChecksumVerdict.Unavailable("sidecar_unavailable").installBlockKey())

        val blockKeys =
            listOf(
                ChecksumVerdict.Mismatch.installBlockKey(),
                ChecksumVerdict.Skipped("no_sha256_asset").installBlockKey(),
                ChecksumVerdict.Unavailable("apk_unreadable").installBlockKey(),
            )
        assertEquals("each refusal must be tellable apart from the others", 3, blockKeys.toSet().size)
    }

    @Test
    fun `the refusal key depends on the verdict, not on its reason code`() {
        // Two different reasons inside one verdict still mean the same thing to the user, so the key
        // must not leak the machine code that happens to explain it that time.
        assertEquals(
            ChecksumVerdict.Unavailable("sidecar_unavailable").installBlockKey(),
            ChecksumVerdict.Unavailable("apk_unreadable").installBlockKey(),
        )
    }

    @Test
    fun `every verdict has a stable log-safe name`() {
        assertEquals("verified", ChecksumVerdict.Verified.logName())
        assertEquals("mismatch", ChecksumVerdict.Mismatch.logName())
        assertEquals("skipped", ChecksumVerdict.Skipped("no_sha256_asset").logName())
        assertEquals("unavailable", ChecksumVerdict.Unavailable("sidecar_unavailable").logName())
    }
}
