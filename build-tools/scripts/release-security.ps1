# SPDX-License-Identifier: AGPL-3.0-or-later
# Shared release-security helpers — dot-sourced by build-android.ps1 and publish-release.ps1.
# Keep ONE implementation here; never copy these functions into the callers.
# Depends on the host script defining Write-LogWithTime (both do, before dot-sourcing).
#
# Get-AndroidBuildToolPath resolution order:
#   1. PATH via Get-Command — covers a toolchain exposed on PATH.
#   2. SDK roots: ANDROID_SDK_ROOT / ANDROID_HOME env vars, then local.properties sdk.dir
#      (Gradle's convention; the env vars are frequently unset on dev hosts).
#      Under <sdk>/build-tools/<version> (newest first) the candidates are tried as
#      <tool> (Linux SDK, extension-less), <tool>.bat and <tool>.exe (Windows SDK).

function Get-AndroidBuildToolPath
{
    param([string]$ToolName)

    $toolCommand = Get-Command $ToolName -ErrorAction SilentlyContinue
    if ($toolCommand)
    {
        return $toolCommand.Source
    }

    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path $_) }

    # local.properties (sdk.dir=...) — repo root, two levels up from this file's dir
    # (build-tools/scripts/). Guarded: when the root cannot be derived, skip this
    # step and rely on env vars/PATH instead of failing the caller.
    $projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    if (-not [string]::IsNullOrWhiteSpace($projectRoot))
    {
        $localPropertiesPath = Join-Path $projectRoot "local.properties"
        if (Test-Path $localPropertiesPath)
        {
            $sdkDirLine = Get-Content -Path $localPropertiesPath |
                Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
                Select-Object -First 1
            if ($sdkDirLine)
            {
                $sdkDirValue = ($sdkDirLine -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
                # local.properties escapes backslashes and the drive colon (sdk.dir=C\:\\Android\\Sdk).
                $sdkDirValue = $sdkDirValue -replace '\\\\', '\'
                $sdkDirValue = $sdkDirValue -replace '\\:', ':'
                if (-not [string]::IsNullOrWhiteSpace($sdkDirValue) -and (Test-Path $sdkDirValue))
                {
                    $sdkRoots = @($sdkRoots + $sdkDirValue | Select-Object -Unique)
                }
            }
        }
    }

    foreach ($sdkRoot in $sdkRoots)
    {
        $buildToolsDir = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path $buildToolsDir))
        {
            continue
        }
        $versionDirs = Get-ChildItem -Path $buildToolsDir -Directory | Sort-Object Name -Descending
        foreach ($versionDir in $versionDirs)
        {
            foreach ($fileCandidate in @($ToolName, "$ToolName.bat", "$ToolName.exe"))
            {
                $fullCandidate = Join-Path $versionDir.FullName $fileCandidate
                if (Test-Path $fullCandidate)
                {
                    return $fullCandidate
                }
            }
        }
    }

    return $null
}

function Get-ApprovedReleaseSignerDigests
{
    param(
        [string]$ApprovedDigestsPath
    )

    # One SHA-256 digest per line; blank lines and #-comments ignored; colons optional.
    if ([string]::IsNullOrWhiteSpace($ApprovedDigestsPath) -or -not (Test-Path $ApprovedDigestsPath))
    {
        return @()
    }

    return @(
        Get-Content -Path $ApprovedDigestsPath |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and -not $_.StartsWith("#") } |
            ForEach-Object { ($_ -replace "[:\s]", "").ToUpperInvariant() }
    )
}

function Invoke-ReleaseSecurityVerification
{
    param(
        [string]$ApkPath,
        [switch]$FailClosed
    )

    Write-LogWithTime "" "White"
    Write-LogWithTime "=== RELEASE SECURITY VERIFY ===" "Magenta"

    $aaptPath = Get-AndroidBuildToolPath -ToolName "aapt"
    if ([string]::IsNullOrWhiteSpace($aaptPath))
    {
        if ($FailClosed)
        {
            Write-LogWithTime "  ❌ aapt not found; cannot verify manifest debuggable flag — fail-closed refusal." "Red"
            throw "Release security verify (fail-closed): aapt not found; cannot verify manifest debuggable flag."
        }
        Write-LogWithTime "  ⚠️ aapt not found; cannot verify manifest debuggable flag from built APK." "Yellow"
    } else
    {
        $badgingOutput = & $aaptPath dump badging $ApkPath 2>&1
        if ($LASTEXITCODE -ne 0)
        {
            throw "aapt badging inspection failed: $badgingOutput"
        }
        if ($badgingOutput -match "application-debuggable")
        {
            throw "Release security verify failed: APK manifest is debuggable."
        }
        Write-LogWithTime "  ✅ APK manifest is non-debuggable." "Green"
    }

    $apksignerPath = Get-AndroidBuildToolPath -ToolName "apksigner"
    if ([string]::IsNullOrWhiteSpace($apksignerPath))
    {
        if ($FailClosed)
        {
            Write-LogWithTime "  ❌ apksigner not found; cannot verify signature certificate identity — fail-closed refusal." "Red"
            throw "Release security verify (fail-closed): apksigner not found; cannot verify signature certificate identity."
        }
        Write-LogWithTime "  ⚠️ apksigner not found; cannot verify signature certificate identity." "Yellow"
        return
    }

    # Join the captured output: PowerShell returns it as a line array, and the signer-digest
    # regex needs real newlines to anchor per line.
    $verifyOutput = (& $apksignerPath verify --verbose --print-certs $ApkPath 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0)
    {
        throw "apksigner verification failed: $verifyOutput"
    }
    if ($verifyOutput -match "CN=Android Debug")
    {
        throw "Release security verify failed: APK is signed with Android Debug certificate."
    }

    # Signer identity: "not the Android Debug cert" is not enough — the certificate must be an
    # approved release signer. Android accepts updates only from the installed certificate (or a
    # proven rotation lineage), so a wrong-keystore build must never reach a release: it would
    # publish fine and then block every future in-place update. The approved digests live in
    # build-tools/release-signer.sha256 (public info — the certificate ships inside every APK).
    $approvedDigestsPath = Join-Path (Split-Path -Parent $PSScriptRoot) "release-signer.sha256"
    $approvedDigests = Get-ApprovedReleaseSignerDigests -ApprovedDigestsPath $approvedDigestsPath
    $signerDigests = @(
        [regex]::Matches($verifyOutput, "(?im)^\s*Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)\s*$") |
            ForEach-Object { ($_.Groups[1].Value -replace "[:\s]", "").ToUpperInvariant() }
    )

    if ($signerDigests.Count -eq 0)
    {
        if ($FailClosed)
        {
            throw "Release security verify (fail-closed): no signer certificate digest found in apksigner output."
        }
        Write-LogWithTime "  ⚠️ No signer certificate digest found; cannot match the approved signer." "Yellow"
        return
    }
    if ($approvedDigests.Count -eq 0)
    {
        if ($FailClosed)
        {
            throw "Release security verify (fail-closed): no approved signer digests configured at $approvedDigestsPath."
        }
        Write-LogWithTime "  ⚠️ No approved signer digests configured ($approvedDigestsPath); signer identity not checked." "Yellow"
        return
    }
    foreach ($digest in $signerDigests)
    {
        if ($approvedDigests -notcontains $digest)
        {
            throw "Release security verify failed: signer certificate $digest is not in the approved list ($approvedDigestsPath)."
        }
    }

    Write-LogWithTime "  ✅ APK signature and signer identity verified (approved release signer)." "Green"
}
