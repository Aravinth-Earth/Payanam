//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the desktop onboarding scaffold's flow logic and its dimension rows.
 *
 * Covers the two things that would break silently: the stage ORDER (a scaffold stage that failed to
 * intercept would drop the user into a passphrase gate with nothing to protect, or straight into the
 * app), and the dimension rows being genuinely derived from the shared catalog rather than a
 * hand-written copy.
 */
class DesktopOnboardingScaffoldTest {
    private fun runtimeState(
        stage: DesktopScaffoldOnboardingStage,
        hasPassphraseConfigured: Boolean = true,
        sessionOpen: Boolean = true,
        databaseLifecycleReady: Boolean = true,
        focusModeOnboardingCompleted: Boolean = true,
        securityStateUnavailable: Boolean = false,
    ) = DesktopStartupRuntimeState(
        hasPassphraseConfigured = hasPassphraseConfigured,
        hasDatabaseArtifacts = databaseLifecycleReady,
        databaseLifecycleReady = databaseLifecycleReady,
        sessionOpen = sessionOpen,
        focusModeOnboardingCompleted = focusModeOnboardingCompleted,
        securityFilePath = "security",
        databaseFilePath = "database",
        scaffoldOnboardingStage = stage,
        securityStateUnavailable = securityStateUnavailable,
    )

    @Test
    fun `intro stage is shown first on a fresh launch`() {
        // A fresh launch means nothing on disk: the Intro stage alone no longer routes to the tour, because
        // a launch that HAS artifacts is a returning one and must not sit through it again.
        val mode =
            resolveDesktopStartupMode(
                runtimeState(DesktopScaffoldOnboardingStage.Intro, databaseLifecycleReady = false),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.OnboardingIntro)
    }

    @Test
    fun `each scaffold stage resolves to its own screen in order`() {
        // Order matters: intro -> choice -> dimensions. Asserted as a sequence so a stage that got
        // reordered is caught, not just a stage that stopped working.
        val sequence =
            listOf(
                DesktopScaffoldOnboardingStage.Intro to DesktopStartupMode.OnboardingIntro,
                // Both of these resolve to the SHARED database-init destination: Android runs the choice and
                // the dimension step inside that screen, so the desktop renders no card of its own for them.
                DesktopScaffoldOnboardingStage.DatabaseChoice to DesktopStartupMode.InitializeDatabase,
                DesktopScaffoldOnboardingStage.DimensionSetup to DesktopStartupMode.InitializeDatabase,
            )

        sequence.forEach { (stage, expected) ->
            // Only the Intro row is a fresh-launch state; the two below it are reached with a database
            // present, and their branches do not read the artifacts at all.
            val freshLaunch = stage == DesktopScaffoldOnboardingStage.Intro
            assertThat(
                resolveDesktopStartupMode(
                    runtimeState(stage, databaseLifecycleReady = !freshLaunch),
                ),
            ).isEqualTo(expected)
        }
    }

    @Test
    fun `finished stage enters the app rather than re-running onboarding`() {
        val mode = resolveDesktopStartupMode(runtimeState(DesktopScaffoldOnboardingStage.Finished))

        assertThat(mode).isEqualTo(DesktopStartupMode.Ready)
    }

    @Test
    fun `finished stage does not bypass the security gates`() {
        // A finished scaffold means "onboarding ran", not "everything is configured". If this ever
        // resolved to Ready, an unconfigured install would open with no passphrase and no database.
        val mode =
            resolveDesktopStartupMode(
                runtimeState(
                    stage = DesktopScaffoldOnboardingStage.Finished,
                    hasPassphraseConfigured = false,
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `bypassed stage leaves the original gate order intact`() {
        val mode =
            resolveDesktopStartupMode(
                runtimeState(
                    stage = DesktopScaffoldOnboardingStage.Bypassed,
                    sessionOpen = false,
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.UnlockPassphrase)
    }

    @Test
    fun `an unreadable security state outranks the scaffold`() {
        // The fail-closed branch must stay first. Resolving to the intro instead would look harmless
        // and would hide an unreadable store behind a friendly welcome screen.
        val mode =
            resolveDesktopStartupMode(
                runtimeState(
                    stage = DesktopScaffoldOnboardingStage.Intro,
                    securityStateUnavailable = true,
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.SecurityStateUnavailable)
    }

    @Test
    fun `dimension rows come from the shared catalog`() {
        val rows = desktopDefaultDimensionInputs()

        // Every row must be traceable to the shared catalog: ids, labels, colours and icon keys are
        // read from it, so a row the catalog does not define means a hand-written copy crept in.
        val catalogById =
            io.payanam.domain.model.DimensionTaxonomyCatalog.entries
                .associateBy { it.id }

        assertThat(rows).isNotEmpty()
        rows.forEach { row ->
            val definition = catalogById[row.id]
            assertThat(definition).isNotNull()
            assertThat(row.label).isEqualTo(definition!!.fallbackLabel)
            assertThat(row.colorHex).isEqualTo(definition.defaultColorHex)
            assertThat(row.iconKey).isEqualTo(definition.defaultIconKey)
        }
    }

    @Test
    fun `dimension rows exclude the unassigned fallback and are sort-ordered`() {
        val rows = desktopDefaultDimensionInputs()

        assertThat(rows.map { it.id }).doesNotContain(UNASSIGNED_DIMENSION_ID)

        val sortOrders =
            rows.map { row ->
                io.payanam.domain.model.DimensionTaxonomyCatalog.entries
                    .first { it.id == row.id }
                    .sortOrder
            }
        assertThat(sortOrders).isInOrder()
    }

    @Test
    fun `dimension rows start enabled so setup is opt-out not opt-in`() {
        // Matches Android's first-run seed: the user turns dimensions OFF rather than hunting for the
        // ones to add.
        assertThat(desktopDefaultDimensionInputs().all { it.isEnabled }).isTrue()
    }

}
