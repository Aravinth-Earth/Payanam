//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Step 1 of the pre-unlock update hatch's promotion out of `:app`: the framework-free state and
 * callback contract, declared here so both platforms can supply it.
 *
 * WHY THE SECTION CANNOT TRAVEL WITHOUT ITS VIEWMODEL: `PreUnlockUpdateSection` took exactly one
 * parameter, `viewModel: PreUnlockUpdateViewModel`, and that ViewModel is an `:app` Hilt class - it
 * takes an `@ApplicationContext Context` and reaches into `AutoDownloadManager` and `FileProvider`.
 * `:app` depends on `:core:ui`, so `:core:ui` can never import it, and the section cannot be moved
 * while its only parameter names a type from the layer above it. Its strings were never the blocker:
 * all the copy this section renders already ships in `:core:strings` with EN+TA parity.
 *
 * WHAT THIS FILE IS INSTEAD: the four values the section reads and the three things the user can do,
 * with the same types the ViewModel exposes, so step 2 rewrites the section's signature from
 * `viewModel: PreUnlockUpdateViewModel` to `uiState: PreUnlockUpdateUiState` plus three callbacks and
 * deletes the `:app` parameter without changing a pixel of the layout. The `:app` ViewModel keeps every
 * behaviour - the 60s cooldown, the DEV-channel check, the poll job, the FileProvider handoff - and
 * keeps its own guard that refuses to act on an F-Droid install; this file only names what the shared
 * section needs in order to draw and to route a tap.
 *
 * NOTHING HERE IS PLATFORM-BOUND: no Android SDK type and no Android string-resource id appears below,
 * which is the whole point - the desktop target of this module cannot see either one.
 */
package io.payanam.core.ui.updates

import io.payanam.presentation.download.DownloadUiState
import io.payanam.shared.platform.SupportState

/**
 * Everything the pre-unlock update hatch draws, and nothing it does not.
 *
 * WHY ONE OBJECT AND NOT SIX PARAMETERS: these five values are one snapshot of one hatch, and a
 * section that took them separately could be handed a build number from one check beside a result from
 * another - a hatch that reports "up to date" next to the version number of a download it never made.
 * One object is one coherent read, matching `SettingsDatabaseSecuritySection(uiState: SettingsUiState,
 * ...)`, the already-landed precedent for a promoted section that takes plain values.
 */
data class PreUnlockUpdateUiState(
    /** The build the user is running, shown beside the "version" label as `#1234 (dev)`. */
    val buildNumber: Int,
    /**
     * What this platform can do about its own updates, which decides whether the hatch renders as a
     * working control at all.
     *
     * Three answers are rendered, and the vocabulary is [SupportState] rather than a fresh enum so a
     * screen never invents its own platform vocabulary:
     *  - `SUPPORTED` - the hatch draws, and its actions are this platform's own.
     *  - `DECLINES` - the platform has an updater and refuses to act on this install (Android under
     *    F-Droid, whose packager owns updates), so the hatch is replaced by a static note instead of a
     *    button that could never work.
     *  - `NOT_APPLICABLE` - the platform has no in-app updater to decline (the desktop, whose `.deb` is
     *    installed by its packager), so the same static note states the absence.
     *
     * WHY THREE VALUES AND NOT THE BOOLEAN THIS REPLACED: `isFDroidBuild: Boolean` could only have
     * said yes or no about one installer on one platform. The desktop is a THIRD answer it could not
     * express at all - not F-Droid-managed, yet with nothing to press - and answering "no" there would
     * have rendered a fully working Android hatch on a build that cannot install an APK, which is the
     * dead control this hatch refuses to draw. Naming the state rather than the installer makes the
     * desktop's third answer expressible without `:core:ui` knowing what F-Droid is.
     *
     * Defaulted to `SUPPORTED` on purpose, unlike the boolean it replaced: "I can update myself" is the
     * reading of a platform that never declared itself, and the only two callers both declare the state
     * explicitly - a default here would exist only so a new caller need not think about it.
     */
    val platformSupport: SupportState = SupportState.SUPPORTED,
    /** Where the APK download has got to; the hatch reads the type and the derived percentage. */
    val downloadState: DownloadUiState = DownloadUiState.Idle,
    /** Whether a check is in flight right now, which greys out the button and shows "checking". */
    val isChecking: Boolean = false,
    /**
     * The last check's outcome as the ViewModel's own reason key - `"up_to_date"`,
     * `"type_mismatch"`, `"no_release"`, `"check_failed_<reason>"`, or `"update_available_<build>"` -
     * or `null` when no check has run.
     *
     * Kept as the ViewModel's `String?` rather than re-declared as a sealed type on purpose: the section
     * matches these keys itself and renders different copy and a different colour per key, so a
     * `null`-defaulting state reads as "nothing has been checked yet", which is exactly what it means.
     */
    val checkResultMessage: String? = null,
)

/**
 * The three things the user can do at the pre-unlock hatch, all of them manual taps.
 *
 * Named for what the user does, not for the ViewModel's method names: `downloadRequested` downloads and
 * does not install, because the hatch's next screen still asks the user to tap "Install now" on a
 * finished download. Collapsing the two would hide that a separate confirmation exists.
 */
interface PreUnlockUpdateActions {
    /** The user tapped "Check for update" - ask the DEV channel whether a newer build exists. */
    fun onCheckRequested()

    /** The user tapped "Download & install" - start fetching the APK the last check found. */
    fun onDownloadRequested()

    /** The user tapped "Install now" - hand the finished APK to this platform's installer. */
    fun onInstallRequested()
}
