//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.updates.ChannelStatus
import io.payanam.shared.updates.UpdateChannel
import org.json.JSONArray
import org.junit.Test

/** A GitHub releases list as the list endpoint returns it: one entry per channel release. */
private val RELEASE_LIST_JSON =
    """
    [
      {
        "tag_name": "dev-v12",
        "name": "Payanam #12",
        "html_url": "https://github.com/Aravinth-Earth/Payanam/releases/tag/dev-v12",
        "assets": [
          {"name": "payanam_1.0.0_debug_arm64-v8a.apk", "browser_download_url": "https://d/dev12.apk"},
          {"name": "payanam_1.0.0_debug_arm64-v8a.apk.sha256", "browser_download_url": "https://d/dev12.sha256"}
        ]
      },
      {
        "tag_name": "v11",
        "name": "Payanam #11",
        "html_url": "https://github.com/Aravinth-Earth/Payanam/releases/tag/v11",
        "assets": [
          {"name": "payanam_1.0.0_release_arm64-v8a.apk", "browser_download_url": "https://d/rel11.apk"}
        ]
      }
    ]
    """.trimIndent()

/** Build an assets array from `(name, download url)` pairs. */
private fun assetsOf(vararg entries: Pair<String, String>): JSONArray =
    JSONArray(
        entries
            .map { (name, url) -> """{"name": "$name", "browser_download_url": "$url"}""" }
            .toString(),
    )

/**
 * The pure half of [UpdateChecker]: which channel a release tag names, which of a release's assets is
 * downloadable for a running build type, and what a release list parses to.
 *
 * None of it needs the network, so all of it is asserted here. The network path (`check`, the page
 * walk) is deliberately left alone — it is IO, and the branch this file exists to pin is the one that
 * decides *which* URL a fetch would later hand to a download.
 *
 * The load-bearing property is fail-closed selection. Every absence must surface as
 * [ApkAssetSelection.typeMismatch] rather than as a null URL, because downstream a null URL that is
 * not flagged reads as "up to date" and the user is never told an update existed.
 */
class UpdateCheckerPureLogicTest {
    @Test
    fun `a persistent tag resolves to the channel its prefix names`() {
        assertThat(channelFromTag("dev-v12")).isEqualTo(UpdateChannel.DEV)
        assertThat(channelFromTag("beta-v3")).isEqualTo(UpdateChannel.BETA)
        assertThat(channelFromTag("stable-v5")).isEqualTo(UpdateChannel.STABLE)
    }

    @Test
    fun `a bare version tag is stable and every other tag is refused`() {
        // The two spellings this repo publishes both have to keep parsing.
        assertThat(channelFromTag("v12")).isEqualTo(UpdateChannel.STABLE)

        // Refusal, not a guess: an unknown prefix, a wrong case, a suffix on the version and a
        // truncated tag all return null so the caller can tell "not a release tag" from "a release
        // whose channel is unknown".
        assertThat(channelFromTag("nightly-v3")).isNull()
        assertThat(channelFromTag("BETA-v3")).isNull()
        assertThat(channelFromTag("dev-v12-rc1")).isNull()
        assertThat(channelFromTag("beta-v")).isNull()
        assertThat(channelFromTag("")).isNull()
    }

    @Test
    fun `the apk of the running build type is selected with its sha256 sibling`() {
        // Both types ship in one release: the running type decides, not presence.
        val assets =
            assetsOf(
                "payanam_1.0.0_release_arm64-v8a.apk" to "https://d/rel.apk",
                "payanam_1.0.0_release_arm64-v8a.apk.sha256" to "https://d/rel.sha256",
                "payanam_1.0.0_debug_arm64-v8a.apk" to "https://d/dbg.apk",
                "payanam_1.0.0_debug_arm64-v8a.apk.sha256" to "https://d/dbg.sha256",
            )

        val debug = selectApkAssets(assets, expectedType = "debug")

        assertThat(debug.apkUrl).isEqualTo("https://d/dbg.apk")
        assertThat(debug.sha256Url).isEqualTo("https://d/dbg.sha256")
        assertThat(debug.typeMismatch).isFalse()
        // Distinct, first-seen order — the fail-closed log reads this list.
        assertThat(debug.assetTypesSeen).containsExactly("release", "debug").inOrder()

        val release = selectApkAssets(assets, expectedType = "release")

        assertThat(release.apkUrl).isEqualTo("https://d/rel.apk")
        assertThat(release.sha256Url).isEqualTo("https://d/rel.sha256")
        assertThat(release.typeMismatch).isFalse()
    }

    @Test
    fun `a release shipping only the other build type fails closed`() {
        val assets = assetsOf("payanam_1.0.0_release_arm64-v8a.apk" to "https://d/rel.apk")

        val selection = selectApkAssets(assets, expectedType = "debug")

        assertThat(selection.apkUrl).isNull()
        assertThat(selection.sha256Url).isNull()
        assertThat(selection.typeMismatch).isTrue()
        assertThat(selection.assetTypesSeen).containsExactly("release")
    }

    @Test
    fun `an absent empty untyped or checksum-only asset list all fail closed`() {
        // No assets key at all, an empty list, a legacy untyped name and a release whose only
        // type-bearing asset is the checksum: each yields a mismatch and never a URL.
        val untyped = assetsOf("payanam-1.0.0.apk" to "https://d/legacy.apk")
        val checksumOnly = assetsOf("payanam_1.0.0_debug_arm64-v8a.apk.sha256" to "https://d/dbg.sha256")

        for (assets in listOf(null, JSONArray("[]"), untyped, checksumOnly)) {
            val selection = selectApkAssets(assets, expectedType = "debug")

            assertThat(selection.apkUrl).isNull()
            assertThat(selection.sha256Url).isNull()
            assertThat(selection.typeMismatch).isTrue()
        }
        // The checksum still names its type, so it shows up in the trace even though it is not
        // selectable.
        assertThat(selectApkAssets(checksumOnly, expectedType = "debug").assetTypesSeen)
            .containsExactly("debug")
    }

