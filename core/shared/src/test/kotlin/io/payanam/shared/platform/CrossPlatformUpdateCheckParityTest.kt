//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.updates.UpdateOutcome
import org.junit.Test
import java.io.File

/**
 * Pins the update-check parity this module's gate is written about: that Android's and the desktop's
 * sources route one shared [io.payanam.shared.updates.UpdateOutcome] through the SAME shared checker, are
 * asked with the SAME input (the version code this install was stamped with), map all six outcomes without
 * a catch-all, and differ in `installable` because the platforms do rather than by accident. It reads the
 * two production sources off disk instead of instantiating either implementation because no module can see
 * both: `AndroidApkUpdateSource` lives in `:app` and needs Robolectric to construct, and
 * `DesktopUnsupportedPorts` is `internal` to `:desktop`, while `:core:shared` depends on NEITHER - so a
 * test here that called either class would not compile, and a test in `:app` or `:desktop` could only
 * ever drive one platform. Nothing can therefore drive both implementations over one release state at
 * runtime; reading both sources is the only place in this build where the two answers can be compared at
 * all, which is why the gate exists. Assertions run against comment-stripped text wherever they claim
 * something is CODE, so a line of prose quoting a rule cannot satisfy a rule, and the comment-stripping
 * keeps line numbers true for the failures this reports.
 *
 * ITS LIMITS, stated rather than implied. It is a TEXT check: a mapping assembled elsewhere, an outcome
 * routed through a helper this does not read, or a variant added to [io.payanam.shared.updates.UpdateOutcome]
 * and handled correctly somewhere unseen would all read as violations, not as behaviour - this pins
 * SHAPE, not runtime answers. One thing is already compiler-enforced and is pinned here only so the pin is
 * visible: each platform's `when` is a statement over an enum in this same module, so Kotlin already
 * rejects a non-exhaustive branch list, and what this adds is the `else` guard - an `else` IS legal and
 * would quietly absorb a variant added later.
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
        listOf(ANDROID_SOURCE, DESKTOP_SOURCE, SHARED_APK_SOURCE, SHARED_STATE_SOURCE).forEach { path ->
            val source = File(repositoryRoot, path)
            assertThat(source.exists()).isTrue()
            assertThat(source.readText()).isNotEmpty()
        }
    }

    /**
     * (i) Both platforms ask the SAME shared checker with the SAME first argument, the version code this
     * install runs, so one release list has one answer rather than two independent implementations.
     */
    @Test
    fun bothPlatforms_askTheSharedChecker_withTheInstalledVersionCode() {
        assertThat(strippedCode(ANDROID_SOURCE)).contains("UpdateChecker.check(")
        assertThat(strippedCode(DESKTOP_SOURCE)).contains("UpdateChecker.check(")

        assertThat(strippedCode(ANDROID_SOURCE)).contains("UpdateChecker.check(installedVersionCode,")
        assertThat(strippedCode(DESKTOP_SOURCE)).contains("UpdateChecker.check(installedVersionCode,")

        // And the number handed to it is the one the build tooling stamped, not a constant.
        assertThat(strippedCode(ANDROID_SOURCE))
            .contains("override val currentBuildNumber: Int? = BuildConfig.VERSION_CODE")
        assertThat(strippedCode(DESKTOP_SOURCE))
            .contains("override val currentBuildNumber: Int? = DesktopBuildInfo.VERSION_CODE")
    }

    /**
     * (ii) Both platforms speak the shared result type: a platform that grew a local shape would have to
     * stop importing the shared one, which fails here.
     */
    @Test
    fun bothPlatforms_readTheSharedResultType() {
        assertThat(strippedCode(ANDROID_SOURCE)).contains("import io.payanam.shared.updates.UpdateCheckResult")
        assertThat(strippedCode(DESKTOP_SOURCE)).contains("import io.payanam.shared.updates.UpdateCheckResult")
        assertThat(strippedCode(ANDROID_SOURCE)).contains("import io.payanam.shared.updates.UpdateOutcome")
        assertThat(strippedCode(DESKTOP_SOURCE)).contains("import io.payanam.shared.updates.UpdateOutcome")
    }

    /**
     * (iii) Both platforms name ALL SIX outcomes and carry NO `else`.
     *
     * Named against [io.payanam.shared.updates.UpdateOutcome.entries] rather than a hardcoded list, so the
     * assertion is exact in both directions: a mapping that stops naming a variant fails, and so does the
     * enum gaining one a platform has not been taught. The `else` half is the part the compiler does NOT
     * enforce - an exhaustive `when` may still carry a default branch, and that branch is where a variant
     * added in six months would land silently, mapping to whatever the catch-all returns.
     */
    @Test
    fun bothPlatforms_nameEveryOutcome_andHaveNoCatchAll() {
        listOf(ANDROID_SOURCE, DESKTOP_SOURCE).forEach { path ->
            val branches = outcomeWhenBody(path)

            val named = outcomeBranch.findAll(branches).map { it.groupValues[1] }.toSet()
            assertThat(named).isEqualTo(UpdateOutcome.entries.map { it.name }.toSet())
            assertThat(branches).doesNotContain("else")
        }
        assertThat(UpdateOutcome.entries).hasSize(SIX)
    }

    /**
     * (iv) The fail-closed rule is the same on both platforms: exactly ONE outcome is ever reported as
     * `UpToDate`, and it is `UP_TO_DATE` — every other outcome means the list was not read to a conclusion.
     */
    @Test
    fun bothPlatforms_reportUpToDate_fromUpToDateOnly() {
        listOf(ANDROID_SOURCE, DESKTOP_SOURCE).forEach { path ->
            val branches = outcomeWhenBody(path)

            assertThat(upToDateClaim.findAll(branches).count()).isEqualTo(1)
            assertThat(upToDateFromUpToDate.find(branches)).isNotNull()
        }
    }

    /**
     * (v) `installable` differs because the platforms do, and each side documents its own half.
     *
     * A constant would be wrong on both ends: `true` on the desktop would advertise an artifact its
     * packager owns, and a store lookup on a platform with no installer would be a question about a
     * capability that does not exist. Asserted as code on both sides, because a documented difference that
     * is not in the code is not a difference.
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
     * (vi) Neither platform asks a channel, and neither reports one: a channel reintroduced in the shared
     * state would be rendered by both platforms even if neither port ever filled it.
     */
    @Test
    fun neitherPlatform_asksForAChannel() {
        assertThat(strippedCode(ANDROID_SOURCE)).doesNotContain("UpdateChannel")
        assertThat(strippedCode(DESKTOP_SOURCE)).doesNotContain("UpdateChannel")
        assertThat(strippedCode(SHARED_STATE_SOURCE)).doesNotContain("updateChannel")
    }

    /**
     * The body of a platform's `when` over the shared outcome, brace-matched from the branch list.
     *
     * Scoped to that `when` rather than the whole file, because the desktop file legitimately has other
     * `when`s and other `else` branches (the download poll's terminal-state switch) that this rule does
     * not govern. Asserting on the file would have made this test fail for the wrong reason the moment
     * either implementation grew an unrelated conditional.
     */
    private fun outcomeWhenBody(relativePath: String): String {
        val source = strippedCode(relativePath)
        val branch = outcomeWhen.find(source) ?: error("No `when (... .outcome)` in $relativePath")
        return bracedBodyAt(source, branch.range.last)
            ?: error("The `when` over the outcome in $relativePath does not balance")
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

    /** A platform's outcome mapping: a `when` over the shared result's outcome, with or without the receiver. */
    private val outcomeWhen = Regex("""when\s*\(\s*(?:result\.)?outcome\s*\)""")

    /** One branch label naming a shared outcome variant. */
    private val outcomeBranch = Regex("""UpdateOutcome\s*\.\s*(\w+)""")

    /** The `UpToDate` claim, however a branch body wraps it. */
    private val upToDateClaim = Regex("""UpdateAvailability\s*\.\s*UpToDate""")

    /** The only label allowed to produce that claim. */
    private val upToDateFromUpToDate =
        Regex("""UpdateOutcome\s*\.\s*UP_TO_DATE\s*->\s*\{?\s*UpdateAvailability\s*\.\s*UpToDate""")

    private companion object {
        const val ANDROID_SOURCE =
            "app/src/main/kotlin/io/payanam/feature/settings/AndroidApkUpdateSource.kt"

        const val DESKTOP_SOURCE = "desktop/src/main/kotlin/io/payanam/desktop/port/DesktopUnsupportedPorts.kt"

        /** The shared type whose own documentation is half of what makes `installable = false` correct. */
        const val SHARED_APK_SOURCE = "core/shared/src/main/kotlin/io/payanam/shared/platform/ApkUpdateSource.kt"

        /** The state both platforms render: a channel reintroduced here would be shown by both. */
        const val SHARED_STATE_SOURCE =
            "core/presentation/src/main/kotlin/io/payanam/presentation/settings/SettingsUiState.kt"

        /** [UpdateOutcome.entries]'s current size, asserted so the mapping above cannot be a subset. */
        const val SIX = 6
    }
}
