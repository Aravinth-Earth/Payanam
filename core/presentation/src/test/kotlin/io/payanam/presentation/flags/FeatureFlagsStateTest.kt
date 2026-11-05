//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.flags

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural lock for the shared feature-flag state and its process-wide holder.
 *
 * The defaults are asserted in BOTH directions on purpose: a flag that ships enabled by accident turns a
 * surface on for a build that was configured without it, and one that ships disabled by accident makes a
 * working feature disappear. Neither is visible in a diff of the defaults, so they are pinned here.
 */
class FeatureFlagsStateTest {
    @Before
    fun setUp() {
        PlatformFeatureFlags.resetForTests()
    }

    @After
    fun tearDown() {
        PlatformFeatureFlags.resetForTests()
    }

    @Test
    fun `defaults are the full feature set`() {
        val state = FeatureFlagsState()

        assertFalse(state.minimalModeEnabled)
        assertTrue(state.recurringTasksEnabled)
        assertTrue(state.scoringEnabled)
        assertTrue(state.remindersEnabled)
        assertTrue(state.tagsEnabled)
        assertTrue(state.plansCtaEnabled)
    }

    @Test
    fun `the un-installed holder reads the same defaults`() {
        assertEquals(FeatureFlagsState(), PlatformFeatureFlags.current)
    }

    @Test
    fun `install is process-wide and replaces every flag at once`() {
        val installed =
            FeatureFlagsState(
                minimalModeEnabled = true,
                recurringTasksEnabled = false,
                scoringEnabled = false,
                remindersEnabled = false,
                plansCtaEnabled = false,
            )

        PlatformFeatureFlags.install(installed)

        assertEquals(installed, PlatformFeatureFlags.current)
        assertTrue(PlatformFeatureFlags.current.minimalModeEnabled)
        assertFalse(PlatformFeatureFlags.current.scoringEnabled)
        assertFalse(PlatformFeatureFlags.current.remindersEnabled)
        assertFalse(PlatformFeatureFlags.current.plansCtaEnabled)
    }

    @Test
    fun `resetForTests restores the default rather than the last installed state`() {
        PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))

        PlatformFeatureFlags.resetForTests()

        assertEquals(FeatureFlagsState(), PlatformFeatureFlags.current)
        assertFalse(PlatformFeatureFlags.current.minimalModeEnabled)
    }

    @Test
    fun `a copy with one flag changed leaves the other flags untouched`() {
        val base = FeatureFlagsState()

        val flipped = base.copy(remindersEnabled = false)

        assertFalse(flipped.remindersEnabled)
        assertTrue(flipped.scoringEnabled)
        assertTrue(base.remindersEnabled)
    }

    @Test
    fun `turning the day-plan affordance off leaves every other flag alone`() {
        // The Time screen reads this flag to decide whether to draw the day-plan affordances, so a copy
        // that turned it off must not also disturb the flags the other Time branches are gated on.
        val base = FeatureFlagsState()

        val flipped = base.copy(plansCtaEnabled = false)

        assertFalse(flipped.plansCtaEnabled)
        assertTrue(flipped.minimalModeEnabled.not())
        assertTrue(flipped.recurringTasksEnabled)
        assertTrue(flipped.scoringEnabled)
        assertTrue(base.plansCtaEnabled)
    }

    @Test
    fun `install carries the day-plan affordance, so a build that compiles it off is respected`() {
        // Android compiles PLANS_CTA_ENABLED from build config, so the holder has to carry a false
        // through rather than force the default - otherwise a minimal build would still draw the
        // affordances it was compiled without.
        PlatformFeatureFlags.install(FeatureFlagsState(plansCtaEnabled = false))

        assertFalse(PlatformFeatureFlags.current.plansCtaEnabled)

        PlatformFeatureFlags.resetForTests()

        assertTrue(PlatformFeatureFlags.current.plansCtaEnabled)
    }
}
