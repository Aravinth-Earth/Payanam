# Performance Capture Stop Script
# Stops active capture, collects artifacts, and writes summary reports.

param(
    [string]$OutputRoot = "output/perf",
    [string]$ActiveSessionFile = ""
)

$ErrorActionPreference = "Stop"

function Write-LogWithTime {
    param([string]$Message, [string]$Color = "White")
    $timestamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$timestamp] $Message" -ForegroundColor $Color
}

function Get-RegexValue {
    param(
        [string]$Text,
        [string]$Pattern,
        [string]$DefaultValue = ""
    )
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($match.Success -and $match.Groups.Count -gt 1) {
        return $match.Groups[1].Value.Trim()
    }
    return $DefaultValue
}

function Get-PerfMarkers {
    param([string]$LogText)

    $events = @()
    $queries = @()
    $recompositions = @()

    $eventMatches = [regex]::Matches($LogText, "PERF_BASELINE_EVENT\s+([^\r\n]+)")
    foreach ($match in $eventMatches) {
        $payload = $match.Groups[1].Value
        $screen = Get-RegexValue -Text $payload -Pattern "screen=([^\s]+)" -DefaultValue ""
        $event = Get-RegexValue -Text $payload -Pattern "event=([^\s]+)" -DefaultValue ""
        $tMs = Get-RegexValue -Text $payload -Pattern "tMs=(\d+)" -DefaultValue "0"
        if (-not [string]::IsNullOrWhiteSpace($screen) -and -not [string]::IsNullOrWhiteSpace($event)) {
            $events += [PSCustomObject]@{
                screen = $screen
                event = $event
                tMs = [long]$tMs
            }
        }
    }

    $queryMatches = [regex]::Matches($LogText, "PERF_BASELINE_QUERY\s+([^\r\n]+)")
    foreach ($match in $queryMatches) {
        $payload = $match.Groups[1].Value
        $screen = Get-RegexValue -Text $payload -Pattern "screen=([^\s]+)" -DefaultValue ""
        $source = Get-RegexValue -Text $payload -Pattern "source=([^\s]+)" -DefaultValue ""
        $delta = Get-RegexValue -Text $payload -Pattern "delta=(\d+)" -DefaultValue "1"
        $total = Get-RegexValue -Text $payload -Pattern "total=(\d+)" -DefaultValue "0"
        if (-not [string]::IsNullOrWhiteSpace($screen) -and -not [string]::IsNullOrWhiteSpace($source)) {
            $queries += [PSCustomObject]@{
                screen = $screen
                source = $source
                delta = [int]$delta
                total = [int]$total
            }
        }
    }

    $recompositionMatches = [regex]::Matches($LogText, "PERF_BASELINE_RECOMPOSITION\s+([^\r\n]+)")
    foreach ($match in $recompositionMatches) {
        $payload = $match.Groups[1].Value
        $screen = Get-RegexValue -Text $payload -Pattern "screen=([^\s]+)" -DefaultValue ""
        $section = Get-RegexValue -Text $payload -Pattern "section=([^\s]+)" -DefaultValue ""
        $total = Get-RegexValue -Text $payload -Pattern "total=(\d+)" -DefaultValue "0"
        if (-not [string]::IsNullOrWhiteSpace($screen) -and -not [string]::IsNullOrWhiteSpace($section)) {
            $recompositions += [PSCustomObject]@{
                screen = $screen
                section = $section
                total = [int]$total
            }
        }
    }

    return [PSCustomObject]@{
        events = $events
        queries = $queries
        recompositions = $recompositions
    }
}

if ([string]::IsNullOrWhiteSpace($ActiveSessionFile)) {
    $ActiveSessionFile = Join-Path $OutputRoot "active-session.json"
}

if (-not (Test-Path $ActiveSessionFile)) {
    throw "No active session file found at '$ActiveSessionFile'. Start capture first."
}

$session = Get-Content -Path $ActiveSessionFile -Raw | ConvertFrom-Json
$sessionDir = [string]$session.outputDir
$packageName = [string]$session.packageName
$deviceTracePath = [string]$session.deviceTracePath
$perfettoPid = $session.perfettoPid

