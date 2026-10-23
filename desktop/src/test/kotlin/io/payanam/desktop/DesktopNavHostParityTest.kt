//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The desktop must hand the shared navigation graph the SAME inputs Android hands it.
 *
 * **Why this test reads source files.** The two mount sites are the whole divergence surface, and there
 * is no other place to observe it: `PayanamNavHost` is a composable whose parameters are ordinary
 * function arguments, so nothing at runtime records which ones a platform supplied. Two identical-looking
 * screens can therefore behave differently purely because one platform passed `false` and the other
 * passed a build flag - and no unit test anywhere would notice, because the graph honours both.
 *
 * Reading the call sites is the honest observation point. The alternative - wrapping the parameters in a
 * data class and asserting on that - would be testing the wrapper rather than the wiring, and would still
 * pass if a mount site forgot to use it.
 *
 * **It is green today, and that is deliberate.** The gaps are recorded as data
 * ([RECORDED_MISSING_ON_DESKTOP], [RECORDED_VALUE_MISMATCHES]) and asserted as an EXACT match rather than
 * as emptiness, because `:desktop:test` failing fails the build gate - a red-by-design test cannot ship,
 * and one that is red for a fortnight trains everyone to ignore it.
 *
 * Inverted, it is sharper than a plain assertion: it goes RED when a gap is closed without this file being
 * updated (the fix landed undocumented), when a recorded entry goes stale, and when a NEW divergence appears
 * that nobody wrote down. Fix and record move in the same commit, enforced.
 *
 * Each entry is one recorded gap in the V2 screen list.
 *
 * The working directory is `desktop/` under Gradle (probed, not assumed), so the repo root is `..`.
 */
class DesktopNavHostParityTest {
    private val repoRoot = File("..").canonicalFile

    private val graphFile = File(repoRoot, "core/ui/src/commonMain/kotlin/io/payanam/core/ui/navigation/PayanamNavHost.kt")
    private val androidFile = File(repoRoot, "app/src/main/kotlin/io/payanam/MainActivity.kt")
    private val desktopFile = File(repoRoot, "desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt")

    /**
     * The named arguments of every `PayanamNavHost(` call in [source], unioned across calls.
     *
     * Two mount sites on Android, one on the desktop, so a parameter Android supplies at only one of its
     * two is still a parameter Android supplies.
     *
     * The scan stops when the CALL's parentheses balance, not the enclosing composable's. A `Surface` or
     * `when` lambda wrapped around a call holds arguments of its own at the same indent - `showDatabaseInit
     * = false` inside `Surface(` is not a graph parameter - and a looser scan swept those in and then
     * failed a healthy build.
     */
    private fun passedArguments(source: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = source.readLines()
        for ((callLine, callColumn) in callSites(lines)) {
            var depth = 0
            var braceDepth = 0
            var lineIndex = callLine
            var first = true
            while (lineIndex < lines.size) {
                val line = lines[lineIndex]
                val scanned = if (first) line.substring(callColumn + "PayanamNavHost(".length) else line
                // The opening line's own parens count too, or the scan ends before reading any argument.
                depth += line.count { it == '(' } - line.count { it == ')' }
                // A lambda argument's BODY holds assignments at the same indent - Android writes
                // `showDatabaseInit = false` inside `onDatabaseReady = { ... }` - and those are the host's
                // statements, not graph parameters. Skipping to the end of a lambda keeps them out; without
                // this the scan reports a parameter the graph does not declare and fails a healthy build.
                if (!first && braceDepth == 0) {
                    val match = ARGUMENT.find(scanned)
                    if (match != null) {
                        result.putIfAbsent(match.groupValues[1], match.groupValues[2].trim())
                    }
                }
                braceDepth += scanned.count { it == '{' } - scanned.count { it == '}' }
                if (depth <= 0) break
                first = false
                lineIndex++
            }
        }
        return result
    }

    /** Every `(line, column)` where a `PayanamNavHost(` call opens. */
    private fun callSites(lines: List<String>): List<Pair<Int, Int>> =
        lines.flatMapIndexed { index, line ->
            val column = line.indexOf("PayanamNavHost(")
            if (column >= 0 && !line.trimStart().startsWith("//")) listOf(index to column) else emptyList()
        }

    /** Every parameter the shared graph declares. */
    private fun declaredParameters(): Set<String> {
        val lines = graphFile.readLines()
        val start = lines.indexOfFirst { it.startsWith("fun PayanamNavHost(") }
        check(start >= 0) { "PayanamNavHost signature not found in ${graphFile.path}" }
        val names = mutableSetOf<String>()
        for (line in lines.drop(start + 1)) {
            if (line.trimStart().startsWith(")") && line.contains("{")) break
            val match = DECLARED.find(line)
            if (match != null) names.add(match.groupValues[1])
        }
        return names
    }

    @Test
    fun `both mount sites are findable and non-empty`() {
        // If either parse silently returned nothing, every assertion below would pass vacuously - the
        // failure mode of a source-reading test. This is the guard against a test that proves nothing.
        val android = passedArguments(androidFile)
        val desktop = passedArguments(desktopFile)

        assertThat(android).isNotEmpty()
        assertThat(desktop).isNotEmpty()
        assertThat(declaredParameters()).isNotEmpty()
    }

