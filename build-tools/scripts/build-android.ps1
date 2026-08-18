# Android Build Script for Kotlin + Compose
# Format: Payanam_Android_buildNumber_dateTimeStamp
# Migrated from Capacitor/TypeScript to pure Kotlin

param(
    [switch]$Clean,
    [switch]$CleanInstall,
    [switch]$SkipTests,
    [switch]$SkipGuardrails,
    [switch]$RunMaestro,
    [switch]$SkipMaestro,
    [switch]$KeepDaemons,
    [switch]$Release,
    [switch]$SizeOptimized,
    [switch]$Publish,
    [switch]$Universal,
    [ValidateSet("auto", "quick", "normal", "full")] [string]$Profile = "auto",
    [string]$OutputDir = "output/apks"
)

$ErrorActionPreference = "Stop"

$MaxApkArtifacts = 2
$MaxSmokeArtifacts = 2
$MaxAndroidTestFailureArtifacts = 2

# Ensure we're in the project root
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
Set-Location $projectRoot

function Write-LogWithTime
{
    param([string]$Message, [string]$Color = "White")
    $timestamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$timestamp] $Message" -ForegroundColor $Color
}

function Get-DateTimeStamp
{
    return (Get-Date -Format "yyyyMMdd_HHmmss")
}

function Get-LocalDateTime
{
    $now = Get-Date
    $offsetMinutes = [System.TimeZoneInfo]::Local.GetUtcOffset($now).TotalMinutes
    $offsetHours = [int][math]::Floor([math]::Abs($offsetMinutes) / 60)
    $offsetMins = [int]([math]::Abs($offsetMinutes) % 60)
    $offsetSign = if ($offsetMinutes -ge 0)
    { "+"
    } else
    { "-"
    }
    $offsetString = "$offsetSign$($offsetHours.ToString('D2')):$($offsetMins.ToString('D2'))"
    return $now.ToString("yyyy-MM-ddTHH:mm:ss") + $offsetString
}

function Format-CanonicalJson
{
    param(
        [Parameter(Mandatory = $true)]
        [string]$Json,
        [int]$IndentSize = 2
    )

    $builder = [System.Text.StringBuilder]::new()
    $indentLevel = 0
    $inString = $false
    $isEscaped = $false
    $indentUnit = " " * $IndentSize

    foreach ($char in $Json.ToCharArray())
    {
        if ($inString)
        {
            [void]$builder.Append($char)
            if ($isEscaped)
            {
                $isEscaped = $false
            } elseif ($char -eq '\')
            {
                $isEscaped = $true
            } elseif ($char -eq '"')
            {
                $inString = $false
            }
            continue
        }

        switch ($char)
        {
            '"'
            {
                $inString = $true
                [void]$builder.Append($char)
            }
            '{'
            {
                [void]$builder.Append($char)
                $indentLevel++
                [void]$builder.Append("`n")
                [void]$builder.Append($indentUnit * $indentLevel)
            }
            '['
            {
                [void]$builder.Append($char)
                $indentLevel++
                [void]$builder.Append("`n")
                [void]$builder.Append($indentUnit * $indentLevel)
            }
            '}'
            {
                $indentLevel--
                [void]$builder.Append("`n")
                [void]$builder.Append($indentUnit * $indentLevel)
                [void]$builder.Append($char)
            }
            ']'
            {
                $indentLevel--
                [void]$builder.Append("`n")
                [void]$builder.Append($indentUnit * $indentLevel)
                [void]$builder.Append($char)
            }
            ','
            {
                [void]$builder.Append($char)
                [void]$builder.Append("`n")
                [void]$builder.Append($indentUnit * $indentLevel)
            }
            ':'
            {
                [void]$builder.Append(": ")
            }
            default
            {
                [void]$builder.Append($char)
            }
        }
    }

    return $builder.ToString()
}

function Write-CanonicalJsonFile
{
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [object]$InputObject
    )

    $resolvedPath = if ([System.IO.Path]::IsPathRooted($Path))
    {
        $Path
    } else
    {
        Join-Path $projectRoot $Path
    }
    $compressedJson = $InputObject | ConvertTo-Json -Depth 10 -Compress
    $formattedJson = Format-CanonicalJson -Json $compressedJson
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($resolvedPath, "$formattedJson`n", $utf8NoBom)
}

function ConvertTo-DateTimeOffsetOrNull
{
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text))
    {
        return $null
    }
    try
    {
        return [DateTimeOffset]::Parse($Text, [System.Globalization.CultureInfo]::InvariantCulture)
    } catch
    {
        return $null
    }
}

function Get-HoursSinceTimestamp
{
    param([string]$IsoDateTime)
    $parsed = ConvertTo-DateTimeOffsetOrNull -Text $IsoDateTime
    if ($null -eq $parsed)
    {
        return [double]::PositiveInfinity
    }
    $now = [DateTimeOffset]::Now
    return [math]::Max(0.0, ($now - $parsed).TotalHours)
}

function Resolve-BuildProfile
{
    param(
        [string]$RequestedProfile,
        [pscustomobject]$CounterState,
        [pscustomobject]$PolicyState
    )

    if ($RequestedProfile -ne "auto")
    {
        return [PSCustomObject]@{
            Profile = $RequestedProfile
            Reason = "explicit"
        }
    }

    if ($null -eq $PolicyState -or $null -eq $PolicyState.autoProfile)
    {
        return [PSCustomObject]@{
            Profile = "quick"
            Reason = "auto_policy_missing_default_quick"
        }
    }

    if (-not $PolicyState.autoProfile.enabled)
    {
        return [PSCustomObject]@{
            Profile = "quick"
            Reason = "auto_disabled_default_quick"
        }
    }

    $fullEveryHours = [double]$PolicyState.autoProfile.fullEveryHours
    $normalEveryHours = [double]$PolicyState.autoProfile.normalEveryHours
    $hoursSinceFull = Get-HoursSinceTimestamp -IsoDateTime $CounterState.lastFullRunDate
    $hoursSinceNormal = Get-HoursSinceTimestamp -IsoDateTime $CounterState.lastNormalRunDate

    if ($hoursSinceFull -ge $fullEveryHours)
    {
        return [PSCustomObject]@{
            Profile = "full"
            Reason = "hours_since_full=$([math]::Round($hoursSinceFull, 2)) >= $fullEveryHours"
        }
    }
    if ($hoursSinceNormal -ge $normalEveryHours)
    {
        return [PSCustomObject]@{
            Profile = "normal"
            Reason = "hours_since_normal=$([math]::Round($hoursSinceNormal, 2)) >= $normalEveryHours"
        }
    }
    return [PSCustomObject]@{
        Profile = "quick"
        Reason = "within_thresholds_full=$([math]::Round($hoursSinceFull, 2))h normal=$([math]::Round($hoursSinceNormal, 2))h"
    }
}

function Get-BuildArtifactMetadata
{
    param(
        [System.IO.FileSystemInfo]$Item
    )

    $artifactName = if ($Item.PSIsContainer)
    { $Item.Name
    } else
    { $Item.BaseName
    }
    $buildNumber = -1
    $timestamp = ""
    if ($artifactName -match '^Payanam_Android_(\d+)_(\d{8}_\d{6})$')
    {
        $buildNumber = [int]$Matches[1]
        $timestamp = $Matches[2]
    }

    return [PSCustomObject]@{
        Item = $Item
        ArtifactName = $artifactName
        BuildNumber = $buildNumber
        Timestamp = $timestamp
        LastWriteUtc = $Item.LastWriteTimeUtc
    }
}