if (-not (Test-Path $sessionDir)) {
    New-Item -ItemType Directory -Force -Path $sessionDir | Out-Null
}

Write-LogWithTime "Stopping active capture for session $($session.sessionId)..." "Cyan"
if ($null -ne $perfettoPid -and "$perfettoPid" -match "^\d+$") {
    adb shell "kill -INT $perfettoPid" | Out-Null
}
Start-Sleep -Seconds 2

# Fallback in case pid is stale but perfetto is still running.
$runningPerfetto = adb shell pidof perfetto 2>&1
if ($runningPerfetto -match "\d+") {
    adb shell "pkill -INT perfetto" | Out-Null
}

Start-Sleep -Seconds 1

$gfxInfoPath = Join-Path $sessionDir "gfxinfo.txt"
$gfxFrameStatsPath = Join-Path $sessionDir "gfxinfo-framestats.txt"
$logcatPath = Join-Path $sessionDir "logcat.txt"
$tracePath = Join-Path $sessionDir "trace.perfetto-trace"

Write-LogWithTime "Collecting gfxinfo..." "Cyan"
adb shell dumpsys gfxinfo $packageName 2>&1 | Set-Content -Path $gfxInfoPath -Encoding UTF8
adb shell dumpsys gfxinfo $packageName framestats 2>&1 | Set-Content -Path $gfxFrameStatsPath -Encoding UTF8

Write-LogWithTime "Collecting logcat..." "Cyan"
adb logcat -d -t 12000 2>&1 | Set-Content -Path $logcatPath -Encoding UTF8

Write-LogWithTime "Pulling Perfetto trace..." "Cyan"
$pullResult = adb pull $deviceTracePath $tracePath 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-LogWithTime "Trace pull failed: $pullResult" "Yellow"
} else {
    adb shell "rm -f $deviceTracePath" | Out-Null
}

$gfxText = Get-Content -Path $gfxInfoPath -Raw
$logcatText = Get-Content -Path $logcatPath -Raw
$perfMarkers = Get-PerfMarkers -LogText $logcatText

$eventsByKey = @{}
foreach ($eventItem in $perfMarkers.events) {
    $key = "$($eventItem.screen)::$($eventItem.event)"
    if (-not $eventsByKey.ContainsKey($key)) {
        $eventsByKey[$key] = @()
    }
    $eventsByKey[$key] += $eventItem
}

function Get-FirstEventTms {
    param([string]$Screen, [string]$Event)
    $key = "$($Screen)::$($Event)"
    if (-not $eventsByKey.ContainsKey($key)) { return 0L }
    $items = $eventsByKey[$key]
    if ($items.Count -eq 0) { return 0L }
    return [long]($items | Sort-Object tMs | Select-Object -First 1).tMs
}

$lensEnterMs = Get-FirstEventTms -Screen "lens" -Event "screen_enter"
$lensFirstContentMs = Get-FirstEventTms -Screen "lens" -Event "first_content"
$tasksEnterMs = Get-FirstEventTms -Screen "tasks" -Event "screen_enter"
$tasksFirstContentMs = Get-FirstEventTms -Screen "tasks" -Event "first_content"
$habitsFirstContentMs = Get-FirstEventTms -Screen "habits" -Event "first_content"

$lensStartupToFirstContentMs = if ($lensEnterMs -gt 0 -and $lensFirstContentMs -ge $lensEnterMs) { $lensFirstContentMs - $lensEnterMs } else { -1 }
$tasksStartupToFirstContentMs = if ($tasksEnterMs -gt 0 -and $tasksFirstContentMs -ge $tasksEnterMs) { $tasksFirstContentMs - $tasksEnterMs } else { -1 }
$habitsStartupToFirstContentMs = if ($tasksEnterMs -gt 0 -and $habitsFirstContentMs -ge $tasksEnterMs) { $habitsFirstContentMs - $tasksEnterMs } else { -1 }

