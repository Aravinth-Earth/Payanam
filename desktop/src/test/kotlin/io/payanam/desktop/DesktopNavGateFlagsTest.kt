//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The startup gate is decided once and projected, never decided twice.
 *
 * The desktop resolves which startup surface to show in `resolveDesktopStartupMode`, and the shared graph
 * takes that answer as four booleans. If those booleans were re-derived from
 * [DesktopStartupRuntimeState] at the mount site, the desktop would hold two implementations of one rule
 * - and the failure mode is not a crash but a *disagreement*: the desktop's own gate and the graph
 * rendering different surfaces at the same moment, which is exactly the class of bug this parity work
 * exists to remove.
 *
 * These tests pin the projection. The structural half - that `desktopNavGateFlags` takes a
 * [DesktopStartupMode] and not a runtime state - is enforced by the signature itself, which is why that
 * is the parameter type and not an implementation detail.
 */
class DesktopNavGateFlagsTest {
    @Test
    fun `setup passphrase shows only the setup gate`() {
        val flags = desktopNavGateFlags(DesktopStartupMode.SetupPassphrase)

        assertThat(flags.shouldShowPassphraseSetup).isTrue()
        assertThat(flags.shouldShowPassphraseUnlock).isFalse()
        assertThat(flags.shouldShowDatabaseInit).isFalse()
        assertThat(flags.shouldShowFocusModeOnboarding).isFalse()
    }

    @Test
    fun `unlock shows only the unlock gate`() {
        val flags = desktopNavGateFlags(DesktopStartupMode.UnlockPassphrase)

        assertThat(flags.shouldShowPassphraseUnlock).isTrue()
        assertThat(flags.shouldShowPassphraseSetup).isFalse()
        assertThat(flags.showsNoGate).isFalse()
    }

    @Test
    fun `database init shows only the init gate`() {
        val flags = desktopNavGateFlags(DesktopStartupMode.InitializeDatabase)

        assertThat(flags.shouldShowDatabaseInit).isTrue()
        assertThat(flags.showsNoGate).isFalse()
    }

    @Test
    fun `focus mode onboarding shows only its own gate`() {
        val flags = desktopNavGateFlags(DesktopStartupMode.FocusModeSelection)

        assertThat(flags.shouldShowFocusModeOnboarding).isTrue()
        assertThat(flags.showsNoGate).isFalse()
    }

    @Test
    fun `ready shows no gate, so the graph may be mounted`() {
        assertThat(desktopNavGateFlags(DesktopStartupMode.Ready).showsNoGate).isTrue()
    }

    @Test
    fun `exactly one gate is ever shown, never two`() {
        // A mode that raised two gates at once would put two startup surfaces on screen together. The
        // startup rule is a chain, so the projection must be a chain too.
        for (mode in DesktopStartupMode.entries) {
            val flags = desktopNavGateFlags(mode)
            val raised =
                listOf(
                    flags.shouldShowPassphraseSetup,
                    flags.shouldShowPassphraseUnlock,
                    flags.shouldShowDatabaseInit,
                    flags.shouldShowFocusModeOnboarding,
                ).count { it }

            assertThat(raised).isAtMost(1)
        }
    }

    @Test
    fun `the desktop-only scaffold modes project to no graph gate`() {
        // These have no graph equivalent: DesktopStartupGateSurface renders them. They must not be
        // mistaken for Ready, though - that is what `showsNoGate` is for, and the mount checks the mode
        // itself rather than this flag.
        //
        // There is no desktop-scaffold dimension mode in this list because the mode no longer exists:
        // the shared database-init screen runs that step, and the desktop's own card was the only thing
        // that could render it.
        for (
        mode in
        listOf(
            DesktopStartupMode.OnboardingIntro,
            DesktopStartupMode.OnboardingDatabaseChoice,
            DesktopStartupMode.SecurityStateUnavailable,
        )
        ) {
            assertThat(desktopNavGateFlags(mode).showsNoGate).isTrue()
        }
    }

    @Test
    fun `the four gated modes are exactly the modes the startup chain gates on`() {
        // Pins the projection against the enum rather than against itself: if a mode is added to
        // DesktopStartupMode and lands in the "no gate" group, this fails. That is the day the compiler
        // stops helping - a `when` with an `else` would compile silently and show no gate for a mode that
        // was supposed to gate.
        val gated =
            DesktopStartupMode.entries.filter { !desktopNavGateFlags(it).showsNoGate }.toSet()

        assertThat(gated).isEqualTo(
            setOf(
                DesktopStartupMode.SetupPassphrase,
                DesktopStartupMode.UnlockPassphrase,
                DesktopStartupMode.InitializeDatabase,
                DesktopStartupMode.FocusModeSelection,
            ),
        )
    }
}
