# Tags the current fdroid-release commit so F-Droid's update check can see it.
# The tag format matches the recipe's UpdateCheckMode: Tags ^fdroid-v\d+$
# and carries the app's versionCode, which the recipe's UpdateCheckData reads
# from the tag name.
#
# Usage:
#   pwsh build-tools/scripts/tag-fdroid-release.ps1          # create locally, print the push command
#   pwsh build-tools/scripts/tag-fdroid-release.ps1 -Push    # create and push

param(
    [switch]$Push
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
Set-Location $projectRoot

function Write-LogWithTime
{
    param([string]$Message, [string]$Color = "White")
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Message" -ForegroundColor $Color
}

# 1. The tag must sit on the branch the F-Droid recipe is built from.
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne "fdroid-release")
{
    Write-LogWithTime "Refusing: on branch '$branch', expected 'fdroid-release'." "Red"
    Write-LogWithTime "The tag must point at a commit of the branch the recipe builds." "Yellow"
    exit 1
}

# 2. versionCode as it will appear in the built APK.
$gradleFile = "app/build.gradle.kts"
$appBuildGradle = Get-Content $gradleFile -Raw
if ($appBuildGradle -notmatch 'versionCode\s*=\s*(\d+)')
{
    Write-LogWithTime "Could not read versionCode from $gradleFile" "Red"
    exit 1
}
$versionCode = [int]$Matches[1]

# 3. Cross-check the counter. build-android.ps1 writes both, so a drift here
#    means one of them was edited by hand - which would publish a wrong version.
$counter = Get-Content "build-tools/tracking/build-counter.json" -Raw | ConvertFrom-Json
if ([int]$counter.androidBuilds -ne $versionCode)
{
    Write-LogWithTime "Refusing: app versionCode $versionCode != build-counter.androidBuilds $($counter.androidBuilds)" "Red"
    Write-LogWithTime "Fix the two so they agree before tagging." "Yellow"
    exit 1
}

$tag = "fdroid-v$versionCode"
$head = (git rev-parse --short HEAD).Trim()

# 4. Never re-tag.
if (git tag -l $tag)
{
    Write-LogWithTime "Tag $tag already exists locally - nothing to do." "Yellow"
    exit 1
}
if (git ls-remote --tags origin "refs/tags/$tag" | Select-String -SimpleMatch "refs/tags/$tag")
{
    Write-LogWithTime "Tag $tag already exists on origin - nothing to do." "Yellow"
    exit 1
}

Write-LogWithTime "Branch     : $branch (head $head)" "Cyan"
Write-LogWithTime "App version: $versionCode (app + counter agree)" "Cyan"
Write-LogWithTime "Tag        : $tag" "Cyan"

git tag $tag
if ($LASTEXITCODE -ne 0)
{
    Write-LogWithTime "git tag failed." "Red"
    exit 1
}
Write-LogWithTime "Created local tag $tag on $head" "Green"

if ($Push)
{
    git push origin $tag
    if ($LASTEXITCODE -ne 0)
    {
        Write-LogWithTime "git push failed - the tag exists locally." "Red"
        exit 1
    }
    Write-LogWithTime "Pushed $tag. F-Droid's update check will pick it up on its next run." "Green"
}
else
{
    Write-LogWithTime "Not pushed. To publish: git push origin $tag" "Yellow"
}