$queryBudgetByScreen = @{}
foreach ($query in $perfMarkers.queries) {
    if (-not $queryBudgetByScreen.ContainsKey($query.screen)) {
        $queryBudgetByScreen[$query.screen] = 0
    }
    $queryBudgetByScreen[$query.screen] += $query.delta
}
$recompositionBudgetByScreen = @{}
foreach ($recomposition in $perfMarkers.recompositions) {
    if (-not $recompositionBudgetByScreen.ContainsKey($recomposition.screen)) {
        $recompositionBudgetByScreen[$recomposition.screen] = 0
    }
    if ($recomposition.total -gt $recompositionBudgetByScreen[$recomposition.screen]) {
        $recompositionBudgetByScreen[$recomposition.screen] = $recomposition.total
    }
}
$scrollSamplesLens = ($perfMarkers.events | Where-Object { $_.screen -eq "lens" -and $_.event -eq "scroll_sample" }).Count
$scrollSamplesTasks = ($perfMarkers.events | Where-Object { $_.screen -eq "tasks" -and $_.event -eq "scroll_sample" }).Count
$scrollSamplesHabits = ($perfMarkers.events | Where-Object { $_.screen -eq "habits" -and $_.event -eq "scroll_sample" }).Count
$firstChartDrawCount = ($perfMarkers.events | Where-Object { $_.screen -eq "lens" -and $_.event -eq "first_chart_draw" }).Count

$summary = [ordered]@{
    sessionId = [string]$session.sessionId
    packageName = $packageName
    startedAtLocal = [string]$session.startedAtLocal
    stoppedAtLocal = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ssK")
    totalFramesRendered = Get-RegexValue -Text $gfxText -Pattern "Total frames rendered:\s*(\d+)" -DefaultValue "0"
    jankyFrames = Get-RegexValue -Text $gfxText -Pattern "Janky frames:\s*(\d+)" -DefaultValue "0"
    jankyPercent = Get-RegexValue -Text $gfxText -Pattern "Janky frames:\s*\d+\s*\(([\d\.]+%)\)" -DefaultValue "n/a"
    frame50pMs = Get-RegexValue -Text $gfxText -Pattern "50th percentile:\s*(\d+)ms" -DefaultValue "n/a"
    frame90pMs = Get-RegexValue -Text $gfxText -Pattern "90th percentile:\s*(\d+)ms" -DefaultValue "n/a"
    frame95pMs = Get-RegexValue -Text $gfxText -Pattern "95th percentile:\s*(\d+)ms" -DefaultValue "n/a"
    frame99pMs = Get-RegexValue -Text $gfxText -Pattern "99th percentile:\s*(\d+)ms" -DefaultValue "n/a"
    missedVsync = Get-RegexValue -Text $gfxText -Pattern "Number Missed Vsync:\s*(\d+)" -DefaultValue "0"
    highInputLatency = Get-RegexValue -Text $gfxText -Pattern "Number High input latency:\s*(\d+)" -DefaultValue "0"
    slowUiThread = Get-RegexValue -Text $gfxText -Pattern "Number Slow UI thread:\s*(\d+)" -DefaultValue "0"
    slowBitmapUploads = Get-RegexValue -Text $gfxText -Pattern "Number Slow bitmap uploads:\s*(\d+)" -DefaultValue "0"
    slowIssueDraw = Get-RegexValue -Text $gfxText -Pattern "Number Slow issue draw commands:\s*(\d+)" -DefaultValue "0"
    lensStartupToFirstContentMs = $lensStartupToFirstContentMs
    tasksStartupToFirstContentMs = $tasksStartupToFirstContentMs
    habitsStartupToFirstContentMs = $habitsStartupToFirstContentMs
    lensFirstChartDrawEvents = $firstChartDrawCount
    lensScrollSamples = $scrollSamplesLens
    tasksScrollSamples = $scrollSamplesTasks
    habitsScrollSamples = $scrollSamplesHabits
    dbQueriesLens = if ($queryBudgetByScreen.ContainsKey("lens")) { $queryBudgetByScreen["lens"] } else { 0 }
    dbQueriesTasks = if ($queryBudgetByScreen.ContainsKey("tasks")) { $queryBudgetByScreen["tasks"] } else { 0 }
    dbQueriesHabits = if ($queryBudgetByScreen.ContainsKey("habits")) { $queryBudgetByScreen["habits"] } else { 0 }
    recompositionsLens = if ($recompositionBudgetByScreen.ContainsKey("lens")) { $recompositionBudgetByScreen["lens"] } else { 0 }
    recompositionsTasks = if ($recompositionBudgetByScreen.ContainsKey("tasks")) { $recompositionBudgetByScreen["tasks"] } else { 0 }
    recompositionsHabits = if ($recompositionBudgetByScreen.ContainsKey("habits")) { $recompositionBudgetByScreen["habits"] } else { 0 }
    targetJankyPercent = "<=15%"
    targetLensFirstContentMs = "<=2500"
    targetTasksFirstContentMs = "<=2000"
    targetHabitsFirstContentMs = "<=2000"
    traceFile = $tracePath
    traceExists = (Test-Path $tracePath)
    gfxInfoFile = $gfxInfoPath
    gfxFrameStatsFile = $gfxFrameStatsPath
    logcatFile = $logcatPath
}

