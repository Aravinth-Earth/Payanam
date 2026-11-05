//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.startup.DesktopScaffoldOnboardingStage
import io.payanam.shared.startup.DesktopStartupRuntimeState
import io.payanam.shared.startup.startupGateInputs
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
    @Test
    fun `the scaffold stages are ordered intro, choice, dimensions, finished, bypassed`() {
        // Order matters: a stage that got reordered is caught, not just a stage that stopped working.
        assertThat(DesktopScaffoldOnboardingStage.entries.map { it.name })
            .containsExactly("Intro", "DatabaseChoice", "DimensionSetup", "Finished", "Bypassed")
            .inOrder()
    }

    @Test
    fun `a fresh launch is the only stage that reads the database artifacts`() {
        // A fresh launch means nothing on disk, so the gate is the SHARED database-init screen - the screen
        // that renders the tour as its own first step. The tour is never a gate of its own on either platform,
        // so no launch may raise it.
        val freshLaunch =
            startupGateInputs(
                DesktopStartupRuntimeState(
                    hasPassphraseConfigured = false,
                    hasDatabaseArtifacts = false,
                    databaseLifecycleReady = false,
                    sessionOpen = false,
                    focusModeOnboardingCompleted = false,
                    securityFilePath = "security",
                    databaseFilePath = "database",
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Intro,
                ),
            )

        assertThat(freshLaunch.shouldShowDatabaseInit).isTrue()
        assertThat(freshLaunch.shouldShowOnboardingIntro).isFalse()

        val returningLaunch =
            startupGateInputs(
                DesktopStartupRuntimeState(
                    hasPassphraseConfigured = false,
                    hasDatabaseArtifacts = true,
                    databaseLifecycleReady = false,
                    sessionOpen = false,
                    focusModeOnboardingCompleted = false,
                    securityFilePath = "security",
                    databaseFilePath = "database",
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Intro,
                ),
            )

        assertThat(returningLaunch.shouldShowOnboardingIntro).isFalse()
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
