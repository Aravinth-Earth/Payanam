//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import io.payanam.ui.components.IMPORT_DATABASE_FILE_BUTTON_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guard for the import-picker unification: both entry points offer ONE single-file import control,
 * and the "(Folder)" controls are gone.
 *
 * REQUIRES A FRESH INSTALL — run with `-KeepInstalled:$false`, like the full tier: the first half
 * asserts the first-run DB-init screen (see [FreshSetup] for why the app's own Delete All Data
 * cannot stand in for a wipe here). The second half needs a *provisioned* app, because Settings is
 * unreachable until create-DB → passphrase → dimensions — so this class walks the setup journey and
 * stops, rather than re-running the per-screen sweep [FullJourneyTest] already covers.
 *
 * SCOPE — a fresh run renders only the no-existing-DB branch of the init screen (1 of its 3
 * [io.payanam.ui.components.ImportDatabaseFileButton] call sites). The other two call sites are NOT exercised by this test. The three "(Folder)" label ids are
 * deleted and compiler-enforced; the folder control carried no testTag, so its absence is
 * review-enforced only — a re-added control with a new string id would still compile clean.
 *
 * ACCEPTED GAPS (documented, not hidden):
 *  - The Settings launcher/contract swap (`OpenDocumentTree` → `OpenDocument`, with the wildcard MIME
 *    array both launchers pass) is MANUAL-ONLY. The "exactly one affordance" assertion below holds
 *    before and after it, so it cannot catch a wrong-contract regression: the in-process tree cannot
 *    see which SAF contract a launcher was built with.
 *  - The `enabled`/`showProgress` split at the call sites, and the contract that a busy button keeps
 *    its label (the spinner replaces the icon only, so TalkBack still has a name), are NOT covered by
 *    this class. It asserts the control exists and is unique — never its disabled or spinner state —
 *    so a regression that re-enables a busy import button passes here silently.
 *  - The Settings import pipeline (`SettingsEncryptedImportSupport`) has no automated coverage; its
 *    new re-entry guard is the risk mitigation and is asserted only through the logging contract.
 *  - MINIMAL_MODE assumption: `MINIMAL_MODE` is hardcoded false (`app/build.gradle.kts`), and only
 *    `DataManagementSettingsSection` — rendered when `!minimalMode` — carries the import affordance.
 *    `MinimalDataManagementSection` has none, so if MINIMAL_MODE ever ships true this assertion must
 *    be revisited, not relaxed.
 */
@RunWith(AndroidJUnit4::class)
class ImportPickerUnificationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-IMPORT-PICKER")

    /** The device sleeps on a short timeout mid-run; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun oneFilePickerOnBothEntryPoints() {
        // ═══ ONBOARDING — the file affordance is the only import control ════════════════════════
        h.awaitRoot()
        if (h.isOnScreen(FreshSetup.TOUR_TITLE)) {
            h.click("Skip Tour")
        }
        // The tag is the locale-neutral anchor: the deleted "(Folder)" labels were locale-bound
        // (TA spelled them "(கோப்புறை)"), and neither folder control ever carried a tag.
        assertTrue(
            "device is not in the fresh state (expected '${FreshSetup.DB_INIT_TITLE}') — run this class with -KeepInstalled:\$false",
            h.isOnScreen(FreshSetup.DB_INIT_TITLE),
        )
        assertTrue("the onboarding import control is missing", h.existsTag(IMPORT_DATABASE_FILE_BUTTON_TAG))
        h.log("onboarding=file-control-present")

        // Hand over to the shared setup journey: it re-checks the fresh state itself (its precheck
        // turns "wrong device state" into one clear message) and continues straight into create-DB →
        // passphrase. FreshSetup.createDatabase() taps "Create New Empty Database" itself, so this
        // class does not — and the fresh-state precheck above exists so a provisioned device fails
        // with one clear message before any onboarding control assertion runs. The skip above is this class's own
        // precondition (the onboarding import control is only reachable past it) and FreshSetup's
        // equivalent is a no-op here — not the same step done twice. This class does NOT
        // log `setup=complete`: FreshSetup.run() already does.
        FreshSetup(h).run()
        h.scrollTo("Verify and proceed")
        h.click("Verify and proceed")
        h.assertVisible("Per Dimension")

        // ═══ SETTINGS — the same control, exactly once ═══════════════════════════════════════════
        h.clickNav("Settings")
        h.assertVisible("Settings")
        // The Data Management card defaults COLLAPSED and its body is only composed once expanded —
        // an assertion before this tap would pass vacuously.
        h.scrollTo(DATA_MANAGEMENT)
        h.click(DATA_MANAGEMENT)
        // scroll-first (assertVisible cannot scroll; the expanded row can sit off-screen) — same
        // discipline as the sibling classes.
        h.scrollTo(EXPORT_LABEL)
        // waitUntil IS the check — it throws if the tag never appears, so an assertTrue on the same
        // predicate right after could only re-prove what this line just proved.
        rule.waitUntil(WIDGET_WAIT_MS) { h.existsTag(IMPORT_DATABASE_FILE_BUTTON_TAG) }

        // Exact-match, not substring: the same card renders "Import uHabits", so a substring count
        // would see 2 and the Export row would not be distinguishable from a second import control.
        val importControls = rule.onAllNodes(h.labelledExactly(IMPORT_LABEL)).fetchSemanticsNodes().size
        assertEquals("Settings must offer exactly one database-import control", 1, importControls)
        h.log("settings=one-file-control")
    }

    private companion object {
        const val IMPORT_LABEL = "Import"
        const val EXPORT_LABEL = "Export"
        const val DATA_MANAGEMENT = "Data Management"
        const val WIDGET_WAIT_MS = 10_000L
    }
}
