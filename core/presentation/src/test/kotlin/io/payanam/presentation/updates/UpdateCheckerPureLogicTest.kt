//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.updates.ReleaseStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** A GitHub release asset URL in the shape GitHub serves it; the policy pins scheme, host and path. */
private fun assetUrl(fileName: String): String = "https://github.com/Aravinth-Earth/Payanam/releases/download/test/$fileName"

/** The debug APK the newest fixture release ships, as a real download URL. */
private const val DEBUG_APK_URL =
    "https://github.com/Aravinth-Earth/Payanam/releases/download/v1.0.0/" +
        "payanam_1.0.0_debug_arm64-v8a.apk"

/** The checksum sibling of [DEBUG_APK_URL]. */
private const val DEBUG_SHA_URL = "$DEBUG_APK_URL.sha256"

/** The release-type APK the older fixture release ships. */
private const val RELEASE_APK_URL =
    "https://github.com/Aravinth-Earth/Payanam/releases/download/v0.9.0/" +
        "payanam_0.9.0_release_arm64-v8a.apk"

/** A GitHub releases list as the list endpoint returns it: one entry per versioned release. */
private val RELEASE_LIST_JSON =
    """
    [
      {
        "tag_name": "v1.0.0",
        "name": "Payanam 1.0.0",
        "html_url": "https://github.com/Aravinth-Earth/Payanam/releases/tag/v1.0.0",
        "assets": [
          {"name": "payanam_1.0.0_debug_arm64-v8a.apk", "browser_download_url": "$DEBUG_APK_URL"},
          {"name": "payanam_1.0.0_debug_arm64-v8a.apk.sha256", "browser_download_url": "$DEBUG_SHA_URL"}
        ]
      },
      {
        "tag_name": "v0.9.0",
        "name": "Payanam 0.9.0",
        "html_url": "https://github.com/Aravinth-Earth/Payanam/releases/tag/v0.9.0",
        "assets": [
          {"name": "payanam_0.9.0_release_arm64-v8a.apk", "browser_download_url": "$RELEASE_APK_URL"}
        ]
      }
    ]
    """.trimIndent()

/** Build an assets array from `(name, download url)` pairs. */
private fun assetsOf(vararg entries: Pair<String, String>): JsonArray =
    JsonArray(
        entries.map { (name, url) ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(name),
                    "browser_download_url" to JsonPrimitive(url),
                ),
            )
        },
    )

