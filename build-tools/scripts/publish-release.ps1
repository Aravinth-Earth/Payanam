# SPDX-FileCopyrightText: 2026 Aravinth-Earth
# SPDX-License-Identifier: AGPL-3.0-or-later
# Publish the declared version as one GitHub release: tag v<version>, every built artefact attached.
#
# Version source: gradle/version.properties. The tag is v<version>; no channel, no build number.
# Artefacts come from -AndroidApkPath / -LinuxDebPath / -WindowsPath, or from output/apks,
# output/debs and output/windows. Each name must be
# Payanam_<platform>_<version>_<sha>_<type>_<yyyyMMdd_HHmmss>[.<ext>] and carry the declared version.
# A platform with no artefact is reported as absent and not attached; -Require turns that into a refusal.
# Android is always required: its release signature, signer identity and build-side checksum are verified.
# F-Droid's recipe lives outside this repo and follows the tag family: tags are v<version>, and versionCode
# is the literal line in gradle/version.properties. Confirm the recipe before the first tag.
#
# Usage:
#   .\build-tools\scripts\publish-release.ps1
#   .\build-tools\scripts\publish-release.ps1 -Require "Android,Linux,Windows"
#   .\build-tools\scripts\publish-release.ps1 -AndroidApkPath output/apks/Payanam_android_1.0.0_ceff870a_release_20261026_110012.apk -DryRun

param(
    [Alias("ApkPath")][string]$AndroidApkPath = "",
    [string]$LinuxDebPath = "",
    [string]$WindowsPath = "",
    [string[]]$Require = @("Android"),
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# git exit codes are control flow here (a missing ref, an unreachable origin); check $LASTEXITCODE.
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
Set-Location $projectRoot

function Write-LogWithTime {
    param([string]$Message, [string]$Color = "White")
    $timestamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$timestamp] $Message" -ForegroundColor $Color
}

# Shared release-security helper (Get-AndroidBuildToolPath, Invoke-ReleaseSecurityVerification).
# Dot-sourced here AND in build-android.ps1 — single implementation, never copied.
. "$PSScriptRoot/release-security.ps1"

# Refuse loudly and stop — a guard that only warns gets ignored.
function Refuse {
    param([string]$Message, [string]$Detail = "")
    Write-LogWithTime "❌ $Message" "Red"
    if (-not [string]::IsNullOrWhiteSpace($Detail)) { Write-LogWithTime "   $Detail" "Yellow" }
    exit 1
}

# ── 1. Declared version — the only version source ────────────────────────────

$versionPropertiesPath = "gradle/version.properties"
if (-not (Test-Path -LiteralPath $versionPropertiesPath)) {
    Refuse "$versionPropertiesPath not found." "It is the only version source; restore it before releasing."
}

$script:declaredVersion = ""
$script:declaredVersionCode = ""
foreach ($versionLine in Get-Content -LiteralPath $versionPropertiesPath) {
    if ($versionLine -match '^\s*version\s*=\s*(.+?)\s*$') { $script:declaredVersion = $Matches[1] }
    if ($versionLine -match '^\s*versionCode\s*=\s*(.+?)\s*$') { $script:declaredVersionCode = $Matches[1] }
}
if ($script:declaredVersion -notmatch '^\d+\.\d+\.\d+$') {
    Refuse "$versionPropertiesPath declares no major.minor.patch 'version' (found '$($script:declaredVersion)')."
}
if ($script:declaredVersionCode -notmatch '^\d+$') {
    Refuse "$versionPropertiesPath declares no integer 'versionCode' (found '$($script:declaredVersionCode)')."
}

$tag = "v$($script:declaredVersion)"

# ── 2. Artefact name contract ────────────────────────────────────────────────
# One shape for every platform; the platform token is matched case-insensitively because older
# artefacts were named Payanam_Android_* and the shared naming helper emits lowercase.
$artifactNamePattern = '^Payanam_(?<platform>(?i:android|linux|windows))_(?<version>\d+\.\d+\.\d+)_(?<sha>[0-9a-f]{7,40}|nogit)_(?<type>debug|release)_(?<timestamp>\d{8}_\d{6})$'

