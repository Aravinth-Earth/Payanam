//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import kotlin.test.assertTrue

/**
 * Layout and geometry assertions for a rendered shared screen.
 *
 * These are the checks a screen-render test can make that a naming/behaviour test cannot: that a control
 * the user is asked to press is actually DRAWN with an area, and that two controls a user is asked to press
 * do not sit on top of each other. They are deliberately measured from the rendered semantics tree, so they
 * hold for whatever the screen composes rather than for what the source appears to say.
 *
 * WHY THEY LIVE HERE. They are called from the same screen sweep that already renders fourteen shared
 * screens on the JVM target ([InteractiveControlNamingTest]), so the shared UI gets them on every platform
 * the shared composables ship to (Android and Linux desktop) without a second, platform-specific copy. This
 * is the least-repetition home: one sweep, one set of geometry assertions.
 *
 * WHAT IS DELIBERATELY NOT ASSERTED, and why:
 *  - A blanket 48dp minimum touch target. Material's own chips, tabs and small icon buttons are legitimately
 *    shorter than 48dp, so a blanket floor would fail correct screens. The touch-target sweep belongs to the
 *    Android device tier, where the platform's own minimum can be checked against real touch input.
 *  - "Every control is inside the window". A scrollable screen composes controls below the fold, so a control
 *    outside the window bounds is normal, not a defect.
 */
internal object UiQualityAssertions {

    /**
     * Every interactive node must be drawn with a non-zero area.
     *
     * A clickable node of zero width or height is a control the user cannot press — it is present in the
     * semantics tree (so a naming sweep passes it) but occupies no pixels. This is the class of defect a
     * semantics-tree dump shows as `bounds=Rect.fromLTRB(0, 0, 0, 0)`.
     */
    fun assertControlsAreLaidOut(
        screen: String,
        nodes: List<SemanticsNode>,
    ) {
        val empty = nodes.filter { it.size.width <= 0 || it.size.height <= 0 }
        assertTrue(
            empty.isEmpty(),
            "$screen draws interactive controls with no area, so they cannot be pressed. " +
                "Each entry is a control present in the tree but occupying no pixels:\n" +
                empty.joinToString("\n") { "  ${it.describeBounds()}" },
        )
    }

    /**
     * No two pressable controls may partially overlap without one containing the other.
     *
     * A CONTAINED pair (one control's bounds inside another's) is a normal nesting — a button inside a
     * clickable card — and is skipped. A PARTIAL overlap is the defect: two controls a user is asked to press
     * share pixels, so which one a tap reaches depends on draw order, which is exactly the "tap lands on the
     * wrong control" bug that is otherwise only found by hand on a device.
     */
    fun assertControlsDoNotOverlap(
        screen: String,
        nodes: List<SemanticsNode>,
    ) {
        val pressable = nodes.filter { it.isPressable() && !it.isToggle() }
        val overlaps = mutableListOf<String>()
        for (i in pressable.indices) {
            for (j in i + 1 until pressable.size) {
                val a = pressable[i].boundsInRoot
                val b = pressable[j].boundsInRoot
                val intersection = a.intersect(b)
                // Adjacent controls (segmented buttons, chips) legitimately share a boundary edge, which
                // intersect() reports as a 1px band. Only a real shared AREA is an overlap.
                if (intersection.width <= EDGE_TOLERANCE_PX || intersection.height <= EDGE_TOLERANCE_PX) continue
                // Contained pairs are legitimate nesting; partial overlaps are not.
                if (a.contains(b) || b.contains(a)) continue
                overlaps +=
                    "  ${pressable[i].describeBounds()} overlaps ${pressable[j].describeBounds()} " +
                    "(shared ${intersection.width.toInt()}x${intersection.height.toInt()}px)"
            }
        }
        assertTrue(
            overlaps.isEmpty(),
            "$screen draws pressable controls that partially overlap, so a tap can reach the wrong one:\n" +
                overlaps.joinToString("\n"),
        )
    }

    /** True when the node exposes the action a tap would invoke. */
    private fun SemanticsNode.isPressable(): Boolean =
        config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)

    /**
     * True when the node is a toggle (a Switch/Checkbox).
     *
     * Switches are EXCLUDED from the overlap check on purpose: Material gives a Switch a minimum
     * interactive size (48dp) larger than its compact row, so adjacent switches in a dense list overlap
     * by design (measured 2026-10-16 on the chart-visibility section). That is a Material layout
     * characteristic, not an app defect, and asserting it would make the check fail on correct screens.
     * The overlap check stays aimed at buttons, rows and cards, where a partial overlap IS a defect.
     */
    private fun SemanticsNode.isToggle(): Boolean =
        config.contains(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState)

    /** Two controls sharing only a boundary edge are adjacent, not overlapping; anything thicker is real. */
    private const val EDGE_TOLERANCE_PX = 2f

    /** A rectangle that is empty when its area is zero. */
    private fun Rect.contains(other: Rect): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    /** Enough of a node to locate it in a failure message. */
    private fun SemanticsNode.describeBounds(): String {
        val text =
            if (config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) {
                config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(" ") { it.text }
            } else {
                null
            }
        val cd =
            if (config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)) {
                config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString(" ")
            } else {
                null
            }
        val label = text ?: cd ?: "(unnamed)"
        return "node #$id bounds=${boundsInRoot} label='$label'"
    }
}
