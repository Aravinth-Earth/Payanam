//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
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
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.payanam.MainActivity
import io.payanam.common.logging.UnifiedLogger
import java.io.File
import kotlin.math.roundToInt

/*
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

/**
 * How long a label is given to arrive before the harness starts scrolling for it.
 *
 * Measured 2026-10-14, and the reason it is not the 5s it was: after `build-android.ps1` installs a
 * freshly built APK, the FIRST launch is a cold one (dexopt/JIT), and the Time screen's own body had not
 * finished composing when the arrival window closed - the journey then reported
 * `'கணாணிப்பைத் தொடங்கவும்' is not on this screen` with only the nav bar in the diagnosis, on the very
 * phase that had PASSED twenty minutes earlier on the warm app. A condition wait returns as soon as the
 * label appears, so a generous window costs nothing when the app is fast and is the only thing that makes
 * the cold case honest.
 */
private const val ARRIVAL_WAIT_MS = 15_000L

/** Below this height a swipe gesture collapses into a tap. */
private const val MIN_SWIPE_HEIGHT_PX = 300

class E2eHarness(
    private val rule: ComposeTestRule,
    private val tag: String = "E2E",
) {

    // ─────────────────────────────── labels (locale-agnostic) ──────────────────────────────────────

    /**
     * The app's own copy for the locale it is currently rendering in.
     *
     * Every label this class is asked to find comes from here rather than an English literal in a test
     * file, so the tier is not a bet that the device is English. See [E2eLabels].
     *
     * Resolved from the ACTIVITY's own context, not the instrumentation's target context. The app
     * applies its language choice to the activity's BASE context
     * (`MainActivity.applyLanguagePreference` → `applyLanguageTag`), so the target context keeps the
     * system locale while the app renders the user's language — the tiers resolved English labels
     * against a Tamil screen and reported "not on this screen" / "(none) matches" instead of a locale
     * mismatch. Read lazily, because a journey can change the language mid-run.
     */
    private val l: E2eLabels.Labels get() = E2eLabels.of(activityContext())

    /** The Activity's context when the rule exposes one; otherwise the target app context. */
    private fun activityContext(): Context {
        val androidRule =
            rule as? AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>
        return androidRule?.activity
            ?: InstrumentationRegistry.getInstrumentation().targetContext
    }

    /**
     * Names the locale each candidate source reports, at the moment a lookup found nothing.
     *
     * Two fixes were tried against a Tamil screen that resolved English labels (reading
     * `LocaleManager.applicationLocales`, and resolving from the Activity's context) and neither changed a
     * single test result. A probe beats a third guess: it reports which branch was taken and what every
     * reachable source actually says, in the same log as the failure.
     */
    private fun localeProbe(label: String): String {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = activityContext()
        val resolvedFromTarget = E2eLabels.of(target).let { runCatching { it.lenses }.getOrNull() }
        val resolvedFromActivity = E2eLabels.of(activity).let { runCatching { it.lenses }.getOrNull() }
        return buildString {
            append("    locale-probe: label='").append(label).append("'\n")
            append("      targetTag=").append(E2eLabels.localeTag(target))
            append(" activityTag=").append(E2eLabels.localeTag(activity)).append('\n')
            append("      targetConfig=").append(target.resources.configuration.locales)
            append(" activityConfig=").append(activity.resources.configuration.locales).append('\n')
            append("      targetActivitySame=").append(target === activity)
            append(" targetLenses='").append(resolvedFromTarget)
            append("' activityLenses='").append(resolvedFromActivity).append("'")
        }
    }

    /** Convenience for call sites that take a plain `String` label. */
    val labels: E2eLabels.Labels get() = l

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
    fun labelled(text: String): SemanticsMatcher = hasText(text) or hasContentDescription(text)

    /**
     * Same lookup, but requiring the whole string.
     *
     * Maestro matches `text` as a regex, so its `tapOn: "Start"` also matches "Start Tracking" and
     * the tap lands on whichever node comes first in the tree. Preferring an exact match removes
     * that coin-flip without narrowing what the suite covers.
     */
    fun labelledExactly(text: String): SemanticsMatcher = hasText(text, substring = false) or hasContentDescription(text, substring = false)

    /**
     * Substring form of [labelled], for a label the app renders with a runtime suffix.
     *
     * `labelled`/`labelledExactly` require the WHOLE string, so a filter chip rendered as "All (3)" is
     * not found by the label "All" — measured on device 2026-10-16, where the Tasks filter chips carry
     * their count in the same text node. Use this for a control whose copy the app formats with a value
     * that changes at runtime (filter chips, "… (N)" rows).
     */
    fun labelledSubstring(text: String): SemanticsMatcher = hasText(text, substring = true) or hasContentDescription(text, substring = true)

    /**
     * Taps the first on-screen node whose text/contentDescription CONTAINS [label].
     *
     * A positional tap, because the node carrying the composed text is usually a child of the clickable
     * surface (the same split [click] handles) — the tap lands on the control underneath.
     */
    fun clickSubstring(label: String, timeoutMs: Long = 30_000) {
        // A bare Compose timeout names no screen, so the wait is caught and the failure carries the diagnosis.
        val appeared = runCatching {
            rule.waitUntil(timeoutMs) {
                rule.onAllNodes(labelledSubstring(label)).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        check(appeared) { "no on-screen node containing '$label' after ${timeoutMs}ms\n${diagnose(label)}" }
        val target = firstDisplayed(rule.onAllNodes(labelledSubstring(label)))
        check(target != null) { "no on-screen node containing '$label'\n${diagnose(label)}" }
        log("substring-tap=$label")
        target.performTouchInput {
            down(center)
            up()
        }
    }

    /** Presence without waiting. */
    fun exists(label: String): Boolean = rule.onAllNodes(labelled(label)).fetchSemanticsNodes().isNotEmpty()

    /** Presence without waiting, by testTag. */
    fun existsTag(tag: String): Boolean = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Blocks until the label appears; fails loudly on timeout (never silently continues). */
    fun waitFor(label: String, timeoutMs: Long = 30_000) {
        // A bare Compose timeout names no screen, so the wait is caught and the failure carries the diagnosis.
        val appeared = runCatching {
            rule.waitUntil(timeoutMs) { exists(label) }
        }.isSuccess
        check(appeared) { "'$label' never appeared within ${timeoutMs}ms\n${diagnose(label)}" }
    }

    /**
     * Waits for the label, scrolling to find it; true when this screen offers it at all.
     *
     * A lazy list composes ONLY what is on screen, so a row further down the Settings screen is not
     * merely invisible — it is absent from the tree, and a plain existence wait times out. Scroll
     * down looking for it, then back up, which is what Maestro's `scrollUntilVisible` does.
     */
    fun find(label: String): Boolean = runCatching { ensurePresent(label) }.isSuccess

    private fun ensurePresent(label: String, waitMs: Long = 5_000, maxSwipes: Int = 15) {
        if (exists(label)) return

        // Give a label that is simply still arriving (a screen just navigated to) a chance first.
        // Scrolling immediately would burn the search on a half-rendered screen and then give up.
        runCatching { rule.waitUntil(ARRIVAL_WAIT_MS) { exists(label) } }
        if (exists(label)) return

        var canScroll = true
        repeat(4) {
            if (exists(label) || !canScroll) return@repeat
            bringIntoView(label)
            if (exists(label)) return
            canScroll = swipeVertically(up = true)
        }
        if (exists(label)) return
        canScroll = true
        repeat(4) {
            if (exists(label) || !canScroll) return@repeat
            bringIntoView(label)
            if (exists(label)) return
            canScroll = swipeVertically(up = false)
        }
        // Reached with the reason NAMED: the check below reports the label and every clickable on the screen,
        // which is the message a reader needs. It used to be pre-empted by a throw from the swipe itself, so a
        // missing control was reported as a broken swipe rule. Measured 2026-10-13 on the Time screen.
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

    /**
     * Blocks until no node carries [label] EXACTLY.
     *
     * The counterpart of [waitForGone] for a label that also occurs somewhere that outlives the thing
     * being waited on. `waitForGone` matches substrings, so it cannot express "the banner is gone" when
     * the banner's message text is also rendered elsewhere — measured 2026-10-16 on the assistant setup
     * surface, where `assistant_error_key_rejected` is BOTH the error banner's message and the API-key
     * field's `supportingText`: dismissing the banner leaves the field's copy in place and the substring
     * wait can never be satisfied.
     *
     * Uses the exact matcher, so a short label cannot be answered by a longer one that contains it.
     */
    fun waitForGoneExact(label: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodes(labelledExactly(label)).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Blocks until ANY of [labels] is on screen; returns the one that arrived.
     *
     * Used where the app offers more than one legal starting point for the same state and the test
     * does not know which one it will get — e.g. a fresh install may show the onboarding tour or go
     * straight to database init. Polling for a single one of them is the race that produced a tier
     * which "still failed" on a provably clean install: the no-wait `isOnScreen` sampled the tree
     * before the startup gate had composed anything.
     */
    fun awaitAnyOnScreen(
        vararg labels: String,
        timeoutMs: Long = 60_000,
    ): String {
        val lastSeen = StringBuilder()
        rule.waitUntil(timeoutMs) {
            // Record what WAS on screen, so a timeout names the screen the app is actually showing
            // instead of only listing what was expected.
            lastSeen.setLength(0)
            labels.filter { isOnScreen(it) }.forEach { lastSeen.append("'$it' ") }
            labels.any { isOnScreen(it) }
        }
        return labels.first { isOnScreen(it) }
    }

    /**
     * True when ANY of [labels] is on screen right now (no waiting).
     *
     * The disjunction counterpart of [awaitAnyOnScreen] for the assertions that only need to know
     * the screen offers the state at all.
     */
    fun isAnyOnScreen(vararg labels: String): Boolean = labels.any { isOnScreen(it) }

    // ─────────────────────────────── assertions ──────────────────────────────────────────────────

    /** Maestro's `assertVisible` — the label must be present AND on screen. */
    fun assertVisible(label: String, timeoutMs: Long = 30_000) {
        ensurePresent(label)
        // `ensurePresent` returns EARLY when the node already EXISTS — which is true for every child of a
        // `verticalScroll` column, composed whether or not it is drawn. So a label that is present but
        // below the fold would never be scrolled to and this would wait out the timeout with the node in
        // the tree. Scroll explicitly when it is not on screen, exactly as [click] does.
        if (!isOnScreen(label)) scrollTo(label)
        rule.waitUntil(timeoutMs) { isOnScreen(label) }
        check(isOnScreen(label)) { "'$label' never became visible\n${diagnose(label)}" }
    }

    /** Negative assertion, for "dialog dismissed" style checks. */
    fun assertNotVisible(label: String) {
        check(!exists(label)) { "expected '$label' to be absent, but it is present" }
    }

    /**
     * [assertVisible] for a label the app formats with a runtime value, matched as a SUBSTRING.
     *
     * [assertVisible] goes through the exact [labelled] matcher, so a message rendered with a live value —
     * the unlock lockout's "… try again in N seconds", a filter chip's "All (3)" — is never found by its
     * static prefix. Measured 2026-10-16 on the lockout message, whose on-screen text was present while the
     * exact lookup reported `(none)`.
     */
    fun assertVisibleSubstring(label: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) { anyDisplayed(rule.onAllNodes(labelledSubstring(label))) }
        check(anyDisplayed(rule.onAllNodes(labelledSubstring(label)))) {
            "'$label' never became visible as a substring\n${diagnose(label)}"
        }
    }

    /**
     * Requires the app's OWN session log to carry the structured marker [source] with [message].
     *
     * This is the LOG half of cross-platform parity on the ANDROID side: the same marker the desktop
     * journey asserts, so a behaviour that works on one platform and silently fails on the other shows up as
     * a missing marker or an extra error line rather than as a difference nobody looked for.
     *
     * The Android logger is ASYNC-BUFFERED (a background scope flushes on an interval), so the buffer is
     * flushed and the file polled rather than read once.
     */
    fun assertLogged(source: String, message: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            if (UnifiedLogger.isInitialized()) UnifiedLogger.getInstance().flush()
            text = readSessionLog()
            if (text.contains("| $source |") && text.contains(message)) {
                log("logged=$source")
                return
            }
            Thread.sleep(300)
        }
        error(
            "the app session log carries no '$source' marker with '$message' " +
                "(log length=${text.length}, file=${sessionLogFile()?.absolutePath})",
        )
    }

    /** The newest session log file the app has written, or null. */
    private fun sessionLogFile(): File? = File(activityContext().filesDir, "logs").listFiles()?.maxByOrNull { it.lastModified() }

    private fun readSessionLog(): String = runCatching { sessionLogFile()?.readText().orEmpty() }.getOrDefault("")

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
            if (nodes.isEmpty()) return "    (none)\n" + localeProbe(label)
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
        // Stops as soon as the screen has nothing left to scroll: a loop that kept asking would gather the same
        // window over and over on a screen with no scrollable container at all.
        var swipes = 0
        while (swipes < maxSwipes && swipeVertically(up = true)) {
            collected.addAll(dumpTexts(limit = 200))
            swipes++
        }
        return collected.toList()
    }

    /** True when the given text is on screen right now (no waiting). */
    fun seesText(text: String): Boolean = dumpTexts(limit = 200).any { it.contains(text, ignoreCase = false) }

    // ─────────────────────────────── touch targets ───────────────────────────────────────────────

    /** One pressable control as the sweep measures it: what it says, and how big it is in dp. */
    data class TouchTarget(
        val label: String,
        val widthDp: Float,
        val heightDp: Float,
    )

    /**
     * Every DISPLAYED node a user can press, with its size in dp.
     *
     * The MERGED tree is walked, so a `clickable` surface that merges its descendants is measured as the
     * one control a user actually presses rather than as its child text. Nodes that are composed but
     * scrolled out of view are skipped - the sweep is about what the user can reach on the screen in
     * front of them.
     *
     * Deliberately EXCLUDED, and named here so the omission is a stated decision rather than a hole:
     *
     *  - `Selected` - filter chips, tabs, segmented buttons and nav items carry the selection state.
     *    Material sizes these below the 44/48dp comfort target on purpose, and a chip is not a control a
     *    user struggles to hit; including them would make every screen with a chip row fail for a
     *    non-defect.
     *  - `ToggleableState` - switches and checkboxes. Same reasoning: the touch surface is the whole row,
     *    not the 32dp track.
     *
     * Everything else - icon buttons, FABs, text buttons, rows, the habit canvas - is measured and must
     * clear the floor. A zero-area or sub-24dp pressable is the defect this exists to catch: it is
     * invisible to every other assertion in the tier, because a tap can still land on it by luck.
     */
    fun pressableTargets(): List<TouchTarget> {
        val density = rule.density.density
        val interactions = rule.onAllNodes(hasClickAction())
        val count = runCatching { interactions.fetchSemanticsNodes().size }.getOrDefault(0)
        val targets = mutableListOf<TouchTarget>()
        for (i in 0 until count) {
            if (!interactions[i].isDisplayed()) continue
            val config = interactions[i].fetchSemanticsNode().config
            if (config.contains(SemanticsProperties.Selected)) continue
            if (config.contains(SemanticsProperties.ToggleableState)) continue
            val label = config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: config.getOrNull(SemanticsProperties.TestTag)
                ?: "(unlabelled)"
            val size = interactions[i].fetchSemanticsNode().size
            targets += TouchTarget(label, size.width / density, size.height / density)
        }
        return targets
    }

    /**
     * Fails unless every displayed pressable control on [screen] is at least [minSizeDp] in BOTH
     * dimensions.
     *
     * The floor is a named parameter rather than a constant so a screen whose controls are legitimately
     * tighter can say so at its call site, and the message lists every offender with its measured size so
     * a failure is a work item rather than a mystery.
     */
    fun assertTouchTargets(
        screen: String,
        minSizeDp: Float = 24f,
    ) {
        val targets = pressableTargets()
        val tooSmall = targets.filter { it.widthDp < minSizeDp || it.heightDp < minSizeDp }
        log("touch-targets screen=$screen checked=${targets.size} tooSmall=${tooSmall.size}")
        check(tooSmall.isEmpty()) {
            "on '$screen', ${tooSmall.size} of ${targets.size} displayed control(s) are below ${minSizeDp}dp " +
                "in one dimension:\n" +
                tooSmall.joinToString("\n") {
                    "  '${it.label}' ${it.widthDp.roundToInt()}x${it.heightDp.roundToInt()}dp"
                }
        }
    }

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
     * Enabled state of the first on-screen node carrying this label.
     *
     * Compose marks a disabled node with [SemanticsProperties.Disabled]; "enabled" is the absence
     * of that key — there is no positive Enabled property in the semantics tree. Returns `false`
     * when the node is disabled OR when there is no displayed match at all (callers treat a
     * missing node as "not usable", which is the honest reading for gates).
     */
    fun isEnabled(label: String): Boolean {
        ensurePresent(label)
        if (!isOnScreen(label)) scrollTo(label)
        val nodes = rule.onAllNodes(labelled(label))
        val count = runCatching { nodes.fetchSemanticsNodes().size }.getOrDefault(0)
        for (i in 0 until count) {
            if (!nodes[i].isDisplayed()) continue
            val disabled = nodes[i].fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled)
            return disabled == null
        }
        return false
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
    private fun switchNamed(label: String): SemanticsMatcher = labelledExactly(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

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
     * Types into a field after REPLACING whatever it already holds.
     *
     * [type] appends, which is right for a field the user has not touched. A field that KEEPS its value
     * after a failed submit — the unlock screen's passphrase field, which clears only on success — would
     * otherwise accumulate one wrong attempt on top of the last. Measured 2026-10-16 on the unlock gate.
     */
    fun replaceType(label: String, value: String, timeoutMs: Long = 30_000) {
        waitFor(label, timeoutMs)
        if (!isOnScreen(label)) scrollTo(label)
        val field = rule.onAllNodes(labelled(label)).filter(hasSetTextAction())
        val target = firstDisplayed(field)
        check(target != null) { "'$label' has no on-screen text field to type into\n${diagnose(label)}" }
        target.performTextReplacement(value)
    }

    /**
     * True when some text field carrying [label] holds [expected] as its EDITABLE text.
     *
     * `assertTextContains` cannot answer this for a LABELLED field. The field's merged node carries the
     * LABEL as its `Text` and the value as `EditableText`, and `assertTextContains` reports the label - so an
     * assertion on the typed value fails against a field that plainly holds it. Measured on the device
     * 2026-10-17: the assistant's goals field held "Test goals" while `assertTextContains("Test goals")`
     * reported the label "இலக்குகள்". This reads the property the value actually lives in.
     *
     * Polls, because a field backed by the encrypted database can be re-populated a frame after the write.
     */
    fun fieldHolds(label: String, expected: String, timeoutMs: Long = 15_000): Boolean = runCatching {
        rule.waitUntil(timeoutMs) { fieldHoldsNow(label, expected) }
    }.isSuccess

    /** One reading of [fieldHolds], without waiting - the poll body. */
    private fun fieldHoldsNow(label: String, expected: String): Boolean {
        val fields = rule.onAllNodes(labelled(label)).filter(hasSetTextAction())
        val count = runCatching { fields.fetchSemanticsNodes().size }.getOrDefault(0)
        for (index in 0 until count) {
            val node = runCatching { fields[index].fetchSemanticsNode() }.getOrNull() ?: continue
            val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            if (text.contains(expected)) return true
        }
        return false
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
     * Swipes the largest scrollable container vertically, and says whether it could.
     *
     * Two traps, both hit for real: a swipe issued on a zero-height container degenerates into a
     * tap, and a gesture that runs to the very bottom edge can land on the bottom navigation bar —
     * which silently navigates away from the screen under test. So: require a properly sized
     * container, take the biggest one, and inset the stroke away from both edges.
     *
     * A screen with no container big enough to scroll is a NORMAL state - the label the caller wants is
     * simply not there - so this reports it instead of throwing. It used to `error(...)` here, which meant the
     * caller's own named diagnosis (the missing label plus every clickable on the screen) never ran: measured
     * 2026-10-13, the Android journey reached the Time screen, could not find the start-tracking control, and
     * died with `no scrollable to swipe` - a message about the SWIPE RULE, on a screen whose real problem was a
     * missing control.
     */
    private fun swipeVertically(up: Boolean): Boolean {
        val scrollables = rule.onAllNodes(hasScrollAction())
        val nodes = runCatching { scrollables.fetchSemanticsNodes() }.getOrDefault(emptyList())
        log(
            "swipe(up=$up): scrollables=${nodes.size} sizes=" +
                nodes.map { "${it.size.width.toInt()}x${it.size.height.toInt()}" },
        )
        val index = nodes.indices
            .filter { nodes[it].size.height > MIN_SWIPE_HEIGHT_PX && nodes[it].size.width > 300 }
            .maxByOrNull { nodes[it].size.height * nodes[it].size.width }
            ?: run {
                // The message says what it LOOKED FOR, not only what it found. A bare list of the candidate
                // sizes leaves the reader to infer the rule from this file, which is the failure this message
                // was written to remove: on the Time screen it printed `candidates were []` and named nothing.
                log(
                    "swipe(up=$up): nothing to swipe - looking for a container taller than " +
                        "${MIN_SWIPE_HEIGHT_PX}px and wider than 300px; the ${nodes.size} scrollable(s) on screen " +
                        "were " + nodes.map { "${it.size.width.toInt()}x${it.size.height.toInt()}" },
                )
                return false
            }
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
        return true
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
        // Each direction stops the moment the screen refuses a swipe, so the check below reports the label and
        // the screen's controls - not a swipe rule - when the screen simply has nothing to scroll.
        while (!isOnScreen(label) && swipes < maxSwipes && swipeVertically(up = true)) {
            swipes++
        }
        swipes = 0
        while (!isOnScreen(label) && swipes < maxSwipes && swipeVertically(up = false)) {
            swipes++
        }
        check(isOnScreen(label)) {
            "scrollTo: '$label' never became visible ($maxSwipes swipes each way)\n${diagnose(label)}"
        }
    }

    /**
     * Answers the focus-mode onboarding gate when it is up, and does nothing when it is not.
     *
     * The gate is evaluated when the ACTIVITY starts, so a fresh install meets it after a RESTART rather than
     * right after the database is created - which is why the journey's early phases never saw it. Nothing
     * answered it, so the run ended on the chooser while looking for a Settings row: measured 2026-10-14, the
     * language change restarted the app, `scrollTo` for the default-landing row failed with the chooser's own
     * copy ("Choose Your Focus" … "I'll choose later") in the diagnosis. "Later" is picked because it leaves the
     * user's dimensions untouched.
     */
    fun answerFocusModeGate(
        gateLabel: () -> String,
        skipLabel: () -> String,
        timeoutMs: Long = 15_000,
    ) {
        // The gate arrives LATE: a restart re-composes the app, and the graph moves to the focus chooser only
        // after the startup gate re-resolves. Measured 2026-10-14: the language change restarted the activity,
        // this question was asked ~600ms BEFORE `PayanamNavHost` logged the move to `focus_mode_selection`, and a
        // one-shot check therefore missed the chooser - the next nav tap then timed out on a screen that was not
        // the shell.
        //
        // The labels are LAMBDAS, not strings: the gate renders in whatever language the app is in when it
        // appears, and this call sits right after a language CHANGE whose locale applies asynchronously. A label
        // resolved once here is a bet on which language won that race - measured: the parameters were resolved
        // while the app was still Tamil, the chooser came up in English, and the comparison could not match for
        // the whole 15 seconds. Resolving per poll costs nothing and cannot lose that race.
        val arrived = runCatching { rule.waitUntil(timeoutMs) { exists(gateLabel()) } }.isSuccess
        if (!arrived) return
        log("focus-mode onboarding gate is up; leaving it with 'later'")
        click(skipLabel())
        rule.waitForIdle()
    }

    /**
     * Dismisses the notification-permission offer when it is up, and does nothing when it is not.
     *
     * WHY THE TIER NEEDS THIS:
     *
     * The app asks for the notification permission AFTER the action that would notify - a started timer, a
     * task saved with a reminder - and it never blocks that action: the timer runs and the task is saved
     * whether or not the user answers. On a device where notifications are switched off (a fresh install on
     * Android 13 and later) that offer IS a modal dialog, so a journey that saves a task or starts a timer
     * meets it. It is a legitimate part of the flow now, and the journey's job is to get past it the way a
     * user would.
     *
     * "Not now" is the answer a user who does not care about notifications picks: it changes nothing that
     * was already saved, and it leaves the offer out of the way.
     *
     * The wait is bounded and this is a NO-OP when the offer never arrives - on a device where the
     * permission is already granted the offer does not exist, and the journey must not be held up by it.
     */
    fun dismissNotificationOfferIfPresent(timeoutMs: Long = 5_000) {
        val arrived = runCatching { rule.waitUntil(timeoutMs) { exists(l.notificationOfferTitle) } }.isSuccess
        if (!arrived) return
        log("notification offer is up; leaving it with 'not now'")
        click(l.notificationOfferNotNow)
        rule.waitForIdle()
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
