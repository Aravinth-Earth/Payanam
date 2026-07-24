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

# Get latest log file
Write-LogWithTime "" "White"
Write-LogWithTime "Finding latest log file..." "Cyan"
$logPath = "/sdcard/Documents/payanam/logs"

# Check if log directory exists
$dirCheck = adb shell "test -d $logPath && echo exists" 2>&1
if ($dirCheck -notmatch "exists") {
    Write-LogWithTime "❌ Log directory not found on device!" "Red"
    Write-LogWithTime "Path: $logPath" "Yellow"
    Write-LogWithTime "Have you launched the app at least once?" "Yellow"
    exit 1
}

# Get latest log file
$latestLog = adb shell "ls -t $logPath/*.log 2>/dev/null | head -1" 2>&1
if (-not $latestLog -or $latestLog.Trim() -eq "") {
    Write-LogWithTime "❌ No log files found on device!" "Red"
    Write-LogWithTime "Directory exists but is empty: $logPath" "Yellow"
    exit 1
}

$latestLog = $latestLog.Trim()
$fileName = Split-Path $latestLog -Leaf

Write-LogWithTime "  Latest log: $fileName" "Green"

# Get file info
$fileSize = adb shell "stat -c%s '$latestLog'" 2>&1
$fileSize = [int]($fileSize.Trim())
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

adb pull $latestLog $localPath 2>&1 | Out-Null

if ($LASTEXITCODE -eq 0 -and (Test-Path $localPath)) {
    Write-LogWithTime "✅ Log file pulled successfully!" "Green"
    Write-LogWithTime "" "White"
    Write-LogWithTime "========================================" "Green"
    Write-LogWithTime "  File: $localPath" "Cyan"
    Write-LogWithTime "  Size: $fileSizeMB MB" "Cyan"
    Write-LogWithTime "========================================" "Green"
} else {
    Write-LogWithTime "❌ Failed to pull log file!" "Red"
    exit 1
}