function Invoke-BuildArtifactRetention
{
    param(
        [string]$TargetPath,
        [ValidateSet("File", "Directory")] [string]$ItemType,
        [int]$KeepCount,
        [string]$CurrentBuildName,
        [string]$Filter = "*"
    )

    if ($KeepCount -lt 1)
    {
        throw "KeepCount must be >= 1 for artifact retention."
    }
    if (-not (Test-Path $TargetPath))
    {
        Write-LogWithTime "  ℹ️ Retention skip: path not found $TargetPath" "DarkGray"
        return
    }

    $items = if ($ItemType -eq "File")
    {
        Get-ChildItem -Path $TargetPath -File -Filter $Filter
    } else
    {
        Get-ChildItem -Path $TargetPath -Directory -Filter $Filter
    }
    if ($items.Count -le $KeepCount)
    {
        Write-LogWithTime "  ℹ️ Retention skip: $TargetPath has $($items.Count) item(s), keep=$KeepCount" "DarkGray"
        return
    }

    $metadata = $items | ForEach-Object { Get-BuildArtifactMetadata -Item $_ }
    $protected = $metadata | Where-Object { $_.ArtifactName -eq $CurrentBuildName }
    $candidates = $metadata | Where-Object { $_.ArtifactName -ne $CurrentBuildName }
    $orderedCandidates = $candidates | Sort-Object `
    @{ Expression = "BuildNumber"; Descending = $true }, `
    @{ Expression = "Timestamp"; Descending = $true }, `
    @{ Expression = "LastWriteUtc"; Descending = $true }

    $toKeep = @($orderedCandidates | Select-Object -First $KeepCount)
    $toDelete = @($orderedCandidates | Select-Object -Skip $KeepCount)

    foreach ($entry in $toDelete)
    {
        if ($ItemType -eq "File")
        {
            Remove-Item -LiteralPath $entry.Item.FullName -Force -ErrorAction Stop
            Write-LogWithTime "  Deleted file: $($entry.Item.FullName)" "DarkGray"
        } else
        {
            Remove-Item -LiteralPath $entry.Item.FullName -Recurse -Force -ErrorAction Stop
            Write-LogWithTime "  Deleted folder: $($entry.Item.FullName)" "DarkGray"
        }
    }

    $keptTotal = $toKeep.Count + $protected.Count
    Write-LogWithTime "  ✅ Retention applied on $TargetPath (deleted=$($toDelete.Count), kept=$keptTotal, protectedCurrent=$($protected.Count))" "Green"
}

function Invoke-DaemonCleanup
{
    if ($KeepDaemons)
    {
        Write-LogWithTime "Keeping Gradle/Kotlin daemons alive (-KeepDaemons)." "Yellow"
        return
    }

    Write-LogWithTime "Low-memory default: stopping Gradle/Kotlin daemons..." "Yellow"
    try
    {
        if ($IsWindows) {
            cmd /c ".\gradlew.bat --stop 2>nul" | Out-Null
        } else {
            ./gradlew --stop 2>/dev/null | Out-Null
        }
    } catch
    {
        Write-LogWithTime "  ⚠️ Gradle daemon stop returned warning: $($_.Exception.Message)" "Yellow"
    }

    try
    {
        $targetCount = 0
        if ($IsWindows) {
            $javaProcesses = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'"
            $targetProcesses = $javaProcesses | Where-Object {
                $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon' -or
                $_.CommandLine -match 'org\.jetbrains\.kotlin\.daemon\.KotlinCompileDaemon'
            }
            foreach ($process in $targetProcesses)
            {
                Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
                $targetCount++
            }
        } else {
            $jvmPids = bash -c "pgrep -f 'GradleDaemon|KotlinCompileDaemon' 2>/dev/null"
            foreach ($procId in $jvmPids)
            {
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue 2>$null
                $targetCount++
            }
        }
        if ($targetCount -gt 0)
        {
            Write-LogWithTime "  ✅ Stopped daemon JVM process(es): $targetCount" "Green"
        } else
        {
            Write-LogWithTime "  ℹ️ No Gradle/Kotlin daemon JVMs found to stop." "DarkGray"
        }
    } catch
    {
        Write-LogWithTime "  ⚠️ JVM daemon cleanup warning: $($_.Exception.Message)" "Yellow"
    }
}

function Exit-WithCleanup
{
    param([int]$Code = 0)
    Invoke-DaemonCleanup
    exit $Code
}

function Invoke-GradleStreaming
{
    param(
        [string]$GradleArgs,
        [string]$StepLabel
    )
    $captured = [System.Collections.Generic.List[string]]::new()
    $lastProgress = [System.Diagnostics.Stopwatch]::StartNew()
    $lastHeartbeatLine = ''

    # Start Gradle in a background process with output redirected to a temp file.
    # stderr is redirected to a separate temp file (stored so it can be cleaned up).
    $outFile = [System.IO.Path]::GetTempFileName()
    $errFile = [System.IO.Path]::GetTempFileName()
    if ($IsWindows) {
        $gradleProc = Start-Process -FilePath 'cmd' `
            -ArgumentList "/c .\gradlew.bat $GradleArgs --console=plain --info 2>&1" `
            -NoNewWindow -PassThru `
            -RedirectStandardOutput $outFile `
            -RedirectStandardError $errFile
    } else {
        $gradleProc = Start-Process -FilePath './gradlew' `
            -ArgumentList @($GradleArgs, "--console=plain", "--no-daemon") `
            -NoNewWindow -PassThru `
            -RedirectStandardOutput $outFile `
            -RedirectStandardError $errFile
    }

    $reader = $null
    try
    {
        # Open the output file for tailing
        $fileStream = [System.IO.FileStream]::new(
            $outFile,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete
        )
        $reader = [System.IO.StreamReader]::new($fileStream)

        while (-not $gradleProc.HasExited -or $reader.Peek() -ge 0)
        {
            $line = $reader.ReadLine()
            if ($null -ne $line)
            {
                $captured.Add($line)
                # Show task lifecycle lines always (> Task :..., BUILD, FAILURE)
                if ($line -match '^\> Task :|^BUILD |^FAILURE')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                }
                # Show Kotlin compilation file progress
                elseif ($line -match 'Kotlin compile|w: |e: ')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                }
                # Show individual test execution lines
                elseif ($line -match '^\s*(PASS|FAIL|Test |Executing test )' -or $line -match 'tests? (completed|started|passed|failed)')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                }
                # Show lint/detekt findings
                elseif ($line -match 'Warning:|Error:|issues? found|Analyzing|analysis of')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                }
                # Track last non-empty line for heartbeat
                $trimmed = $line.Trim()
                if ($trimmed.Length -gt 0)
                {
                    $lastHeartbeatLine = $trimmed
                }
            } else
            {
                # No new output — check heartbeat timer
                if ($lastProgress.Elapsed.TotalSeconds -ge 15 -and $lastHeartbeatLine.Length -gt 0)
                {
                    Write-Host "  ... $lastHeartbeatLine" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                }
                Start-Sleep -Milliseconds 500
            }
        }
    } finally
    {
        if ($null -ne $reader) { $reader.Close() }

        # Re-read the FULL output file after WaitForExit(). The streaming reader
        # may have missed the last lines due to Linux pipe buffering. On Linux,
        # HasExited can become true before the file handle is fully flushed.
        $gradleProc.WaitForExit()
        $procExitCode = $gradleProc.ExitCode

        $fullContent = try { [System.IO.File]::ReadAllText($outFile) } catch { "" }
        $captured.Clear()
        foreach ($line in ($fullContent -split "`n")) {
            $t = $line.Trim()
            if ($t.Length -gt 0) {
                $captured.Add($t)
                if ($t -match '^\> Task :|^BUILD |^FAILURE|Kotlin compile|w: |e: |Warning:|Error:|issues? found') {
                    Write-Host "  $t" -ForegroundColor DarkGray
                }
            }
        }
        Remove-Item -Path $outFile -Force -ErrorAction SilentlyContinue
        Remove-Item -Path $errFile -Force -ErrorAction SilentlyContinue
    }

    $buildSucceeded = ($captured | Where-Object { $_ -match '^BUILD SUCCESSFUL' }).Count -gt 0
    $buildFailed    = ($captured | Where-Object { $_ -match '^BUILD FAILED' }).Count -gt 0

    $exitCode = if ($buildSucceeded -and -not $buildFailed) { 0 }
                elseif ($buildFailed) { 1 }
                else { $procExitCode }

    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output   = $captured
    }
}

function Wait-ForAppProcess
{
    param(
        [string]$PackageName,
        [int]$MaxChecks = 8,
        [int]$SleepSeconds = 1
    )

    for ($attempt = 1; $attempt -le $MaxChecks; $attempt++)
    {
        $runningCheck = adb shell pidof $PackageName 2>&1
        if ($runningCheck -match '\d+')
        {
            return $runningCheck.Trim()
        }
        Start-Sleep -Seconds $SleepSeconds
    }

    return $null
}

