//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import android.content.Intent
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.port.ProcessRestart
import io.payanam.domain.port.ProcessRestartOutcome
import io.payanam.domain.port.ProcessRestartRefusal
import io.payanam.shared.logging.CrashBreadcrumbs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's [ProcessRestart]: re-launch the task, then end this process.
 *
 * ## WHAT THIS PRESERVES, CALL BY CALL
 *
 * This is the code that was inline in
 * `DatabasePassphraseChangeViewModel.restartProcess` (`:135-146` at the time of extraction), moved here
 * without changing its behaviour. The sequence, in its original order, is:
 *
 * 1. [CrashBreadcrumbs.record] with stage [RESTART_STAGE] — a breadcrumb the NEXT launch replays,
 *    because this process is about to stop existing and the log flush below is the last thing that will
 *    ever be written to it.
 * 2. [UnifiedLogger.flush], so the breadcrumb's own log line reaches the file before the process goes.
 * 3. `getLaunchIntentForPackage(packageName)`, and INSIDE a `?.let` — set
 *    `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` and `startActivity`. `CLEAR_TASK` is what makes
 *    the new task start cold rather than resume the destroyed one.
 * 4. [Process.killProcess] with [Process.myPid] — **unconditionally**.
 *
 * ## THE TWO THINGS THAT LOOK LIKE BUGS AND ARE NOT
 *
 * **Step 4 runs even when step 3's intent was null.** That is preserved on purpose. This is not a
 * correctness slip: the process kill is the point of the call, and the launch intent is an optimization on
 * top of it. A launcher that reports no launch intent (a work-profile-restricted package, a package whose
 * `getLaunchIntentForPackage` returns null) must still get its process replaced, and a cold start by the
 * user or the system is what covers the missing intent. Making the kill conditional would leave the app
 * running with a rekeyed database and the old passphrase's session still cached — the exact state this
 * call exists to leave.
 *
 * **The return value is unreachable on Android.** [restartProcess] ends the process, so
 * `ProcessRestartOutcome.Launched` is returned only on the path where the kill somehow did not end the
 * process, and the `Refused` arm is a defensive fallback for a platform that refused the kill rather
 * than a case this code expects to reach. A caller written against the contract is already told that
 * nothing after this call runs.
 *
 * ## WHY A MARKER IS NOT WRITTEN HERE
 *
 * Android's equivalent is `MainActivity.persistRestartMarker` (SharedPreferences, `commit()`), but that
 * marker carries a database-artifact SNAPSHOT — sizes and a directory listing — for a diagnosis that
 * belongs to the import flow which still owns that code. Reproducing a stripped-down version of it here
 * would be a second answer to "what was the database before the restart" that disagrees with the first.
 * The breadcrumb above is the part that is genuinely about *this* call.
 */
@Singleton
class AndroidProcessRestartPort
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ProcessRestart {
        private val logger = UnifiedLogger.getInstance()

        /**
         * The four calls above, in that order, returning only if the process survived the kill.
         */
        override fun restartProcess(): ProcessRestartOutcome {
            CrashBreadcrumbs.record(
                source = SOURCE,
                stage = RESTART_STAGE,
            )
            logger.flush()
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
            }
            Process.killProcess(Process.myPid())
            logger.w(
                source = SOURCE,
                message = "The process survived a killProcess call; reporting the restart as unconfirmed",
            )
            return ProcessRestartOutcome.Refused(
                reason = ProcessRestartRefusal.PROCESS_DID_NOT_END,
                detail = "killProcess returned without ending the process",
            )
        }

        private companion object {
            private const val SOURCE = "AndroidProcessRestartPort.restartProcess"

            /**
             * The breadcrumb stage name, kept byte-for-byte as the inline code spelled it.
             *
             * A stage string is a key the next launch's trail is read with, so changing it would orphan
             * every breadcrumb a currently-installed build wrote.
             */
            private const val RESTART_STAGE = "kill_process_for_passphrase_change"
        }
    }
