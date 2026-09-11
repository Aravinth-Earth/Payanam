# SPDX-FileCopyrightText: 2026 Aravinth-Earth
# SPDX-License-Identifier: AGPL-3.0-or-later
<#
.SYNOPSIS
    Standalone runner for the in-process (Compose UI Test / Espresso) tier.

.DESCRIPTION
    Runs an instrumented Android test N times, reports each run's verdict, and persists every
    run's artefacts. It exists so a stability campaign (e.g. "5 consecutive passes") can be
    driven directly, without any coupling to the build pipeline.

    INDEPENDENCE
      * Does NOT call, read, or modify build-tools/scripts/build-android.ps1.
      * Is NOT part of the build pipeline and adds nothing to it.
      * Invokes only the connected-test Gradle task, so the only build work it can trigger is
        what Gradle considers stale. Because the versionCode is bumped exclusively by
        build-android.ps1, a plain run of this script reuses the APKs already built and does
        NOT rebuild them.

    NOT verify-by-build. If you changed app code, run build-android.ps1 first (project rule);
    this script is for running tests repeatedly, not for validating a change.

    WHY GRADLE AND NOT adb: running instrumented tests means installing an app APK + a test
    APK and starting the instrumentation. The only supported way to do that here is Gradle's
    connected-test task; the adb alternative is forbidden in this project.

.EXAMPLE
    # 5 consecutive cycles of the in-process probe, results persisted per run
    pwsh build-tools/scripts/run-inprocess-tests.ps1 -Runs 5

.EXAMPLE
    # a different app/module and a single test class
    pwsh build-tools/scripts/run-inprocess-tests.ps1 -Runs 10 -Module ":app" -TestClass io.payanam.poc.NavSpeedProbeTest