function Invoke-DeviceSmokePack
{
    param(
        [string]$PackageName,
        [string]$ActivityName,
        [string]$BuildName
    )

    Write-LogWithTime "" "White"
    Write-LogWithTime "=== DEVICE SMOKE PACK ===" "Magenta"

    $smokeDir = Join-Path "output/smoke" $BuildName
    New-Item -ItemType Directory -Path $smokeDir -Force | Out-Null

    $emulatorFlag = adb shell getprop ro.kernel.qemu 2>&1
    if ($emulatorFlag -match "1")
    {
        throw "Smoke pack requires a real local Android device. Emulator detected (ro.kernel.qemu=1)."
    }
    Write-LogWithTime "Smoke target verified as real device." "Green"

    Write-LogWithTime "Smoke check: deep-open time navigation (safe mode, no quick-start)." "Cyan"
    $navigateResult = adb shell am start -W -n "$PackageName/$ActivityName" --es navigate_to time --es nav_source smoke_pack 2>&1
    if ($LASTEXITCODE -ne 0 -or -not ($navigateResult -match "Status:\s+ok"))
    {
        throw "Smoke navigation intent failed. Output: $navigateResult"
    }
    $postNavigatePid = Wait-ForAppProcess -PackageName $PackageName -MaxChecks 8 -SleepSeconds 1
    if ($null -eq $postNavigatePid)
    {
        throw "Smoke navigation intent succeeded but app process is not running."
    }
    Write-LogWithTime "  ✅ Time navigation smoke check passed (PID: $postNavigatePid)" "Green"

    Write-LogWithTime "Smoke check: responsiveness ping with deterministic fallback..." "Cyan"
    $pingResult = adb shell "am broadcast -a io.payanam.PING --include-stopped-packages" 2>&1
    if ($pingResult -match "Broadcast completed")
    {
        Write-LogWithTime "  ✅ Ping responsiveness confirmed." "Green"
    } else
    {
        Write-LogWithTime "  ⚠️ Ping inconclusive. Running fallback relaunch check..." "Yellow"
        adb shell am force-stop $PackageName 2>$null | Out-Null
        Start-Sleep -Seconds 1
        $fallbackLaunch = adb shell am start -W -n "$PackageName/$ActivityName" 2>&1
        if ($LASTEXITCODE -ne 0 -or -not ($fallbackLaunch -match "Status:\s+ok"))
        {
            throw "Fallback launch failed after inconclusive ping. Output: $fallbackLaunch"
        }
        $fallbackPid = Wait-ForAppProcess -PackageName $PackageName -MaxChecks 8 -SleepSeconds 1
        if ($null -eq $fallbackPid)
        {
            throw "Fallback launch succeeded but app process is not running."
        }
        Write-LogWithTime "  ✅ Fallback responsiveness check passed (PID: $fallbackPid)." "Green"
    }

    $logcatPath = Join-Path $smokeDir "logcat-smoke.txt"
    $logcatSlice = adb logcat -d -t 600 2>&1
    Set-Content -Path $logcatPath -Value $logcatSlice -Encoding UTF8
    Write-LogWithTime "Saved smoke logcat slice: $logcatPath" "DarkGray"

    $fatalSignals = @("FATAL EXCEPTION", "AndroidRuntime", "Process: $PackageName")
    $fatalDetected = $false
    foreach ($signal in $fatalSignals)
    {
        if ($logcatSlice -match [regex]::Escape($signal))
        {
            $fatalDetected = $true
            break
        }
    }
    if ($fatalDetected)
    {
        throw "Smoke logcat indicates a potential fatal runtime signal for package $PackageName. See $logcatPath"
    }

    $latestInAppPath = adb shell "ls -t /storage/emulated/0/Documents/payanam/logs/payanam-unified*.log 2>/dev/null | head -1" 2>&1
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($latestInAppPath))
    {
        $latestInAppPath = $latestInAppPath.Trim()
        $pulledLogPath = Join-Path $smokeDir "inapp-unified-log.txt"
        $pullResult = adb pull $latestInAppPath $pulledLogPath 2>&1
        if ($LASTEXITCODE -eq 0)
        {
            Write-LogWithTime "Saved in-app unified log snapshot: $pulledLogPath" "DarkGray"
        } else
        {
            Write-LogWithTime "  ⚠️ Unable to pull in-app unified log snapshot: $pullResult" "Yellow"
        }
    } else
    {
        Write-LogWithTime "  ⚠️ No in-app unified log file found to pull." "Yellow"
    }

    Write-LogWithTime "✅ Device smoke pack passed." "Green"
}

function Invoke-MaestroFlow
{
    param(
        [string]$FlowPath,
        [string]$BuildName
    )

    Write-LogWithTime "" "White"
    Write-LogWithTime "=== MAESTRO UI FLOW ===" "Magenta"

    $maestroCommand = Get-Command maestro -ErrorAction SilentlyContinue
    if (-not $maestroCommand)
    {
        throw "Maestro CLI not found on PATH. Install from https://docs.maestro.dev/getting-started/installing-maestro"
    }
    if (-not (Test-Path $FlowPath))
    {
        throw "Maestro flow file not found: $FlowPath"
    }

    $maestroDir = Join-Path "output/smoke" $BuildName
    New-Item -ItemType Directory -Path $maestroDir -Force | Out-Null
    $logPath = Join-Path $maestroDir "maestro-output.txt"

    Write-LogWithTime "Running Maestro flow: $FlowPath" "Cyan"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $supportsNativeErrorPreference = $null -ne (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue)
    if ($supportsNativeErrorPreference)
    {
        $previousNativeErrorPreference = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
    }
    try
    {
        $maestroOutput = & maestro test $FlowPath 2>&1 | Tee-Object -FilePath $logPath
        $exitCode = $LASTEXITCODE
    } finally
    {
        $ErrorActionPreference = $previousErrorActionPreference
        if ($supportsNativeErrorPreference)
        {
            $PSNativeCommandUseErrorActionPreference = $previousNativeErrorPreference
        }
    }
    if ($exitCode -ne 0)
    {
        Write-LogWithTime "  ❌ Maestro flow failed (exit $exitCode)." "Red"
        Write-LogWithTime "  Tail (last 20 lines):" "Red"
        $tail = $maestroOutput | Select-Object -Last 20
        foreach ($line in $tail)
        {
            Write-Host $line
        }
        throw "Maestro flow failed. See $logPath"
    }

    Write-LogWithTime "✅ Maestro flow passed. Log: $logPath" "Green"
}

function Get-AndroidBuildToolPath
{
    param([string]$ToolName)

    $toolCommand = Get-Command $ToolName -ErrorAction SilentlyContinue
    if ($toolCommand)
    {
        return $toolCommand.Source
    }

    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path $_) } |
        Select-Object -Unique

    foreach ($sdkRoot in $sdkRoots)
    {
        $buildToolsDir = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path $buildToolsDir))
        {
            continue
        }
        $candidate = Get-ChildItem -Path $buildToolsDir -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "$ToolName.bat" } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($candidate)
        {
            return $candidate
        }
    }

    return $null
}

function Invoke-ReleaseSecurityVerification
{
    param([string]$ApkPath)

    Write-LogWithTime "" "White"
    Write-LogWithTime "=== RELEASE SECURITY VERIFY ===" "Magenta"

    $aaptPath = Get-AndroidBuildToolPath -ToolName "aapt"
    if ([string]::IsNullOrWhiteSpace($aaptPath))
    {
        Write-LogWithTime "  ⚠️ aapt not found; cannot verify manifest debuggable flag from built APK." "Yellow"
    } else
    {
        $badgingOutput = & $aaptPath dump badging $ApkPath 2>&1
        if ($LASTEXITCODE -ne 0)
        {
            throw "aapt badging inspection failed: $badgingOutput"
        }
        if ($badgingOutput -match "application-debuggable")
        {
            throw "Release security verify failed: APK manifest is debuggable."
        }
        Write-LogWithTime "  ✅ APK manifest is non-debuggable." "Green"
    }

    $apksignerPath = Get-AndroidBuildToolPath -ToolName "apksigner"
    if ([string]::IsNullOrWhiteSpace($apksignerPath))
    {
        Write-LogWithTime "  ⚠️ apksigner not found; cannot verify signature certificate identity." "Yellow"
        return
    }

    $verifyOutput = & $apksignerPath verify --verbose --print-certs $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0)
    {
        throw "apksigner verification failed: $verifyOutput"
    }
    if ($verifyOutput -match "CN=Android Debug")
    {
        throw "Release security verify failed: APK is signed with Android Debug certificate."
    }

    Write-LogWithTime "  ✅ APK signature verification passed (non-debug cert)." "Green"
}


