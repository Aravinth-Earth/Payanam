//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * No passphrase-length value reaches any log or breadcrumb, on either platform.
 *
 * **Why this is a source scan and not a log assertion.** The defect is a *call site* that passes a
 * length into a log payload - `mapOf("passphraseLength" to passphrase.length)` - in seven places across
 * `app/`, `core/database` and `core/presentation`. A log assertion can only see a site a test happens
 * to exercise; a scan sees every site in the tree, which is the gate's own wording ("proven from the
 * repo's log-call sites").
 *
 * **The mutants this catches:**
 *
 * 1. a length is put back into a log or breadcrumb payload under the old key (`passphraseLength`),
 *    under a generic key (`"length" to passphrase...`), or inline in a message;
 * 2. a NEW file is written with a length-leak pattern - the scan reads the whole tree, not a list of
 *    files that existed when the test was written;
 * 3. the honest replacement is deleted, so a diagnostic claim about the passphrase disappears while the
 *    leak pattern silently has nothing to compare against (see the `passphraseProvided` marker case).
 *
 * **What it deliberately does NOT flag:** `PassphraseContracts`' `passphrase.length < MIN_LENGTH`
 * validation and similar *comparisons* - those never reach a log, and a scan that forbade
 * `passphrase.length` outright would fail on correct code and be worked around.
 */
class PassphraseLogHygieneTest {
    /** Every main source root in the repo; the scan is repo-wide by construction. */
    private val sourceRoots =
        listOf(
            "app/src/main",
            "core/database/src/main",
            "core/presentation/src/main",
            "core/shared/src/main",
            "desktop/src/main",
        )

    /** Patterns that can only appear when a length (or the old key) is being written out. */
    private val forbidden =
        listOf(
            // The exact key the gate names.
            Regex("""passphraseLength"""),
            // A length under a generic key inside a payload: `"length" to passphrase.length`.
            Regex(""""[a-zA-Z]*[Ll]ength[a-zA-Z]*"\s+to\s+\w*[Pp]assphrase\w*\.length"""),
            // A length interpolated straight into a log/breadcrumb message.
            Regex("""passphrase\w*\.length[^<]*(?:\$|\+)"""),
        )

    @Test
    fun no_main_source_writes_a_passphrase_length_into_a_log_payload() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (root in sourceRoots) {
            val dir = repoFile(root)
            if (!dir.isDirectory) continue
            dir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    scanned++
                    val text = file.readText()
                    for (pattern in forbidden) {
                        for (match in pattern.findAll(text)) {
                            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                            offenders += "${file.path.removePrefix(repoRoot().path + "/")}:$line -> ${match.value.take(80)}"
                        }
                    }
                }
        }
        // The scan must have actually read the tree, or "no offenders" would pass on an empty walk.
        assertThat(scanned).isGreaterThan(50)
        assertThat(offenders).isEmpty()
    }

    @Test
    fun the_honest_replacement_is_present_where_the_lengths_were() {
        // The fix is not "delete the diagnostic" - it is "say provided/not provided". If this marker
        // disappears, the leak patterns above would have nothing to compare against and a silent
        // reversion to a length would be caught only by the scan's first case.
        val markers = mutableListOf<String>()
        for (root in sourceRoots) {
            val dir = repoFile(root)
            if (!dir.isDirectory) continue
            dir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    if (file.readText().contains("passphraseProvided")) {
                        markers += file.path.removePrefix(repoRoot().path + "/")
                    }
                }
        }
        assertThat(markers).isNotEmpty()
    }

    /** The repository root, walked up from the module the test runs in (the runner's cwd). */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate the repository root from ${File(".").absolutePath}")
    }

    private fun repoFile(relative: String): File = File(repoRoot(), relative)
}
