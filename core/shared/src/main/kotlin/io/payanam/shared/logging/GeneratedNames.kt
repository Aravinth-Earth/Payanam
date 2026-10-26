//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicFunction")

package io.payanam.shared.logging

/** OS token for generated names: android, linux, windows, macos, ios. Never a desktop_/mobile_ prefix. */
fun osToken(platform: String): String = platform.trim().lowercase()

fun generatedName(
    prefix: String,
    platform: String,
    version: String,
    sha: String,
    type: String,
    timestamp: String,
): String = "${prefix}_${osToken(platform)}_${version}_${sha}_${type}_$timestamp"

fun sessionLogName(
    platform: String,
    version: String,
    sha: String,
    debugBuild: Boolean,
    timestamp: String,
): String = generatedName("payanam", platform, version, sha, buildType(debugBuild), timestamp) + ".log"

fun logExportName(
    platform: String,
    version: String,
    sha: String,
    debugBuild: Boolean,
    timestamp: String,
): String = generatedName("payanam_logs", platform, version, sha, buildType(debugBuild), timestamp) + ".zip"

fun dbExportName(
    platform: String,
    version: String,
    sha: String,
    debugBuild: Boolean,
    timestamp: String,
): String = generatedName("payanam_backup_encrypted", platform, version, sha, buildType(debugBuild), timestamp) + ".db"

fun autoBackupDirName(
    platform: String,
    version: String,
    sha: String,
    debugBuild: Boolean,
    timestamp: String,
): String = generatedName("auto_bk", platform, version, sha, buildType(debugBuild), timestamp)

private fun buildType(debugBuild: Boolean): String = if (debugBuild) "debug" else "release"
