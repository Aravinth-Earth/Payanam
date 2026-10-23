//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Both platforms' rekey ports DELEGATE to the shared rekey sequence instead of holding a step of their own.
 *
 * **The defect this rules out, stated as the user sees it.** A rekey is the one operation where a partial
 * success is worse than a failure: a port that ran some of the sequence inline - opened the file, issued its
 * own rekey statement, skipped the verify - would report success on a file that still opens under the old
 * passphrase, and the user would have a database they believe is re-keyed and is not. The shared sequence
 * exists precisely so no platform owns a step; the moment one does, the two platforms can disagree about what
 * "rekeyed" means.
 *
 * **Why the behavioural tests cannot hold this.** The existing tests assert outcomes - a real file is
 * re-keyed, the outcome enum flows through the port - and those outcomes stay green if someone inlines the
 * step order into either port, because the inline version produces the same outcome on the happy path. Only
 * the source shows whether the port still delegates, which is why this test reads it.
 *
 * Both directions are asserted, and both are scan-guarded: asserting "the delegation call is present" alone
 * would pass on a file that also inlined steps beside it, and asserting "no step name is present" alone would
 * pass on a file that does nothing at all.
 *
 * The working directory is `desktop/` under Gradle, so the repo root is `..`.
 */
class DatabaseRekeyDelegationGuardTest {
    private val repoRoot = File("..").canonicalFile

    private val androidPort =
        File(repoRoot, "app/src/main/kotlin/io/payanam/platform/AndroidDatabaseRekey.kt")

    private val desktopPort =
        File(repoRoot, "core/database-jvm/src/main/kotlin/io/payanam/database/jvm/DesktopDatabaseEncryptionProbe.kt")

    private companion object {
        // Comments are stripped before the step scan: the ports' KDocs legitimately explain the sequence
        // (the Android port's own doc names `PRAGMA rekey` as prose), and a scan that counted prose would
        // fail on documentation rather than on behaviour.
        private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("//[^\\n]*")
    }

    private fun code(file: File): String =
        file
            .readText()
            .replace(BLOCK_COMMENT, "")
            .replace(LINE_COMMENT, "")

    /**
     * The one statement an inline copy would necessarily contain. The sequence's own API names are NOT
     * scanned on purpose: a delegating port legitimately calls the sequence's steps, so their names prove
     * nothing. Nor is the text "PRAGMA rekey" scanned - the desktop probe's own refusal messages quote that
     * phrase as a log string, and a scan that counted log text would fail on diagnostics rather than on
     * behaviour. `sqlcipher_export` is the canonical way to move data between keys, so a port that inlined
     * the work rather than delegating it has to reach for it; neither port may name it.
     */
    private val inlinedStatements = listOf("sqlcipher_export")

    @Test
    fun `both ports delegate to the shared rekey sequence`() {
        val android = code(androidPort)
        val desktop = code(desktopPort)

        assertThat(android).contains("DatabaseEncryptionMigrationSupport.rekeyEncryptedDatabase")
        assertThat(desktop).contains("DatabaseRekeySequence.rekeyToNewPassphrase")
    }

    @Test
    fun `neither port holds a step of the sequence`() {
        for ((name, file) in listOf("Android" to androidPort, "Desktop" to desktopPort)) {
            val body = code(file)
            for (statement in inlinedStatements) {
                assertThat(body).doesNotContain(statement)
            }
            // The scan itself is guarded: if the file were empty or unreadable the assertions above would
            // pass vacuously, so the file must be long enough to be the port it claims to be.
            assertThat(body.length).isGreaterThan(500)
        }
    }
}
