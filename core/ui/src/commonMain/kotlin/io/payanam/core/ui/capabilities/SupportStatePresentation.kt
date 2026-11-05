//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.capabilities

import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.platform.SupportState

/**
 * How shared UI renders one capability state (the capability-contract proposal's state table), as a value
 * both platforms derive from the same rule.
 *
 * WHY THIS EXISTS:
 *
 * `SupportState` says what a platform DELIVERS; it says nothing about what a screen shows. Without one
 * function turning state into presentation, every screen would re-decide - and drift on - the same four
 * questions (disabled or absent, with which copy), and the rule that `NOT_SURFACED` and `DECLINES` render
 * IDENTICALLY would live only in review comments.
 *
 * The copy carries the reason: `NOT_SURFACED` and `DECLINES` share one reason string by design (shared UI
 * must not branch between them; the distinction is for logs and tests), `IN_PROCESS_ONLY` carries its
 * qualifier, and `NOT_APPLICABLE` renders NOTHING - the platform has no such concept, so there is no
 * control to explain. A rendering surface attaches [DisabledWithReason.reason] inside the control's
 * semantics (`stateDescription`), never as a sibling text outside it.
 */
sealed interface SupportStatePresentation {
    /** `SUPPORTED` - the control is enabled. */
    data object Enabled : SupportStatePresentation

    /** `NOT_APPLICABLE` - the platform has no such concept; the control is absent, not disabled. */
    data object Absent : SupportStatePresentation

    /** `IN_PROCESS_ONLY` - enabled, with the qualifier shown beside the control. */
    data class EnabledWithQualifier(
        val qualifier: String,
    ) : SupportStatePresentation

    /** `NOT_SURFACED` / `DECLINES` - disabled, its reason attached in the control's semantics. */
    data class DisabledWithReason(
        val reason: String,
    ) : SupportStatePresentation
}

/**
 * The presentation for this state, written as an expression with no `else` so that a state added later is
 * a compile error here rather than a silently unrendered capability.
 */
fun SupportState.presentation(strings: UiStrings): SupportStatePresentation =
    when (this) {
        SupportState.SUPPORTED -> {
            SupportStatePresentation.Enabled
        }

        SupportState.NOT_SURFACED -> {
            SupportStatePresentation.DisabledWithReason(strings.locCapabilityUnavailableReason)
        }

        SupportState.NOT_APPLICABLE -> {
            SupportStatePresentation.Absent
        }

        SupportState.IN_PROCESS_ONLY -> {
            SupportStatePresentation.EnabledWithQualifier(strings.locCapabilityInProcessQualifier)
        }

        SupportState.DECLINES -> {
            SupportStatePresentation.DisabledWithReason(strings.locCapabilityUnavailableReason)
        }
    }
