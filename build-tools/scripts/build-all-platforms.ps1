# All-Platforms Build Script for Payanam
# Runs Android build first, then Windows desktop build on success.
# TODO: reduce duplicate verification and logging between platform scripts
# when shared-code changes require both builds.

param(
    [switch]$Clean,
    [switch]$CleanInstall,
    [switch]$SkipTests,
    [switch]$SkipInstallVerification,
    [switch]$KeepDaemons,
    [switch]$Release,
    [ValidateSet("auto", "quick", "normal", "full")] [string]$Profile = "auto"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
Set-Location $projectRoot

function Get-BuildCounterState {
    $counterFile = Join-Path $projectRoot "build-tools\tracking\build-counter.json"
    if (-not (Test-Path $counterFile)) {
        return $null
    }

    return Get-Content -LiteralPath $counterFile -Raw | ConvertFrom-Json
}

function Format-BuildSummaryLine {
    param(
        [string]$PlatformName,
        [bool]$Succeeded,
        [object]$BuildNumber
    )

    $status = if ($Succeeded) { "SUCCESS" } else { "FAILED" }
    $buildSuffix = if ($null -ne $BuildNumber -and "$BuildNumber".Trim().Length -gt 0) { " (Build #$BuildNumber)" } else { "" }
    return "${PlatformName}: $status$buildSuffix"
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Payanam All-Platforms Build" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$androidSuccess = $false
$desktopSuccess = $false
$androidBuildNum = ""
$desktopBuildNum = ""

Write-Host "=== PHASE 1: Android Build ===" -ForegroundColor Yellow
& "$scriptDir\build-android.ps1" -Clean:$Clean -CleanInstall:$CleanInstall -SkipTests:$SkipTests -KeepDaemons:$KeepDaemons -Release:$Release -Profile:$Profile

if ($LASTEXITCODE -eq 0) {
    $androidSuccess = $true
    Write-Host "[Android] BUILD SUCCESSFUL" -ForegroundColor Green

    $counter = Get-BuildCounterState
    if ($null -ne $counter) {
        $androidBuildNum = $counter.androidBuilds
    }
} else {
    Write-Host "[Android] BUILD FAILED (exit code: $LASTEXITCODE)" -ForegroundColor Red
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  BUILD SUMMARY" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "Android: FAILED" -ForegroundColor Red
    Write-Host "Desktop: SKIPPED (Android failed)" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "=== PHASE 2: Desktop Build ===" -ForegroundColor Yellow
& "$scriptDir\build-desktop.ps1" -Clean:$Clean -CleanInstall:$CleanInstall -SkipTests:$SkipTests -SkipInstallVerification:$SkipInstallVerification -KeepDaemons:$KeepDaemons -Release:$Release -Profile:$Profile

if ($LASTEXITCODE -eq 0) {
    $desktopSuccess = $true
    Write-Host "[Desktop] BUILD SUCCESSFUL" -ForegroundColor Green

    $counter = Get-BuildCounterState
    if ($null -ne $counter) {
        $desktopBuildNum = $counter.windowsBuilds
    }
} else {
    Write-Host "[Desktop] BUILD FAILED (exit code: $LASTEXITCODE)" -ForegroundColor Red
    $counter = Get-BuildCounterState
    if ($null -ne $counter) {
        $desktopBuildNum = $counter.windowsBuilds
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  BUILD SUMMARY" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host (Format-BuildSummaryLine -PlatformName "Android" -Succeeded $androidSuccess -BuildNumber $androidBuildNum) -ForegroundColor $(if ($androidSuccess) { "Green" } else { "Red" })
Write-Host (Format-BuildSummaryLine -PlatformName "Desktop" -Succeeded $desktopSuccess -BuildNumber $desktopBuildNum) -ForegroundColor $(if ($desktopSuccess) { "Green" } else { "Red" })

if ($androidSuccess -and $desktopSuccess) {
    exit 0
} else {
    exit 1
}
