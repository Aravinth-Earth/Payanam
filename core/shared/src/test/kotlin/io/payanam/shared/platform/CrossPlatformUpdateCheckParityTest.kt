//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.updates.UpdateOutcome
import org.junit.Test
import java.io.File

/**
 * Pins the update-check parity this module's gate is written about: that Android's and the desktop's
 * sources route one shared [io.payanam.shared.updates.UpdateOutcome] through the SAME shared checker,
 * map all seven outcomes without a catch-all, ask DELIBERATELY DIFFERENT channels, and differ in
 * `installable` because the platforms do rather than by accident. It reads the two production sources
 * off disk instead of instantiating either implementation because no module can see both:
 * `AndroidApkUpdateSource` lives in `:app` and needs Robolectric to construct, and
 * `DesktopUnsupportedPorts` is `internal` to `:desktop`, while `:core:shared` depends on NEITHER - so a
 * test here that called either class would not compile, and a test in `:app` or `:desktop` could only
 * ever drive one platform. Nothing can therefore drive both implementations over one channel state at
 * runtime; reading both sources is the only place in this build where the two answers can be compared at
 * all, which is why the gate exists. Assertions run against comment-stripped text wherever they claim
 * something is CODE, so a line of prose quoting a rule cannot satisfy a rule, and the comment-stripping
 * keeps line numbers true for the failures this reports.
 *
 * ITS LIMITS, stated rather than implied. It is a TEXT check: a mapping assembled elsewhere, an outcome
 * routed through a helper this does not read, or a variant added to [io.payanam.shared.updates.UpdateOutcome]
 * and handled correctly somewhere unseen would all read as violations, not as behaviour - this pins
 * SHAPE, not runtime answers. Two things are already compiler-enforced and are pinned here only so the
 * pin is visible: the desktop's `when (result.outcome)` is a statement over an enum in this same module,
 * so Kotlin already rejects a non-exhaustive branch list, and what this adds is the `else` guard - an
 * `else` IS legal and would quietly absorb a variant added later. And the STABLE rationale is asserted
 * where it actually lives, in the desktop's settings collaborators rather than in the port file; see
 * [theChannelsDifferDeliberately_andEachPlatformSaysWhy].
 */
class CrossPlatformUpdateCheckParityTest {
    /**
     * Both platform sources exist, so a path typo cannot turn every assertion below into a silent pass.
     *
     * This is the failure mode a source-reading check is most exposed to: a check pointed at a file that
     * was renamed reports nothing and stays green. Asserted before anything else for that reason.
     */
    @Test
    fun bothPlatformSources_arePresent() {
        listOf(ANDROID_SOURCE, DESKTOP_SOURCE, DESKTOP_SETTINGS_SOURCE, SHARED_APK_SOURCE).forEach { path ->
            val source = File(repositoryRoot, path)
            assertThat(source.exists()).isTrue()
            assertThat(source.readText()).isNotEmpty()
        }
    }

    /**
     * (i) Both platforms ask the SAME shared checker, so one channel state produces one verdict.
     *
     * This is the root of the parity claim: if either platform rolled its own release walk, "the same
     * answer for the same channel state" would be a coincidence of two independent implementations
     * rather than a property of one shared object. Both default parameter values are asserted, because a
     * platform that kept the shared checker but swapped the default for its own copy would satisfy a
     * looser "calls UpdateChecker" check.
     */
    @Test
    fun bothPlatforms_askTheSharedChecker() {
        assertThat(strippedCode(ANDROID_SOURCE)).contains("UpdateChecker.check(")
        assertThat(strippedCode(DESKTOP_SOURCE)).contains("UpdateChecker.check(")
        assertThat(strippedCode(ANDROID_SOURCE))
            .contains("UpdateChecker.check(currentBuildNumber, requested, ApkBuildType.running())")
        assertThat(strippedCode(DESKTOP_SOURCE))
            .contains("UpdateChecker.check(currentBuildNumber, requested, DESKTOP_UPDATE_RUNNING_TYPE)")
    }