/**
 * The pure half of [UpdateChecker]: which version a release tag names, which of a release's assets is
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
    fun `a canonical release tag parses to its version name and packed code`() {
        assertThat(parseReleaseTag("v1.2.3")).isEqualTo(ReleaseVersion(name = "1.2.3", code = 1_002_003))
        assertThat(parseReleaseTag("v0.0.1")).isEqualTo(ReleaseVersion(name = "0.0.1", code = 1))
        assertThat(parseReleaseTag("v12.0.0")).isEqualTo(ReleaseVersion(name = "12.0.0", code = 12_000_000))
    }

    @Test
    fun `every tag the old build-number scheme produced is refused`() {
        // The checker speaks versions now, so a tag that names a build number names nothing it can read.
        assertThat(parseReleaseTag("dev-v12")).isNull()
        assertThat(parseReleaseTag("beta-v3")).isNull()
        assertThat(parseReleaseTag("stable-v5")).isNull()
        assertThat(parseReleaseTag("v12")).isNull()
        assertThat(parseReleaseTag("latest")).isNull()
        assertThat(parseReleaseTag("latest-nightly")).isNull()
    }

    @Test
    fun `a malformed version tag is refused rather than repaired`() {
        // Refusal, not a guess: a pre-release suffix, a missing field, extra content and a wrong case all
        // return null so the caller can tell "not a release tag" from "a release this checker cannot read".
        assertThat(parseReleaseTag("v1.2.3-rc1")).isNull()
        assertThat(parseReleaseTag("v1.2")).isNull()
        assertThat(parseReleaseTag("v1.2.3.4")).isNull()
        assertThat(parseReleaseTag("V1.2.3")).isNull()
        assertThat(parseReleaseTag("1.2.3")).isNull()
        assertThat(parseReleaseTag("")).isNull()
    }

    @Test
    fun `a tag whose segment exceeds 999 is refused rather than packed alongside a smaller one`() {
        // 999 is the bound `packed()` enforces. Above it, v1.1000.0 packs to the same code as v2.0.0,
        // so a genuinely newer tag would read as already installed.
        assertThat(parseReleaseTag("v1.1000.0")).isNull()
        assertThat(parseReleaseTag("v1.0.1000")).isNull()
        assertThat(parseReleaseTag("v1000.0.0")).isNull()
        assertThat(parseReleaseTag("v1.999.999")).isEqualTo(ReleaseVersion(name = "1.999.999", code = 1_999_999))
    }

    @Test
    fun `the apk of the running build type is selected with its sha256 sibling`() {
        // Both types ship in one release: the running type decides, not presence.
        val assets =
            assetsOf(
                "payanam_1.0.0_release_arm64-v8a.apk" to assetUrl("rel.apk"),
                "payanam_1.0.0_release_arm64-v8a.apk.sha256" to assetUrl("rel.apk.sha256"),
                "payanam_1.0.0_debug_arm64-v8a.apk" to assetUrl("dbg.apk"),
                "payanam_1.0.0_debug_arm64-v8a.apk.sha256" to assetUrl("dbg.apk.sha256"),
            )

        val debug = selectApkAssets(assets, expectedType = "debug")

        assertThat(debug.apkUrl).isEqualTo(assetUrl("dbg.apk"))
        assertThat(debug.sha256Url).isEqualTo(assetUrl("dbg.apk.sha256"))
        assertThat(debug.typeMismatch).isFalse()
        // Distinct, first-seen order — the fail-closed log reads this list.
        assertThat(debug.assetTypesSeen).containsExactly("release", "debug").inOrder()

        val release = selectApkAssets(assets, expectedType = "release")

        assertThat(release.apkUrl).isEqualTo(assetUrl("rel.apk"))
        assertThat(release.sha256Url).isEqualTo(assetUrl("rel.apk.sha256"))
        assertThat(release.typeMismatch).isFalse()
    }

    @Test
    fun `a release shipping only the other build type fails closed`() {
        val assets = assetsOf("payanam_1.0.0_release_arm64-v8a.apk" to assetUrl("rel.apk"))

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
        val untyped = assetsOf("payanam-1.0.0.apk" to assetUrl("legacy.apk"))
        val checksumOnly = assetsOf("payanam_1.0.0_debug_arm64-v8a.apk.sha256" to assetUrl("dbg.apk.sha256"))

        for (assets in listOf(null, JsonArray(emptyList()), untyped, checksumOnly)) {
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
    fun `a well formed release list parses to one status per versioned release`() {
        val statuses = requireNotNull(parseReleases(RELEASE_LIST_JSON, runningType = "debug"))

        assertThat(statuses).hasSize(2)
        assertThat(statuses.map { it.versionName }).containsExactly("1.0.0", "0.9.0").inOrder()
        assertThat(statuses.map { it.versionCode }).containsExactly(1_000_000, 9_000).inOrder()

        val newest = statuses.first()
        assertThat(newest.releaseUrl).isEqualTo("https://github.com/Aravinth-Earth/Payanam/releases/tag/v1.0.0")
        assertThat(newest.apkDownloadUrl).isEqualTo(DEBUG_APK_URL)
        assertThat(newest.apkSha256Url).isEqualTo(DEBUG_SHA_URL)
        assertThat(newest.typeMismatch).isFalse()

        // The older release ships only a release apk, so a debug install fails closed on it.
        val older = statuses.last()
        assertThat(older.apkDownloadUrl).isNull()
        assertThat(older.apkSha256Url).isNull()
        assertThat(older.typeMismatch).isTrue()
    }

    @Test
    fun `unversioned tags and malformed array entries are skipped not fatal`() {
        val body =
            """
            [
              "not an object",
              42,
              null,
              {"tag_name": "nightly-v3", "name": "Payanam #99"},
              {"tag_name": "v0.4.0", "name": "Payanam 0.4.0", "assets": []}
            ]
            """.trimIndent()

        val statuses = requireNotNull(parseReleases(body, runningType = "debug"))

        // A tag carrying no version is not a release this checker speaks about; a versioned release with no
        // assets still is, and is reported as a mismatch rather than dropped.
        assertThat(statuses).hasSize(1)
        assertThat(statuses.single().versionName).isEqualTo("0.4.0")
        assertThat(statuses.single().versionCode).isEqualTo(4_000)
        assertThat(statuses.single().typeMismatch).isTrue()
        assertThat(statuses.single().apkDownloadUrl).isNull()
    }

    @Test
    fun `null scalars and non-array assets read the way the previous parser read them`() {
        val body =
            """
            [
              {"tag_name": "v1.2.0", "name": null, "html_url": null, "assets": {}},
              {"tag_name": "v0.5.0", "name": "Payanam 0.5.0", "assets": "not-an-array"},
              {"tag_name": 123, "name": "Payanam #7", "assets": []}
            ]
            """.trimIndent()

        val statuses = requireNotNull(parseReleases(body, runningType = "debug"))

        // A numeric tag is coerced to its text ("123") and then refused as a version, exactly as before; an
        // explicit null url falls back to "".
        assertThat(statuses.map { it.versionName }).containsExactly("1.2.0", "0.5.0").inOrder()
        val first = statuses.first()
        assertThat(first.releaseUrl).isEqualTo("")
        assertThat(first.typeMismatch).isTrue()

        // `assets` shaped as an object, or as a string, is no asset list: the release is still
        // reported and fails closed rather than disappearing from the scan.
        val second = statuses.last()
        assertThat(second.apkDownloadUrl).isNull()
        assertThat(second.typeMismatch).isTrue()
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
    fun `a tag that names no version is skipped rather than reported without one`() {
        val body =
            """
            [
              {"tag_name": "dev-v12", "html_url": "https://d/rel"},
              {"tag_name": "v1.0.0", "name": "Payanam", "html_url": "https://d/rel100"}
            ]
            """.trimIndent()

        val statuses = requireNotNull(parseReleases(body, runningType = "debug"))

        // The version is the tag, and only a tag can supply it, so an unreadable tag cannot become a status:
        // the caller sees NO_RELEASE from a scan that lost every release rather than a version-less entry.
        assertThat(statuses).hasSize(1)
        assertThat(statuses.single().versionName).isEqualTo("1.0.0")
        assertThat(statuses.single().releaseUrl).isEqualTo("https://d/rel100")
        assertThat(statuses.single().typeMismatch).isTrue()
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
        assertThat(ReleaseStatus(versionName = "0.3.0", versionCode = 3_000, releaseUrl = "u"))
            .isEqualTo(
                ReleaseStatus(
                    versionName = "0.3.0",
                    versionCode = 3_000,
                    releaseUrl = "u",
                    apkDownloadUrl = null,
                    apkSha256Url = null,
                    typeMismatch = false,
                ),
            )
    }
}
