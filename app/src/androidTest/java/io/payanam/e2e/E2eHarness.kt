//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.payanam.common.logging.UnifiedLogger
import java.io.File

/**
 * Shared interaction vocabulary for the in-process E2E tiers.
 *
 * Each helper mirrors one Maestro command so the port from the Maestro flows stays mechanical:
 *
 * | Maestro | here |
 * |---|---|
 * | `assertVisible: "X"` | [assertVisible] |
 * | `tapOn: "X"` | [click] / [clickNav] |
 * | `tapOn: {id: "X"}` | [clickTag] |
 * | `inputText: "T"` | [type] |
 * | `scrollUntilVisible: {element: "X"}` | [scrollTo] |
 * | `pressKey: Back` | [back] |
 * | `takeScreenshot: "X"` | [capture] |
 * | `runFlow: {when: {visible: "X"}, …}` | `if (h.exists("X")) { … }` |
 *
 * Two behaviours are deliberately different from a naive Compose port:
 *
 * 1. **Maestro's `text:` matches visible text OR contentDescription; Compose's `hasText` does not.**
 *    The suite's labels come from both mechanisms, so every lookup goes through [labelled].
 * 2. **The bottom-nav labels collide with on-screen titles.** [clickNav] picks the lowest clickable
 *    match instead of the first, which is what a human taps.
 */
/**
 * Keeps the device screen awake for the duration of a test.
 *
 * The device sleeps on a short timeout, and a sleeping screen sends the Activity to `onStop` — the
 * Compose tree then disappears and the run fails with `no Compose root appeared`. That is a device
 * precondition, not an app bug, so it is handled here from TEST code: no device setting is touched
 * (project rule), and no permission is required.
 *
 * Call it in `@Before`, as early as possible.
 */