    /**
     * (ii) The desktop names ALL SEVEN outcomes and carries NO `else`.
     *
     * Named against [io.payanam.shared.updates.UpdateOutcome.entries] rather than a hardcoded list, so
     * the assertion is exact in both directions: a mapping that stops naming a variant fails, and so does
     * the enum gaining one the desktop has not been taught. The `else` half is the part the compiler does
     * NOT enforce - an exhaustive `when` may still carry a default branch, and that branch is where a
     * variant added in six months would land silently, mapping to whatever the catch-all returns.
     */
    @Test
    fun theDesktopMapping_namesEveryOutcome_andHasNoCatchAll() {
        val branches = desktopOutcomeWhenBody()

        val named =
            outcomeBranch
                .findAll(branches)
                .map { it.groupValues[1] }
                .toList()
        assertThat(named.toSet()).isEqualTo(UpdateOutcome.entries.map { it.name }.toSet())
        assertThat(UpdateOutcome.entries).hasSize(SEVEN)
        assertThat(branches).doesNotContain("else")
    }

    /**
     * (iii) The two platforms ask DIFFERENT channels on purpose, and each says why at its own site.
     *
     * The difference is the point rather than a defect: Android defaults to its rescue channel because its
     * pre-unlock hatch is a dev-track feature, while the desktop has no stored channel to read. Asserted
     * as code, because a difference that only existed in a comment would be the platforms agreeing in
     * fact while claiming otherwise in prose.
     *
     * AND WHERE THE DESKTOP'S OWN REASONING LIVES: the "no update-channel row and writes none" explanation
     * sits beside the channel value the desktop REPORTS to shared settings state, in
     * [DESKTOP_SETTINGS_SOURCE], not in the port file this class otherwise reads. The port file explains
     * that it asks "this platform's channel" without saying which one is chosen or why. So the
     * explanation is asserted where it is written rather than where it would have been convenient, and
     * asserting it in both files is deliberately not done - a test that demanded prose in a file that
     * honestly does not carry any would push the next change to duplicate an explanation instead of
     * putting it where it belongs.
     */
    @Test
    fun theChannelsDifferDeliberately_andEachPlatformSaysWhy() {
        assertThat(strippedCode(DESKTOP_SOURCE)).contains("checkForUpdates(build, UpdateChannel.STABLE)")
        assertThat(strippedCode(ANDROID_SOURCE)).contains("private val channel: UpdateChannel = UpdateChannel.DEV")

        // Android's reason, at the parameter it documents.
        assertThat(File(repositoryRoot, ANDROID_SOURCE).readText())
            .contains("the channel this app's")
        assertThat(File(repositoryRoot, ANDROID_SOURCE).readText())
            .contains("pre-unlock update hatch already treats as its rescue channel")

        // The desktop's reason, at the channel the desktop reports rather than the one it asks with.
        assertThat(File(repositoryRoot, DESKTOP_SETTINGS_SOURCE).readText())
            .contains("the desktop has no update-channel row and writes none")
        assertThat(File(repositoryRoot, DESKTOP_SETTINGS_SOURCE).readText())
            .contains("It is a named answer, not a copy of Android")
    }

    /**
     * (iv) `installable` differs because the platforms do, and each side documents its own half.
     *
     * A constant would be wrong on both ends: `true` on the desktop would advertise an artifact its
     * packager owns, and a store lookup on a platform with no installer would be a question about a
     * capability that does not exist. Asserted as code on both sides for the same reason the channel
     * difference is - a documented difference that is not in the code is not a difference.
     */
    @Test
    fun installableDiffers_becauseThePlatformsDo() {
        assertThat(strippedCode(DESKTOP_SOURCE)).contains("installable = false")
        assertThat(strippedCode(ANDROID_SOURCE)).contains("installable = !isStoreManaged")

        // The desktop states the platform reason and cites the shared type's own wording for it.
        val desktop = File(repositoryRoot, DESKTOP_SOURCE).readText()
        assertThat(desktop).contains("WHY false AND NOT A STORE CHECK")
        assertThat(desktop).contains("the NORMAL desktop case")
        assertThat(File(repositoryRoot, SHARED_APK_SOURCE).readText())
            .contains("which is the NORMAL case on a desktop")
    }