    @Test
    fun `every parameter Android supplies, the desktop supplies too - except the recorded gaps`() {
        // Asserted as an EXACT match against the recorded gap list, not as "is empty". A plain isEmpty()
        // would have to be red today and would then break the desktop gate on every run until all eleven
        // are fixed, which is not a shippable state for a test. This form is green now, and turns RED in
        // exactly two situations worth catching:
        //   - a gap is closed and this list is not updated (the fix landed undocumented), or
        //   - a new divergence appears that nobody recorded.
        // Either way the fix and the record move together.
        val android = passedArguments(androidFile)
        val desktop = passedArguments(desktopFile)

        assertThat((android.keys - desktop.keys).sorted()).isEqualTo(RECORDED_MISSING_ON_DESKTOP)
    }

    @Test
    fun `the recorded gap list is still accurate - no stale entries`() {
        // Guards the list above from rotting in the other direction: if a row was fixed and left behind,
        // this fails and points at exactly which one.
        val android = passedArguments(androidFile)
        val desktop = passedArguments(desktopFile)

        val recordedButNowSupplied = RECORDED_MISSING_ON_DESKTOP.filter { it in desktop }

        assertThat(recordedButNowSupplied).isEmpty()
    }

    @Test
    fun `build-decision inputs match on both platforms - except the recorded ones`() {
        // Presence is not enough: a parameter passed on both sides with DIFFERENT values is the same
        // divergence as one that is absent, and is the harder one to see. `minimalModeEnabled` is the case
        // that motivated this test - the desktop passes `false` where Android passes a build constant, so
        // the parameter is present and still wrong.
        val android = passedArguments(androidFile)
        val desktop = passedArguments(desktopFile)

        val mismatched =
            SHARED_DECISION_INPUTS
                .filter { it in android && it in desktop }
                .filter { normalise(android.getValue(it)) != normalise(desktop.getValue(it)) }
                .sorted()

        assertThat(mismatched).isEqualTo(RECORDED_VALUE_MISMATCHES)
    }

    @Test
    fun `no platform supplies a parameter the graph does not declare`() {
        // A renamed graph parameter would otherwise be silently dropped by both platforms, and every other
        // test here would still pass.
        val declared = declaredParameters()
        val unknown =
            (passedArguments(androidFile).keys + passedArguments(desktopFile).keys) - declared

        assertThat(unknown).isEmpty()
    }

    private companion object {
        /** `name = ...` as a named argument, indented the way a call site indents them. */
        val ARGUMENT = Regex("""^\s{12,}(\w+)\s*=\s*(.+?)\s*,?\s*$""")

        /** A parameter declaration in the graph's signature. */
        val DECLARED = Regex("""^\s{4}(\w+):""")

        /**
         * The parameters where both platforms must be reading literally the SAME source.
         *
         * Scoped tightly on purpose. A textual difference is only evidence of a divergence when both sides
         * should be reading the same thing, and most of them are: `shouldShowPassphraseSetup` is
         * `showPassphraseSetup` on Android and `gateFlags.shouldShowPassphraseSetup` on the desktop, and
         * those are the same fact reached through a different local - asserting they read alike would fail
         * on correct code. `tabVisibility` is `defaultPrefsState.tabVisibility` versus
         * `state.preferences.tabVisibility` for the same reason.
         *
         * What is left is the genuine class: a build-time feature decision, which has ONE source
         * (`FeatureFlags`, Android-only today) that the desktop cannot consult, so it hard-codes a value
         * that can silently disagree. `minimalModeEnabled` is the case that motivated this test.
         */
        val SHARED_DECISION_INPUTS = listOf("minimalModeEnabled")

        /**
         * The parameters Android supplies that the desktop does not, as of 2026-09-26.
         *
         * Recorded rather than asserted-empty on purpose - see the test that uses it. Every entry is one
         * recorded gap in the V2 screen list, and the two must shrink together: fixing one
         * without deleting it here fails `the recorded gap list is still accurate`, which is the intended
         * prompt to update the doc in the same commit.
         *
         * Seven were closed on 2026-09-26 - the backup-failure banner, return-route-after-unlock, and the
         * whole `aiAssistantEnabled` build flag, which is DELETED rather than wired: AI Assistance is a
         * permanent capability in both apps, so the desktop no longer hard-codes a value that can disagree.
         * That leaves four, all of them features that silently do not work on the desktop: no external
         * navigation, no post-unlock initialisation, no post-import reinitialisation, and the
         * consumed-callback that has to land with the first.
         */
        val RECORDED_MISSING_ON_DESKTOP =
            listOf(
                "externalCommand",
                "onDatabaseReady",
                "onExternalCommandConsumed",
                "onPassphraseUnlocked",
            )

        /**
         * The build-decision inputs both platforms pass with DIFFERENT values.
         *
         * `minimalModeEnabled` is present on both sides and still wrong: Android reads a build constant and
         * the desktop hard-codes `false`, because minimal mode is build-time on Android and the desktop has
         * no such knob to read. `aiAssistantEnabled` USED to be here for the same reason - the desktop
         * hard-coded `true` against Android's build constant - but the flag is gone as of 2026-09-26: the
         * assistant is always available, so there is no value left to disagree about.
         */
        val RECORDED_VALUE_MISMATCHES = listOf("minimalModeEnabled")

        /** Collapses whitespace so a reflowed expression is not read as a different value. */
        fun normalise(expression: String): String = expression.replace(Regex("\\s+"), " ").trim()
    }
}