$platforms = @("Android", "Linux", "Windows")
$artifactDirs = @{ Android = "output/apks"; Linux = "output/debs"; Windows = "output/windows" }
$artifactExtensions = @{ Android = @(".apk"); Linux = @(".deb"); Windows = @(".zip") }
$buildHints = @{
    Android = "Build one with build-tools/scripts/build-android.ps1."
    Linux   = "Build one with build-tools/scripts/build-linux.sh (release build)."
    Windows = "Build one with build-tools/scripts/build-windows.ps1 -Release."
}

function Get-ParsedArtifactName {
    param([string]$Name)
    $nameMatch = [regex]::Match($Name, $artifactNamePattern)
    if (-not $nameMatch.Success) { return $null }
    return [PSCustomObject]@{
        Platform  = $nameMatch.Groups["platform"].Value.ToLowerInvariant()
        Version   = $nameMatch.Groups["version"].Value
        Sha       = $nameMatch.Groups["sha"].Value
        Type      = $nameMatch.Groups["type"].Value
        Timestamp = $nameMatch.Groups["timestamp"].Value
    }
}

function New-ArtifactRecord {
    param([System.IO.FileSystemInfo]$Item, [PSCustomObject]$Parsed)
    return [PSCustomObject]@{
        Platform     = $Parsed.Platform
        Item         = $Item
        Name         = $Item.Name
        Version      = $Parsed.Version
        Sha          = $Parsed.Sha
        Type         = $Parsed.Type
        Timestamp    = $Parsed.Timestamp
        LastWriteUtc = $Item.LastWriteTimeUtc
    }
}

# Assert every field a release depends on: platform, declared version, release type.
function Assert-ArtifactFields {
    param([PSCustomObject]$Parsed, [string]$Platform, [string]$Name)
    if ($Parsed.Platform -ne $Platform.ToLowerInvariant()) {
        Refuse "$Name names platform '$($Parsed.Platform)' but was passed as $Platform."
    }
    if ($Parsed.Version -ne $script:declaredVersion) {
        Refuse "$Name carries version $($Parsed.Version), not the declared $($script:declaredVersion)." "Bump gradle/version.properties, or rebuild the artefact."
    }
    if ($Parsed.Type -ne "release") {
        Refuse "$Name is a '$($Parsed.Type)' artefact; a $tag release ships release-type artefacts only." "Rebuild it in release mode."
    }
}

function Resolve-NamedArtifact {
    param([string]$Path, [string]$Platform)
    if (-not (Test-Path -LiteralPath $Path)) {
        Refuse "$Platform artefact not found: $Path"
    }
    $item = Get-Item -LiteralPath $Path
    $baseName = if ($item.PSIsContainer) { $item.Name } else { $item.BaseName }
    $parsed = Get-ParsedArtifactName -Name $baseName
    if ($null -eq $parsed) {
        Refuse "$Platform artefact name does not match the release shape: $($item.Name)." "Expected Payanam_<platform>_<version>_<sha>_<release>_<yyyyMMdd_HHmmss>[.<ext>]."
    }
    Assert-ArtifactFields -Parsed $parsed -Platform $Platform -Name $item.Name
    return New-ArtifactRecord -Item $item -Parsed $parsed
}

