# SPDX-License-Identifier: AGPL-3.0-or-later
# Publish APK to GitHub as a persistent release on a channel.
#
# Channel matrix:
#   channel  branch       tag format         prerelease  cadence
#   dev      feature/*    dev-v{build}       yes
#   beta     dev          beta-v{build}      yes
#   stable   main         v{build}           no
#
# Each publish creates:
#   1. Persistent release (dev-v{build} / beta-v{build} / v{build})
#   2. v{build} tag for stable (used by both app and F-Droid)
#
# Channel auto-detects from the current git branch when -Channel is omitted.
# Artifact pick without -ApkPath: latest build number among artifacts of the
# channel's expected type (dev=debug, beta/stable=release), tiebroken by
# Timestamp then LastWriteUtc — never by mtime only. Zero valid artifacts => exit 1.
# Usage:
#   .\build-tools\scripts\publish-release.ps1
#   .\build-tools\scripts\publish-release.ps1 -Channel beta
#   .\build-tools\scripts\publish-release.ps1 -ApkPath output/apks/Payanam_Android_1607_release_20260912_143000.apk -Channel stable

param(
    [string]$ApkPath = "",
    [string]$OutputDir = "output/apks",
    [ValidateSet("auto", "dev", "beta", "stable")] [string]$Channel = "auto",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
Set-Location $projectRoot

function Write-LogWithTime {
    param([string]$Message, [string]$Color = "White")
    $timestamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$timestamp] $Message" -ForegroundColor $Color
}

# Shared release-security helpers (Get-AndroidBuildToolPath, Invoke-ReleaseSecurityVerification).
# Dot-sourced here AND in build-android.ps1 — single implementation, never copied.
. "$PSScriptRoot/release-security.ps1"

# ── 1. Get git commit hash and branch ────────────────────────────────────────

$commitHash = (git rev-parse --short HEAD 2>$null).Trim()
$branch     = (git rev-parse --abbrev-ref HEAD 2>$null).Trim()

if ([string]::IsNullOrEmpty($commitHash)) { $commitHash = "unknown" }
if ([string]::IsNullOrEmpty($branch))     { $branch = "unknown" }

# ── 2. Resolve channel (explicit -Channel wins; else auto-detect from branch) ──

if ($Channel -eq "auto") {
    $Channel = switch -Wildcard ($branch) {
        "feature/*" { "dev" }
        "dev"       { "beta" }
        "main"      { "stable" }
        default     { "dev" }
    }
    Write-LogWithTime "Auto-detected channel '$Channel' from branch '$branch'" "Cyan"
}

# ── 2b. Channel ↔ branch guard (hard fail, never a warning) ───────────────────
# Only beta/stable are strict: they must be published from their owning
# branches (dev → beta, main → stable). The dev channel is the throwaway
# pre-release channel — publishing it from any branch is harmless, so no guard.
# A guard that warns gets ignored; this one exits non-zero with a clear message.
if ($Channel -eq "beta" -and $branch -ne "dev") {
    Write-LogWithTime "❌ Channel 'beta' cannot be published from branch '$branch'." "Red"
    Write-LogWithTime "   '-Channel beta' requires branch 'dev'." "Red"
    Write-LogWithTime "   Switch to 'dev', or use '-Channel dev' for feature-branch builds." "Red"
    exit 1
}
if ($Channel -eq "stable" -and $branch -ne "main") {
    Write-LogWithTime "❌ Channel 'stable' cannot be published from branch '$branch'." "Red"
    Write-LogWithTime "   '-Channel stable' requires branch 'main'." "Red"
    Write-LogWithTime "   Switch to 'main', or use '-Channel dev' for feature-branch builds." "Red"
    exit 1
}

# Channel → display title + prerelease flag mapping.
# dev/beta roll as prereleases; stable is a full release.
$ChannelTitle = switch ($Channel) {
    "dev"    { "Dev" }
    "beta"   { "Beta" }
    "stable" { "Stable" }
}

# Channel → APK build type contract (ONE apk per channel, user decision):
#   dev → debug-type | beta → release-type | stable → release-type
$expectedType = if ($Channel -eq "dev") { "debug" } else { "release" }