function Test-LocalizedStringDuplicates
{
    param([string]$ResourceRoot = "app/src/main/res")

    # Rare, intentional duplicates can be allowlisted here.
    # Format:
    # "app/src/main/res/values/strings.xml" = @{
    #   "Exact normalized value" = @("key_one", "key_two")
    # }
    $stringDuplicateAllowList = @{
        "app/src/main/res/values/strings.xml" = @{
            "Auto" = @("backup_trigger_auto", "loc_auto")
            "Export" = @("backup_trigger_export", "settings_action_export")
            'Last failure (%1$s): %2$s' = @("backup_failure_dialog_message_with_time", "settings_auto_backup_error_with_time")
            'Last failure: %1$s' = @("backup_failure_dialog_message_without_time", "settings_auto_backup_error_without_time")
            "Manual" = @("backup_trigger_manual", "loc_manual")
        }
        "app/src/main/res/values-ta/strings.xml" = @{
            "ஏற்றுமதி" = @("backup_trigger_export", "settings_action_export")
            'கடைசி தோல்வி (%1$s): %2$s' = @("backup_failure_dialog_message_with_time", "settings_auto_backup_error_with_time")
            'கடைசி தோல்வி: %1$s' = @("backup_failure_dialog_message_without_time", "settings_auto_backup_error_without_time")
        }
    }

    $stringFiles = Get-ChildItem -Path $ResourceRoot -Recurse -File -Filter "strings.xml" |
        Where-Object { $_.FullName -match '[\\/]values([\\-][^\\/]+)?[\\/]strings\.xml$' }

    if ($stringFiles.Count -eq 0)
    {
        Write-LogWithTime "  ⚠️ No strings.xml files found under $ResourceRoot" "Yellow"
        return $true
    }

    $violations = @()

    foreach ($file in $stringFiles)
    {
        try
        {
            [xml]$xmlDoc = Get-Content $file.FullName -Raw
        } catch
        {
            $violations += [PSCustomObject]@{
                Type = "parse"
                File = $file.FullName
                Detail = "Failed to parse XML: $($_.Exception.Message)"
            }
            continue
        }

        $localeFile = ($file.FullName -replace '\\', '/')
        $localeFile = $localeFile.Substring($localeFile.IndexOf("app/src/main/res/"))

        $strings = @()
        foreach ($node in $xmlDoc.resources.string)
        {
            $name = [string]$node.name
            if ([string]::IsNullOrWhiteSpace($name))
            {
                continue
            }

            $rawValue = [string]$node.'#text'
            if ($null -eq $rawValue)
            {
                $rawValue = ""
            }

            $normalizedValue = ($rawValue.Trim() -replace '\s+', ' ')
            $strings += [PSCustomObject]@{
                Name = $name
                Value = $normalizedValue
            }
        }

        $duplicateNames = $strings |
            Group-Object Name |
            Where-Object { $_.Count -gt 1 }
        foreach ($dup in $duplicateNames)
        {
            $violations += [PSCustomObject]@{
                Type = "duplicate_name"
                File = $localeFile
                Detail = "name='$($dup.Name)' count=$($dup.Count)"
            }
        }

        $duplicateValues = $strings |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_.Value) } |
            Group-Object Value |
            Where-Object { $_.Count -gt 1 }

        foreach ($dup in $duplicateValues)
        {
            $keys = $dup.Group | Select-Object -ExpandProperty Name | Sort-Object
            $value = $dup.Name

            $isAllowlisted = $false
            if ($stringDuplicateAllowList.ContainsKey($localeFile))
            {
                $localeAllowList = $stringDuplicateAllowList[$localeFile]
                if ($localeAllowList.ContainsKey($value))
                {
                    $expectedKeys = @($localeAllowList[$value] | Sort-Object)
                    if (
                        $expectedKeys.Count -eq $keys.Count -and
                        ((Compare-Object -ReferenceObject $expectedKeys -DifferenceObject $keys).Count -eq 0)
                    )
                    {
                        $isAllowlisted = $true
                    }
                }
            }

            if (-not $isAllowlisted)
            {
                $violations += [PSCustomObject]@{
                    Type = "duplicate_value"
                    File = $localeFile
                    Detail = "value='$value' keys=[$($keys -join ', ')]"
                }
            }
        }
    }

    if ($violations.Count -gt 0)
    {
        Write-LogWithTime "  ❌ Localized strings duplicate check failed ($($violations.Count) issue(s)):" "Red"
        foreach ($violation in $violations)
        {
            Write-LogWithTime "     - [$($violation.Type)] $($violation.File): $($violation.Detail)" "Red"
        }
        Write-LogWithTime "  Resolve duplicates or add a precise allowlist exception in build-android.ps1." "Yellow"
        return $false
    }

    return $true
}

function Test-DbAuthFlowSecurityContract
{
    param([string]$SpecPath = "docs/db/db-flow-boot-entry-flows.json")

    if (-not (Test-Path $SpecPath))
    {
        Write-LogWithTime "  ❌ DB flow spec not found: $SpecPath" "Red"
        return $false
    }

    try
    {
        $spec = Get-Content $SpecPath -Raw | ConvertFrom-Json
    } catch
    {
        Write-LogWithTime "  ❌ Failed to parse DB flow spec JSON: $($_.Exception.Message)" "Red"
        return $false
    }

    $transitions = @($spec.transitions)
    $nodes = @($spec.nodes)
    $violations = @()

    $transitionById = @{}
    foreach ($t in $transitions)
    {
        if ($null -ne $t.id)
        {
            $transitionById[[string]$t.id] = $t
        }
    }

    if (-not $transitionById.ContainsKey("TAUTH004"))
    {
        $violations += "Missing transition TAUTH004"
    } else
    {
        $t = $transitionById["TAUTH004"]
        if ([string]$t.to -ne "AUTH_MANUAL_ENTRY")
        {
            $violations += "TAUTH004 must route to AUTH_MANUAL_ENTRY"
        }
        if (-not ([string]$t.guard).ToLower().Contains("biometric not enabled"))
        {
            $violations += "TAUTH004 guard must explicitly require manual entry when biometric is not enabled"
        }
    }

    if (-not $transitionById.ContainsKey("TAUTH027"))
    {
        $violations += "Missing transition TAUTH027"
    } else
    {
        $t = $transitionById["TAUTH027"]
        if (-not ([string]$t.guard).ToLower().Contains("manual passphrase unlock"))
        {
            $violations += "TAUTH027 guard must require manual passphrase unlock for biometric offer eligibility"
        }
    }

    if (-not $transitionById.ContainsKey("TBIOSEC005"))
    {
        $violations += "Missing transition TBIOSEC005"
    } else
    {
        $t = $transitionById["TBIOSEC005"]
        if (-not ([string]$t.action).Contains("setUserAuthenticationRequired=true"))
        {
            $violations += "TBIOSEC005 action must reference biometric-required Keystore wrapping"
        }
    }

    if (-not $transitionById.ContainsKey("TBIOSEC013"))
    {
        $violations += "Missing transition TBIOSEC013"
    } else
    {
        $t = $transitionById["TBIOSEC013"]
        if ([string]$t.to -ne "BIOSEC_DELETE_KEY")
        {
            $violations += "TBIOSEC013 must route directly to BIOSEC_DELETE_KEY (no disable passphrase prompt)"
        }
    }

    $legacyDisableRoute = $transitions | Where-Object {
        [string]$_.from -eq "BIOSEC_DISABLE_FLOW" -and [string]$_.to -eq "BIOSEC_DISABLE_VERIFY"
    }
    if ($legacyDisableRoute.Count -gt 0)
    {
        $violations += "Legacy disable verification route BIOSEC_DISABLE_FLOW -> BIOSEC_DISABLE_VERIFY must not exist"
    }

    $legacyDisableNode = $nodes | Where-Object { [string]$_.id -eq "BIOSEC_DISABLE_VERIFY" }
    if ($legacyDisableNode.Count -gt 0)
    {
        $violations += "Legacy node BIOSEC_DISABLE_VERIFY must not exist in canonical flow"
    }

    if ($violations.Count -gt 0)
    {
        Write-LogWithTime "  ❌ DB auth flow security contract failed ($($violations.Count) issue(s)):" "Red"
        foreach ($v in $violations)
        {
            Write-LogWithTime "     - $v" "Red"
        }
        Write-LogWithTime "  Update docs/db/db-flow-boot-entry-flows.json to satisfy the security contract." "Yellow"
        return $false
    }

    return $true
}