function Find-NewestArtifact {
    param([string]$Directory, [string]$Platform, [string[]]$Extensions)
    if (-not (Test-Path -LiteralPath $Directory)) { return $null }

    $candidates = @()
    $shapeSkipped = 0
    $typeSkipped = 0
    $versionSkipped = @()

    foreach ($item in Get-ChildItem -LiteralPath $Directory) {
        if ($item.PSIsContainer -and $Platform -ne "Windows") { continue }
        if (-not $item.PSIsContainer -and $Extensions -notcontains $item.Extension.ToLowerInvariant()) { continue }

        $baseName = if ($item.PSIsContainer) { $item.Name } else { $item.BaseName }
        $parsed = Get-ParsedArtifactName -Name $baseName
        if ($null -eq $parsed -or $parsed.Platform -ne $Platform.ToLowerInvariant()) { $shapeSkipped++; continue }
        if ($parsed.Version -ne $script:declaredVersion) { $versionSkipped += "$baseName (version $($parsed.Version))"; continue }
        if ($parsed.Type -ne "release") { $typeSkipped++; continue }
        $candidates += (New-ArtifactRecord -Item $item -Parsed $parsed)
    }

    if ($versionSkipped.Count -gt 0) {
        Write-LogWithTime "  $Platform : ignored $($versionSkipped.Count) artefact(s) of another version: $($versionSkipped -join ', ')" "Gray"
    }
    if ($typeSkipped -gt 0) {
        Write-LogWithTime "  $Platform : ignored $typeSkipped debug-type artefact(s); a release ships release-type artefacts" "Gray"
    }
    if ($shapeSkipped -gt 0) {
        Write-LogWithTime "  $Platform : ignored $shapeSkipped name(s) outside the release shape" "Gray"
    }
    if ($candidates.Count -eq 0) { return $null }

    $sort = @(
        @{ Expression = "Timestamp"; Descending = $true }
        @{ Expression = "LastWriteUtc"; Descending = $true }
    )
    return ($candidates | Sort-Object -Property $sort | Select-Object -First 1)
}

# ── 3. Guards — every one of them runs before a tag exists ───────────────────

$ghCmd = Get-Command gh -ErrorAction SilentlyContinue
if (-not $ghCmd) {
    Refuse "'gh' (GitHub CLI) not found." "Install it (https://cli.github.com) or use -DryRun for the artefact checks."
}

$commit = (git rev-parse HEAD 2>$null).Trim()
$head = (git rev-parse --short HEAD 2>$null).Trim()
if ([string]::IsNullOrWhiteSpace($commit)) { Refuse "Could not resolve HEAD - refusing to release." }

$statusLines = @(git status --porcelain 2>$null)
$statusExit = $LASTEXITCODE
if ($statusExit -ne 0) {
    Refuse "Could not read the working-tree status (git exit $statusExit)." "A release needs a readable tree."
}
if (-not [string]::IsNullOrWhiteSpace(($statusLines -join ""))) {
    Refuse "The working tree has uncommitted changes." "Commit or stash before releasing; a release must be reproducible from its tag."
}

$originDev = (git rev-parse origin/dev 2>$null).Trim()
$originExit = $LASTEXITCODE
if ($originExit -ne 0 -or [string]::IsNullOrWhiteSpace($originDev)) {
    Refuse "Could not resolve origin/dev." "Fetch it first: git fetch origin dev."
}
if ($originDev -ne $commit) {
    Refuse "HEAD ($head) differs from origin/dev." "Push dev first: the tag must point at a commit that exists on GitHub."
}

$changelogRel = "fastlane/metadata/android/en-US/changelogs/$($script:declaredVersionCode).txt"
$changelogText = (git show "${commit}:$changelogRel" 2>$null | Out-String)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($changelogText.Trim())) {
    Refuse "No changelog for versionCode $($script:declaredVersionCode) at $head." "Expected $changelogRel in the tagged commit; F-Droid shows that file as What's new."
}
foreach ($locale in @("ta-IN")) {
    $translatedRel = "fastlane/metadata/android/$locale/changelogs/$($script:declaredVersionCode).txt"
    $translatedText = (git show "${commit}:$translatedRel" 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($translatedText.Trim())) {
        Write-LogWithTime "⚠️ No $locale changelog for versionCode $($script:declaredVersionCode) - the default locale is shown." "Yellow"
    }
}