#>
[CmdletBinding()]
param(
    # How many cycles to run.
    [int]$Runs = 5,

    # Gradle module to test.
    [string]$Module = ":app",

    # Build variant (debug / release). Only the variant name is used to build the task name.
    [string]$Variant = "debug",

    # Optional fully-qualified test class to run (instrumentation runner argument).
    [string]$TestClass = "",

    # Directory for the CSV and the per-run artefact copies (relative to the repo root).
    [string]$OutDir = "build-logs/inprocess-runs",

    # Device serial to target (sets ANDROID_SERIAL).
    [string]$Device = "",

    # Leave the app + test APK installed after each run so the next cycle does not reinstall.
    [bool]$KeepInstalled = $true,

    # Tag whose stdout lines carry the run's own metrics (probe-specific; harmless if absent).
    [string]$MetricTag = "NAV_PROBE",

    # Extra arguments appended verbatim to the Gradle invocation.
    [string[]]$ExtraGradleArgs = @(),

    # Wipe the previous results before each run (keeps a stale result from being read as fresh).
    [bool]$CleanResults = $true
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------- environment
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..\..")
Set-Location $repoRoot

if ($Device) { $env:ANDROID_SERIAL = $Device }

$gradlew = if ($IsWindows) { ".\gradlew.bat" } else { "./gradlew" }

$variantCap = $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
$testTask = "$Module`:connected$($variantCap)AndroidTest"

$resultsRoot = Join-Path $repoRoot (($Module.TrimStart(":") + "/build/outputs/androidTest-results/connected") -replace "//", "/")
$reportRoot = Join-Path $repoRoot (($Module.TrimStart(":") + "/build/reports/androidTests/connected") -replace "//", "/")

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$csvPath = Join-Path $OutDir "runs.csv"
"run,rc,verdict,setup_ms,per_tap_ms,total_ms,median_ms" | Set-Content -Path $csvPath

Write-Host ""
Write-Host "in-process test runner" -ForegroundColor Cyan
Write-Host "  repo      : $repoRoot"
Write-Host "  task      : $testTask"
Write-Host "  runs      : $Runs"
Write-Host "  results   : $resultsRoot"
Write-Host "  output    : $OutDir"
Write-Host ""

# ---------------------------------------------------------------- helpers
function Get-LatestResultDir {
    if (-not (Test-Path $resultsRoot)) { return $null }
    Get-ChildItem -Path $resultsRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Get-MetricValue {
    param([string]$Text, [string]$Key)
    if (-not $Text) { return $null }
    $m = [regex]::Match($Text, "$Key=([^\s]+)")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

$summary = @()

# ---------------------------------------------------------------- run loop
for ($i = 1; $i -le $Runs; $i++) {
    $stamp = Get-Date -Format "HH:mm:ss"
    Write-Host ""
    Write-Host "########## RUN $i / $Runs   start $stamp ##########" -ForegroundColor Yellow

    if ($CleanResults -and (Test-Path $resultsRoot)) {
        Remove-Item -Path $resultsRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    $gradleArgs = @(
        $testTask,
        "-Ppayanam.noMinify=true",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=$($KeepInstalled.ToString().ToLower())"
    )
    if ($TestClass) { $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass" }
    if ($ExtraGradleArgs.Count -gt 0) { $gradleArgs += $ExtraGradleArgs }

    & $gradlew @gradleArgs | Out-Host
    $rc = $LASTEXITCODE

    # ------------------------------------------------------------ verdict + metrics (host-side)
    $dir = Get-LatestResultDir
    $verdict = "NO_RESULT"
    $setupMs = ""; $perTap = ""; $totalMs = ""; $medianMs = ""

    if ($dir) {
        # Recursive: AGP nests the device dir differently across versions/backends
        # (connected/<device>/... vs connected/<variant>/<device>/...), so never assume a depth.
        $logFile = Get-ChildItem -Path $dir.FullName -Recurse -Filter "test-results.log" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($logFile) {
            $hits = Get-Content -Path $logFile.FullName |
                Select-String -Pattern "^OK \(", "^Tests run:" |
                Select-Object -Last 2
            if ($hits) { $verdict = (($hits | ForEach-Object { $_.Line.Trim() }) -join " | ") }
        }

        $logcat = Get-ChildItem -Path $dir.FullName -Recurse -Filter "logcat-*.txt" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($logcat) {
            $probeLines = Get-Content -Path $logcat.FullName -ErrorAction SilentlyContinue |
                Select-String -Pattern $MetricTag -SimpleMatch
            $joined = ($probeLines | ForEach-Object { $_.Line }) -join "`n"
            $setupMs  = Get-MetricValue $joined "setup_ms"
            $perTap   = Get-MetricValue $joined "per_tap_ms"
            $totalMs  = Get-MetricValue $joined "total_ms"
            $medianMs = Get-MetricValue $joined "median_ms"
        }
    }

    # ------------------------------------------------------------ persist this run
    $runDir = Join-Path $OutDir "run$i-results"
    if (Test-Path $runDir) { Remove-Item -Path $runDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    if (Test-Path $resultsRoot) { Copy-Item -Path (Join-Path $resultsRoot "*") -Destination $runDir -Recurse -Force }
    if (Test-Path $reportRoot)  { Copy-Item -Path (Join-Path $reportRoot "*")  -Destination $runDir -Recurse -Force }

    $green = $verdict -like "OK*"
    $colour = if ($green) { "Green" } else { "Red" }
    Write-Host "  rc=$rc  verdict=[$verdict]" -ForegroundColor $colour
    if ($totalMs) {
        Write-Host "  setup_ms=$setupMs  total_ms=$totalMs  median_ms=$medianMs"
        Write-Host "  per_tap_ms=$perTap"
    }
    Write-Host "  persisted -> $runDir"

    Add-Content -Path $csvPath -Value "$i,$rc,`"$verdict`",$setupMs,$perTap,$totalMs,$medianMs"
    $summary += [pscustomobject]@{ Run = $i; Green = $green; Verdict = $verdict; Total = $totalMs; Median = $medianMs }
}

# ---------------------------------------------------------------- summary
$greenRuns = @($summary | Where-Object { $_.Green })
$redRuns = @($summary | Where-Object { -not $_.Green })

# longest consecutive green streak
$best = 0; $cur = 0
foreach ($r in $summary) {
    if ($r.Green) { $cur++; if ($cur -gt $best) { $best = $cur } } else { $cur = 0 }
}

Write-Host ""
Write-Host "================ SUMMARY ================" -ForegroundColor Cyan
Write-Host "  runs      : $($summary.Count)"
Write-Host "  green     : $($greenRuns.Count)" -ForegroundColor Green
Write-Host "  red       : $($redRuns.Count)" -ForegroundColor $(if ($redRuns.Count -gt 0) { "Red" } else { "Green" })
Write-Host "  longest consecutive green streak : $best" -ForegroundColor $(if ($best -ge 5) { "Green" } else { "Yellow" })

$streak = @($summary | Where-Object { $_.Green } | Where-Object { $_.Total })
if ($streak.Count -gt 0) {
    $totals  = $streak | ForEach-Object { [double]$_.Total }
    $medians = $streak | Where-Object { $_.Median } | ForEach-Object { [double]$_.Median }
    $avgT = ($totals | Measure-Object -Average).Average
    Write-Host ("  avg total over green runs : {0:N0} ms  (min {1:N0}, max {2:N0})" -f $avgT, ($totals | Measure-Object -Minimum).Minimum, ($totals | Measure-Object -Maximum).Maximum)
    if ($medians.Count -gt 0) {
        Write-Host ("  avg per-tap median        : {0:N0} ms" -f (($medians | Measure-Object -Average).Average))
    }
}
Write-Host "  csv       : $csvPath"
Write-Host "========================================="
