//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppPreferencesState
import org.junit.Test
import java.io.File

/**
 * The settings destination is handed the shell's preferences as a PARAMETER, not read from the ambient
 * `LocalAppPreferences`.
 *
 * **The defect this rules out, stated as the user sees it.** `AppPreferencesState.isLoading` DEFAULTS TO TRUE,
 * and `SettingsAppearanceSection.kt:62` and `SettingsDefaultLandingSection.kt:81` both early-return on it. A
 * destination that built its own default-constructed state - or read the ambient local before the shell had
 * installed one - would therefore render the settings screen with all thirteen card headers and NOTHING inside
 * the appearance and default-landing sections. No exception, no log line, no empty-state text: two sections
 * that look like the screen simply do not work.
 *
 * **Why a render test cannot hold this, and why both files exist.** `DesktopSettingsDestinationRenderTest`
 * mounts the destination with a state it supplies and asserts the two guarded sections render controls - which
 * fails if the destination ignores its parameter entirely. It cannot fail if the destination reads the
 * ambient value instead, because in that mount both values are the same instance and the tree is identical.
 * This file asserts on the source, which is the only place the difference between the two is visible.
 *
 * The same reasoning makes the two directions load-bearing here: asserting "the source mentions the parameter"
 * alone would pass on a file that also reads `LocalAppPreferences`, and asserting "the source does not mention
 * `LocalAppPreferences`" alone would pass on a file that does not render anything. Both are asserted, and both
 * are scan-guarded so neither can pass because a pattern stopped matching.
 *
 * The working directory is `desktop/` under Gradle, so the repo root is `..`.
 */
class DesktopSettingsPreferencesSourceTest {
    private val repoRoot = File("..").canonicalFile

    private val screensFile =
        File(repoRoot, "desktop/src/main/kotlin/io/payanam/desktop/DesktopPayanamNavHostScreens.kt")

    private val settingsBody = screensFile.readText()

    private companion object {
        private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    }

    @Test
    fun `the settings destination is passed the shell's preferences state`() {
        // The collaborator's field, read in the destination body. This is the value that reaches the shared
        // screen's `prefsState`, and the assertion is that the body reads it from the HOLDER rather than from
        // a source of its own.
        assertThat(settingsBody).contains("settingsState = settingsCollaborators.preferences")

        // And the holder's own field is the shell's published state, which the shell supplies: the mount
        // passes `models.shellRenderState.preferences`, never a freshly constructed state.
        val appFile = File(repoRoot, "desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt").readText()
        assertThat(appFile).contains("preferences = models.shellRenderState.preferences")
    }

    @Test
    fun `no destination in this file reads the ambient preferences`() {
        // The ambient read would be a `LocalAppPreferences.current` READ. Asserting its absence over the file's
        // CODE rather than its raw text is deliberate and load-bearing: this file's own KDoc discusses the trap
        // by name, so a raw-text negative matched the prose that explains it and failed for the wrong reason.
        // Stripping comments leaves only what compiles, which is what the claim is about.
        //
        // Scope is the WHOLE file rather than the settings body, because an ambient read in ANY destination on
        // this platform has the same failure mode - a default-constructed state rendering a real screen at
        // default values - so the guard belongs to the file.
        assertThat(codeOnly(settingsBody)).doesNotContain("LocalAppPreferences")
    }

    /**
     * [source] with every comment removed, so a scan of it sees only what compiles.
     *
     * Hand-rolled rather than pulled from a parser library: the file is Kotlin, the comments are line and block
     * forms, and the desktop module has no parsing dependency to reuse. A KDoc block containing a line-comment
     * marker does not occur in the files this class scans, which is what makes this safe here - a file that grew
     * one would need a real parser rather than a longer regex.
     */
    private fun codeOnly(source: String): String =
        source
            .replace(BLOCK_COMMENT, " ")
            .lines()
            .joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun `the settings destination renders the SHARED screen, not a private one`() {
        // A screen of its own would be invisible to every other assertion in this module - the renderability
        // record only checks that the destination stopped throwing, so a private re-implementation would pass
        // it. Naming the shared entry point pins that the desktop and Android render the same composable.
        assertThat(settingsBody).contains("SettingsScreen(")
        assertThat(settingsBody).doesNotContain("fun desktopSettingsScreen")
    }

    /**
     * The two guards above would all pass vacuously if the patterns stopped matching anything.
     *
     * The failure mode of a source-reading test is a rename in the file under test that leaves the test
     * matching nothing, so every assertion above would pass while asserting nothing. This pins the patterns
     * against the constructs they name, so a rename in either direction fails here instead of silently.
     */
    @Test
    fun `the scanned patterns still match the constructs they name`() {
        val collaboratorsFile =
            File(repoRoot, "desktop/src/main/kotlin/io/payanam/desktop/database/DesktopSettingsCollaborators.kt")
                .readText()

        // The ambient local exists in `:core:ui` and is a `val`, which is what makes `LocalAppPreferences.current`
        // the only way to read it. If it became a function, the negative assertion above would stop being the
        // check it claims to be, so its shape is pinned from the shared side.
        val preferencesFile =
            File(repoRoot, "core/ui/src/commonMain/kotlin/io/payanam/core/ui/preferences/AppPreferencesState.kt")
                .readText()
        assertThat(preferencesFile).contains("val LocalAppPreferences")

        // The field the holder exposes under the name the destination reads it by.
        assertThat(collaboratorsFile).contains("val preferences: AppPreferencesState")

        // And the collaborator type exists where the destination's constructor says it does - the default
        // constructor case in the two gate fixtures supplies it too, so a rename would break all four call
        // sites, and this makes the break land here with a message about the pattern instead.
        assertThat(settingsBody).contains("settingsCollaborators: DesktopSettingsCollaborators")
    }

    /**
     * `isLoading` still defaults to true, which is the fact both files' reasoning rests on.
     *
     * If a future change gave `AppPreferencesState.isLoading` a default of false, the trap these guards exist
     * for would change shape - and a guard that silently stopped protecting anything is worse than none, so the
     * load-bearing constant is asserted here against the real declaration rather than assumed.
     */
    @Test
    fun `isLoading still defaults to true in the shared preferences state`() {
        assertThat(AppPreferencesState().isLoading).isTrue()

        // And the two sections that consume it are still the only guards on it, so a third early-return would
        // make the two guarded assertions in the render test incomplete rather than sufficient.
        val appearance =
            File(repoRoot, "core/ui/src/commonMain/kotlin/io/payanam/core/ui/settings/SettingsAppearanceSection.kt")
                .readText()
        val landing =
            File(repoRoot, "core/ui/src/commonMain/kotlin/io/payanam/core/ui/settings/SettingsDefaultLandingSection.kt")
                .readText()
        assertThat(appearance).contains("if (prefsState.isLoading)")
        assertThat(landing).contains("if (prefsState.isLoading)")
    }
}
