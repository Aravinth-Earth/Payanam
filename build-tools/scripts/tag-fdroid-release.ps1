# SPDX-FileCopyrightText: 2026 Aravinth-Earth
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Tag the F-Droid release commit so F-Droid's update check can see it.
#
# ── F-Droid recipe contract (the recipe lives OUTSIDE this repo) ─────────────
# The recipe must carry UpdateCheckMode: Tags ^fdroid-v\d+$ and read the version
# code from the tag name (UpdateCheckData). It MUST be updated to that contract
# BEFORE the first fdroid-v* tag is published: a tag the recipe cannot see means
# F-Droid never offers the new version. Do not tag blindly - check the recipe.
#
# Tag namespace: fdroid-v{versionCode} (e.g. fdroid-v2047) - its own namespace,
# distinct from publish-release.ps1's v{build} / dev-v{build} / beta-v{build}.
# The tag is created LOCALLY; it is pushed only with an explicit -Push, and only
# Aravinth pushes (repo rule: agents never push).
#
# Usage: pwsh build-tools/scripts/tag-fdroid-release.ps1 [-Push]

param(
    [switch]$Push
)

$ErrorActionPreference = "Stop"

# git returns non-zero exit codes as normal control flow here (a missing path, an
# unreachable origin). Check $LASTEXITCODE instead of letting a native command throw.
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
Set-Location $projectRoot

# Shared release helpers live in release-security.ps1 - one implementation, never
# copied into the callers. That file documents that the HOST script owns
# Write-LogWithTime, so this script follows the same convention and only defines
# it when the shared file does not provide one of its own.
. "$PSScriptRoot/release-security.ps1"

if (-not (Get-Command -Name Write-LogWithTime -CommandType Function -ErrorAction SilentlyContinue)) {
    function Write-LogWithTime {
        param([string]$Message, [string]$Color = "White")
        $timestamp = Get-Date -Format "HH:mm:ss"
        Write-Host "[$timestamp] $Message" -ForegroundColor $Color
    }
}

# Refuse loudly and stop - a guard that only warns gets ignored.
function Refuse {
    param([string]$Message, [string]$Detail = "")
    Write-LogWithTime "❌ $Message" "Red"
    if (-not [string]::IsNullOrWhiteSpace($Detail)) { Write-LogWithTime "   $Detail" "Yellow" }
    exit 1
}

# File text from the working tree, or from a commit when -Commit is given.
function Get-RepoFileText {
    param([string]$Path, [string]$Commit = "")
    if ([string]::IsNullOrWhiteSpace($Commit)) {
        if (-not (Test-Path -LiteralPath $Path)) { return $null }
        return (Get-Content -LiteralPath $Path -Raw)
    }
    $text = (git show "${Commit}:${Path}" 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0) { return $null }
    return $text
}

# android.builds out of the build counter, or $null when it cannot be read.
function Get-AndroidBuilds {
    param([string]$CounterJson)
    if ([string]::IsNullOrWhiteSpace($CounterJson)) { return $null }
    try { $counter = $CounterJson | ConvertFrom-Json } catch { return $null }
    if ($null -eq $counter.android -or $null -eq $counter.android.builds) { return $null }
    return [int]$counter.android.builds
}

# ── 1. The tag must sit on the branch the F-Droid recipe is built from ──────
$branch = (git rev-parse --abbrev-ref HEAD 2>$null).Trim()
if ($branch -ne "fdroid-release") {
    Refuse "Refusing: on branch '$branch', expected 'fdroid-release'." "The tag must point at a commit of the branch the recipe builds."
}
$commit = (git rev-parse HEAD 2>$null).Trim()
$head   = (git rev-parse --short HEAD 2>$null).Trim()
if ([string]::IsNullOrWhiteSpace($commit)) { Refuse "Could not resolve HEAD. Refusing to tag." }

# ── 2. The version code ─────────────────────────────────────────────────────
# app/build.gradle.kts carries no literal number: it computes one
# (`versionCode = buildNumber`), and buildNumber resolves to
# build-tools/tracking/build-counter.json -> android.builds. So the counter is the
# source of truth here; if that scheme changes, the number below would be a guess
# - so the scheme is asserted and the script refuses instead.
$counterPath = "build-tools/tracking/build-counter.json"
$gradlePath  = "app/build.gradle.kts"
$computedVersionPattern = 'versionCode\s*=\s*buildNumber'