$localTag = @(git tag -l $tag 2>$null)
if ($localTag -contains $tag) {
    Refuse "Tag $tag already exists locally." "Delete it (git tag -d $tag) if it is stale; a version is released once."
}
$remoteTag = (git ls-remote --tags origin "refs/tags/$tag" 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    Refuse "Could not reach origin to check for an existing $tag." "Refusing to release blind."
}
if ($remoteTag -match [regex]::Escape("refs/tags/$tag")) {
    Refuse "Tag $tag already exists on origin." "A version is released once; bump gradle/version.properties for the next release."
}

Write-LogWithTime "Guards passed: clean tree, HEAD $head == origin/dev, changelog at $head, $tag unused." "Green"

# ── 4. Resolve one artefact per platform ─────────────────────────────────────

$resolved = @{}
foreach ($platform in $platforms) {
    $explicitPath = switch ($platform) {
        "Android" { $AndroidApkPath }
        "Linux" { $LinuxDebPath }
        "Windows" { $WindowsPath }
    }
    $resolved[$platform] = if (-not [string]::IsNullOrWhiteSpace($explicitPath)) {
        Resolve-NamedArtifact -Path $explicitPath -Platform $platform
    } else {
        Find-NewestArtifact -Directory $artifactDirs[$platform] -Platform $platform -Extensions $artifactExtensions[$platform]
    }
}

