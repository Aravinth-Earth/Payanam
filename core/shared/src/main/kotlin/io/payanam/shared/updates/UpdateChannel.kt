//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.updates

/**
 * Release channels. Persistent GitHub release tags (publish-release.ps1): dev/beta use
 * "{tagSuffix}-v{buildNumber}"; stable uses plain "v{buildNumber}".
 *
 * Lives beside [UpdatePrefKeys] because the stored preference value is an enum NAME and the row key
 * that holds it is one of those keys: the enum and the key it is written under are one contract, and
 * the parser that reads the value back ([fromStorage]) has to travel with both.
 */
enum class UpdateChannel(
    val tagSuffix: String,
) {
    DEV("dev"),
    BETA("beta"),
    STABLE("stable"),
    ;

    companion object {
        /** Parse a stored preference value; unknown/garbage falls back to DEV. */
        fun fromStorage(raw: String?): UpdateChannel = entries.firstOrNull { it.name == raw } ?: DEV
    }
}