    /**
     * The body of the desktop's `when (result.outcome)`, brace-matched from the branch list.
     *
     * Scoped to that `when` rather than the whole file, because the desktop file legitimately has other
     * `when`s and other `else` branches (the download poll's terminal-state switch) that this rule does
     * not govern. Asserting on the file would have made this test fail for the wrong reason the moment
     * either implementation grew an unrelated conditional.
     */
    private fun desktopOutcomeWhenBody(): String {
        val source = strippedCode(DESKTOP_SOURCE)
        val branch = outcomeWhen.find(source) ?: error("No `when (result.outcome)` in $DESKTOP_SOURCE")
        return bracedBodyAt(source, branch.range.last)
            ?: error("The `when (result.outcome)` in $DESKTOP_SOURCE does not balance")
    }

    /** [relativePath]'s source with every comment blanked out, so prose cannot satisfy a code claim. */
    private fun strippedCode(relativePath: String): String = stripComments(File(repositoryRoot, relativePath).readText())

    /**
     * The text inside the braces starting at or after [braceStart].
     *
     * Null rather than a best guess when the braces do not balance: that means the shape is not what this
     * check understands, and reporting it as "no `else` found" would be the one failure a guard must not
     * have - so the caller turns null into a failure instead.
     */
    private fun bracedBodyAt(
        source: String,
        braceStart: Int,
    ): String? {
        val open = source.indexOf('{', braceStart)
        if (open < 0) return null
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        return null
    }

    /**
     * The repository root: the nearest ancestor of the working directory holding `settings.gradle.kts`.
     *
     * Discovered rather than hardcoded, because a scan pointed at the wrong directory passes vacuously:
     * it finds no sources and reports nothing. A check that can be green by looking in the wrong place
     * is not a check.
     */
    private val repositoryRoot: File by lazy {
        var candidate = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error(
                "Could not find settings.gradle.kts above ${System.getProperty("user.dir")}",
            )
        }
        candidate
    }

    /**
     * Replaces comment text with spaces, keeping every line's length and numbering.
     *
     * Whitespace rather than removal, so a violation is still reported at the line the reader would find
     * it on. Block comments are handled first, and the line-comment rule stops at end-of-line so a `//`
     * inside a string literal cannot truncate real code after it.
     */
    private fun stripComments(source: String): String =
        source
            .replace(blockComment) { match -> match.value.blankOutKeepingNewlines() }
            .replace(lineComment) { match -> match.value.blankOutKeepingNewlines() }

    /**
     * Replaces [this] with spaces, keeping every newline so line numbers do not move.
     *
     * The newline preservation is the whole reason this is a function and not `replace(regex, " ")`: a
     * block comment replaced by a single space collapses the lines it spanned, so every violation after
     * it would be reported at a line number the reader cannot find it on.
     */
    private fun String.blankOutKeepingNewlines(): String = map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    private val lineComment = Regex("""//[^\n]*""")

    /** The desktop's outcome mapping: a `when` over the shared result's outcome. */
    private val outcomeWhen = Regex("""when\s*\(\s*result\.outcome\s*\)""")

    /** One branch label naming a shared outcome variant. */
    private val outcomeBranch = Regex("""UpdateOutcome\s*\.\s*(\w+)""")

    private companion object {
        const val ANDROID_SOURCE =
            "app/src/main/kotlin/io/payanam/feature/settings/AndroidApkUpdateSource.kt"

        const val DESKTOP_SOURCE = "desktop/src/main/kotlin/io/payanam/desktop/port/DesktopUnsupportedPorts.kt"

        /** Where the desktop states WHICH channel it reports and why, as distinct from asking with it. */
        const val DESKTOP_SETTINGS_SOURCE =
            "desktop/src/main/kotlin/io/payanam/desktop/database/DesktopSettingsCollaborators.kt"

        /** The shared type whose own documentation is half of what makes `installable = false` correct. */
        const val SHARED_APK_SOURCE = "core/shared/src/main/kotlin/io/payanam/shared/platform/ApkUpdateSource.kt"

        /** [UpdateOutcome.entries]'s current size, asserted so the mapping above cannot be a subset. */
        const val SEVEN = 7
    }
}
