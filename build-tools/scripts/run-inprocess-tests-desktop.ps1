# SPDX-FileCopyrightText: 2026 Aravinth-Earth
# SPDX-License-Identifier: AGPL-3.0-or-later
<#
.SYNOPSIS
    Standalone runner for the desktop in-process (Compose UI Test) tier - the Linux twin of
    run-inprocess-tests.ps1.

.DESCRIPTION
    Runs one desktop in-process test class N times, reports each run's verdict, and persists every
    run's artefacts. It exists so a stability campaign ("5 consecutive passes") can be driven on the
    desktop exactly the way it is driven on Android, and so both platforms' runs are READ the same
    way: same verdict wording, same runs.csv columns, same per-run artefact layout, same exit
    contract.

    INDEPENDENCE
      * Does NOT call, read, or modify build-tools/scripts/build-linux.sh.
      * Is NOT part of the build pipeline and adds nothing to it.
      * Invokes only the :desktop:test Gradle task, so the only build work it can trigger is what
        Gradle considers stale.

    NOT verify-by-build. If you changed app code, run build-linux.sh first (project rule); this
    script is for running tests repeatedly, not for validating a change.

    WHY GRADLE AND NOT A HEADED RUN: the desktop in-process tier rasters through offscreen Skia, so
    no X display, no Xvfb and no click coordinates are involved. Gradle's test task is the only
    supported way to run it here, and it needs no device.

    THE VERDICT SOURCE IS THE JUNIT XML, not a device log: on Android the verdict comes from the
    connected run's test-results.log; on the desktop the equivalent record is Gradle's
    build/test-results/test/TEST-<class>.xml. The wording is kept identical - "OK (n tests)" or
    "Tests run: n, Failures: f, Errors: e" - so a campaign's rows read the same on both platforms.
    Because the desktop tier writes ONE FILE PER CLASS, a run's verdict is AGGREGATED over every
    result file that run produced; reading a single newest file reports one class's counts as the
    whole run's verdict, which is a green verdict for a run nobody counted.

    WHAT DIFFERS, AND WHY: setup_ms and per_tap_ms are Android probe concepts with no desktop
    counterpart, so they stay EMPTY here unless a test prints the metric tag itself (the probe
    mechanism is honoured identically: tag-named lines carrying key=value). total_ms and median_ms
    are filled from the XML's own per-test timings when no probe is present, because the desktop
    has a native timing source and leaving it unread would make the column useless.

.EXAMPLE
    # 5 consecutive cycles of one desktop UI-test class, results persisted per run
    pwsh build-tools/scripts/run-inprocess-tests-desktop.ps1 -TestClass io.payanam.desktop.DesktopFirstRunJourneyTest

.EXAMPLE
    # the whole :desktop:test task, twice
    pwsh build-tools/scripts/run-inprocess-tests-desktop.ps1 -Runs 2
#>
[CmdletBinding()]
param(
    # How many cycles to run.
    [int]$Runs = 5,

    # Gradle module to test.
    [string]$Module = ":desktop",

    # Optional fully-qualified test class to run (compiled into a Gradle --tests filter).
    [string]$TestClass = "",

    # Directory for the CSV and the per-run artefact copies (relative to the repo root).
    [string]$OutDir = "build-logs/inprocess-runs-desktop",

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

$gradlew = if ($IsWindows) { ".\gradlew.bat" } else { "./gradlew" }

$testTask = "$Module`:test"

$moduleDir = $Module.TrimStart(":")
$resultsRoot = Join-Path $repoRoot "$moduleDir/build/test-results/test"
$reportRoot = Join-Path $repoRoot "$moduleDir/build/reports/tests/test"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$csvPath = Join-Path $OutDir "runs.csv"
"run,rc,verdict,setup_ms,per_tap_ms,total_ms,median_ms" | Set-Content -Path $csvPath

Write-Host ""
Write-Host "in-process test runner (desktop)" -ForegroundColor Cyan
Write-Host "  repo      : $repoRoot"
Write-Host "  task      : $testTask"
Write-Host "  runs      : $Runs"
Write-Host "  testClass : $(if ($TestClass) { $TestClass } else { '(whole task)' })"
Write-Host "  results   : $resultsRoot"
Write-Host "  output    : $OutDir"
Write-Host ""

# ---------------------------------------------------------------- helpers
function Get-RunResultXml {
    param([datetime]$Since)
    if (-not (Test-Path $resultsRoot)) { return @() }
    @(
        Get-ChildItem -Path $resultsRoot -Filter "TEST-*.xml" -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -ge $Since } |
            Sort-Object Name
    )
}