$summaryJsonPath = Join-Path $sessionDir "summary.json"
$summaryMdPath = Join-Path $sessionDir "summary.md"
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryJsonPath -Encoding UTF8

$summaryMd = @(
    "# Perf Summary",
    "",
    "- Session: $($summary.sessionId)",
    "- Package: $($summary.packageName)",
    "- Started: $($summary.startedAtLocal)",
    "- Stopped: $($summary.stoppedAtLocal)",
    "",
    "## Frame Stats",
    "- Total frames: $($summary.totalFramesRendered)",
    "- Janky frames: $($summary.jankyFrames) ($($summary.jankyPercent))",
    "- P50/P90/P95/P99 (ms): $($summary.frame50pMs) / $($summary.frame90pMs) / $($summary.frame95pMs) / $($summary.frame99pMs)",
    "- Missed vsync: $($summary.missedVsync)",
    "- High input latency: $($summary.highInputLatency)",
    "- Slow UI thread: $($summary.slowUiThread)",
    "- Slow bitmap uploads: $($summary.slowBitmapUploads)",
    "- Slow issue draw commands: $($summary.slowIssueDraw)",
    "",
    "## Baseline Signals",
    "- Lens startup -> first content (ms): $($summary.lensStartupToFirstContentMs)",
    "- Tasks startup -> first content (ms): $($summary.tasksStartupToFirstContentMs)",
    "- Habits startup -> first content (ms): $($summary.habitsStartupToFirstContentMs)",
    "- Lens first chart draw events: $($summary.lensFirstChartDrawEvents)",
    "- Scroll samples (lens/tasks/habits): $($summary.lensScrollSamples) / $($summary.tasksScrollSamples) / $($summary.habitsScrollSamples)",
    "- DB query counters (lens/tasks/habits): $($summary.dbQueriesLens) / $($summary.dbQueriesTasks) / $($summary.dbQueriesHabits)",
    "- Recomposition counters (lens/tasks/habits): $($summary.recompositionsLens) / $($summary.recompositionsTasks) / $($summary.recompositionsHabits)",
    "",
    "## Initial Targets (Informational)",
    "- Janky frames target: $($summary.targetJankyPercent)",
    "- Lens first content target (ms): $($summary.targetLensFirstContentMs)",
    "- Tasks first content target (ms): $($summary.targetTasksFirstContentMs)",
    "- Habits first content target (ms): $($summary.targetHabitsFirstContentMs)",
    "",
    "## Artifacts",
    "- Trace: $($summary.traceFile)",
    "- gfxinfo: $($summary.gfxInfoFile)",
    "- framestats: $($summary.gfxFrameStatsFile)",
    "- logcat: $($summary.logcatFile)"
)
$summaryMd -join [Environment]::NewLine | Set-Content -Path $summaryMdPath -Encoding UTF8

Remove-Item -Path $ActiveSessionFile -Force

Write-LogWithTime "Capture stopped and artifacts collected." "Green"
Write-LogWithTime "Summary: $summaryMdPath" "Green"
Write-LogWithTime "JSON:    $summaryJsonPath" "Green"