# Comma or space separated, so both '-Require Android,Linux' and -File invocation work.
$requiredPlatforms = @("Android")
foreach ($requireEntry in $Require) {
    foreach ($token in ($requireEntry -split '[,;\s]+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
        $knownPlatform = $platforms | Where-Object { $_ -eq $token } | Select-Object -First 1
        if ($null -eq $knownPlatform) {
            Refuse "Unknown platform '$token' in -Require." "Use Android, Linux or Windows."
        }
        $requiredPlatforms += $knownPlatform
    }
}
$requiredPlatforms = @($requiredPlatforms | Select-Object -Unique)
Write-LogWithTime "" "White"
Write-LogWithTime "Artefacts for $tag ($($script:declaredVersion)):" "Cyan"
foreach ($platform in $platforms) {
    $artifact = $resolved[$platform]
    $platformDir = $artifactDirs[$platform]
    if ($null -eq $artifact) {
        if ($requiredPlatforms -contains $platform) {
            Refuse "$platform artefact is required but none was found." $buildHints[$platform]
        }
        Write-LogWithTime "  $platform : absent - not attached (no release-type artefact of $($script:declaredVersion) in $($platformDir))" "Yellow"
        continue
    }
    Write-LogWithTime "  $platform : $($artifact.Name)" "Cyan"
}

# ── 5. Verify the release signature of the Android artefact ──────────────────
# Fail-closed: if the toolchain cannot complete the checks, the publish refuses. Shared
# implementation (release-security.ps1) - never copied.
$android = $resolved["Android"]
try {
    Invoke-ReleaseSecurityVerification -ApkPath $android.Item.FullName -FailClosed
} catch {
    Refuse "Android signature verification failed." $_.Exception.Message
}

# ── 6. Verify each build-side SHA256 checksum ────────────────────────────────
# The build writes the checksum; the publisher verifies it, never generates it, so an artefact
# altered between build and publish is refused rather than hashed as it now is.
function Assert-BuildChecksum {
    param([string]$ArtifactPath, [string]$Label)
    $checksumPath = "$ArtifactPath.sha256"
    if (-not (Test-Path -LiteralPath $checksumPath)) {
        Refuse "$Label has no build-side checksum: $(Split-Path -Leaf $checksumPath)." "The build writes it beside the artefact; without it the artefact cannot be verified."
    }
    $checksumLine = ((Get-Content -LiteralPath $checksumPath -Raw) -split "`n")[0]
    if ([string]::IsNullOrWhiteSpace($checksumLine)) {
        Refuse "$Label checksum file is empty: $(Split-Path -Leaf $checksumPath)." "A build-side checksum must carry the hash on its first line."
    }
    $recordedHash = ($checksumLine.Trim() -split '\s+')[0].ToUpper()
    $actualHash = (Get-FileHash -Path $ArtifactPath -Algorithm SHA256).Hash.ToUpper()
    if ($recordedHash -ne $actualHash) {
        Refuse "$Label checksum MISMATCH - refusing to publish." "recorded at build: $recordedHash ; computed now: $actualHash"
    }
    Write-LogWithTime "✅ $Label checksum verified: $actualHash" "Green"
    return $actualHash
}

function Resolve-WindowsAsset {
    param([PSCustomObject]$Artifact)
    if (-not $Artifact.Item.PSIsContainer) {
        return [PSCustomObject]@{ Path = $Artifact.Item.FullName; Name = $Artifact.Item.Name }
    }
    $parent = Split-Path -Parent $Artifact.Item.FullName
    $zipPath = Join-Path $parent "$($Artifact.Name).zip"
    if (Test-Path -LiteralPath $zipPath) {
        Write-LogWithTime "Reusing packaged Windows artefact: $(Split-Path -Leaf $zipPath)" "Gray"
        return [PSCustomObject]@{ Path = $zipPath; Name = (Split-Path -Leaf $zipPath) }
    }
    if ($DryRun) {
        return [PSCustomObject]@{ Path = $zipPath; Name = (Split-Path -Leaf $zipPath) }
    }
    Compress-Archive -Path $Artifact.Item.FullName -DestinationPath $zipPath -CompressionLevel Optimal
    if (-not (Test-Path -LiteralPath $zipPath)) { Refuse "Could not package the Windows artefact as $(Split-Path -Leaf $zipPath)." }
    Write-LogWithTime "Packaged Windows artefact: $(Split-Path -Leaf $zipPath)" "Green"
    return [PSCustomObject]@{ Path = $zipPath; Name = (Split-Path -Leaf $zipPath) }
}

$assets = @()

function ConvertTo-BuildTime {
    param([string]$Timestamp)
    return [DateTime]::ParseExact($Timestamp, "yyyyMMdd_HHmmss", [System.Globalization.CultureInfo]::InvariantCulture)
}

$androidHash = Assert-BuildChecksum -ArtifactPath $android.Item.FullName -Label "Android APK"
$assets += [PSCustomObject]@{
    Path         = $android.Item.FullName
    Name         = $android.Item.Name
    Hash         = $androidHash
    ChecksumPath = "$($android.Item.FullName).sha256"
    BuildTime    = ConvertTo-BuildTime $android.Timestamp
}

if ($null -ne $resolved["Linux"]) {
    $linux = $resolved["Linux"]
    $linuxHash = Assert-BuildChecksum -ArtifactPath $linux.Item.FullName -Label "Linux DEB"
    $assets += [PSCustomObject]@{
        Path         = $linux.Item.FullName
        Name         = $linux.Item.Name
        Hash         = $linuxHash
        ChecksumPath = "$($linux.Item.FullName).sha256"
        BuildTime    = ConvertTo-BuildTime $linux.Timestamp
    }
}

if ($null -ne $resolved["Windows"]) {
    $windows = $resolved["Windows"]
    $windowsAsset = Resolve-WindowsAsset -Artifact $windows
    # The Windows build writes no checksum sidecar, so this asset ships unverified.
    Write-LogWithTime "Windows artefact has no build-side checksum; it is attached unverified." "Yellow"
    $assets += [PSCustomObject]@{
        Path         = $windowsAsset.Path
        Name         = $windowsAsset.Name
        Hash         = ""
        ChecksumPath = ""
        BuildTime    = ConvertTo-BuildTime $windows.Timestamp
    }
}

# ── 7. Artefact-vs-commit warnings (never a block) ───────────────────────────
foreach ($platform in $platforms) {
    $artifact = $resolved[$platform]
    if ($null -ne $artifact -and $artifact.Sha -ne "nogit" -and $artifact.Sha -ne $head) {
        Write-LogWithTime "⚠️  $($artifact.Name) was built from $($artifact.Sha), not the release commit $head." "Yellow"
    }
}

$oldestBuildTime = ($assets | Sort-Object BuildTime | Select-Object -First 1).BuildTime
$buildStamp = $oldestBuildTime.ToString("yyyy-MM-dd HH:mm")

$latestCommitTime = git log -1 --format=%cI 2>$null
if ($oldestBuildTime -and -not [string]::IsNullOrWhiteSpace($latestCommitTime)) {
    $commitTime = [DateTimeOffset]::Parse($latestCommitTime, [System.Globalization.CultureInfo]::InvariantCulture).LocalDateTime
    $gap = $commitTime - $oldestBuildTime
    if ($gap.TotalHours -ge 1) {
        Write-LogWithTime "⚠️  An attached artefact was built at $($oldestBuildTime.ToString('yyyy-MM-dd HH:mm:ss')), $($gap.ToString('hh\:mm\:ss')) before the latest commit ($($commitTime.ToString('yyyy-MM-dd HH:mm:ss')))." "Yellow"
        Write-LogWithTime "   It may not contain the latest committed changes. Publishing anyway (warning only)." "Yellow"
    }
}

# ── 8. Release notes ─────────────────────────────────────────────────────────
$releaseNotes = @(
    $buildStamp,
    "",
    "Version: $($script:declaredVersion)",
    "Commit: $head",
    "Tag: $tag",
    "",
    "Artefacts:"
)
foreach ($asset in $assets) {
    $releaseNotes += "- $($asset.Name)"
    $releaseNotes += if ([string]::IsNullOrWhiteSpace($asset.Hash)) { "  SHA256: none - no build-side checksum" } else { "  SHA256: $($asset.Hash)" }
}
$releaseNotes += ""
$releaseNotes += "Verify before installing: see [INSTALL.md](https://github.com/Aravinth-Earth/Payanam/blob/main/INSTALL.md) for checksum verification and sideload steps."
$releaseText = $releaseNotes -join "`n"

# ── 9. Create the release and its tag ────────────────────────────────────────
Write-LogWithTime "" "White"
Write-LogWithTime "Release: $tag ($($script:declaredVersion)) on $head" "Cyan"

if ($DryRun) {
    Write-LogWithTime "[DRY RUN] Would create release $tag" "Yellow"
    Write-LogWithTime "[DRY RUN]   title  : Payanam $($script:declaredVersion)" "Yellow"
    Write-LogWithTime "[DRY RUN]   target : $commit" "Yellow"
    foreach ($asset in $assets) {
        Write-LogWithTime "[DRY RUN]   asset  : $($asset.Name)" "Yellow"
        if (-not [string]::IsNullOrWhiteSpace($asset.ChecksumPath)) {
            Write-LogWithTime "[DRY RUN]   asset  : $(Split-Path -Leaf $asset.ChecksumPath)" "Yellow"
        }
    }
    Write-LogWithTime "[DRY RUN] Complete — no tag and no release were created." "Green"
    exit 0
}

# gh creates the remote tag at the verified commit; the guards above prove it does not exist yet.
$ghArgs = @(
    $tag
    "--title", "Payanam $($script:declaredVersion)"
    "--notes", $releaseText
    "--target", $commit
)
foreach ($asset in $assets) {
    $ghArgs += $asset.Path
    if (-not [string]::IsNullOrWhiteSpace($asset.ChecksumPath)) { $ghArgs += $asset.ChecksumPath }
}

gh release create @ghArgs
if ($LASTEXITCODE -ne 0) {
    Refuse "Release creation failed." "Check 'gh release list' first; a tag created before the failure has to be removed on the remote before retrying."
}

# ── 10. Report ───────────────────────────────────────────────────────────────
$releaseUrl = gh release view $tag --json url --jq ".url" 2>$null
Write-LogWithTime "Release published: $releaseUrl" "Green"
Write-LogWithTime "Tag    : $tag on $head" "Green"
Write-LogWithTime "Local  : git fetch --tags $tag" "Gray"
foreach ($asset in $assets) {
    Write-LogWithTime "Asset  : $($asset.Name)" "Green"
}
