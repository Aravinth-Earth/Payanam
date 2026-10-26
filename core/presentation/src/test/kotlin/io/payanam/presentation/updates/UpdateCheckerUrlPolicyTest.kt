//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A real paginated releases page: the URL the checker would fetch next. */
private const val TRUSTED_API_PAGE =
    "https://api.github.com/repos/Aravinth-Earth/Payanam/releases?per_page=100&page=2"

/** A real release asset: the URL that would be handed to a downloader. */
private const val TRUSTED_ASSET =
    "https://github.com/Aravinth-Earth/Payanam/releases/download/v1.0.0/payanam_1.0.0_debug_arm64-v8a.apk"

/**
 * The trusted-URL policy for the two network-supplied URLs in the update path: the pagination
 * `next` URL out of the `Link` header, and an asset URL out of the release JSON.
 *
 * Both are attacker-influenced if the response is tampered with, so the property pinned here is
 * fail-closed rejection: a URL is trusted because of its scheme, host and path — never because it
 * looks familiar. A refused page URL stops the walk without completing the scan, and a refused
 * asset URL means the release offers no update at all.
 *
 * The network walk itself (`check`, `fetchPage`) is not exercised here: "no further fetch is
 * attempted" is pinned as the pure [nextPageDecision] the walk branches on.
 */
class UpdateCheckerUrlPolicyTest {
    @Test
    fun `a releases api page is accepted only on the project host and path`() {
        assertThat(isTrustedReleasesApiUrl(TRUSTED_API_PAGE)).isTrue()
        assertThat(isTrustedReleasesApiUrl("https://api.github.com/repos/Aravinth-Earth/Payanam/releases")).isTrue()
        // Scheme and host are case-insensitive; the path is not.
        assertThat(isTrustedReleasesApiUrl("HTTPS://API.GITHUB.COM/repos/Aravinth-Earth/Payanam/releases")).isTrue()

        assertThat(releasesApiRejection(TRUSTED_API_PAGE)).isNull()
    }

    @Test
    fun `plain http is refused even on the right host and path`() {
        assertThat(isTrustedReleasesApiUrl(TRUSTED_API_PAGE.replace("https://", "http://"))).isFalse()
        assertThat(isTrustedAssetUrl(TRUSTED_ASSET.replace("https://", "http://"))).isFalse()

        // The reason is log-safe: a constant name, never URL text.
        assertThat(releasesApiRejection(TRUSTED_API_PAGE.replace("https://", "http://")))
            .isEqualTo(UrlRejection.NOT_HTTPS)
        assertThat(assetRejection(TRUSTED_ASSET.replace("https://", "http://")))
            .isEqualTo(UrlRejection.NOT_HTTPS)
    }

    @Test
    fun `a foreign host is refused with the right scheme and path`() {
        assertThat(isTrustedReleasesApiUrl("https://evil.example/repos/Aravinth-Earth/Payanam/releases")).isFalse()
        assertThat(isTrustedAssetUrl("https://evil.example/Aravinth-Earth/Payanam/releases/download/x.apk")).isFalse()

        assertThat(releasesApiRejection("https://evil.example/repos/Aravinth-Earth/Payanam/releases"))
            .isEqualTo(UrlRejection.FOREIGN_HOST)
    }

    @Test
    fun `the api host with another repo or another path is refused`() {
        // Right host, wrong repository.
        assertThat(isTrustedReleasesApiUrl("https://api.github.com/repos/evil/Payanam/releases")).isFalse()
        // Right repository, path outside the releases tree.
        assertThat(isTrustedReleasesApiUrl("https://api.github.com/repos/Aravinth-Earth/Payanam/tags")).isFalse()
        // Sibling path that a naive prefix check would wave through.
        assertThat(isTrustedReleasesApiUrl("https://api.github.com/repos/Aravinth-Earth/Payanam/releases-evil?page=2"))
            .isFalse()
        // The web host is not the API host.
        assertThat(isTrustedReleasesApiUrl("https://github.com/repos/Aravinth-Earth/Payanam/releases")).isFalse()

        assertThat(releasesApiRejection("https://api.github.com/repos/Aravinth-Earth/Payanam/tags"))
            .isEqualTo(UrlRejection.OUTSIDE_PATH)
    }