function Test-CriticalLoggingCoverageContract
{
    $contract = @(
        [PSCustomObject]@{
            Path = "app/src/main/kotlin/io/payanam/ui/viewmodel/DatabaseInitViewModel.kt"
            Patterns = @(
                "DatabaseInitViewModel\.checkDatabaseStatus",
                "DatabaseInitViewModel\.executeImportDatabase",
                "DatabaseInitViewModel\.resumeImportWithPassphrase"
            )
        },
        [PSCustomObject]@{
            Path = "app/src/main/kotlin/io/payanam/feature/settings/SettingsEncryptedImportSupport.kt"
            Patterns = @(
                "SettingsViewModel\.importDatabase",
                "SettingsViewModel\.resumeImportWithPassphrase",
                "SettingsViewModel\.cancelImportPassphrase"
            )
        },
        [PSCustomObject]@{
            Path = "app/src/main/kotlin/io/payanam/ui/viewmodel/DatabasePassphraseUnlockViewModel.kt"
            Patterns = @(
                "DatabasePassphraseUnlockViewModel\.unlock",
                "DatabasePassphraseUnlockViewModel\.startBiometricUnlock",
                "DatabasePassphraseUnlockViewModel\.forgotPassphraseReset"
            )
        },
        [PSCustomObject]@{
            Path = "core/database/src/main/kotlin/io/payanam/database/security/DatabaseEncryptionManager.kt"
            Patterns = @(
                "DatabaseEncryptionManager\.configurePassphrase",
                "DatabaseEncryptionManager\.verifyPassphrase",
                "DatabaseEncryptionManager\.disableBiometricUnlock"
            )
        },
        [PSCustomObject]@{
            Path = "core/database/src/main/kotlin/io/payanam/database/security/DatabaseArtifactJanitor.kt"
            Patterns = @(
                "DatabaseArtifactJanitor\.",
                "recoverFromTempBackupIfPrimaryMissing",
                "pruneCorruptSnapshots"
            )
        },
        [PSCustomObject]@{
            Path = "core/database/src/main/kotlin/io/payanam/database/repository/TaskRepositoryImpl.kt"
            Patterns = @(
                "TaskRepositoryImpl\.createTask",
                "TaskRepositoryImpl\.updateTask",
                "TaskRepositoryImpl\.markDirtyForTaskDay"
            )
        },
        [PSCustomObject]@{
            Path = "core/database/src/main/kotlin/io/payanam/database/repository/TimeEntryRepositoryImpl.kt"
            Patterns = @(
                "TimeEntryRepositoryImpl\.createTimeEntry",
                "TimeEntryRepositoryImpl\.updateTimeEntry",
                "TimeEntryRepositoryImpl\.markDirtyForDay"
            )
        }
    )

    $violations = @()
    foreach ($item in $contract)
    {
        if (-not (Test-Path $item.Path))
        {
            $violations += "Missing file: $($item.Path)"
            continue
        }
        $content = Get-Content $item.Path -Raw
        foreach ($pattern in $item.Patterns)
        {
            if ($content -notmatch $pattern)
            {
                $violations += "Missing logging marker '$pattern' in $($item.Path)"
            }
        }
    }

    if ($violations.Count -gt 0)
    {
        Write-LogWithTime "  ❌ Critical logging coverage contract failed ($($violations.Count) issue(s)):" "Red"
        foreach ($v in $violations)
        {
            Write-LogWithTime "     - $v" "Red"
        }
        Write-LogWithTime "  Add/restore structured logs for critical init/import/auth/task-time paths." "Yellow"
        return $false
    }

    return $true
}

Write-LogWithTime "========================================" "Cyan"
Write-LogWithTime "  Payanam Android Build (Kotlin/Compose)" "Cyan"
Write-LogWithTime "========================================" "Cyan"

$buildType = if ($Release)
{ "release"
} else
{ "debug"
}
Write-LogWithTime "Build Type: $buildType" "Yellow"

if ($Clean)
{
    Write-LogWithTime "Clean mode: will remove previous builds" "Yellow"
}
if ($CleanInstall)
{
    Write-LogWithTime "Clean install: will uninstall before installing" "Yellow"
}
if ($KeepDaemons)
{
    Write-LogWithTime "Daemon policy: KEEP alive (-KeepDaemons)" "Yellow"
} else
{
    Write-LogWithTime "Daemon policy: STOP after build (default low-memory mode)" "Yellow"
}

