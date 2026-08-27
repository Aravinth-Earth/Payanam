# SPDX-License-Identifier: AGPL-3.0-or-later
# Publish latest (or specified) APK to GitHub as a rolling release on a channel.
#
# Channel matrix (rolling tags, one release per channel at a time):
#   channel  branch       tag            prerelease  cadence
#   dev      feature/*    latest-dev     yes         10+ builds/day
#   beta     dev          latest-beta    yes         2 builds/week
#   stable   main         latest-stable  no          2 builds/month
# Explicit -Channel beta/stable from the wrong branch hard-fails (guard below).
#
# Channel auto-detects from the current git branch when -Channel is omitted.
# Usage:
#   .\build-tools\scripts\publish-release.ps1
#   .\build-tools\scripts\publish-release.ps1 -Channel beta
#   .\build-tools\scripts\publish-release.ps1 -ApkPath output/apks/Payanam_Android_1027_20260226_222417.apk -Channel stable

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

# ── 1. Resolve APK ────────────────────────────────────────────────────────────

if ($ApkPath -eq "") {
    $apks = Get-ChildItem -Path $OutputDir -Filter "*.apk" | Sort-Object LastWriteTime -Descending
    if ($apks.Count -eq 0) {
        Write-LogWithTime "No APKs found in $OutputDir. Run build-android.ps1 first." "Red"
        exit 1
    }
    $apkFile = $apks[0]
    Write-LogWithTime "Auto-selected latest APK: $($apkFile.Name)" "Cyan"
} else {
    $apkFile = Get-Item $ApkPath
    if (-not $apkFile.Exists) {
        Write-LogWithTime "APK not found: $ApkPath" "Red"
        exit 1
    }
    Write-LogWithTime "Using specified APK: $($apkFile.Name)" "Cyan"
}

# ── 2. Parse APK filename for build number & timestamp ────────────────────────
# Expected format: Payanam_Android_1027_20260226_222417.apk

$apkName = $apkFile.BaseName  # without .apk
$parts = $apkName -split "_"

if ($parts.Count -lt 5) {
    Write-LogWithTime "APK filename format unexpected: $apkName" "Red"
    exit 1
}

$buildNumber = $parts[2]
$datePart    = $parts[3]   # yyyyMMdd
$timePart    = $parts[4]   # HHmmss

$buildDate = "$($datePart.Substring(0,4))-$($datePart.Substring(4,2))-$($datePart.Substring(6,2))"
$buildTime = "$($timePart.Substring(0,2)):$($timePart.Substring(2,2))"

# ── 3. Get git commit hash and branch ────────────────────────────────────────

$commitHash = (git rev-parse --short HEAD 2>$null).Trim()
$branch     = (git rev-parse --abbrev-ref HEAD 2>$null).Trim()

if ([string]::IsNullOrEmpty($commitHash)) { $commitHash = "unknown" }
if ([string]::IsNullOrEmpty($branch))     { $branch = "unknown" }

# ── 3b. Stale-APK warning (never a block) ─────────────────────────────────────
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

# ── 4. Resolve channel (explicit -Channel wins; else auto-detect from branch) ──

if ($Channel -eq "auto") {
    $Channel = switch -Wildcard ($branch) {
        "feature/*" { "dev" }
        "dev"       { "beta" }
        "main"      { "stable" }
        default     { "dev" }
    }
    Write-LogWithTime "Auto-detected channel '$Channel' from branch '$branch'" "Cyan"
}

# ── 4b. Channel ↔ branch guard (hard fail, never a warning) ───────────────────
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

# ── 5. Generate SHA256 checksum ───────────────────────────────────────────────

Write-LogWithTime "Generating SHA256 checksum..." "Gray"
$hash = (Get-FileHash -Path $apkFile.FullName -Algorithm SHA256).Hash.ToUpper()
$sha256FileName = "$($apkFile.Name).sha256"
$sha256FilePath = Join-Path $apkFile.DirectoryName $sha256FileName
"$hash  $($apkFile.Name)" | Set-Content -Path $sha256FilePath -Encoding UTF8
Write-LogWithTime "SHA256: $hash" "Gray"

# ── 6. Build release notes ────────────────────────────────────────────────────