    @Test
    fun `a release asset url is accepted only under the project download path`() {
        assertThat(isTrustedAssetUrl(TRUSTED_ASSET)).isTrue()
        assertThat(assetRejection(TRUSTED_ASSET)).isNull()

        // Another repository, the release page rather than the asset, and a lookalike host each fail.
        assertThat(isTrustedAssetUrl("https://github.com/evil/Payanam/releases/download/x/payanam.apk")).isFalse()
        assertThat(isTrustedAssetUrl("https://github.com/Aravinth-Earth/Payanam/releases/tag/v1.0.0")).isFalse()
        assertThat(isTrustedAssetUrl("https://github.com.evil.example/Aravinth-Earth/Payanam/releases/download/x/a.apk"))
            .isFalse()
        // Userinfo makes the authority read as one host while resolving to another.
        assertThat(isTrustedAssetUrl("https://github.com@evil.example/Aravinth-Earth/Payanam/releases/download/x/a.apk"))
            .isFalse()
        // The API host never serves assets.
        assertThat(isTrustedAssetUrl("https://api.github.com/repos/Aravinth-Earth/Payanam/releases/download/a.apk"))
            .isFalse()
    }

    @Test
    fun `a file or content uri is refused as an asset url`() {
        // The schemes an APK download must never be pointed at: a local file, or any content provider.
        assertThat(isTrustedAssetUrl("file:///data/local/tmp/x.apk")).isFalse()
        assertThat(isTrustedAssetUrl("content://media/external/downloads/1")).isFalse()
        // Refused for its scheme shape (an authority-less `file:///` URL is not a plain absolute URL).
        assertThat(assetRejection("file:///data/local/tmp/x.apk")).isNotNull()

        // Absent or empty input is a refusal, not a pass.
        assertThat(isTrustedAssetUrl(null)).isFalse()
        assertThat(isTrustedAssetUrl("")).isFalse()
        assertThat(isTrustedReleasesApiUrl(null)).isFalse()
        assertThat(isTrustedReleasesApiUrl("not a url")).isFalse()
    }

    @Test
    fun `a link header naming a foreign next page stops the walk`() {
        val foreignHeader = "<https://evil.example/repos/Aravinth-Earth/Payanam/releases?page=2>; rel=\"next\""

        // REFUSED, not END_OF_LIST: the walk must stop, and the scan stays incomplete so it can
        // never resolve to "no release" or "up to date".
        assertThat(nextPageDecision(nextPageUrl(foreignHeader))).isEqualTo(NextPageDecision.REFUSED)

        // No next page at all is the only way the list genuinely ends.
        assertThat(nextPageDecision(nextPageUrl(null))).isEqualTo(NextPageDecision.END_OF_LIST)
        assertThat(nextPageDecision("")).isEqualTo(NextPageDecision.REFUSED)

        // A next page on this project's API continues the walk.
        assertThat(nextPageDecision(nextPageUrl("<$TRUSTED_API_PAGE>; rel=\"next\"")))
            .isEqualTo(NextPageDecision.CONTINUE)
    }

    @Test
    fun `a release whose apk url is not trusted offers no update`() {
        val body =
            """
            [
              {
                "tag_name": "v1.0.0",
                "name": "Payanam 1.0.0",
                "assets": [
                  {"name": "payanam_1.0.0_debug_arm64-v8a.apk", "browser_download_url": "file:///data/local/tmp/x.apk"},
                  {"name": "payanam_1.0.0_debug_arm64-v8a.apk.sha256", "browser_download_url": "https://evil.example/x.apk.sha256"}
                ]
              }
            ]
            """.trimIndent()

        val status = requireNotNull(parseReleases(body, runningType = "debug")).single()

        // Fail closed: the URL and its checksum are dropped, and the release reports a mismatch so
        // downstream reads TYPE_MISMATCH rather than a null URL that looks "up to date".
        assertThat(status.versionName).isEqualTo("1.0.0")
        assertThat(status.apkDownloadUrl).isNull()
        assertThat(status.apkSha256Url).isNull()
        assertThat(status.typeMismatch).isTrue()
    }

    @Test
    fun `a trusted apk with an untrusted checksum keeps the apk and drops the checksum`() {
        val body =
            """
            [
              {
                "tag_name": "v1.0.0",
                "name": "Payanam 1.0.0",
                "assets": [
                  {"name": "payanam_1.0.0_debug_arm64-v8a.apk", "browser_download_url": "$TRUSTED_ASSET"},
                  {"name": "payanam_1.0.0_debug_arm64-v8a.apk.sha256", "browser_download_url": "https://evil.example/x.apk.sha256"}
                ]
              }
            ]
            """.trimIndent()

        val status = requireNotNull(parseReleases(body, runningType = "debug")).single()

        assertThat(status.versionName).isEqualTo("1.0.0")
        assertThat(status.apkDownloadUrl).isEqualTo(TRUSTED_ASSET)
        assertThat(status.apkSha256Url).isNull()
        assertThat(status.typeMismatch).isFalse()
    }
}
