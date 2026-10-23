# All-Platforms Build Script for Payanam
# Android (always) + desktop (Linux or Windows, based on current OS).
#
# -Profile is forwarded to BOTH phases. Pass `-Profile full` for any run whose result you intend
# to trust. Each sub-script otherwise auto-resolves its own profile and can silently choose
# `quick`, which SKIPS unit tests - a green quick run compiles but does not verify.

param(
    [ValidateSet("auto", "quick", "normal", "full")]
    [string]$Profile = "auto"
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Payanam All-Platforms Build (profile: $Profile)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Phase 1: Android
Write-Host ""
Write-Host "=== PHASE 1: Android ===" -ForegroundColor Yellow
& "$scriptDir/build-android.ps1" -Profile $Profile
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "❌ Android phase failed (exit $LASTEXITCODE). Stopping." -ForegroundColor Red
    exit $LASTEXITCODE
}

# Phase 2: Desktop (OS-specific)
Write-Host ""
if ($IsWindows) {
    Write-Host "=== PHASE 2: Windows ===" -ForegroundColor Yellow
    & "$scriptDir/build-windows.ps1" -Profile $Profile
    $desktopExit = $LASTEXITCODE
} else {
    Write-Host "=== PHASE 2: Linux ===" -ForegroundColor Yellow
    bash "$scriptDir/build-linux.sh" --profile "$Profile"
    $desktopExit = $LASTEXITCODE
}

if ($desktopExit -ne 0) {
    Write-Host ""
    Write-Host "❌ Desktop phase failed (exit $desktopExit)." -ForegroundColor Red
    exit $desktopExit
}

Write-Host ""
Write-Host "✅ All built phases passed (profile: $Profile)." -ForegroundColor Green