$channelWarning = switch ($Channel) {
    "dev"    { "🔧 Development build — for testing only" }
    "beta"   { "🧪 Beta build — feedback welcome" }
    "stable" { "" }
}

$releaseNotes = @"
Payanam $ChannelTitle Build

Build: #$buildNumber | $buildDate $buildTime
Channel: $Channel

Commit: $commitHash
Branch: $branch

SHA256: $hash

Verify before installing: see [INSTALL.md](https://github.com/Aravinth-Earth/Payanam/blob/main/INSTALL.md) for checksum verification and sideload steps.
$(if ($channelWarning) { "`n$channelWarning" })
"@

# ── 7. Delete existing channel release + tag (rolling release) ────────────────

# Guard: gh CLI must exist. Without this, $LASTEXITCODE below would be stale
# from a previous native command and the script could misbehave silently.
$ghCmd = Get-Command gh -ErrorAction SilentlyContinue
if (-not $ghCmd) {
    Write-LogWithTime "❌ 'gh' (GitHub CLI) not found. Install it (https://cli.github.com) or run with -DryRun." "Red"
    exit 1
}

$tag = "latest-$Channel"
if ($DryRun) {
    Write-LogWithTime "[DRY RUN] Would delete existing $tag release + tag (if present)" "Yellow"
} else {
    $existingRelease = gh release view $tag 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-LogWithTime "Deleting existing $tag release..." "Yellow"
        gh release delete $tag --yes 2>$null
    }

    # Always ensure local tag is deleted (even if release doesn't exist)
    # This prevents "tag exists locally but not pushed" errors
    $localTagExists = git rev-parse $tag 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-LogWithTime "Removing stale local tag..." "Yellow"
        git tag -d $tag
    }

    # Also delete remote tag to prevent stale commit reference
    $remoteTagExists = git ls-remote origin "refs/tags/$tag" 2>$null
    if (-not [string]::IsNullOrWhiteSpace($remoteTagExists)) {
        Write-LogWithTime "Removing stale remote tag..." "Yellow"
        git push origin ":refs/tags/$tag" 2>$null
    }
}

# ── 8. Create new release ─────────────────────────────────────────────────────

Write-LogWithTime "Creating GitHub release: $tag ..." "Cyan"

# dev/beta roll as prereleases; stable is a full (non-prerelease) release.
$prereleaseFlag = if ($Channel -eq "stable") { @() } else { @("--prerelease") }

if ($DryRun) {
    Write-LogWithTime "[DRY RUN] Would create release: $tag" "Yellow"
    Write-LogWithTime "[DRY RUN]   title  : Latest $ChannelTitle Build (#$buildNumber)" "Yellow"
    Write-LogWithTime "[DRY RUN]   flags  : $($prereleaseFlag -join ' ')" "Yellow"
    Write-LogWithTime "[DRY RUN]   assets : $($apkFile.Name) + $sha256FileName" "Yellow"
    Write-LogWithTime "[DRY RUN]   notes  : Payanam $ChannelTitle Build, build #$buildNumber, channel $Channel, commit $commitHash, branch $branch" "Yellow"
} else {
    # Build the full argument list first, then splat once — splatting
    # mid-command with backtick continuations misparses in PowerShell.
    $ghArgs = @(
        $tag
        "--title", "Latest $ChannelTitle Build (#$buildNumber)"
        "--notes", $releaseNotes
    )
    if ($Channel -ne "stable") {
        $ghArgs += "--prerelease"
    }
    $ghArgs += "$($apkFile.FullName)#$($apkFile.Name)"
    $ghArgs += "$sha256FilePath#$sha256FileName"

    gh release create @ghArgs

    if ($LASTEXITCODE -ne 0) {
        Write-LogWithTime "Release creation failed." "Red"
        exit 1
    }
}

# ── 9. Print release URL ──────────────────────────────────────────────────────

if ($DryRun) {
    Write-LogWithTime "[DRY RUN] Complete — nothing was published." "Green"
} else {
    $releaseUrl = gh release view $tag --json url --jq ".url" 2>$null
    Write-LogWithTime "Release published: $releaseUrl" "Green"
}
Write-LogWithTime "APK : $($apkFile.Name)" "Green"
Write-LogWithTime "SHA256 file: $sha256FileName" "Green"
