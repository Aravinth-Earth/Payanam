#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# Generated name: <prefix>_<platform>_<version>_<sha>_<type>_<timestamp>[.<ext>]
function Get-PayanamGeneratedName
{
    param(
        [Parameter(Mandatory = $true)][string]$Prefix,
        [Parameter(Mandatory = $true)][string]$Platform,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Sha,
        [Parameter(Mandatory = $true)][string]$Type,
        [Parameter(Mandatory = $true)][string]$Timestamp
    )

    $platformToken = switch ($Platform.ToLowerInvariant())
    {
        "android" { "android" }
        "linux" { "linux" }
        "windows" { "windows" }
        "macos" { "macos" }
        default { throw "Unknown platform: $Platform" }
    }

    return "${Prefix}_${platformToken}_${Version}_${Sha}_${Type}_${Timestamp}"
}