$counterPath = "build-tools/tracking/build-counter.json"
$policyPath = "build-tools/config/build-profile-policy.json"
if (-not (Test-Path $counterPath))
{
    throw "Build counter file not found: $counterPath"
}
$counter = Get-Content $counterPath -Raw | ConvertFrom-Json
if (-not (Get-Member -InputObject $counter -Name "lastQuickRunDate" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "lastQuickRunDate" -Value ""
}
if (-not (Get-Member -InputObject $counter -Name "lastNormalRunDate" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "lastNormalRunDate" -Value ""
}
if (-not (Get-Member -InputObject $counter -Name "lastFullRunDate" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "lastFullRunDate" -Value ""
}

$profilePolicy = $null
if (Test-Path $policyPath)
{
    $profilePolicy = Get-Content $policyPath -Raw | ConvertFrom-Json
} else
{
    Write-LogWithTime "  ⚠️ Profile policy file not found at $policyPath, using quick defaults for auto." "Yellow"
}
$resolvedProfile = Resolve-BuildProfile -RequestedProfile $Profile -CounterState $counter -PolicyState $profilePolicy
$effectiveProfile = $resolvedProfile.Profile
Write-LogWithTime "Build Profile: $effectiveProfile (requested=$Profile, reason=$($resolvedProfile.Reason))" "Yellow"

$runUnitTests = $false
$runRegressionTests = $false
$runCoverage = $false
$runStaticAnalysis = $false
$runPostInstallVerification = $false
$runDeviceSmoke = $false
$runMaestroFlow = $false
$runDeviceInstall = $true
$runAndroidGuardrails = $true
switch ($effectiveProfile)
{
    "quick"
    {
        # Static guardrails (file length, import count, strings dedupe,
        # security/logging contracts) are cheap file scans and always run —
        # they catch structural regressions even on fast iteration builds.
        # Only expensive verification (tests/coverage/smoke) stays profile-gated.
        $runPostInstallVerification = $false
    }
    "normal"
    {
        $runUnitTests = $true
        $runRegressionTests = $true
        $runStaticAnalysis = $true
        $runPostInstallVerification = $false
    }
    "full"
    {
        $runUnitTests = $true
        $runRegressionTests = $true
        $runCoverage = $true
        $runStaticAnalysis = $true
        $runPostInstallVerification = $true
        $runDeviceSmoke = $true
        $runMaestroFlow = $false
    }
}

Write-LogWithTime "Device install: $(if ($runDeviceInstall) { 'enabled' } else { 'disabled' })" "Yellow"
Write-LogWithTime "Android guardrails: $(if ($runAndroidGuardrails) { 'enabled' } else { 'disabled' })" "Yellow"

$maestroEnabledByFlag = $RunMaestro.IsPresent
$maestroEnvValue = [string]$env:PAYANAM_RUN_MAESTRO
$maestroEnabledByEnv = $maestroEnvValue -match '^(1|true|yes)$'
if ($maestroEnabledByFlag -or $maestroEnabledByEnv)
{
    $runMaestroFlow = $true
}

if ($SkipTests)
{
    $runUnitTests = $false
    $runRegressionTests = $false
    $runCoverage = $false
}
if ($SkipGuardrails)
{
    $runAndroidGuardrails = $false
}
if ($SkipMaestro)
{
    $runMaestroFlow = $false
}
Write-LogWithTime "Maestro UI flow: $(if ($runMaestroFlow) { 'enabled' } else { 'disabled' }) (flag=$maestroEnabledByFlag, env='$maestroEnvValue', skip=$($SkipMaestro.IsPresent))" "Yellow"

# ============================================
# PREFLIGHT CHECKS
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "=== PREFLIGHT CHECKS ===" "Magenta"
Write-LogWithTime "Preflight order: environment -> structure -> version -> optional guardrails -> optional verification." "DarkGray"

# Check 1: Java version
Write-LogWithTime "Checking Java version..." "Cyan"
$javaHome = $env:JAVA_HOME
if (-not $javaHome)
{
    # Try gradle.properties
    $gradleProps = Get-Content "gradle.properties" -Raw
    if ($gradleProps -match 'org\.gradle\.java\.home=(.+)')
    {
        $javaHome = $matches[1].Trim()
    }
}
if ($javaHome)
{
    Write-LogWithTime "  JAVA_HOME: $javaHome" "Green"
} else
{
    Write-LogWithTime "  Warning: JAVA_HOME not set, using system default" "Yellow"
}

# Check 2: Gradle wrapper exists
Write-LogWithTime "Checking Gradle wrapper..." "Cyan"
$requiredGradleFile = if ($IsWindows) { "gradlew.bat" } else { "gradlew" }
if (-not (Test-Path $requiredGradleFile))
{
    Write-LogWithTime "  ❌ $requiredGradleFile not found!" "Red"
    Exit-WithCleanup 1
}
if (-not (Test-Path "gradle/wrapper/gradle-wrapper.jar"))
{
    Write-LogWithTime "  ❌ gradle-wrapper.jar not found!" "Red"
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ Gradle wrapper present" "Green"

# Check 3: Core source files exist
Write-LogWithTime "Checking source structure..." "Cyan"
$requiredPaths = @(
    "app/src/main/kotlin/io/payanam/PayanamApp.kt",
    "app/src/main/kotlin/io/payanam/MainActivity.kt",
    "core/domain/src/main/kotlin/io/payanam/domain/model/Task.kt",
    "core/database/src/main/kotlin/io/payanam/database/PayanamDatabase.kt"
)
$missingPaths = @()
foreach ($path in $requiredPaths)
{
    if (-not (Test-Path $path))
    {
        $missingPaths += $path
    }
}
if ($missingPaths.Count -gt 0)
{
    Write-LogWithTime "  ❌ Missing source files:" "Red"
    foreach ($missing in $missingPaths)
    {
        Write-LogWithTime "     - $missing" "Red"
    }
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ All core source files present" "Green"

if ($runAndroidGuardrails)
{
    Write-LogWithTime "Checking Android guardrails (always-on static checks)... " "Cyan"
    $moduleLineLimits = @{
        "app/src/main/kotlin" = 9700
        "core/common/src/main/kotlin" = 9400
        "core/database/src/main/kotlin" = 9650
        "core/domain/src/main/kotlin" = 9300
        "core/scoring/src/main/kotlin" = 9350
    }
    $warningRatio = 0.70
    $lengthViolations = @()
    $lengthWarnings = @()
    $importWarnings = @()
    $importViolations = @()
    $importWarningThreshold = 70
    $importHardLimit = 100
    foreach ($modulePath in $moduleLineLimits.Keys)
    {
        if (-not (Test-Path $modulePath))
        {
            continue
        }
        $lineLimit = $moduleLineLimits[$modulePath]
        $kotlinFiles = Get-ChildItem -Path $modulePath -Recurse -Filter *.kt
        foreach ($file in $kotlinFiles)
        {
            $lineCount = (Get-Content $file.FullName).Count
            $warningLimit = [int][math]::Floor($lineLimit * $warningRatio)
            $importCount = (Get-Content $file.FullName | Where-Object { $_ -match '^import\s+' }).Count

            if ($lineCount -gt $warningLimit -and $lineCount -le $lineLimit)
            {
                $relativePath = Resolve-Path -Relative $file.FullName
                $lengthWarnings += [PSCustomObject]@{
                    Path = $relativePath
                    Lines = $lineCount
                    WarningLimit = $warningLimit
                    Limit = $lineLimit
                }
            }

            if ($lineCount -gt $lineLimit)
            {
                $relativePath = Resolve-Path -Relative $file.FullName
                $lengthViolations += [PSCustomObject]@{
                    Path = $relativePath
                    Lines = $lineCount
                    Limit = $lineLimit
                    Module = $modulePath
                }
            }

            if ($importCount -gt $importWarningThreshold -and $importCount -le $importHardLimit)
            {
                $relativePath = Resolve-Path -Relative $file.FullName
                $importWarnings += [PSCustomObject]@{
                    Path = $relativePath
                    Imports = $importCount
                    WarningLimit = $importWarningThreshold
                    Limit = $importHardLimit
                }
            }

            if ($importCount -gt $importHardLimit)
            {
                $relativePath = Resolve-Path -Relative $file.FullName
                $importViolations += [PSCustomObject]@{
                    Path = $relativePath
                    Imports = $importCount
                    Limit = $importHardLimit
                }
            }
        }
    }
    if ($lengthWarnings.Count -gt 0)
    {
        Write-LogWithTime "  ⚠️ File length warnings (approaching module limits):" "Yellow"
        $lengthWarnings | Sort-Object Lines -Descending | Select-Object -First 10 | ForEach-Object {
            Write-LogWithTime "     - $($_.Path) : $($_.Lines) lines (warn: $($_.WarningLimit), limit: $($_.Limit))" "Yellow"
        }
    }
    if ($lengthViolations.Count -gt 0)
    {
        Write-LogWithTime "  ❌ File length guard failed. Oversized Kotlin files detected:" "Red"
        $lengthViolations | Sort-Object Lines -Descending | ForEach-Object {
            Write-LogWithTime "     - $($_.Path) : $($_.Lines) lines (limit: $($_.Limit))" "Red"
        }
        Write-LogWithTime "  Reduce file size or adjust agreed module limits before building." "Yellow"
        Exit-WithCleanup 1
    }
    if ($importWarnings.Count -gt 0)
    {
        Write-LogWithTime "  ⚠️ High import-count warnings:" "Yellow"
        $importWarnings | Sort-Object Imports -Descending | Select-Object -First 10 | ForEach-Object {
            Write-LogWithTime "     - $($_.Path) : $($_.Imports) imports (warn: $($_.WarningLimit), limit: $($_.Limit))" "Yellow"
        }
    }
    if ($importViolations.Count -gt 0)
    {
        Write-LogWithTime "  ❌ Import-count guard failed:" "Red"
        $importViolations | Sort-Object Imports -Descending | ForEach-Object {
            Write-LogWithTime "     - $($_.Path) : $($_.Imports) imports (limit: $($_.Limit))" "Red"
        }
        Write-LogWithTime "  Split the file or remove unused imports before building." "Yellow"
        Exit-WithCleanup 1
    }
    Write-LogWithTime "  ✅ File length guard passed" "Green"

    Write-LogWithTime "Checking localized strings duplicate guard..." "Cyan"
    if (-not (Test-LocalizedStringDuplicates))
    {
        Exit-WithCleanup 1
    }
    Write-LogWithTime "  ✅ Localized strings duplicate guard passed" "Green"

    Write-LogWithTime "Checking DB auth flow security contract..." "Cyan"
    if (-not (Test-DbAuthFlowSecurityContract))
    {
        Exit-WithCleanup 1
    }
    Write-LogWithTime "  ✅ DB auth flow security contract passed" "Green"

    Write-LogWithTime "Checking critical logging coverage contract..." "Cyan"
    if (-not (Test-CriticalLoggingCoverageContract))
    {
        Exit-WithCleanup 1
    }
    Write-LogWithTime "  ✅ Critical logging coverage contract passed" "Green"
} else
{
    Write-LogWithTime "Skipping Android guardrails (explicit -SkipGuardrails)." "Yellow"
}

# Check 5: Unit tests (profile + optional skip)
if ($runUnitTests)
{
    Write-LogWithTime "Running unit tests..." "Cyan"
    $testRun = Invoke-GradleStreaming -GradleArgs ":app:testDebugUnitTest :core:common:testDebugUnitTest :core:database:testDebugUnitTest :core:domain:testDebugUnitTest :core:scoring:testDebugUnitTest :core:shared:test" -StepLabel "Unit tests"
    if ($testRun.ExitCode -ne 0)
    {
        Write-LogWithTime "  ❌ Unit tests failed!" "Red"
        $testRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        Exit-WithCleanup 1
    }
    Write-LogWithTime "  ✅ Unit tests passed" "Green"
} else
{
    Write-LogWithTime "  ⚠️ Skipping unit tests (profile/flag)" "Yellow"
}

if ($runRegressionTests)
{
    Write-LogWithTime "Running regression lock tests..." "Cyan"
    $regressionRun = Invoke-GradleStreaming -GradleArgs ":app:testDebugUnitTest --tests `"*RegressionTest*`"" -StepLabel "Regression tests"
    if ($regressionRun.ExitCode -ne 0)
    {
        Write-LogWithTime "  ❌ Regression lock tests failed!" "Red"
        $regressionRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        Exit-WithCleanup 1
    }
    Write-LogWithTime "  ✅ Regression lock tests passed" "Green"
} else
{
    Write-LogWithTime "  ⚠️ Skipping regression lock tests (profile/flag)" "Yellow"
}

if ($runCoverage)
{
    Write-LogWithTime "Running coverage check..." "Cyan"
    $coverageRun = Invoke-GradleStreaming -GradleArgs "coverageCheck" -StepLabel "Coverage"
    if ($coverageRun.ExitCode -ne 0)
    {
        Write-LogWithTime "  ⚠️ Coverage check reported issues (non-fatal on first public setup)" "Yellow"
    } else {
        Write-LogWithTime "  ✅ Coverage check passed" "Green"
    }
} else
{
    Write-LogWithTime "  ⚠️ Skipping coverage check (profile/flag)" "Yellow"
}

# Check 6: Static analysis (lint, detekt)
if ($runStaticAnalysis)
{
    Write-LogWithTime "Running static analysis..." "Cyan"
    $staticRun = Invoke-GradleStreaming -GradleArgs "staticAnalysisCheck" -StepLabel "Static analysis"
    if ($staticRun.ExitCode -ne 0)
    {
        Write-LogWithTime "  ⚠️ Static analysis reported issues (non-fatal on first public setup)" "Yellow"
    } else {
        Write-LogWithTime "  ✅ Static analysis passed" "Green"
    }
} else
{
    Write-LogWithTime "  ⚠️ Skipping static analysis (profile/flag)" "Yellow"
}

Write-LogWithTime "✅ All preflight checks passed!" "Green"

# ============================================
# BUILD COUNTER UPDATE
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "=== BUILD COUNTER ===" "Magenta"

$counter.totalBuilds++
$counter.androidBuilds++
$counter.lastBuildDate = Get-LocalDateTime
switch ($effectiveProfile)
{
    "quick"
    {
    }
    "normal"
    { $counter.lastNormalRunDate = $counter.lastBuildDate
    }
    "full"
    {
        $counter.lastNormalRunDate = $counter.lastBuildDate
        $counter.lastFullRunDate = $counter.lastBuildDate
    }
}

$buildNumber = $counter.androidBuilds
Write-LogWithTime "Build #$buildNumber (Total: $($counter.totalBuilds))" "Cyan"

# Save counter
Write-CanonicalJsonFile -Path $counterPath -InputObject $counter
Write-LogWithTime "Build counter saved" "Green"

# Generate build name
$dateTimeStamp = Get-DateTimeStamp
$buildName = "Payanam_Android_$($buildNumber)_$dateTimeStamp"
Write-LogWithTime "Build Name: $buildName" "Cyan"

# Update version code in build.gradle.kts
$appBuildGradle = Get-Content "app/build.gradle.kts" -Raw
$appBuildGradle = $appBuildGradle -replace 'versionCode = \d+', "versionCode = $buildNumber"
$versionDisplayName = "#$buildNumber ($dateTimeStamp)"
$appBuildGradle = $appBuildGradle -replace 'versionName = \"[^\"]+\"', "versionName = `"$versionDisplayName`""
Set-Content "app/build.gradle.kts" $appBuildGradle -Encoding UTF8 -NoNewline
Write-LogWithTime "Updated versionCode to $buildNumber" "Green"
Write-LogWithTime "Updated versionName to '$versionDisplayName'" "Green"

# ============================================
# CLEAN (if requested)
# ============================================
if ($Clean)
{
    Write-LogWithTime "" "White"
    Write-LogWithTime "=== CLEANING ===" "Magenta"

    Write-LogWithTime "Stopping Gradle daemons..." "Yellow"
    if ($IsWindows) {
        cmd /c ".\gradlew.bat --stop 2>nul" | Out-Null
    } else {
        ./gradlew --stop 2>/dev/null | Out-Null
    }
    Start-Sleep -Seconds 2

    if (Test-Path "app/build")
    {
        Write-LogWithTime "Removing app/build..." "Yellow"
        Remove-Item -Recurse -Force "app/build" -ErrorAction SilentlyContinue
    }

    Write-LogWithTime "Running Gradle clean..." "Yellow"
    $cleanRun = Invoke-GradleStreaming -GradleArgs "clean" -StepLabel "Clean"
    if ($cleanRun.ExitCode -ne 0)
    {
        Write-LogWithTime "  ⚠️ Gradle clean had issues" "Yellow"
    }
    Write-LogWithTime "✅ Clean completed" "Green"
}

# ============================================
# BUILD APK
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "=== BUILDING APK ===" "Magenta"

$gradleTask = if ($Release)
{ "assembleRelease"
} else
{ "assembleDebug"
}

# SizeOptimized: shrink + obfuscate the debug APK for on-the-move downloads.
# -PdebugMinify=true makes the debug buildType enable R8 (minify + obfuscate).
# Mapping file is preserved after build for stack-trace retrace.
if ($SizeOptimized)
{
    $gradleTask += " -PdebugMinify=true"
    if (-not $Release)
    {
        Write-LogWithTime "SizeOptimized: R8 minify+obfuscate enabled for debug APK" "Yellow"
    }
}
if ($Universal)
{
    $gradleTask += " -PuniversalBuild=true"
    Write-LogWithTime "Universal: all ABIs included (arm64, arm32, x86, x86_64)" "Yellow"
}
Write-LogWithTime "Running: gradlew $gradleTask" "Cyan"

$buildRun = Invoke-GradleStreaming -GradleArgs "$gradleTask" -StepLabel "APK assembly"
if ($buildRun.ExitCode -ne 0)
{
    Write-LogWithTime "❌ Gradle build failed!" "Red"
    $buildRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Exit-WithCleanup 1
}
Write-LogWithTime "✅ APK build successful!" "Green"
if ($Universal) {
    Write-LogWithTime "ABI filter: none (universal build, all ABIs included)" "Yellow"
} else {
    Write-LogWithTime "ABI filter: arm64-v8a only (~11 MB saved vs universal)" "Yellow"
}

# Find APK
$apkDir = if ($Release)
{ "app/build/outputs/apk/release"
} else
{ "app/build/outputs/apk/debug"
}
$apkSourceName = if ($Release)
{ "app-release.apk"
} else
{ "app-debug.apk"
}
$apkSourcePath = Join-Path $apkDir $apkSourceName

if (-not (Test-Path $apkSourcePath))
{
    Write-LogWithTime "❌ APK not found at: $apkSourcePath" "Red"
    Exit-WithCleanup 1
}

# Copy to output with build name
if (-not (Test-Path $OutputDir))
{
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}
$apkFinalName = "$buildName.apk"
$apkFinalPath = Join-Path $OutputDir $apkFinalName
Copy-Item $apkSourcePath $apkFinalPath -Force

$apkSize = [math]::Round((Get-Item $apkFinalPath).Length / 1MB, 2)
Write-LogWithTime "APK: $apkFinalPath ($apkSize MB)" "Cyan"
if ($Release)
{
    Invoke-ReleaseSecurityVerification -ApkPath $apkFinalPath
}

# ── Publish to channel (default OFF; -Publish opts in) ────────────────────────
# Local iteration builds never publish: the home loop is edit → build → USB
# install → test → logs → repeat, and publish happens only AFTER the tested
# code is committed (publish-release.ps1 -ApkPath <tested.apk>, no rebuild).
# -Publish makes THIS build go to the channel too; branch guards inside
# publish-release.ps1 (feature/* → dev, dev → beta, main → stable) still apply.
if ($Publish)
{
    Write-LogWithTime "Publishing APK to channel (auto-detect from branch)..." "Magenta"
    & "$PSScriptRoot/publish-release.ps1" -ApkPath $apkFinalPath
    if ($LASTEXITCODE -ne 0)
    {
        Write-LogWithTime "❌ Publish failed!" "Red"
        Exit-WithCleanup 1
    }
    Write-LogWithTime "✅ Published to channel." "Green"
} else
{
    Write-LogWithTime "Skipping channel publish (local-only build; use -Publish to ship)." "Yellow"
}

# Preserve R8 mapping file (debug when SizeOptimized, release always) for
# stack-trace retrace: java -jar retrace.jar mapping.txt stacktrace.txt
if ($SizeOptimized -or $Release)
{
    # AGP 9.x writes mapping to build/intermediates; fall back to outputs for older AGP.
    $mappingCandidates = if ($Release)
    { @("app/build/intermediates/mapping/release/minifyReleaseWithR8/mapping.txt", "app/build/outputs/mapping/release/mapping.txt") }
    else
    { @("app/build/intermediates/mapping/debug/minifyDebugWithR8/mapping.txt", "app/build/outputs/mapping/debug/mapping.txt") }
    $mappingSourcePath = $mappingCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($mappingSourcePath)
    {
        $mappingFinalName = "$buildName.mapping.txt"
        $mappingFinalPath = Join-Path $OutputDir $mappingFinalName
        Copy-Item $mappingSourcePath $mappingFinalPath -Force
        Write-LogWithTime "Mapping: $mappingFinalPath (for stack-trace retrace)" "Cyan"
    }
    else
    {
        Write-LogWithTime "⚠️ Mapping file not found (searched: $($mappingCandidates -join ', '))" "Yellow"
    }
}

# ============================================
# INSTALL TO DEVICE
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "=== DEVICE INSTALLATION ===" "Magenta"

if (-not $runDeviceInstall)
{
    Write-LogWithTime "Skipping device install for profile '$effectiveProfile'." "Yellow"
    Write-LogWithTime "APK ready for manual install: $apkFinalPath" "Cyan"
} else
{
    $deviceConnected = $false
    try
    {
        $deviceList = adb devices 2>&1
        if ($deviceList -match "device$")
        {
            $deviceConnected = $true
            Write-LogWithTime "Android device detected" "Green"

            $currentPackage = adb shell pm list packages 2>&1 | Select-String "io.payanam"
            if ($currentPackage)
            {
                if ($CleanInstall)
                {
                    Write-LogWithTime "Uninstalling previous version..." "Yellow"
                    adb uninstall io.payanam 2>$null | Out-Null
                    Write-LogWithTime "Installing fresh..." "Yellow"
                    $installResult = adb install $apkFinalPath 2>&1
                } else
                {
                    Write-LogWithTime "Upgrading (keeping data)..." "Yellow"
                    $installResult = adb install -r -d $apkFinalPath 2>&1
                }
            } else
            {
                Write-LogWithTime "First time install..." "Yellow"
                $installResult = adb install $apkFinalPath 2>&1
            }

            if ($LASTEXITCODE -eq 0)
            {
                Write-LogWithTime "✅ APK installed successfully!" "Green"

                if ($runPostInstallVerification)
                {
                    Write-LogWithTime "" "White"
                    Write-LogWithTime "=== POST-INSTALL VERIFICATION ===" "Magenta"

                    $packageName = if ($buildType -eq "release")
                    { "io.payanam"
                    } else
                    { "io.payanam.debug"
                    }
                    $fullActivity = "io.payanam.MainActivity"

                    Write-LogWithTime "Stopping previous app instance..." "Cyan"
                    adb shell am force-stop $packageName 2>$null | Out-Null
                    Start-Sleep -Seconds 1

                    Write-LogWithTime "Launching app..." "Cyan"
                    $launchResult = adb shell am start -W -n $packageName/$fullActivity 2>&1
                    if ($LASTEXITCODE -ne 0)
                    {
                        Write-LogWithTime "  ❌ App launch command failed!" "Red"
                        Write-LogWithTime "  Launch output: $launchResult" "Red"
                        Exit-WithCleanup 1
                    }
                    Start-Sleep -Seconds 2

                    Write-LogWithTime "Checking if app is running..." "Cyan"
                    $runningPid = Wait-ForAppProcess -PackageName $packageName -MaxChecks 8 -SleepSeconds 1
                    if ($null -ne $runningPid)
                    {
                        Write-LogWithTime "  ✅ App is running (PID: $runningPid)" "Green"
                    } else
                    {
                        Write-LogWithTime "  ❌ App launch failed!" "Red"
                        Write-LogWithTime "" "White"
                        Write-LogWithTime "  📋 For debugging:" "Cyan"
                        Write-LogWithTime "  - Use Settings > Data Management > Export Latest Log" "Yellow"
                        Write-LogWithTime "  - Or export all logs to Documents/payanam/exported-logs/" "Yellow"
                        Exit-WithCleanup 1
                    }

                    Write-LogWithTime "Checking app responsiveness..." "Cyan"
                    Start-Sleep -Seconds 2
                    $pingResult = adb shell "am broadcast -a io.payanam.PING --include-stopped-packages" 2>&1
                    if ($pingResult -match "Broadcast completed")
                    {
                        Write-LogWithTime "  ✅ App is responsive" "Green"
                    } else
                    {
                        Write-LogWithTime "  ⚠️ App responsiveness check inconclusive (may still be initializing)" "Yellow"
                    }

                    if ($runDeviceSmoke)
                    {
                        Invoke-DeviceSmokePack -PackageName $packageName -ActivityName $fullActivity -BuildName $buildName
                    } else
                    {
                        Write-LogWithTime "Skipping device smoke pack for profile '$effectiveProfile'." "Yellow"
                    }

                    if ($Release)
                    {
                        Write-LogWithTime "Skipping Maestro UI flow for release build (flow targets io.payanam.debug)." "Yellow"
                    } elseif (-not $runMaestroFlow)
                    {
                        Write-LogWithTime "Skipping Maestro UI flow (profile/flag)." "Yellow"
                    } else
                    {
                        $maestroFlowPath = Join-Path "UI-test-Maestro" "p-k-t-1.yaml"
                        Invoke-MaestroFlow -FlowPath $maestroFlowPath -BuildName $buildName
                    }

                    Write-LogWithTime "✅ Post-install verification complete!" "Green"
                } else
                {
                    Write-LogWithTime "Skipping post-install verification for profile '$effectiveProfile'." "Yellow"
                }

            } else
            {
                if ($installResult -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                {
                    Write-LogWithTime "⚠️ APK installation skipped due to signature mismatch with installed app" "Yellow"
                    Write-LogWithTime "$installResult" "Yellow"
                    Write-LogWithTime "APK ready for manual install after uninstalling the incompatible app build: $apkFinalPath" "Cyan"
                } else
                {
                    Write-LogWithTime "❌ APK installation failed!" "Red"
                    Write-LogWithTime "$installResult" "Red"
                    Exit-WithCleanup 1
                }
            }
        } else
        {
            Write-LogWithTime "⚠️ No Android device connected" "Yellow"
            Write-LogWithTime "APK ready for manual install: $apkFinalPath" "Cyan"
        }
    } catch
    {
        if ($deviceConnected)
        {
            Write-LogWithTime "❌ Device verification failed: $($_.Exception.Message)" "Red"
            Exit-WithCleanup 1
        } else
        {
            Write-LogWithTime "⚠️ Device check failed: $($_.Exception.Message)" "Yellow"
            Write-LogWithTime "APK ready for manual install: $apkFinalPath" "Cyan"
        }
    }
}

# ============================================
# ARTIFACT RETENTION
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "=== ARTIFACT RETENTION ===" "Magenta"
try
{
    Invoke-BuildArtifactRetention -TargetPath $OutputDir -ItemType File -Filter "*.apk" -KeepCount $MaxApkArtifacts -CurrentBuildName $buildName
    Invoke-BuildArtifactRetention -TargetPath $OutputDir -ItemType File -Filter "*.mapping.txt" -KeepCount $MaxApkArtifacts -CurrentBuildName $buildName
    Invoke-BuildArtifactRetention -TargetPath "output/smoke" -ItemType Directory -KeepCount $MaxSmokeArtifacts -CurrentBuildName $buildName
    Invoke-BuildArtifactRetention -TargetPath "output/androidtest-failures" -ItemType Directory -KeepCount $MaxAndroidTestFailureArtifacts -CurrentBuildName $buildName
} catch
{
    Write-LogWithTime "  ⚠️ Artifact retention warning: $($_.Exception.Message)" "Yellow"
}

# ============================================
# BUILD SUMMARY
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "========================================" "Green"
Write-LogWithTime "  BUILD COMPLETED SUCCESSFULLY!" "Green"
Write-LogWithTime "========================================" "Green"
Write-LogWithTime "  Build Name: $buildName" "Cyan"
Write-LogWithTime "  Build #: $buildNumber" "Cyan"
Write-LogWithTime "  APK Size: $apkSize MB" "Cyan"
Write-LogWithTime "  APK Path: $apkFinalPath" "Cyan"
Write-LogWithTime "========================================" "Green"

Exit-WithCleanup 0