foreach ($source in @(@{ Label = "working tree"; Text = (Get-RepoFileText -Path $gradlePath) },
                      @{ Label = $head;         Text = (Get-RepoFileText -Path $gradlePath -Commit $commit) })) {
    if ($null -eq $source.Text -or $source.Text -notmatch $computedVersionPattern) {
        Refuse "Refusing: $gradlePath ($($source.Label)) does not set 'versionCode = buildNumber'." "The version scheme changed - update this script for the new scheme before tagging."
    }
}

$workTreeBuilds = Get-AndroidBuilds (Get-RepoFileText -Path $counterPath)
$commitBuilds   = Get-AndroidBuilds (Get-RepoFileText -Path $counterPath -Commit $commit)
if ($null -eq $workTreeBuilds -or $null -eq $commitBuilds) {
    Refuse "Could not read android.builds from $counterPath (working tree or $head)."
}

# Drift: the working tree moved past the commit to be tagged, or the commit was
# made against a different counter - either way the tag would carry a wrong version.
if ($workTreeBuilds -ne $commitBuilds) {
    Refuse "Refusing: version drift between the working tree and $head." "working tree android.builds = $workTreeBuilds ; $head android.builds = $commitBuilds - commit the counter before tagging."
}

$versionCode = $commitBuilds
$tag = "fdroid-v$versionCode"

# ── 3. A changelog must exist for the version this tag will publish ─────────
# F-Droid shows fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt as the
# "What's new" text for that release. Because the version code is computed from the build
# counter, the filename can never be committed in advance for the number this run will
# produce - so the check is that the file for THIS number already exists at the commit
# being tagged. The default locale is required; a missing translation is a warning rather
# than a refusal, because a locale with no changelogs directory at all would otherwise
# block every tag. The filename carries the version number; the file body is prose for
# users and is deliberately not parsed for a version token.
$changelogRoot = "fastlane/metadata/android"
$requiredChangelog = "$changelogRoot/en-US/changelogs/$versionCode.txt"
if ($null -eq (Get-RepoFileText -Path $requiredChangelog -Commit $commit)) {
    Refuse "Refusing: no changelog for version $versionCode." "Expected $requiredChangelog at $head. F-Droid shows that file as What's new, so a tag without it publishes release notes that do not exist."
}
Write-LogWithTime "Changelog: $requiredChangelog" "Cyan"
foreach ($locale in @("ta-IN")) {
    $translatedChangelog = "$changelogRoot/$locale/changelogs/$versionCode.txt"
    if ($null -eq (Get-RepoFileText -Path $translatedChangelog -Commit $commit)) {
        Write-LogWithTime "⚠️ No $locale changelog for $versionCode - the default locale is shown." "Yellow"
    }
}

# ── 4. Never re-tag ─────────────────────────────────────────────────────────
$localTags = @(git tag -l $tag 2>$null)
if ($localTags -contains $tag) { Refuse "Tag $tag already exists locally. Nothing to do." }
$remoteTags = (git ls-remote --tags origin "refs/tags/$tag" 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { Refuse "Could not reach origin to check for an existing $tag - refusing to tag blind." }
if ($remoteTags -match [regex]::Escape("refs/tags/$tag")) { Refuse "Tag $tag already exists on origin. Nothing to do." }

# ── 5. Create the tag LOCALLY ───────────────────────────────────────────────
Write-LogWithTime "Branch  : $branch (head $head)" "Cyan"
Write-LogWithTime "Version : $versionCode (app + counter agree at $head)" "Cyan"
Write-LogWithTime "Tag     : $tag" "Cyan"
Write-LogWithTime "Recipe  : UpdateCheckMode: Tags ^fdroid-v\d+$ - confirm the recipe before publishing." "Yellow"
git tag $tag $commit
if ($LASTEXITCODE -ne 0) { Refuse "git tag failed." }
Write-LogWithTime "✅ Created local tag $tag on $head" "Green"

# ── 6. Push only when asked ─────────────────────────────────────────────────
if ($Push) {
    git push origin $tag
    if ($LASTEXITCODE -ne 0) { Refuse "git push failed - the tag exists locally." }
    Write-LogWithTime "✅ Pushed $tag. F-Droid's update check sees it on its next run." "Green"
} else {
    Write-LogWithTime "Not pushed. To publish: git push origin $tag" "Yellow"
}
