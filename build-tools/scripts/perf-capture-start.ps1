# Performance Capture Start Script
# Starts a device-side Perfetto trace and prepares a local session folder.

param(
    [string]$PackageName = "",
    [int]$DurationMinutes = 20,
    [string]$OutputRoot = "output/perf"
)

$ErrorActionPreference = "Stop"

function Write-LogWithTime {
    param([string]$Message, [string]$Color = "White")
    $timestamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$timestamp] $Message" -ForegroundColor $Color
}

function Assert-AdbAvailable {
    try {
        $null = adb version
    } catch {
        throw "adb is not available on PATH."
    }
}

function Assert-DeviceConnected {
    $state = adb get-state 2>&1
    if ($LASTEXITCODE -ne 0 -or ($state -notmatch "device")) {
        throw "No Android device connected (adb get-state: $state)."
    }
}

function Resolve-PackageName {
    param([string]$InputPackage)

    if (-not [string]::IsNullOrWhiteSpace($InputPackage)) {
        return $InputPackage.Trim()
    }

    $candidates = @("io.payanam.debug", "io.payanam")
    foreach ($candidate in $candidates) {
        $match = adb shell pm list packages $candidate 2>&1
        if ($match -match $candidate) {
            return $candidate
        }
    }

    throw "Could not auto-detect package. Pass -PackageName io.payanam.debug or io.payanam."
}

function Assert-PackageInstalled {
    param([string]$Package)
    $match = adb shell pm list packages $Package 2>&1
    if ($LASTEXITCODE -ne 0 -or ($match -notmatch $Package)) {
        throw "Package '$Package' is not installed on device."
    }
}

Assert-AdbAvailable
Assert-DeviceConnected

$resolvedPackage = Resolve-PackageName -InputPackage $PackageName
Assert-PackageInstalled -Package $resolvedPackage

if ($DurationMinutes -lt 1 -or $DurationMinutes -gt 120) {
    throw "DurationMinutes must be between 1 and 120."
}

$sessionId = Get-Date -Format "yyyyMMdd_HHmmss"
$sessionDir = Join-Path $OutputRoot "session_$sessionId"
New-Item -ItemType Directory -Force -Path $sessionDir | Out-Null

$activeSessionPath = Join-Path $OutputRoot "active-session.json"
$deviceTracePath = "/data/misc/perfetto-traces/payanam_perf_$sessionId.perfetto-trace"
$durationMs = $DurationMinutes * 60 * 1000

Write-LogWithTime "Resetting frame stats for $resolvedPackage..." "Cyan"
adb shell dumpsys gfxinfo $resolvedPackage reset | Out-Null

Write-LogWithTime "Force-stopping $resolvedPackage to reset in-memory telemetry counters..." "Cyan"
adb shell am force-stop $resolvedPackage | Out-Null

Write-LogWithTime "Clearing logcat ring buffer..." "Cyan"
adb logcat -c | Out-Null

# Note: keep config compact and stable for repeatable comparisons.
$perfettoConfig = @"
buffers: {
  size_kb: 32768
  fill_policy: RING_BUFFER
}
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "binder_driver"
      atrace_categories: "freq"
      atrace_categories: "idle"
      atrace_categories: "sched"
      atrace_categories: "dalvik"
      atrace_apps: "$resolvedPackage"
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
    }
  }
}
data_sources: {
  config {
    name: "android.surfaceflinger.frametimeline"
  }
}
duration_ms: $durationMs
write_into_file: true
"@

Write-LogWithTime "Starting Perfetto capture ($DurationMinutes min)..." "Cyan"
$startResult = $perfettoConfig | adb shell "perfetto -c - --txt -o $deviceTracePath --background" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start Perfetto capture: $startResult"
}

$perfettoPid = $null
if ($startResult -match "(\d+)") {
    $perfettoPid = [int]$Matches[1]
}

$metadata = [ordered]@{
    sessionId = $sessionId
    packageName = $resolvedPackage
    startedAtLocal = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ssK")
    durationMinutes = $DurationMinutes
    outputDir = $sessionDir
    activeSessionFile = $activeSessionPath
    deviceTracePath = $deviceTracePath
    perfettoPid = $perfettoPid
}

$metadata | ConvertTo-Json -Depth 8 | Set-Content -Path $activeSessionPath -Encoding UTF8
$metadata | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $sessionDir "session-metadata.json") -Encoding UTF8

Write-LogWithTime "Capture started." "Green"
Write-LogWithTime "Session: $sessionId" "Green"
Write-LogWithTime "Package: $resolvedPackage" "Green"
Write-LogWithTime "Baseline run contract:" "Yellow"
Write-LogWithTime "  1) Launch app and execute Lens -> Tasks -> Habits flows (scroll each surface)." "Yellow"
Write-LogWithTime "  2) Keep interactions deterministic for comparable runs." "Yellow"
Write-LogWithTime "Now stop capture and summarize by running:" "Yellow"
Write-LogWithTime "  .\build-tools\scripts\perf-capture-stop.ps1" "Yellow"
