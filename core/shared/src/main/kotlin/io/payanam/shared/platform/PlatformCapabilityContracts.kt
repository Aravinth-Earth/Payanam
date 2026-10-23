//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

/**
 * What a platform's answer to one capability actually IS — the single vocabulary every seam reports in.
 *
 * One vocabulary rather than a boolean per feature, because the answers are not all the same shape: a
 * control the platform has no concept of is not the same as one that exists but shows nothing yet, and
 * neither is the same as one that works only while the app is open. Shared UI renders each state (absent
 * vs disabled-with-reason vs enabled-with-qualifier) and never branches on which platform it is on.
 */
enum class SupportState {
    /** Implemented and delivered to the user. */
    SUPPORTED,

    /** Works only while the app is open — the desktop's in-process backup timer. */
    IN_PROCESS_ONLY,

    /** Accepted, but nothing is shown to the user yet — the desktop's reminders today. */
    NOT_SURFACED,

    /** The call is logged and does nothing — a platform that refuses on purpose. */
    DECLINES,

    /** The platform has no such concept — a home-screen widget on the desktop. */
    NOT_APPLICABLE,
}

/**
 * What the platform under this app can actually do.
 *
 * A capability object rather than a set of `expect`/`actual` constants, because every one of these is
 * consumed by shared UI that has to RENDER the difference: a disabled-looking control with a note ("your
 * device does not support this") is a different screen from a control that is missing. The shared layer
 * cannot ask `Build.VERSION` or `SystemTray.isSupported`, so the platform answers once and the UI reads it.
 *
 * This is a description, not a promise: [supportsDynamicColor] does not mean the current theme IS dynamic,
 * only that asking for it is meaningful.
 */
data class PlatformCapabilities(
    /** Whether biometric unlock can be offered. Android needs a biometric sensor and Keystore; the desktop has neither. */
    val biometrics: SupportState = SupportState.NOT_APPLICABLE,
    /** Whether the OS can supply a wallpaper-derived colour scheme. Android 12+ can; the desktop uses the app's own palette. */
    val supportsDynamicColor: Boolean = false,
    /** Whether the device can vibrate. */
    val supportsHaptics: Boolean = false,
    /** Whether reminders are actually surfaced to the user. */
    val reminders: SupportState = SupportState.NOT_SURFACED,
    /** Whether backups can run with the app closed. */
    val backupBackground: SupportState = SupportState.IN_PROCESS_ONLY,
) {
    /** Controls that only exist where biometrics work. */
    val offersBiometricControls: Boolean get() = biometrics == SupportState.SUPPORTED

    /** True when at least one capability is not fully supported, so the UI knows to explain itself somewhere. */
    val hasLimitations: Boolean
        get() =
            biometrics != SupportState.SUPPORTED ||
                !supportsDynamicColor ||
                !supportsHaptics ||
                reminders != SupportState.SUPPORTED ||
                backupBackground != SupportState.SUPPORTED
}

/**
 * The debug-logging switch, read by shared code and defaulted by each platform.
 *
 * The DEFAULT is the platform's business and is deliberately not part of this contract: Android defaults to
 * its own build's debug flag, which a released app must not expose, while a desktop build has no equivalent
 * and chooses for itself. Shared code may read the setting and change it; it must not decide what an
 * unconfigured install is.
 */
interface DebugFlags {
    val debugLoggingEnabled: Boolean

    /** Applies a new value. Returns the value in force afterwards. */
    fun setDebugLoggingEnabled(enabled: Boolean): Boolean
}

/** The kinds of touch feedback the shared UI asks for, named by intent rather than by platform constant. */
enum class HapticFeedback {
    /** A control was tapped. */
    TAP,

    /** A selection changed. */
    SELECTION,

    /** A destructive or notable action completed. */
    CONFIRMATION,

    /** An action was rejected. */
    REJECTION,
}

/**
 * Touch feedback, where the device has any.
 *
 * Implementations must treat this as best-effort and never fail: a device without a vibrator, or with
 * haptics disabled system-wide, is a normal configuration, not an error — and a UI that threw because it
 * could not buzz would be worse than one that stays silent.
 */
interface HapticSink {
    /** True when this sink actually does something; a no-op sink is a legitimate implementation. */
    val isActive: Boolean

    /**
     * Requests [feedback]. Best-effort: must never throw for a device that cannot vibrate.
     */
    fun perform(feedback: HapticFeedback)
}

/** A sink for platforms with no vibrator, so call sites never branch. */
object NoOpHapticSink : HapticSink {
    override val isActive: Boolean = false

    override fun perform(feedback: HapticFeedback) = Unit
}