    @Test
    fun `a well formed release list parses to one status per channel release`() {
        val statuses = requireNotNull(parseReleases(RELEASE_LIST_JSON, runningType = "debug"))

        assertThat(statuses).hasSize(2)
        assertThat(statuses.map { it.channel })
            .containsExactly(UpdateChannel.DEV, UpdateChannel.STABLE)
            .inOrder()

        val dev = statuses.first()
        assertThat(dev.buildNumber).isEqualTo(12)
        assertThat(dev.releaseUrl).isEqualTo("https://github.com/Aravinth-Earth/Payanam/releases/tag/dev-v12")
        assertThat(dev.apkDownloadUrl).isEqualTo("https://d/dev12.apk")
        assertThat(dev.apkSha256Url).isEqualTo("https://d/dev12.sha256")
        assertThat(dev.typeMismatch).isFalse()

        // The stable release ships only a release apk, so a debug install fails closed on it.
        val stable = statuses.last()
        assertThat(stable.buildNumber).isEqualTo(11)
        assertThat(stable.apkDownloadUrl).isNull()
        assertThat(stable.apkSha256Url).isNull()
        assertThat(stable.typeMismatch).isTrue()
    }

    @Test
    fun `non-channel tags and malformed array entries are skipped not fatal`() {
        val body =
            """
            [
              "not an object",
              42,
              null,
              {"tag_name": "nightly-v3", "name": "Payanam #99"},
              {"tag_name": "beta-v4", "name": "Payanam #4", "assets": []}
            ]
            """.trimIndent()

        val statuses = requireNotNull(parseReleases(body, runningType = "debug"))

        // A non-channel tag is not a release this checker speaks about; a beta release with no assets
        // still is, and is reported as a mismatch rather than dropped.
        assertThat(statuses).hasSize(1)
        assertThat(statuses.single().channel).isEqualTo(UpdateChannel.BETA)
        assertThat(statuses.single().buildNumber).isEqualTo(4)
        assertThat(statuses.single().typeMismatch).isTrue()
        assertThat(statuses.single().apkDownloadUrl).isNull()
    }

    @Test
    fun `a body that is not a release array returns null and an empty array returns empty`() {
        // The fail-closed split the caller branches on: null means "could not parse" and becomes
        // PARSE_ERROR, whereas a genuinely empty list means "no releases".
        assertThat(parseReleases("not json", runningType = "debug")).isNull()
        assertThat(parseReleases("", runningType = "debug")).isNull()
        assertThat(parseReleases("{}", runningType = "debug")).isNull()
        assertThat(parseReleases("[]", runningType = "debug")).isEmpty()
    }

    @Test
    fun `a release with no parseable build number keeps its status with a null build`() {
        val body =
            """
            [
              {"tag_name": "dev-v12", "html_url": "https://d/rel"},
              {"tag_name": "dev-v13", "name": "Payanam", "html_url": "https://d/rel13"}
            ]
            """.trimIndent()

        val statuses = requireNotNull(parseReleases(body, runningType = "debug"))

        // An absent name falls back to the tag, which carries no "#N"; the release is still reported
        // so it can resolve to RELEASE_UNREADABLE instead of NO_RELEASE_ON_CHANNEL.
        assertThat(statuses).hasSize(2)
        assertThat(statuses.map { it.buildNumber }).containsExactly(null, null).inOrder()
        assertThat(statuses.map { it.releaseUrl }).containsExactly("https://d/rel", "https://d/rel13").inOrder()
        assertThat(statuses.map { it.typeMismatch }).containsExactly(true, true)
    }

    @Test
    fun `an artifact name matches only the running build type`() {
        assertThat(artifactNameMatchesBuildType("payanam_1.0.0_debug_arm64-v8a.apk", "debug")).isTrue()
        assertThat(artifactNameMatchesBuildType("payanam_1.0.0_release_arm64-v8a.apk", "release")).isTrue()

        // The other build type, the checksum sibling and a legacy untyped name all fail closed, so a
        // persisted download URL is never retried under the wrong type.
        assertThat(artifactNameMatchesBuildType("payanam_1.0.0_release_arm64-v8a.apk", "debug")).isFalse()
        assertThat(artifactNameMatchesBuildType("payanam_1.0.0_debug_arm64-v8a.apk", "release")).isFalse()
        assertThat(artifactNameMatchesBuildType("payanam_1.0.0_debug_arm64-v8a.apk.sha256", "debug")).isFalse()
        assertThat(artifactNameMatchesBuildType("payanam-1.0.0.apk", "debug")).isFalse()
        assertThat(artifactNameMatchesBuildType("", "debug")).isFalse()
    }

    @Test
    fun `a status parsed from a release is the shape the shared resolve step reads`() {
        // The constructor contract parseReleases relies on: unset urls and a false mismatch by default.
        assertThat(ChannelStatus(channel = UpdateChannel.BETA, buildNumber = 3, releaseUrl = "u"))
            .isEqualTo(
                ChannelStatus(
                    channel = UpdateChannel.BETA,
                    buildNumber = 3,
                    releaseUrl = "u",
                    apkDownloadUrl = null,
                    apkSha256Url = null,
                    typeMismatch = false,
                ),
            )
    }
}
