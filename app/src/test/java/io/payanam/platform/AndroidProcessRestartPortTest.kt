//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.port.ProcessRestartOutcome
import io.payanam.domain.port.ProcessRestartRefusal
import io.payanam.shared.logging.CrashBreadcrumbStore
import io.payanam.shared.logging.CrashBreadcrumbs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowProcess

/**
 * The restart path is REACHED - the breadcrumb, the cold-restart intent and the kill request - not that
 * the process died.
 *
 * WHY THE ASSERTION IS "REACHED" AND NOT "DIED": `restartProcess` ends its process by construction, so a
 * test that ran it for real could never report on it - the JVM under the test would be the one that went
 * away. Robolectric is the tier that makes the distinction assertable: its process cannot die, so every
 * observable the method produces BEFORE the kill is assertable (the breadcrumb the next launch replays,
 * the `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` intent), and the kill itself is observable as
 * a *request* through `ShadowProcess.wasKilled`. The method then returns its defensive
 * `Refused(PROCESS_DID_NOT_END)` arm - which is exactly what a surviving process is owed, and asserting
 * it pins the contract's own account of what "reached" means.
 *
 * Each observable is one decision this call owns: record-before-you-go (the trail exists for the NEXT
 * launch, because this process's log is about to stop), the cold task (`CLEAR_TASK` is what stops the new
 * launch resuming the destroyed one), and the unconditional kill (the port's own KDoc argues why the kill
 * must not be conditional on the launch intent existing).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidProcessRestartPortTest {
    /** In-memory trail: the breadcrumb is observable without touching SharedPreferences. */
    private class RecordingStore : CrashBreadcrumbStore {
        val entries = mutableListOf<String>()

        override val location: String = "test-in-memory"

        override fun record(
            source: String,
            stage: String,
            data: Map<String, Any?>,
        ) {
            entries += "$source | $stage"
        }

        override fun readAndClear(): List<String> = entries.toList().also { entries.clear() }
    }

    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    private lateinit var store: RecordingStore

    @Before
    fun setUp() {
        // The port's constructor reaches for the process-wide logger; every app test that needs one
        // initializes it the same way (see the sibling app/src/test classes).
        UnifiedLogger.initialize(application, "test", 0)
        CrashBreadcrumbs.resetForTests()
        store = RecordingStore()
        CrashBreadcrumbs.install(store)
        ShadowProcess.clearKilledProcesses()
        // Arrange the PLATFORM's answer - the launcher intent this package resolves to - so the test pins
        // the port's decisions (which flags, start-then-kill) rather than the framework's resolution.
        val launcherIntent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(application.packageName)
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = application.packageName
                        name = LAUNCHER_ACTIVITY
                    }
            }
        shadowOf(application.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    @After
    fun tearDown() {
        CrashBreadcrumbs.resetForTests()
        ShadowProcess.clearKilledProcesses()
    }

    @Test
    fun `the restart path is reached - breadcrumb, cold-restart intent, then the kill request`() {
        val outcome = AndroidProcessRestartPort(application).restartProcess()

        // 1. The breadcrumb the NEXT launch replays, written before this process goes.
        val trail = store.readAndClear()
        assertThat(trail).hasSize(1)
        assertThat(trail.single()).contains("kill_process_for_passphrase_change")

        // 2. The cold restart: a launcher intent that clears the destroyed task.
        val started = shadowOf(application).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started.component?.className).isEqualTo(LAUNCHER_ACTIVITY)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_CLEAR_TASK)

        // 3. The kill was REACHED - a request, because in this tier the process cannot die.
        assertThat(ShadowProcess.wasKilled(Process.myPid())).isTrue()

        // 4. The contract's defensive arm: reached, but this process did not end.
        assertThat(outcome).isInstanceOf(ProcessRestartOutcome.Refused::class.java)
        assertThat((outcome as ProcessRestartOutcome.Refused).reason)
            .isEqualTo(ProcessRestartRefusal.PROCESS_DID_NOT_END)
    }

    private companion object {
        /** The activity the arranged launcher intent names; also what the started intent must carry. */
        const val LAUNCHER_ACTIVITY = "io.payanam.MainActivity"
    }
}