fun keepScreenOn(activity: Activity) {
    activity.runOnUiThread {
        activity.setTurnScreenOn(true)
        activity.setShowWhenLocked(true)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** Below this height a swipe gesture collapses into a tap. */
private const val MIN_SWIPE_HEIGHT_PX = 300

/** How long a label gets to arrive before the search starts scrolling a lazy list for it. */
private const val ARRIVAL_WAIT_MS = 5_000L

class E2eHarness(
    private val rule: ComposeTestRule,
    private val tag: String = "E2E",
) {

    // ─────────────────────────────── logging (host-readable without adb) ─────────────────────────

    /**
     * Writes to stdout AND the app log. stdout lands in the host-side per-test logcat that AGP
     * emits at
     * `app/build/outputs/androidTest-results/connected/<device>/logcat-<class>-<method>.txt`,
     * so a campaign can read results with no device access at all.
     */
    fun log(line: String) {
        println("$tag $line")
        if (UnifiedLogger.isInitialized()) {
            UnifiedLogger.getInstance().i(tag, line)
        }
    }

    // ─────────────────────────────── readiness ───────────────────────────────────────────────────

    /**
     * Waits for a Compose root to exist.
     *
     * `MainActivity` decides which gate to show asynchronously (DB artifact / encryption / health
     * checks, then `setContent`). Until that resolves there is NO root and any query throws
     * `IllegalStateException: No compose hierarchies found in the app` — so this must run first.
     */
    fun awaitRoot(timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { rule.onRoot().fetchSemanticsNode() }.isSuccess) return
            Thread.sleep(200)
        }
        error("no Compose root appeared within ${timeoutMs}ms")
    }

    /** Waits for Compose to be idle. Call it after a write before asserting the new state. */
    fun awaitIdle() {
        rule.waitForIdle()
    }

    // ─────────────────────────────── matching ────────────────────────────────────────────────────

    /** Maestro's `text:` semantics: match the visible text OR the contentDescription. */
    fun labelled(text: String): SemanticsMatcher =
        hasText(text) or hasContentDescription(text)

    /**
     * Same lookup, but requiring the whole string.
     *
     * Maestro matches `text` as a regex, so its `tapOn: "Start"` also matches "Start Tracking" and
     * the tap lands on whichever node comes first in the tree. Preferring an exact match removes
     * that coin-flip without narrowing what the suite covers.
     */
    fun labelledExactly(text: String): SemanticsMatcher =
        hasText(text, substring = false) or hasContentDescription(text, substring = false)

    /** Presence without waiting. */
    fun exists(label: String): Boolean =
        rule.onAllNodes(labelled(label)).fetchSemanticsNodes().isNotEmpty()

    /** Presence without waiting, by testTag. */
    fun existsTag(tag: String): Boolean =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Blocks until the label appears; fails loudly on timeout (never silently continues). */
    fun waitFor(label: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) { exists(label) }
    }

    /**
     * Waits for the label, scrolling to find it.
     *
     * A lazy list composes ONLY what is on screen, so a row further down the Settings screen is not
     * merely invisible — it is absent from the tree, and a plain existence wait times out. Scroll
     * down looking for it, then back up, which is what Maestro's `scrollUntilVisible` does.
     */
    /** Scrolls looking for the label; true when this screen offers it at all. */
    fun find(label: String): Boolean = runCatching { ensurePresent(label) }.isSuccess

    private fun ensurePresent(label: String, waitMs: Long = 5_000, maxSwipes: Int = 15) {
        if (exists(label)) return

        // Give a label that is simply still arriving (a screen just navigated to) a chance first.
        // Scrolling immediately would burn the search on a half-rendered screen and then give up.
        runCatching { rule.waitUntil(ARRIVAL_WAIT_MS) { exists(label) } }
        if (exists(label)) return

        repeat(4) {
            if (exists(label)) return
            bringIntoView(label)
            if (exists(label)) return
            swipeVertically(up = true)
        }
        repeat(4) {
            if (exists(label)) return
            bringIntoView(label)
            if (exists(label)) return
            swipeVertically(up = false)
        }
        check(exists(label)) {
            "'$label' is not on this screen, even after scrolling\n${diagnose(label)}"
        }
    }

    /**
     * Asks the list itself to bring a row into view.
     *
     * This goes through the container's own scroll semantics. Injected touch strokes were measured
     * doing nothing at all on the lazy lists in this app — 1298 swipes, not one of them moved the
     * content — so they are only a fallback for a container that cannot scroll by semantics.
     */
    private fun bringIntoView(label: String) = bringIntoViewFor(labelled(label), label)

    private fun bringIntoViewFor(matcher: SemanticsMatcher, label: String) {
        val scrollables = rule.onAllNodes(hasScrollAction())
        val candidates = runCatching { scrollables.fetchSemanticsNodes() }.getOrDefault(emptyList())
        val biggest = candidates.indices
            .filter { candidates[it].size.height > MIN_SWIPE_HEIGHT_PX }
            .maxByOrNull { candidates[it].size.height * candidates[it].size.width }
            ?: return
        runCatching {
            scrollables[biggest].performScrollToNode(matcher)
            rule.waitForIdle()
        }
    }

    /** Blocks until the label leaves the screen. */
    fun waitForGone(label: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) { !isOnScreen(label) }
    }

    // ─────────────────────────────── assertions ──────────────────────────────────────────────────

    /** Maestro's `assertVisible` — the label must be present AND on screen. */
    fun assertVisible(label: String, timeoutMs: Long = 30_000) {
        ensurePresent(label)
        rule.waitUntil(timeoutMs) { isOnScreen(label) }
        check(isOnScreen(label)) { "'$label' never became visible\n${diagnose(label)}" }
    }

    /** Negative assertion, for "dialog dismissed" style checks. */
    fun assertNotVisible(label: String) {
        check(!exists(label)) { "expected '$label' to be absent, but it is present" }
    }

    // ─────────────────────────────── actions ─────────────────────────────────────────────────────

    /** Maestro's `tapOn: "X"` — the first clickable node carrying the label. */
    fun click(label: String, timeoutMs: Long = 30_000) {
        ensurePresent(label)
        // A node can exist while scrolled out of view; a tap there does nothing at all.
        if (!isOnScreen(label)) scrollTo(label)
        val exact = firstDisplayed(rule.onAllNodes(labelledExactly(label)).filter(hasClickAction()))
        if (exact != null) {
            exact.performClick()
            return
        }
        val clickable = rule.onAllNodes(labelled(label)).filter(hasClickAction())
        val target = firstDisplayed(clickable)
        if (target != null) {
            target.performClick()
            return
        }

        // This app renders several controls as a label plus a separate clickable surface, so the
        // node carrying the text frequently has no click action of its own. Compose's semantics tree
        // exposes that split; the platform accessibility tree Maestro reads does not, which is why the
        // Maestro tier taps these elements happily. Do what both a human and Maestro do — tap the
        // position the label occupies.
        val labelled = firstDisplayed(rule.onAllNodes(labelled(label)))
        check(labelled != null) {
            "no on-screen node for '$label'\n${diagnose(label)}"
        }
        log("positional-tap=$label")
        // down+up rather than click(center): `click` would resolve to this class's own click(label)
        // overload, and the touch-scope helpers below have no name that can collide.
        labelled.performTouchInput {
            down(center)
            up()
        }
    }

    /**
     * Taps a bottom-navigation item: the LOWEST clickable node carrying the label.
     * A screen title or list row can share the same text, and the nav bar is at the bottom.
     */
    fun clickNav(label: String, timeoutMs: Long = 30_000) {
        waitFor(label, timeoutMs)
        val matches = rule.onAllNodes(labelled(label) and hasClickAction())
        val nodes = matches.fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "no clickable nav node for '$label'" }
        val lowest = nodes.indices.maxByOrNull { nodes[it].boundsInRoot.top }!!
        matches[lowest].performClick()
    }

    /**
     * Explains why a lookup failed: which nodes carry the label, in both the merged and the
     * unmerged tree, and whether each one can be clicked.
     *
     * A label can sit on a node whose click action lives on an ancestor (or the reverse), and the
     * merged tree is what decides which of those a query sees. Printing both trees turns an
     * ambiguous "not found" into a specific, fixable a11y gap.
     */
    fun diagnose(label: String): String {
        fun describe(useUnmerged: Boolean): String {
            val nodes = rule.onAllNodes(labelled(label), useUnmergedTree = useUnmerged).fetchSemanticsNodes()
            if (nodes.isEmpty()) return "    (none)"
            return nodes.joinToString("\n") { n ->
                val click = n.config.getOrNull(SemanticsActions.OnClick) != null
                val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                val tag = n.config.getOrNull(SemanticsProperties.TestTag)
                val state = n.config.getOrNull(SemanticsProperties.ToggleableState)
                val role = n.config.getOrNull(SemanticsProperties.Role)
                "    click=$click text=$text cd=$cd tag=$tag state=$state role=$role " +
                    "bounds=${n.boundsInRoot}"
            }
        }
        val clickables = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .take(40)
            .joinToString("\n") { n ->
                val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                "    clickable text=$text cd=$cd bounds=${n.boundsInRoot}"
            }
        val texts = dumpTexts(limit = 40).joinToString("\n") { "    text=$it" }
        return buildString {
            append("  matches for '").append(label).append("':")
            append("\n  merged:\n").append(describe(false))
            append("\n  unmerged:\n").append(describe(true))
            append("\n  ALL text nodes:\n").append(texts)
            append("\n  ALL clickable nodes:\n").append(clickables)
        }
    }

    /**
     * Clicks the LAST displayed match instead of the first.
     *
     * Needed when a confirmation dialog reuses the label of the row that opened it ("Delete All
     * Data" is both a Settings row and the dialog's confirm button). The dialog is composed after
     * the screen underneath it, so its copy of the label comes later in the tree.
     */
    fun clickLast(label: String, timeoutMs: Long = 30_000) {
        waitFor(label, timeoutMs)
        val clickable = rule.onAllNodes(labelledExactly(label)).filter(hasClickAction())
        val all = runCatching { clickable.fetchSemanticsNodes() }.getOrDefault(emptyList())
        val displayed = all.indices.filter { clickable[it].isDisplayed() }
        check(displayed.isNotEmpty()) {
            "no on-screen node for '$label'\n${diagnose(label)}"
        }
        clickable[displayed.last()].performClick()
    }

    /**
     * Every text currently on screen, in tree order.
     *
     * Used to record what a screen actually shows (e.g. which analytics sections are rendered under
     * a given set of preferences) instead of guessing titles from the source.
     */
    fun dumpTexts(limit: Int = 80): List<String> = runCatching {
        val collected = mutableListOf<String>()
        val root = rule.onRoot().fetchSemanticsNode()
        fun walk(node: SemanticsNode) {
            if (collected.size >= limit) return
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString()?.let { text ->
                // isDisplayed() lives on the interaction, not on a bare SemanticsNode; a node with
                // real size is laid out and therefore part of what the screen shows.
                if (text.isNotBlank() && node.size.width > 0 && node.size.height > 0) {
                    collected.add(text)
                }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        collected
    }.getOrDefault(emptyList())

    /**
     * Every text on the screen, scrolled from top to bottom.
     *
     * A lazy list composes only its visible window, so a single snapshot under-reports a long
     * screen. Scroll to the end and union the snapshots.
     */
    fun collectTexts(maxSwipes: Int = 12): List<String> {
        val collected = LinkedHashSet<String>()
        collected.addAll(dumpTexts(limit = 200))
        repeat(maxSwipes) {
            swipeVertically(up = true)
            collected.addAll(dumpTexts(limit = 200))
        }
        return collected.toList()
    }

    /** True when the given text is on screen right now (no waiting). */
    fun seesText(text: String): Boolean =
        dumpTexts(limit = 200).any { it.contains(text, ignoreCase = false) }

    /**
     * Reads a switch's on/off state from its accessibility node.
     *
     * Asserting the STATE rather than "a tap happened" is what catches a toggle that silently does
     * nothing — a disabled or mis-wired row looks identical to a working one from the outside.
     */
    fun isChecked(label: String): Boolean? {
        // Scroll to the switch itself — bringIntoView takes the first match of its matcher, and for a
        // label that is also a nav tab that would be the nav item at the bottom of the screen.
        bringIntoViewFor(switchNamed(label), label)
        val nodes = rule.onAllNodes(switchNamed(label))
        val count = runCatching { nodes.fetchSemanticsNodes().size }.getOrDefault(0)
        for (i in 0 until count) {
            if (!nodes[i].isDisplayed()) continue
            val state = nodes[i].fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ToggleableState)
            if (state != null) return state == ToggleableState.On
        }
        return null
    }

    /**
     * How many switch ROWS carry this exact label.
     *
     * A row renders its label twice — once as text and again as the switch's accessibility label — so
     * a plain node count sees two entries per row and would never agree with a row count.
     */
    fun switchCount(label: String): Int = runCatching {
        // Existing, not on-screen: a row the list has not composed does not exist at all, which is
        // exactly the difference being asserted. Counting only visible ones would cap the answer at
        // whatever fits on screen and make the assertion meaningless.
        rule.onAllNodes(switchNamed(label)).fetchSemanticsNodes().size
    }.getOrDefault(0)

    /** How many on-screen nodes carry this exact label. */
    fun count(label: String): Int = runCatching {
        val nodes = rule.onAllNodes(labelledExactly(label))
        val total = nodes.fetchSemanticsNodes().size
        (0 until total).count { nodes[it].isDisplayed() }
    }.getOrDefault(0)

    /** Flips a switch and requires it to actually reach the requested state. */
    fun setChecked(label: String, checked: Boolean, timeoutMs: Long = 15_000) {
        val current = isChecked(label)
        check(current != null) { "'$label' is not a switch on this screen" }
        if (current == checked) return
        clickSwitch(label)
        rule.waitUntil(timeoutMs) { isChecked(label) == checked }
        check(isChecked(label) == checked) {
            "'$label' stayed ${if (checked) "off" else "on"} after the tap — the control did not react"
        }
    }

    /**
     * A switch carrying this label.
     *
     * Several switch labels are also bottom-navigation tabs ("Tasks", "Notes", "Time"). The nav item
     * is always on screen and carries no state, so a lookup by label alone matches it, reports "not a
     * switch", and never scrolls down to the real control.
     */
    private fun switchNamed(label: String): SemanticsMatcher =
        labelledExactly(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    /** Taps the switch itself — never a same-named nav item or heading. */
    private fun clickSwitch(label: String) {
        val target = firstDisplayed(rule.onAllNodes(switchNamed(label)))
        check(target != null) { "no on-screen switch for '$label'\n${diagnose(label)}" }
        target.performClick()
    }

    /** Maestro's `tapOn: {id: "X"}` — a Compose testTag. */
    fun clickTag(tag: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) { existsTag(tag) }
        rule.onAllNodes(hasTestTag(tag)).onFirst().performClick()
    }

    /** Maestro's `inputText` — type into the field carrying the label. */
    fun type(label: String, value: String, timeoutMs: Long = 30_000) {
        waitFor(label, timeoutMs)
        if (!isOnScreen(label)) scrollTo(label)
        val field = rule.onAllNodes(labelled(label)).filter(hasSetTextAction())
        val target = firstDisplayed(field)
        check(target != null) { "'$label' has no on-screen text field to type into\n${diagnose(label)}" }
        target.performTextInput(value)
    }

    /**
     * True when the label exists AND is actually on screen.
     *
     * `exists()` is not enough: a Compose tree contains composables that are laid out but scrolled
     * out of the viewport. A tap aimed at one of those lands outside the screen and silently does
     * nothing — the app then never leaves the screen, and the failure surfaces much later at an
     * unrelated step. Always check visibility before acting.
     */
    fun isOnScreen(label: String): Boolean = anyDisplayed(rule.onAllNodes(labelled(label)))

    /**
     * A label can be carried by several nodes at once — Navigation Compose keeps the previous
     * destination composed during (and briefly after) a transition, so a field that was just typed
     * into can still be in the tree while off screen. Any displayed match counts.
     */
    private fun anyDisplayed(nodes: SemanticsNodeInteractionCollection): Boolean = runCatching {
        (0 until nodes.fetchSemanticsNodes().size).any { nodes[it].isDisplayed() }
    }.getOrDefault(false)

    /** The first match that is genuinely on screen, or null when none is. */
    private fun firstDisplayed(
        nodes: SemanticsNodeInteractionCollection,
    ): SemanticsNodeInteraction? {
        val count = runCatching { nodes.fetchSemanticsNodes().size }.getOrDefault(0)
        for (i in 0 until count) if (nodes[i].isDisplayed()) return nodes[i]
        return null
    }

    /**
     * Swipes the largest scrollable on the screen.
     *
     * Two traps, both hit for real: a swipe issued on a zero-height container degenerates into a
     * tap, and a gesture that runs to the very bottom edge can land on the bottom navigation bar —
     * which silently navigates away from the screen under test. So: require a properly sized
     * container, take the biggest one, and inset the stroke away from both edges.
     */
    private fun swipeVertically(up: Boolean) {
        val scrollables = rule.onAllNodes(hasScrollAction())
        val nodes = runCatching { scrollables.fetchSemanticsNodes() }.getOrDefault(emptyList())
        log(
            "swipe(up=$up): scrollables=${nodes.size} sizes=" +
                nodes.map { "${it.size.width.toInt()}x${it.size.height.toInt()}" },
        )
        val index = nodes.indices
            .filter { nodes[it].size.height > MIN_SWIPE_HEIGHT_PX && nodes[it].size.width > 300 }
            .maxByOrNull { nodes[it].size.height * nodes[it].size.width }
            ?: error(
                "swipe(up=$up): no scrollable to swipe — candidates were " +
                    nodes.map { "${it.size.width.toInt()}x${it.size.height.toInt()}" },
            )
        // Coordinates are node-local, and the node's height is known from its semantics, so the
        // stroke can be inset without reaching into the touch scope's own geometry helpers.
        val height = nodes[index].size.height.toFloat()
        val inset = height * 0.2f
        val before = dumpTexts().take(3)
        scrollables[index].performTouchInput {
            if (up) {
                swipeUp(startY = height - inset, endY = inset)
            } else {
                swipeDown(startY = inset, endY = height - inset)
            }
        }
        rule.waitForIdle()
        // The container's own bounds never move while its content scrolls, so the content is what
        // has to be compared — a swipe that changes nothing leaves the caller scrolling blindly.
        val after = dumpTexts().take(3)
        log("swipe(up=$up): content ${if (before == after) "UNCHANGED" else "moved"} | $before -> $after")
    }

    /**
     * Maestro's `scrollUntilVisible` — swipe until the label is on screen.
     *
     * Scrolling down first, then back up, covers a target that sits above the current position
     * (the suite hits both: "Save Task" below the fields, "Add New" above the dimension list).
     */
    fun scrollTo(label: String, maxSwipes: Int = 12) {
        if (isOnScreen(label)) return

        bringIntoView(label)
        if (isOnScreen(label)) return

        var swipes = 0
        while (!isOnScreen(label) && swipes < maxSwipes) {
            swipeVertically(up = true)
            swipes++
        }
        swipes = 0
        while (!isOnScreen(label) && swipes < maxSwipes) {
            swipeVertically(up = false)
            swipes++
        }
        check(isOnScreen(label)) {
            "scrollTo: '$label' never became visible ($maxSwipes swipes each way)\n${diagnose(label)}"
        }
    }

    /** Maestro's `pressKey: Back`. */
    fun back() {
        Espresso.pressBack()
        rule.waitForIdle()
    }

    // ─────────────────────────────── evidence ────────────────────────────────────────────────────

    /**
     * Best-effort screenshot into the app's external files dir.
     *
     * NOTE: retrieving these to the host needs adb (forbidden here) or MTP, so screenshots are
     * evidence-of-record only. The authoritative artefacts stay the host-side JUnit XML, the HTML
     * report and the per-test logcat. Returns the file path, or null if capture failed.
     */
    fun capture(name: String): String? = runCatching {
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir("e2e-screenshots")
            ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        val file = File(dir, "$name.png")
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { out ->
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        log("screenshot=$name path=${file.absolutePath}")
        file.absolutePath
    }.getOrNull()

    // ─────────────────────────────── navigation ──────────────────────────────────────────────────

    /** Taps a bottom-nav tab and waits for the destination to settle. */
    fun goToTab(name: String) {
        clickNav(name)
        rule.waitForIdle()
        log("nav=$name")
    }
}
