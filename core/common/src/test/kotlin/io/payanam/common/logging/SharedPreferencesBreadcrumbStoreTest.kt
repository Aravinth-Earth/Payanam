//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.CrashBreadcrumbTrail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Decision tests for [SharedPreferencesBreadcrumbStore].
 *
 * Every test here pins a decision the store makes, not a method it has. The ones that matter are the
 * ring bound ([record_keepsNewestEntriesWhenTrailOverflows]) and the survival claim
 * ([aNewStoreInstanceOverTheSamePrefsStillSeesTheTrail]) — a breadcrumb trail that did not survive a
 * process death would be a trail that could never do its job, and that is the one property here that
 * nothing else in the codebase exercises.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesBreadcrumbStoreTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clearTrail()
    }

    @After
    fun tearDown() {
        clearTrail()
    }

    @Test
    fun readAndClear_returnsRecordedTrailOldestFirstAndEmptiesOnSecondCall() {
        val store = SharedPreferencesBreadcrumbStore(context)
        store.record(source = "TestSource.first", stage = "step_one")
        store.record(source = "TestSource.second", stage = "step_two")
        store.record(source = "TestSource.third", stage = "step_three")

        val first = store.readAndClear()

        assertThat(first).hasSize(3)
        // Order, not just contents: a trail replayed backwards reads as a process that ran its steps in
        // the wrong order, which is a different and wrong diagnosis from the one that happened.
        assertThat(first[0]).contains("step_one")
        assertThat(first[1]).contains("step_two")
        assertThat(first[2]).contains("step_three")
        // The empty second read is the session-attribution guarantee: a trail drained twice would replay
        // the same steps into every later session.
        assertThat(store.readAndClear()).isEmpty()
    }

    @Test
    fun readAndClear_returnsEmptyWhenNothingWasRecorded() {
        assertThat(SharedPreferencesBreadcrumbStore(context).readAndClear()).isEmpty()
    }

    @Test
    fun record_keepsNewestEntriesWhenTrailOverflows() {
        val store = SharedPreferencesBreadcrumbStore(context)
        val overflow = CrashBreadcrumbTrail.MAX_LINES + 10

        repeat(overflow) { index ->
            store.record(source = "TestSource.overflow", stage = "step_$index")
        }

        val trail = store.readAndClear()
        assertThat(trail).hasSize(CrashBreadcrumbTrail.MAX_LINES)
        // The oldest ten must go, not the newest: the crash-adjacent entries are the only ones worth
        // having, and keeping the wrong end discards exactly them.
        assertThat(trail.first()).contains("step_10")
        assertThat(trail.last()).contains("step_${overflow - 1}")
        assertThat(trail.none { it.contains("step_9") }).isTrue()
    }

    @Test
    fun aNewStoreInstanceOverTheSamePrefsStillSeesTheTrail() {
        SharedPreferencesBreadcrumbStore(context)
            .record(source = "TestSource.beforeDeath", stage = "step_before_process_death")
        // A NEW instance stands in for the next process: the trail is in the preference, not in a field
        // of the store object, so process death cannot take it. This is the whole reason the store is
        // backed by SharedPreferences and commits synchronously.
        val afterRestart = SharedPreferencesBreadcrumbStore(context)

        val trail = afterRestart.readAndClear()

        assertThat(trail).hasSize(1)
        assertThat(trail.first()).contains("step_before_process_death")
    }

    @Test
    fun readAndClear_stillParsesALineWrittenInTheOldRawFormat() {
        // The exact payload shape `CrashSafeBreadcrumbs` writes, and that a build in the field still
        // holds. The preference name and key are deliberately unchanged, so a trail recorded before the
        // port has to remain replayable after it — otherwise the breadcrumb is lost in precisely the
        // upgrade window where the crash it explains is most likely to be investigated.
        val legacyLine = "2026-09-27 21:04:11.512 | LegacySource.old | legacy_stage | key=value"
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRAIL, legacyLine)
            .commit()

        val trail = SharedPreferencesBreadcrumbStore(context).readAndClear()

        assertThat(trail).containsExactly(legacyLine)
    }

    @Test
    fun readAndClear_keepsAStoreWrittenOnTopOfALegacyTrail() {
        val legacyLine = "2026-09-27 21:04:11.512 | LegacySource.old | legacy_stage"
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRAIL, legacyLine)
            .commit()
        val store = SharedPreferencesBreadcrumbStore(context)

        store.record(source = "TestSource.mixed", stage = "step_after_upgrade")
        val trail = store.readAndClear()

        assertThat(trail).hasSize(2)
        assertThat(trail[0]).contains("legacy_stage")
        assertThat(trail[1]).contains("step_after_upgrade")
    }

    @Test
    fun record_doesNotThrowWhenStorageFails() {
        val failingContext = ThrowingContext(context)

        SharedPreferencesBreadcrumbStore(failingContext)
            .record(source = "TestSource.failing", stage = "step_with_broken_prefs")

        // The real prefs are untouched, i.e. the failing store neither half-wrote the trail nor threw at
        // the caller — and the healthy store over the same preference still reads an empty trail rather
        // than a corrupted one.
        assertThat(SharedPreferencesBreadcrumbStore(context).readAndClear()).isEmpty()
    }

    @Test
    fun record_redactsUserContentAndPathsThroughTheSharedSanitizer() {
        SharedPreferencesBreadcrumbStore(context)
            .record(
                source = "TestSource.redaction",
                stage = "step",
                data =
                    mapOf(
                        "taskTitle" to "Private Task",
                        "dbPath" to "/data/data/io.payanam/databases/tasks.db",
                    ),
            )

        val entry = SharedPreferencesBreadcrumbStore(context).readAndClear().single()
        // Not a claim this store invented: `CrashBreadcrumbTrail.formatEntry` redacts for every store on
        // the contract, and `CrashSafeBreadcrumbs` does not redact at all — the difference is the reason
        // the call sites are being moved onto the port rather than left where they are.
        //
        // Both placeholders below are named on purpose. `path` is one of `LogSanitizer`'s CONTENT-class
        // tokens, so a key containing it loses its value wholesale; `<path>` is only what an
        // UNCLASSIFIED key's path-shaped value becomes. Asserting "either placeholder" would pass while
        // the sanitizer's rule for one of these keys silently changed.
        assertThat(entry).contains("taskTitle=<redacted>")
        assertThat(entry).contains("dbPath=<redacted>")
        assertThat(entry).doesNotContain("Private Task")
        assertThat(entry).doesNotContain("/data/data/io.payanam/databases/tasks.db")
    }

    @Test
    fun location_namesThePreferenceTheTrailLivesIn() {
        val location = SharedPreferencesBreadcrumbStore(context).location

        assertThat(location).contains(PREFS_NAME)
        assertThat(location).contains(KEY_TRAIL)
    }

    private fun clearTrail() {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TRAIL)
            .commit()
    }

    private companion object {
        private const val PREFS_NAME = "payanam_crash_breadcrumbs"
        private const val KEY_TRAIL = "trail"
    }
}

/**
 * A [Context] whose [getSharedPreferences] throws, standing in for a storage failure the store must
 * survive: a breadcrumb is raised *before* a step is attempted, so the attempt proceeds either way.
 *
 * The failure is forced rather than provoked through a real `SharedPreferences` so the test does not
 * depend on Robolectric's or the platform's idea of when a write fails.
 */
private class ThrowingContext(
    private val delegate: Context,
) : ContextWrapper(delegate) {
    override fun getSharedPreferences(
        name: String?,
        mode: Int,
    ): android.content.SharedPreferences = error("Simulated SharedPreferences failure for '$name'")
}