# ── 3. Resolve APK (type-aware; explicit -ApkPath or latest-number pick) ──────
# Expected format: Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>.apk
# Name standard only — there is no legacy fallback: unparseable/legacy names are
# refused loudly (pre-rename APKs cannot be re-published; intended, see plan).
$apkNameRegex = '^Payanam_Android_(\d+)_(debug|release)_(\d{8}_\d{6})$'

if ($ApkPath -eq "") {
    $apkCandidates = @(
        Get-ChildItem -Path $OutputDir -Filter "*.apk" -File | ForEach-Object {
            $fileMatch = [regex]::Match($_.BaseName, $apkNameRegex)
            [PSCustomObject]@{
                File         = $_
                IsValid      = $fileMatch.Success
                BuildNumber  = if ($fileMatch.Success) { [int]$fileMatch.Groups[1].Value } else { -1 }
                Type         = if ($fileMatch.Success) { $fileMatch.Groups[2].Value } else { "" }
                Timestamp    = if ($fileMatch.Success) { $fileMatch.Groups[3].Value } else { "" }
                LastWriteUtc = $_.LastWriteTimeUtc
            }
        }
    )

    $validCandidates = @($apkCandidates | Where-Object { $_.IsValid })
    if ($validCandidates.Count -eq 0) {
        Write-LogWithTime "❌ No parse-valid APK in $OutputDir (expected: Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>.apk)." "Red"
        Write-LogWithTime "   Legacy/unrecognized names are refused by design. Run build-android.ps1 to produce a current-format APK." "Red"
        exit 1
    }

    $eligibleCandidates = @($validCandidates | Where-Object { $_.Type -eq $expectedType })
    if ($eligibleCandidates.Count -eq 0) {
        $typesSeen = ($validCandidates | Group-Object Type | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ", "
        Write-LogWithTime "❌ No '$expectedType'-type APK in $OutputDir for channel '$Channel' (types present: $typesSeen)." "Red"
        Write-LogWithTime "   Contract: dev=debug | beta=release | stable=release. Build the matching type or pass -ApkPath explicitly." "Red"
        exit 1
    }

    # Latest-number pick: BuildNumber → Timestamp → LastWriteUtc, all descending
    # (mirrors build-android.ps1 retention; no mtime-only fallback).
    $pickSort = @(
        @{ Expression = "BuildNumber"; Descending = $true }
        @{ Expression = "Timestamp"; Descending = $true }
        @{ Expression = "LastWriteUtc"; Descending = $true }
    )
    $apkFile = ($eligibleCandidates | Sort-Object -Property $pickSort | Select-Object -First 1).File
    Write-LogWithTime "Auto-selected latest $expectedType-type APK: $($apkFile.Name)" "Cyan"
} else {
    $apkFile = Get-Item $ApkPath -ErrorAction SilentlyContinue
    if (-not $apkFile -or -not $apkFile.Exists) {
        Write-LogWithTime "APK not found: $ApkPath" "Red"
        exit 1
    }
    Write-LogWithTime "Using specified APK: $($apkFile.Name)" "Cyan"
}

# Parse the APK name (both paths) — anchored regex; parse failure refuses loudly.
$apkName = $apkFile.BaseName  # without .apk
$apkMatch = [regex]::Match($apkName, $apkNameRegex)

if (-not $apkMatch.Success) {
    Write-LogWithTime "❌ APK filename not in the current standard (legacy names are refused): $apkName" "Red"
    Write-LogWithTime "   Expected format: Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>" "Red"
    exit 1
}

# ── 3b. Channel ↔ type guard (hard fail, never a warning) ─────────────────────
# dev must get a debug-type APK; beta/stable must get a release-type APK.
# Type unknown ⇒ refused (covered by the parse failure above).
$buildNumber = $apkMatch.Groups[1].Value
$apkType     = $apkMatch.Groups[2].Value
$datePart    = $apkMatch.Groups[3].Value.Substring(0, 8)   # yyyyMMdd
$timePart    = $apkMatch.Groups[3].Value.Substring(9, 6)   # HHmmss

if ($apkType -ne $expectedType) {
    Write-LogWithTime "❌ Type mismatch: '$apkType'-type APK cannot be published to channel '$Channel'." "Red"
    Write-LogWithTime "   Contract: dev=debug | beta=release | stable=release." "Red"
    exit 1
}

$buildDate = "$($datePart.Substring(0,4))-$($datePart.Substring(4,2))-$($datePart.Substring(6,2))"
$buildTime = "$($timePart.Substring(0,2)):$($timePart.Substring(2,2))"

# ── 4. Stale-APK warning (never a block) ─────────────────────────────────────
# The publish flow is "commit tested code, then publish the SAME tested APK".
# If the APK was built BEFORE the latest commit, it may not contain the just-
# pushed changes. This is usually a mistake (publishing an old artifact) —
# warn loudly, but the user may still intend it (e.g. publishing a known-good
# rollback), so this is a warning, never an exit.
$apkBuildTime = [DateTime]::ParseExact(
    "$datePart $timePart", "yyyyMMdd HHmmss",
    [System.Globalization.CultureInfo]::InvariantCulture)
$latestCommitTime = git log -1 --format=%cI 2>$null
if (-not [string]::IsNullOrWhiteSpace($latestCommitTime)) {
    $commitTime = [DateTimeOffset]::Parse($latestCommitTime, [System.Globalization.CultureInfo]::InvariantCulture).LocalDateTime
    if ($apkBuildTime -lt $commitTime) {
        $gap = $commitTime - $apkBuildTime
        if ($gap.TotalHours -ge 1) {
            Write-LogWithTime "⚠️  WARNING: APK built at $($apkBuildTime.ToString('yyyy-MM-dd HH:mm:ss')) is OLDER than the latest commit ($($commitTime.ToString('yyyy-MM-dd HH:mm:ss')))." "Yellow"
            Write-LogWithTime "   The APK may NOT contain the latest committed changes." "Yellow"
            Write-LogWithTime "   Publish anyway? (This is a warning only — proceeding.)" "Yellow"
        } else {
            Write-LogWithTime "ℹ️  APK built at $($apkBuildTime.ToString('yyyy-MM-dd HH:mm:ss')) is $($gap.ToString('hh\:mm\:ss')) older than the latest commit ($($commitTime.ToString('yyyy-MM-dd HH:mm:ss'))). Within 1h window — expected (build starts before commit)." "Cyan"
        }
    }
}

# ── 5. Release signature verification (beta/stable, fail-closed) ──────────────
# For beta/stable, re-run the signature ground truth from the artifact itself —
# never trust the name token. Fail-closed: if the toolchain cannot complete the
# checks, the publish refuses. Shared implementation (release-security.ps1) —
# never copied. The dev channel ships debug-type APKs by contract (debuggable +
# debug-signed), so this release-only check is skipped there.
if ($Channel -eq "dev") {
    Write-LogWithTime "Signature verification skipped (dev channel ships debug-type APKs)." "Gray"
} else {
    Invoke-ReleaseSecurityVerification -ApkPath $apkFile.FullName -FailClosed
}

# ── 6. Generate SHA256 checksum ───────────────────────────────────────────────

Write-LogWithTime "Generating SHA256 checksum..." "Gray"
$hash = (Get-FileHash -Path $apkFile.FullName -Algorithm SHA256).Hash.ToUpper()
$sha256FileName = "$($apkFile.Name).sha256"
$sha256FilePath = Join-Path $apkFile.DirectoryName $sha256FileName
"$hash  $($apkFile.Name)" | Set-Content -Path $sha256FilePath -Encoding UTF8
Write-LogWithTime "SHA256: $hash" "Gray"

# ── 7. Build release notes ────────────────────────────────────────────────────

$releaseNotes = @"
$buildDate $buildTime

Commit: $commitHash

SHA256: $hash

Verify before installing: see [INSTALL.md](https://github.com/Aravinth-Earth/Payanam/blob/main/INSTALL.md) for checksum verification and sideload steps.
"@

# Transition note (Change 6 — no existing beta/stable users yet): beta/stable now
# ship release-type APKs; a debug-type install stays on the dev channel or
# exports + re-imports (different app ID — data does not transfer).
if ($Channel -ne "dev") {
    $releaseNotes += @"

Channel note: beta/stable now ship a release-type APK. If you already run a debug-type install, stay on the dev channel or export + re-import — the app IDs differ, so data does not transfer.
"@
}

# ── 8. Guard: gh CLI must exist ─────────────────────────────────────────────

$ghCmd = Get-Command gh -ErrorAction SilentlyContinue
if (-not $ghCmd) {
    Write-LogWithTime "❌ 'gh' (GitHub CLI) not found. Install it (https://cli.github.com) or run with -DryRun." "Red"
    exit 1
}

# ── 8b. Channel gap awareness ──────────────────────────────────────────────
# Before publishing, check how far behind other channels are.
# Prevents "published 50 dev builds and forgot to promote beta/stable".

$DEV_BETA_THRESHOLD = 30    # warn if beta is this many builds behind dev
$DEV_STABLE_THRESHOLD = 50  # warn if stable is this many builds behind dev
$BETA_STABLE_THRESHOLD = 5  # warn if stable is this many builds behind beta

$allReleases = gh release list --limit 50 --json tagName,name --jq '.' 2>$null
if ($LASTEXITCODE -eq 0 -and $allReleases) {
    $parsed = $allReleases | ConvertFrom-Json
    $latestByChannel = @{}

    foreach ($r in $parsed) {
        $rTag = $r.tagName
        $tagMatch = [regex]::Match($rTag, '^(?:(dev|beta)-)?v(\d+)$')
        if (-not $tagMatch.Success) { continue }
        $rBuild = [int]$tagMatch.Groups[2].Value

        if ($rTag -match '^dev-v\d+$') {
            if (-not $latestByChannel.ContainsKey('dev') -or $rBuild -gt $latestByChannel['dev']) {
                $latestByChannel['dev'] = $rBuild
            }
        } elseif ($rTag -match '^beta-v\d+$') {
            if (-not $latestByChannel.ContainsKey('beta') -or $rBuild -gt $latestByChannel['beta']) {
                $latestByChannel['beta'] = $rBuild
            }
        } elseif ($rTag -match '^v\d+$') {
            if (-not $latestByChannel.ContainsKey('stable') -or $rBuild -gt $latestByChannel['stable']) {
                $latestByChannel['stable'] = $rBuild
            }
        }
    }

    # Show current state per channel (always show all 3)
    $devBuild = if ($latestByChannel.ContainsKey('dev')) { "#$($latestByChannel['dev'])" } else { "—" }
    $betaBuild = if ($latestByChannel.ContainsKey('beta')) { "#$($latestByChannel['beta'])" } else { "—" }
    $stableBuild = if ($latestByChannel.ContainsKey('stable')) { "#$($latestByChannel['stable'])" } else { "—" }
    Write-LogWithTime "Channel status: dev:$devBuild | beta:$betaBuild | stable:$stableBuild" "Gray"

    $hasWarning = $false

    if ($latestByChannel.ContainsKey('dev') -and $latestByChannel.ContainsKey('beta')) {
        $gap = $latestByChannel['dev'] - $latestByChannel['beta']
        if ($gap -ge $DEV_BETA_THRESHOLD) {
            Write-LogWithTime "  dev→beta gap: $gap (threshold: $DEV_BETA_THRESHOLD) — ⚠️ WARNING" "Yellow"
            $hasWarning = $true
        } else {
            Write-LogWithTime "  dev→beta gap: $gap (threshold: $DEV_BETA_THRESHOLD) — OK" "Gray"
        }
    } else {
        Write-LogWithTime "  dev→beta gap: — (beta not published yet)" "Gray"
    }

    if ($latestByChannel.ContainsKey('dev') -and $latestByChannel.ContainsKey('stable')) {
        $gap = $latestByChannel['dev'] - $latestByChannel['stable']
        if ($gap -ge $DEV_STABLE_THRESHOLD) {
            Write-LogWithTime "  dev→stable gap: $gap (threshold: $DEV_STABLE_THRESHOLD) — ⚠️ WARNING" "Yellow"
            $hasWarning = $true
        } else {
            Write-LogWithTime "  dev→stable gap: $gap (threshold: $DEV_STABLE_THRESHOLD) — OK" "Gray"
        }
    } else {
        Write-LogWithTime "  dev→stable gap: — (stable not published yet)" "Gray"
    }

    if ($latestByChannel.ContainsKey('beta') -and $latestByChannel.ContainsKey('stable')) {
        $gap = $latestByChannel['beta'] - $latestByChannel['stable']
        if ($gap -ge $BETA_STABLE_THRESHOLD) {
            Write-LogWithTime "  beta→stable gap: $gap (threshold: $BETA_STABLE_THRESHOLD) — ⚠️ WARNING" "Yellow"
            $hasWarning = $true
        } else {
            Write-LogWithTime "  beta→stable gap: $gap (threshold: $BETA_STABLE_THRESHOLD) — OK" "Gray"
        }
    } else {
        Write-LogWithTime "  beta→stable gap: — (beta/stable not published yet)" "Gray"
    }

    if ($hasWarning -and -not $DryRun) {
        Write-LogWithTime "" "Yellow"
        Write-LogWithTime "Consider promoting older channels before publishing more builds." "Yellow"
        $confirm = Read-Host "Continue publishing to $Channel anyway? (y/N)"
        if ($confirm -ne 'y' -and $confirm -ne 'Y') {
            Write-LogWithTime "Publish cancelled." "Red"
            exit 0
        }
    }
} else {
    Write-LogWithTime "Channel gap check skipped (offline or API unavailable)" "Gray"
}

# ── 9. Create persistent release ────────────────────────────────────────────

# Stable uses plain v{build} tag (works with F-Droid); dev/beta use {channel}-v{build}
$tag = if ($Channel -eq "stable") { "v$buildNumber" } else { "$Channel-v$buildNumber" }
Write-LogWithTime "Creating GitHub release: $tag ..." "Cyan"

# dev/beta are prereleases; stable is a full (non-prerelease) release.
$prereleaseFlag = if ($Channel -eq "stable") { @() } else { @("--prerelease") }

if ($DryRun) {
    Write-LogWithTime "[DRY RUN] Would create persistent release: $tag" "Yellow"
    Write-LogWithTime "[DRY RUN]   title  : $ChannelTitle #$buildNumber" "Yellow"
    Write-LogWithTime "[DRY RUN]   flags  : $($prereleaseFlag -join ' ')" "Yellow"
    Write-LogWithTime "[DRY RUN]   assets : $($apkFile.Name) + $sha256FileName" "Yellow"
} else {
    # Build the full argument list first, then splat once — splatting
    # mid-command with backtick continuations misparses in PowerShell.
    $ghArgs = @(
        $tag
        "--title", "$ChannelTitle #$buildNumber"
        "--notes", $releaseNotes
    )
    if ($Channel -ne "stable") {
        $ghArgs += "--prerelease"
    }
    $ghArgs += "$($apkFile.FullName)#$($apkFile.Name)"
    $ghArgs += "$sha256FilePath#$sha256FileName"

    gh release create @ghArgs

    if ($LASTEXITCODE -ne 0) {
        Write-LogWithTime "❌ Persistent release creation failed." "Red"
        exit 1
    }
    Write-LogWithTime "Persistent release created: $tag" "Green"

    # F-Droid tag is the same as the release tag for stable (v{build}).
    # For dev/beta, no F-Droid tag needed.
    if ($Channel -ne "stable") {
        Write-LogWithTime "Skipping F-Droid tag (not stable channel)" "Gray"
    }
}

# ── 10. Print release URL ──────────────────────────────────────────────────

if ($DryRun) {
    Write-LogWithTime "[DRY RUN] Complete — nothing was published." "Green"
} else {
    $releaseUrl = gh release view $tag --json url --jq ".url" 2>$null
    Write-LogWithTime "Release published: $releaseUrl" "Green"
}
Write-LogWithTime "APK : $($apkFile.Name)" "Green"
Write-LogWithTime "SHA256 file: $sha256FileName" "Green"