function Get-MetricValue {
    param([string]$Text, [string]$Key)
    if (-not $Text) { return $null }
    $m = [regex]::Match($Text, "$Key=([^\s]+)")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Get-Median   {
    param([double[]]$Values)
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $mid = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$mid] }
    return ($sorted[$mid - 1] + $sorted[$mid]) / 2
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

    # --rerun is deliberate: a campaign must actually run each cycle. Without it a second identical
    # invocation can come back UP-TO-DATE, write no result file at all, and be read as NO_RESULT - a
    # red verdict for a test that never ran. (CleanResults already forces this by deleting the task's
    # outputs, but -CleanResults:$false must not silently degrade into "nothing ran".)
    $gradleArgs = @($testTask, "--console=plain", "--rerun")
    if ($TestClass) { $gradleArgs += @("--tests", $TestClass) }
    if ($ExtraGradleArgs.Count -gt 0) { $gradleArgs += $ExtraGradleArgs }

    $runConsole = Join-Path $OutDir "run$i-console.log"
    # Only result files written by THIS run are read as its verdict. The Android runner reads one
    # whole-run summary log; the desktop tier writes one XML per CLASS, so a run is aggregated over
    # the XMLs it produced - and "produced" is a timestamp, not "whatever is in the directory", or a
    # stale file left by -CleanResults:$false would be counted as this run's result. The tolerance
    # absorbs filesystem timestamp granularity.
    $runStart = (Get-Date).AddSeconds(-2)
    & $gradlew @gradleArgs 2>&1 | Tee-Object -FilePath $runConsole | Out-Host
    $rc = $LASTEXITCODE

    # ------------------------------------------------------------ verdict + metrics (host-side)
    $xmlFiles = @(Get-RunResultXml -Since $runStart)
    $verdict = "NO_RESULT"
    $classes = 0
    $setupMs = ""; $perTap = ""; $totalMs = ""; $medianMs = ""

    if ($xmlFiles.Count -gt 0) {
        # AGGREGATED over every result file THIS run wrote, because the desktop tier writes one XML
        # per CLASS rather than Android's single whole-run summary log. Reading just the newest file
        # reported one class's counts as the whole run's verdict: measured, a 414-test tier reported
        # "OK (8 tests)" - a green verdict for a run nobody had counted.
        $tests = 0; $failures = 0; $errors = 0
        $times = @()
        $probe = ""
        foreach ($xmlFile in $xmlFiles) {
            [xml]$doc = Get-Content -Path $xmlFile.FullName
            $suite = $doc.testsuite
            $classes++
            $tests += [int]$suite.tests
            $failures += [int]$suite.failures
            $errors += [int]$suite.errors
            $times += @($suite.testcase | ForEach-Object { [double]$_.time })
            $probe += (($suite.'system-out' -join "`n") + "`n")
        }

        # Same wording the Android runner reports from test-results.log, so the CSV rows match.
        if (($failures + $errors) -eq 0) {
            $verdict = "OK ($tests tests)"
        } else {
            $verdict = "Tests run: $tests, Failures: $failures, Errors: $errors"
        }

        # The probe mechanism, honoured identically: a tag-named stdout line carrying key=value.
        # Gradle captures test stdout into the XML, so system-out is this tier's logcat.
        $setupMs  = Get-MetricValue $probe "setup_ms"
        $perTap   = Get-MetricValue $probe "per_tap_ms"
        $totalMs  = Get-MetricValue $probe "total_ms"
        $medianMs = Get-MetricValue $probe "median_ms"

        # No probe? Fill the timing columns from the run's own per-test timings, which is the desktop
        # tier's native source. setup_ms / per_tap_ms stay empty - they have no desktop counterpart.
        if (-not $totalMs) {
            if ($times.Count -gt 0) {
                $totalMs = [int][math]::Round((($times | Measure-Object -Sum).Sum) * 1000)
                $medianMs = [int][math]::Round((Get-Median -Values $times) * 1000)
            }
        }
    }

    # ------------------------------------------------------------ persist this run
    $runDir = Join-Path $OutDir "run$i-results"
    if (Test-Path $runDir) { Remove-Item -Path $runDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    if (Test-Path $resultsRoot) { Copy-Item -Path (Join-Path $resultsRoot "*") -Destination $runDir -Recurse -Force }
    if (Test-Path $reportRoot)  { Copy-Item -Path (Join-Path $reportRoot "*")  -Destination $runDir -Recurse -Force }
    Copy-Item -Path $runConsole -Destination $runDir -Force

    # The desktop's explicit verdict file. On Android the verdict lives inside the copied
    # test-results.log; here it is also written on its own so a run's outcome is readable without
    # opening the XML or the CSV, and it names how much was counted so a thin run is visible.
    $sourceDesc = if ($xmlFiles.Count -gt 0) { "$($xmlFiles.Count) result file(s) written by this run" } else { "no result file" }
    $verdictFile = Join-Path $runDir "verdict.txt"
    @(
        "run: $i"
        "rc: $rc"
        "verdict: $verdict"
        "test class: $(if ($TestClass) { $TestClass } else { $testTask })"
        "classes: $classes"
        "source: $sourceDesc"
        "setup_ms: $setupMs"
        "per_tap_ms: $perTap"
        "total_ms: $totalMs"
        "median_ms: $medianMs"
    ) | Set-Content -Path $verdictFile

    # A run is green only when Gradle succeeded AND the verdict parsed as OK - the verdict alone once
    # called a Gradle failure green when an OK line existed, so both must hold.
    $green = ($rc -eq 0) -and ($verdict -like "OK*")
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

$timed = @($summary | Where-Object { $_.Green } | Where-Object { $_.Total })
if ($timed.Count -gt 0) {
    $totals  = $timed | ForEach-Object { [double]$_.Total }
    $medians = $timed | Where-Object { $_.Median } | ForEach-Object { [double]$_.Median }
    $avgT = ($totals | Measure-Object -Average).Average
    Write-Host ("  avg total over green runs : {0:N0} ms  (min {1:N0}, max {2:N0})" -f $avgT, ($totals | Measure-Object -Minimum).Minimum, ($totals | Measure-Object -Maximum).Maximum)
    if ($medians.Count -gt 0) {
        Write-Host ("  avg per-test median       : {0:N0} ms" -f (($medians | Measure-Object -Average).Average))
    }
}
Write-Host "  csv       : $csvPath"
Write-Host "========================================="

# Automation contract: exit 0 only when every run in the campaign was green. A red run - a test
# failure or a Gradle failure - must surface to callers as a non-zero exit, otherwise a failed
# campaign reads as success from the exit code alone.
if ($redRuns.Count -gt 0) {
    Write-Host "  exit      : 1 ($($redRuns.Count) red run(s) failed the campaign)" -ForegroundColor Red
    exit 1
}
Write-Host "  exit      : 0 (all runs green)" -ForegroundColor Green
exit 0
