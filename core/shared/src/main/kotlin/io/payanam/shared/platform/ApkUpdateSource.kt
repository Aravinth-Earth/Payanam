//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

/**
 * Whether a newer build of THIS app exists, without importing any one platform's outcome vocabulary.
 *
 * [Inconclusive] is a real answer and is kept apart from [UpToDate] on purpose: the pre-unlock update
 * hatch fails closed, so "could not tell" must never render as "you are up to date". A platform that folds
 * the two together will quietly tell users they have the latest build when it never checked.
 */
sealed class UpdateAvailability {
    /** The channel was read and holds nothing newer. */
    data object UpToDate : UpdateAvailability()

    /**
     * A newer build exists. [installable] is false when the newest release ships no artifact this
     * platform could install — which is the NORMAL case on a desktop, and is why [Newer] is separate
     * from the act of installing.
     */
    data class Newer(
        val buildNumber: Int,
        val installable: Boolean,
    ) : UpdateAvailability()

    /** The check ran and could not conclude: no release found, an unparseable tag, or a truncated scan. */
    data object Inconclusive : UpdateAvailability()
}

/**
 * A fetched build, addressed by whatever the platform needs to reopen it.
 *
 * [platformHandle] is deliberately opaque: Android holds a `FileProvider` content URI, and a platform
 * that cannot fetch anything never produces one of these at all.
 */
data class UpdateDownload(
    val buildNumber: Int,
    val platformHandle: String,
)

/** Why an install handoff was refused, so shared UI picks a message instead of printing a platform string. */
enum class InstallRefusal {
    /** Nothing was fetched yet. */
    NO_DOWNLOAD,

    /** The artifact was gone by the time the installer was asked for it. */
    FILE_MISSING,

    /** No installer on this device could accept the artifact. */
    NO_INSTALLER,

    /** This platform cannot install app updates at all. */
    PLATFORM_NOT_APPLICABLE,
}

/** Whether the platform's installer actually took the handoff. */
sealed class InstallHandoff {
    /** The platform's installer was launched with the artifact. */
    data object Launched : InstallHandoff()

    /** The handoff could not start; the platform logged why. */
    data class Refused(
        val reason: InstallRefusal,
    ) : InstallHandoff()
}

/**
 * Whether a newer build of this app exists, and whether this platform could install it.
 *
 * The two questions are deliberately split. [UpdateAvailability] is a statement about the RELEASE CHANNEL,
 * which any platform can ask, while [state] is a statement about the INSTALLER, which only a platform that
 * has one can answer. A desktop build ships as a binary its packager owns — there is no APK to replace and
 * no per-app installer — but it can still be told a newer build exists, and hiding that would be the
 * silence [SupportState] exists to prevent.
 *
 * This is a PLATFORM CAPABILITY contract, not a behaviour one: it says what the platform can do, never
 * whether a particular build should act on it.
 */
interface ApkUpdateSource {
    /**
     * What this platform can do about an available update: [SupportState.SUPPORTED] where a self-install
     * handoff exists, [SupportState.DECLINES] where the installer answers for updates instead of the app
     * (an F-Droid install), and [SupportState.NOT_APPLICABLE] where the concept does not exist.
     */
    val state: SupportState

    /**
     * The build number this platform is currently running, or null when it has none to compare.
     *
     * Null rather than a sentinel: the check compares a build number against release tags, and a desktop
     * has no build number meaning the same thing, so a fabricated 0 would read as "always out of date".
     */
    val currentBuildNumber: Int?

    /**
     * Asks the release channel whether anything newer than [currentBuildNumber] exists.
     *
     * Suspend because the real check is a paginated network walk with connect and read timeouts — never a
     * blocking call on the caller's thread.
     */
    suspend fun isNewerBuildAvailable(): UpdateAvailability

    /**
     * Fetches the newer build somewhere this platform could install from, or null when it cannot fetch an
     * installable artifact at all — having logged why.
     */
    suspend fun downloadNewerBuild(availability: UpdateAvailability): UpdateDownload?

    /**
     * Hands the fetched build to this platform's installer.
     *
     * NOT suspend and NOT fire-and-forget: it returns whether the handoff actually started, because the
     * caller has to render the difference, and a Unit-returning call would leave a user staring at a
     * button that appeared to do nothing.
     */
    fun startInstall(download: UpdateDownload): InstallHandoff
}
