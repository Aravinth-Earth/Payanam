# SPDX-FileCopyrightText: 2026 Aravinth-Earth
# SPDX-License-Identifier: AGPL-3.0-or-later
# Pull Latest Device Log Script
# Retrieves the most recent payanam log file from connected Android device

param(
    [string]$OutputDir = "output"
)

$ErrorActionPreference = "Stop"

function Write-LogWithTime {
    param([string]$Message, [string]$Color = "White")
    $timestamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$timestamp] $Message" -ForegroundColor $Color
}

# Copies an adb subcommand's stdout into a file unchanged. A redirected Process stream preserves the
# bytes on every OS; PowerShell's `>` re-encodes them and cmd.exe exists only on Windows.
function Save-AdbExecOutFile {
    param(
        [string]$AdbPath,
        [string]$AdbArguments,
        [string]$LocalPath
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $AdbPath
    $psi.Arguments = $AdbArguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.BaseStream
    $file = [System.IO.File]::Create($LocalPath)
    try {
        $stdout.CopyTo($file)
    } finally {
        $file.Dispose()
        $stdout.Dispose()
    }
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    $process.Dispose()
    return $exitCode
}

Write-LogWithTime "========================================" "Cyan"
Write-LogWithTime "  Pull Device Log" "Cyan"
Write-LogWithTime "========================================" "Cyan"

# Check device connection
Write-LogWithTime "Checking device connection..." "Cyan"
$deviceCheck = adb devices 2>&1 | Select-String "device$"
if (-not $deviceCheck) {
    Write-LogWithTime "❌ No Android device connected!" "Red"
    Write-LogWithTime "Connect device and enable USB debugging" "Yellow"
    exit 1
}
Write-LogWithTime "✅ Device connected" "Green"

# The installed package decides both the export folder and the app-private log dir.
# The debug package is checked first: a dev device carries it, and run-as only works for it.
Write-LogWithTime "" "White"
Write-LogWithTime "Detecting installed package..." "Cyan"
$installedPackages = adb shell pm list packages 2>&1
$packageName = if ($installedPackages -match "package:io\.payanam\.debug") {
    "io.payanam.debug"
} elseif ($installedPackages -match "package:io\.payanam") {
    "io.payanam"
} else {
    $null
}
if (-not $packageName) {
    Write-LogWithTime "❌ Payanam is not installed on the device!" "Red"
    Write-LogWithTime "Install it first: .\build-tools\scripts\build-android.ps1 -InstallApk upgrade" "Yellow"
    exit 1
}
Write-LogWithTime "✅ Package: $packageName" "Green"

# UnifiedLogger writes live sessions to files/logs and exports to Documents/Payanam[-debug]/exported-logs.
# Session file names are payanam_<os>_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.log.
$exportDir = if ($packageName -eq "io.payanam.debug") {
    "/sdcard/Documents/Payanam-debug/exported-logs"
} else {
    "/sdcard/Documents/Payanam/exported-logs"
}
$privateLogDir = "/data/data/$packageName/files/logs"
$logGlob = "payanam_*.log"
# A real log leaf is payanam_<os>_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.log: one token,
# no spaces or colons, so tool error text can never pass for a file name.
$logLeafPattern = '^payanam_[A-Za-z0-9._-]+\.log$'

Write-LogWithTime "" "White"
Write-LogWithTime "Finding latest log file..." "Cyan"

# The glob is expanded inside run-as, as the app uid: the shell user cannot read files/logs.
# run-as refuses a non-debuggable package, which is the release install case, so empty is not an error.
$privateLeaf = ""
$privateListing = [string](adb shell "run-as $packageName sh -c 'ls -t files/logs/$logGlob 2>/dev/null | head -1'" 2>&1)
$listingLeaf = ($privateListing.Trim() -split '/')[-1]
if ($listingLeaf -match $logLeafPattern) {
    $privateLeaf = $listingLeaf
}
$privateLog = if ($privateLeaf) { "$privateLogDir/$privateLeaf" } else { "" }

# Fallback: the exported snapshot, pullable on every build type.
$exportedLog = [string](adb shell "ls -t $exportDir/$logGlob 2>/dev/null | head -1" 2>&1)
$exportedLog = $exportedLog.Trim()
if ($exportedLog -and -not ((Split-Path $exportedLog -Leaf) -match $logLeafPattern)) {
    $exportedLog = ""
}

$latestLog = if ($privateLog) { $privateLog } else { $exportedLog }
$logSource = if ($privateLog) { "live session" } elseif ($exportedLog) { "exported" } else { "" }

if (-not $latestLog) {
    Write-LogWithTime "❌ No Payanam log found on the device!" "Red"
    Write-LogWithTime "Looked for $logGlob in:" "Yellow"
    Write-LogWithTime "  $privateLogDir (live session; needs a debuggable build)" "Yellow"
    Write-LogWithTime "  $exportDir (Settings > Data Management > Export logs)" "Yellow"
    Write-LogWithTime "Launch the app at least once, or export the logs from Settings." "Yellow"
    exit 1
}

$fileName = Split-Path $latestLog -Leaf

Write-LogWithTime "  Latest log: $fileName" "Green"
Write-LogWithTime "  Source: $logSource" "Cyan"

# Get file info
$fileSizeRaw = if ($privateLog) {
    adb shell "run-as $packageName stat -c%s '$latestLog'" 2>&1
} else {
    adb shell "stat -c%s '$latestLog'" 2>&1
}
$fileSize = 0
if ("$fileSizeRaw".Trim() -match '^\d+$') { $fileSize = [int]$Matches[0] }
$fileSizeMB = [math]::Round($fileSize / 1MB, 2)

Write-LogWithTime "  Size: $fileSizeMB MB" "Cyan"

# Create output directory
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

# Pull the file
Write-LogWithTime "" "White"
Write-LogWithTime "Pulling log file..." "Cyan"
$localPath = Join-Path $OutputDir $fileName

$pullExitCode = 1
if ($privateLog) {
    # An app-private file cannot be adb pull-ed, so its stdout is streamed into the local file.
    $adbPath = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $adbPath) { $adbPath = "adb" }
    $pullExitCode = Save-AdbExecOutFile -AdbPath $adbPath -AdbArguments "exec-out run-as $packageName cat $latestLog" -LocalPath $localPath
} else {
    adb pull $latestLog $localPath 2>&1 | Out-Null
    $pullExitCode = $LASTEXITCODE
}

$pulledLength = if (Test-Path $localPath) { (Get-Item $localPath).Length } else { 0 }

if ($pullExitCode -eq 0 -and $pulledLength -gt 0) {
    $localSizeMB = [math]::Round($pulledLength / 1MB, 2)
    Write-LogWithTime "✅ Log file pulled successfully!" "Green"
    Write-LogWithTime "" "White"
    Write-LogWithTime "========================================" "Green"
    Write-LogWithTime "  File: $localPath" "Cyan"
    Write-LogWithTime "  Size: $localSizeMB MB" "Cyan"
    Write-LogWithTime "========================================" "Green"
} else {
    if (Test-Path $localPath) { Remove-Item $localPath -Force }
    Write-LogWithTime "❌ Failed to pull log file! (exit code $pullExitCode)" "Red"
    exit 1
}
