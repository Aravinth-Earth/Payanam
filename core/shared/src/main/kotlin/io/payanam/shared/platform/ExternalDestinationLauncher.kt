//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import io.payanam.shared.logging.UnifiedLogger

/**
 * A place the user has to LEAVE the app to reach.
 *
 * [DocumentSource] covers the half of the picker story that is about handing the app a file; this covers
 * the half that is about handing the user to something else, and the two are genuinely different
 * problems. Picking a file has an answer on both platforms (`DocumentSource.requestSource`), so it
 * needed no new contract. Sending someone to an OS settings screen does not: `Intent`, a `Context` and
 * `startActivity` are the only way Android has of doing it, and no shared signature can name any of
 * the three.
 *
 * WHY NAMED BY INTENT: the destinations are listed as what the user is trying to DO, not as the actions
 * Android happens to use. `ACTION_BIOMETRIC_ENROLL` on API 30 and `ACTION_FINGERPRINT_ENROLL` on 28 are
 * two constants for one destination, and the desktop has no constant for it at all — so a shared layer
 * that enumerated the intents would be writing an Android API into shared code, which is the exact leak
 * this contract exists to stop.
 */
sealed interface ExternalDestination {
    /**
     * The OS screen where a biometric gets enrolled.
     *
     * Its own case because the user's fix for it is unlike every other destination's: there is nothing to
     * retry and no file to choose, only a sensor that has to be registered with the device first.
     */
    data object BiometricEnrollment : ExternalDestination

    /** The OS screen holding security settings generally — the fallback when [BiometricEnrollment] cannot be shown. */
    data object SystemSecuritySettings : ExternalDestination

    /**
     * An arbitrary web page (a project's homepage, an F-Droid listing).
     *
     * The URL is validated by [ExternalDestinationPolicy], not trusted from the caller: this is the one
     * destination whose argument is caller-supplied text, so it is the one that can be malformed, and a
     * contract that forwarded it unchecked would hand every shared screen a way to launch nonsense.
     */
    data class Url(
        val url: String,
    ) : ExternalDestination
}

/**
 * What actually happened when a destination was opened — never an assumed success.
 *
 * A `Unit`-returning `open` would force every caller to guess, and the guess that reads most naturally
 * ("it launched") is exactly the one that reports success for a screen that never opened. Each platform
 * answers for itself.
 */
sealed interface ExternalLaunch {
    /** The platform handed the destination to whatever handles it. */
    data object Launched : ExternalLaunch

    /**
     * Nothing on this platform could open it.
     *
     * Distinct from a refused call: nothing was attempted because there is no handler to attempt it with.
     * A caller that swallows this is the defect this member exists to make visible.
     */
    data object NoHandler : ExternalLaunch

    /** The destination itself was not usable — a malformed [ExternalDestination.Url]. */
    data object Malformed : ExternalLaunch
}

/**
 * Opening a destination outside the app, as each platform can do it.
 *
 * The dependency direction is one-way and this interface sits at the bottom of it: `:core:ui` depends on
 * `:core:presentation`, which depends on `:core:shared`, and a shared module can never call into an app
 * module — so the capability is declared here and each platform's entry point installs an implementation.
 * No platform type appears in any signature below, which is what lets the same interface be implemented
 * by an `Activity` and by AWT.
 */
interface ExternalDestinationLauncher {
    /**
     * Whether this platform has somewhere to send the user for [destination].
     *
     * A PER-DESTINATION question, not one flag for the whole interface, and the reason is that the two
     * kinds genuinely part company. A desktop can open a URL in the user's browser every bit as well as a
     * phone can, while the biometric-enrollment and security-settings screens have no equivalent on a
     * generic Linux desktop at all — so a single `state` would force one of those two truths to be a
     * lie, and the UI could no longer tell "absent here" from "not implemented".
     *
     * Safe to call from composition, and must never prompt: it is a question about the platform.
     */
    fun stateOf(destination: ExternalDestination): SupportState

    /**
     * Opens [destination], reporting what happened.
     *
     * Never throws for a destination this platform cannot open — that is [ExternalLaunch.NoHandler], and an
     * implementation that threw would crash a settings screen over a link the user tapped.
     */
    fun open(destination: ExternalDestination): ExternalLaunch
}

/**
 * The rules a caller would otherwise write once per screen: which URL is safe to launch, and what to do
 * when the specific destination the user needs has no handler.
 *
 * In shared code rather than in each adapter because BOTH halves are decisions the user experiences, and
 * an adapter that decided them privately would decide them differently per platform — which is the same
 * drift the rest of the capability contract exists to prevent.
 */
object ExternalDestinationPolicy {
    /**
     * Schemes [ExternalDestination.Url] may use.
     *
     * A web page or nothing else: `http` and `https` are the two a link in a settings screen can mean,
     * and every other scheme is a request to run something (`file:`, `content:`, an intent scheme) that a
     * caller-supplied string should never be able to make. Rejecting rather than sanitising, because the
     * correct destination of a link is never a repaired version of a broken one.
     */
    private val ALLOWED_URL_SCHEMES = setOf("http", "https")

    /** Whether [url] may be handed to [ExternalDestinationLauncher.open]. */
    fun isLaunchableUrl(url: String): Boolean {
        val schemeEnd = url.indexOf(':')
        // No scheme means a bare host or a relative string; nothing to hand a browser, so it is rejected.
        if (schemeEnd <= 0) return false
        // Case-folded, because a scheme is case-insensitive by RFC 3986 and "HTTPS://" is the same scheme.
        return url.substring(0, schemeEnd).lowercase() in ALLOWED_URL_SCHEMES
    }

    /**
     * Enrols a biometric, falling back to the general security screen when this platform has no
     * enrolment screen.
     *
     * The fallback is a POLICY about what the user was trying to do — reach a screen where they can turn
     * biometrics on — so it lives here where both platforms read the same one, rather than inside Android's
     * adapter where it would be invisible to every other platform that ever needs the same fallback.
     *
     * Reports the outcome of the FIRST destination that was actually launched, and [ExternalLaunch.NoHandler]
     * only when neither was: reporting the fallback's success is what lets the user learn they were sent
     * somewhere less specific than they asked for.
     */
    fun openBiometricEnrollment(
        launcher: ExternalDestinationLauncher,
        logger: UnifiedLogger,
    ): ExternalLaunch {
        if (launcher.stateOf(ExternalDestination.BiometricEnrollment) == SupportState.SUPPORTED) {
            val direct = launcher.open(ExternalDestination.BiometricEnrollment)
            if (direct == ExternalLaunch.Launched) return direct
            logger.w(
                "ExternalDestinationPolicy.openBiometricEnrollment",
                "Enrolment screen could not be launched; falling back to general security settings",
                mapOf("primaryOutcome" to direct.toString()),
            )
        }
        return launcher.open(ExternalDestination.SystemSecuritySettings)
    }
}

/**
 * The launcher for a platform that can open none of its destinations.
 *
 * An implementation that exists so a platform without the capability still satisfies the contract and
 * reports honestly, rather than the caller needing to know which platforms those are. It returns
 * [ExternalLaunch.NoHandler] from [open] instead of pretending to work: the alternative — a launcher that
 * reported success — would let a control ship that reports having opened a settings screen while nothing
 * happened, and the user would be left looking at a screen that changed for no reason.
 */
object UnavailableExternalDestinationLauncher : ExternalDestinationLauncher {
    override fun stateOf(destination: ExternalDestination): SupportState = SupportState.NOT_APPLICABLE

    override fun open(destination: ExternalDestination): ExternalLaunch = ExternalLaunch.NoHandler
}
