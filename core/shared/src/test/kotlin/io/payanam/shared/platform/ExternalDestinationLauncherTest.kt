//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.UnifiedLogger
import org.junit.Test

/**
 * Tests for the external-destination launcher contract and its policy.
 *
 * Two properties carry the contract, and each is asserted in BOTH directions because either one asserted
 * once would pass an implementation that is wrong the other way:
 *
 *  - [ExternalDestinationLauncher.stateOf] is per-destination, not per-platform. A launcher that answered
 *    [SupportState.SUPPORTED] for everything would satisfy a test that only checks URLs open, and a
 *    launcher that answered [SupportState.NOT_APPLICABLE] for everything would satisfy a test that only
 *    checks the honest refusal. Both wrong directions are asserted here.
 *  - [ExternalDestinationPolicy.openBiometricEnrollment] reports the outcome the USER got, so the fallback's
 *    success cannot be reported as the specific screen's success.
 */
class ExternalDestinationLauncherTest {
    private class RecordingLogger : UnifiedLogger {
        val warnings = mutableListOf<Pair<String, String>>()

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            warnings += source to message
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit
    }

    /** A launcher that opens only what it was given, so "was it opened" is a real observation. */
    private class FakeLauncher(
        private val supported: Set<ExternalDestination>,
        private val outcomes: Map<ExternalDestination, ExternalLaunch> = emptyMap(),
    ) : ExternalDestinationLauncher {
        val opened = mutableListOf<ExternalDestination>()

        override fun stateOf(destination: ExternalDestination): SupportState =
            if (destination in supported) SupportState.SUPPORTED else SupportState.NOT_APPLICABLE

        override fun open(destination: ExternalDestination): ExternalLaunch {
            opened += destination
            return outcomes[destination]
                ?: if (destination in supported) ExternalLaunch.Launched else ExternalLaunch.NoHandler
        }
    }

    private val enrollment = ExternalDestination.BiometricEnrollment
    private val security = ExternalDestination.SystemSecuritySettings
    private val projectUrl = ExternalDestination.Url("https://github.com/Aravinth-Earth/Payanam")

    // ---- stateOf is per destination, both directions ----

    @Test
    fun `a supported destination is SUPPORTED even when a sibling on the same platform is not`() {
        val launcher = FakeLauncher(supported = setOf(projectUrl))

        assertThat(launcher.stateOf(projectUrl)).isEqualTo(SupportState.SUPPORTED)
        // The two-part claim: opening a URL is not evidence that every destination opens.
        assertThat(launcher.stateOf(enrollment)).isEqualTo(SupportState.NOT_APPLICABLE)
    }

    @Test
    fun `a platform offering nothing must not report SUPPORTED for anything`() {
        val launcher = UnavailableExternalDestinationLauncher

        assertThat(launcher.stateOf(projectUrl)).isEqualTo(SupportState.NOT_APPLICABLE)
        assertThat(launcher.stateOf(enrollment)).isEqualTo(SupportState.NOT_APPLICABLE)
        assertThat(launcher.stateOf(security)).isEqualTo(SupportState.NOT_APPLICABLE)
    }

    @Test
    fun `a launcher that opened nothing reports NoHandler and never Launched`() {
        val launcher = UnavailableExternalDestinationLauncher

        assertThat(launcher.open(projectUrl)).isEqualTo(ExternalLaunch.NoHandler)
        // Guards the defect this contract exists to prevent: a launcher that reports success while
        // doing nothing would let a link ship that silently swallows the user's tap.
        assertThat(launcher.open(projectUrl)).isNotEqualTo(ExternalLaunch.Launched)
    }

    // ---- URL policy, both directions ----

    @Test
    fun `http and https are launchable regardless of scheme case`() {
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("https://example.org/a")).isTrue()
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("http://example.org")).isTrue()
        // Case-folded: a scheme is case-insensitive, so an uppercase one is the same scheme.
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("HTTPS://example.org")).isTrue()
    }

    @Test
    fun `a scheme that would run something local is rejected`() {
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("file:///etc/passwd")).isFalse()
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("content://com.example/db")).isFalse()
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("intent://scan/#Intent;end")).isFalse()
        // And the shape that carries no scheme at all cannot be handed to a browser.
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("example.org")).isFalse()
        assertThat(ExternalDestinationPolicy.isLaunchableUrl("")).isFalse()
    }

    // ---- enrolment fallback reports the outcome the user actually got ----

    @Test
    fun `enrolment opens the enrolment screen when it is supported`() {
        val launcher = FakeLauncher(supported = setOf(enrollment, security))
        val logger = RecordingLogger()

        val outcome = ExternalDestinationPolicy.openBiometricEnrollment(launcher, logger)

        assertThat(outcome).isEqualTo(ExternalLaunch.Launched)
        assertThat(launcher.opened).containsExactly(enrollment)
        assertThat(logger.warnings).isEmpty()
    }

    @Test
    fun `enrolment falls back to security settings and never claims the specific screen opened`() {
        val launcher = FakeLauncher(supported = setOf(security))
        val logger = RecordingLogger()

        val outcome = ExternalDestinationPolicy.openBiometricEnrollment(launcher, logger)

        // The fallback's success is the outcome — claiming Launched-for-enrolment would hide from the
        // user that they landed somewhere less specific than they asked for.
        assertThat(outcome).isEqualTo(ExternalLaunch.Launched)
        assertThat(launcher.opened).containsExactly(security)
        // No warning: a destination this platform never had is a configured fact, not an anomaly, and
        // warning on it would fire on every attempt on a platform that never will have one.
        assertThat(logger.warnings).isEmpty()
    }

    @Test
    fun `enrolment reports NoHandler only when neither destination exists`() {
        val launcher = FakeLauncher(supported = emptySet())
        val logger = RecordingLogger()

        val outcome = ExternalDestinationPolicy.openBiometricEnrollment(launcher, logger)

        assertThat(outcome).isEqualTo(ExternalLaunch.NoHandler)
        assertThat(outcome).isNotEqualTo(ExternalLaunch.Launched)
        assertThat(launcher.opened).containsExactly(security)
    }

    @Test
    fun `a launcher that claims enrolment is supported but then fails still reaches security settings`() {
        // The state and the launch disagree, which is a real Android failure (the enrolment screen
        // missing on a device that reports a sensor), so the fallback must run on the OUTCOME.
        val launcher =
            FakeLauncher(
                supported = setOf(enrollment, security),
                outcomes = mapOf(enrollment to ExternalLaunch.NoHandler),
            )
        val logger = RecordingLogger()

        val outcome = ExternalDestinationPolicy.openBiometricEnrollment(launcher, logger)

        assertThat(outcome).isEqualTo(ExternalLaunch.Launched)
        assertThat(launcher.opened).containsExactly(enrollment, security)
        assertThat(logger.warnings).hasSize(1)
    }
}
