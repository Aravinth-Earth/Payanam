# Android build script for Kotlin + Compose.
# Artefact: Payanam_android_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.apk
# Migrated from Capacitor/TypeScript to pure Kotlin

param(
    # -InstallApk <upgrade|clean> installs the built APK: 'upgrade' installs in place,
    # 'clean' uninstalls first. It runs no verification, smoke pack, tests or log pulls.
    [ValidateSet('upgrade', 'clean')]
    [string]$InstallApk,
    # -SmokePack runs the device smoke pack after an -InstallApk install. Requires -InstallApk.
    [switch]$SmokePack,
    # -RunInProcess runs the device-run androidTest tier. Independent of -InstallApk.
    [switch]$RunInProcess,
    [switch]$Release,
    [switch]$Arm64Only
)

$ErrorActionPreference = "Stop"

$MaxApkArtifacts = 5
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

# Shared release-security helpers (Get-AndroidBuildToolPath, Invoke-ReleaseSecurityVerification).
# Dot-sourced here AND in publish-release.ps1 — single implementation, never copied.
. "$PSScriptRoot/release-security.ps1"
. "$PSScriptRoot/lib/naming.ps1"

function Get-DateTimeStamp
{
    return (Get-Date -Format "yyyyMMdd_HHmmss")
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
    # Mapping sidecars keep a ".mapping" BaseName suffix that the name standard does not carry.
    $artifactStem = $artifactName -replace '\.mapping$', ''
    $timestamp = ""
    # Name standard only: Payanam_android_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.
    # Unparsed names (legacy/unrecognized) get an empty Timestamp, so they are the first retention candidates.
    if ($artifactStem -match '^Payanam_android_([^_]+)_([^_]+)_(debug|release)_(\d{8}_\d{6})$')
    {
        $timestamp = $Matches[4]
    }

    return [PSCustomObject]@{
        Item = $Item
        ArtifactName = $artifactName
        ArtifactStem = $artifactStem
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

    # KeepCount is the TOTAL number of artifacts retained, including the current build.
    # Both platforms use the same meaning, so 'keep 5' means 5 files on each.
    if ($KeepCount -lt 2)
    {
        throw "KeepCount must be >= 2 for artifact retention (current build + at least one older)."
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
        Write-LogWithTime "  ℹ️ Retention skip: $TargetPath has $($items.Count) item(s), keep=$KeepCount total" "DarkGray"
        return
    }

    $metadata = $items | ForEach-Object { Get-BuildArtifactMetadata -Item $_ }
    $protected = $metadata | Where-Object { $_.ArtifactStem -eq $CurrentBuildName }
    $candidates = $metadata | Where-Object { $_.ArtifactStem -ne $CurrentBuildName }
    # Newest name timestamp first; LastWriteUtc breaks ties and orders unparsed names last.
    $orderedCandidates = $candidates | Sort-Object `
    @{ Expression = "Timestamp"; Descending = $true }, `
    @{ Expression = "LastWriteUtc"; Descending = $true }

    # The current build is protected in addition to these, so keep KeepCount-1 candidates to land
    # on KeepCount files in total. Previously this kept KeepCount candidates + current, which meant
    # a configured 2 actually retained 3 - the count did not describe what happened.
    $toKeep = @($orderedCandidates | Select-Object -First ($KeepCount - 1))
    $toDelete = @($orderedCandidates | Select-Object -Skip ($KeepCount - 1))

    foreach ($entry in $toDelete)
    {
        if ($ItemType -eq "File")
        {
            Remove-Item -LiteralPath $entry.Item.FullName -Force -ErrorAction Stop
            Write-LogWithTime "  Deleted file: $($entry.Item.FullName)" "DarkGray"
            # A .sha256 only means anything next to the artifact it verifies. Leaving it behind is
            # how output/apks accumulated 65 orphaned checksums against 3 resident APKs.
            $sidecar = "$($entry.Item.FullName).sha256"
            if (Test-Path -LiteralPath $sidecar)
            {
                Remove-Item -LiteralPath $sidecar -Force -ErrorAction Stop
                Write-LogWithTime "  Deleted file: $sidecar (checksum)" "DarkGray"
            }
        } else
        {
            Remove-Item -LiteralPath $entry.Item.FullName -Recurse -Force -ErrorAction Stop
            Write-LogWithTime "  Deleted folder: $($entry.Item.FullName)" "DarkGray"
        }
    }

    $keptTotal = $toKeep.Count + $protected.Count
    Write-LogWithTime "  ✅ Retention applied on $TargetPath (deleted=$($toDelete.Count), kept=$keptTotal, protectedCurrent=$($protected.Count))" "Green"
}

function Remove-OrphanedArtifactChecksums
{
    param(
        [string]$TargetPath
    )

    if (-not (Test-Path $TargetPath))
    {
        return
    }

    # A .sha256 whose matching artifact is gone can never be verified against anything, so it is dead
    # weight that only grows. publish-release.ps1 writes these beside the APK, and build retention used
    # to leave them behind - output/apks had accumulated 65 orphaned .apk.sha256 files against 3
    # resident APKs before this sweep was added.
    $suffixLength = ".sha256".Length
    $orphans = @(Get-ChildItem -Path $TargetPath -File -Filter "*.sha256" | Where-Object {
        $owner = $_.FullName.Substring(0, $_.FullName.Length - $suffixLength)
        -not (Test-Path -LiteralPath $owner)
    })
    foreach ($orphan in $orphans)
    {
        Remove-Item -LiteralPath $orphan.FullName -Force -ErrorAction Stop
        Write-LogWithTime "  Deleted orphaned checksum: $($orphan.Name)" "DarkGray"
    }
    if ($orphans.Count -gt 0)
    {
        Write-LogWithTime "  ✅ Removed $($orphans.Count) orphaned checksum(s) from $TargetPath" "Green"
    }
}

function Invoke-DaemonCleanup
{
    Write-LogWithTime "Stopping Gradle/Kotlin daemons..." "Yellow"
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

# Probes a directory by writing $RequiredBytes of zeros into it and removing the probe file
# again. Returns $true only when the whole amount landed on disk. The write goes out in 1 MB
# chunks on purpose: allocating the whole 64 MB in memory would add the very pressure this
# check exists to detect.
function Test-TempDirectoryCapacity
{
    param(
        [string]$Directory,
        [long]$RequiredBytes
    )

    $probePath = Join-Path $Directory ("payanam-tmp-probe-{0}.bin" -f $PID)
    $chunk = [byte[]]::new(1048576)
    $written = 0L
    $usable = $false

    try
    {
        $stream = [System.IO.FileStream]::new(
            $probePath,
            [System.IO.FileMode]::Create,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None
        )
        try
        {
            while ($written -lt $RequiredBytes)
            {
                $remaining = $RequiredBytes - $written
                $chunkSize = if ($remaining -lt $chunk.Length) { [int]$remaining } else { $chunk.Length }
                $stream.Write($chunk, 0, $chunkSize)
                $written += $chunkSize
            }
            $stream.Flush()
        } finally
        {
            $stream.Dispose()
        }
        # A quota-exceeded write can fail part-way through and still leave a file behind, so the
        # size on disk is checked as well as the absence of an exception.
        $actualBytes = (Get-Item -LiteralPath $probePath).Length
        $usable = ($actualBytes -ge $RequiredBytes)
    } catch
    {
        $usable = $false
    } finally
    {
        if (Test-Path -LiteralPath $probePath)
        {
            Remove-Item -LiteralPath $probePath -Force -ErrorAction SilentlyContinue
        }
    }

    return $usable
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
        # Gradle writes error-level output (the FAILURE block, Kotlin `e:` compiler errors and
        # per-task failure diagnostics) to stderr. It used to be deleted unread below, which made
        # every failing Gradle invocation undiagnosable on Linux. Append it after stdout.
        $errContent = try { [System.IO.File]::ReadAllText($errFile) } catch { "" }
        $captured.Clear()
        foreach ($line in (($fullContent + "`n" + $errContent) -split "`n")) {
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

    Write-LogWithTime "✅ Device smoke pack passed." "Green"
}

# NOTE: Get-AndroidBuildToolPath and Invoke-ReleaseSecurityVerification live in
# build-tools/scripts/release-security.ps1 (dot-sourced at the top of this script) so
# publish-release.ps1 shares the exact same implementation instead of copying it.


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

function Get-StringTable
{
    param([string]$Path)

    if (-not (Test-Path $Path))
    {
        return $null
    }

    try
    {
        [xml]$doc = Get-Content $Path -Raw
    } catch
    {
        return $null
    }

    $table = @{}
    foreach ($node in $doc.resources.string)
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

        $table[$name] = ($rawValue.Trim() -replace '\s+', ' ')
    }

    return $table
}

function Test-StringParity
{
    # WHAT "PARITY" MEANS HERE, because the word gets used loosely and the resources hold no category
    # to hang it on. There are exactly FOUR shipping string tables - app/.../values + values-ta, and
    # core/strings/.../values + values-ta - and they are FLAT: every entry is a single <string
    # name="...">, with no plurals, no string-arrays, no translatable="false", and no "normal" versus
    # "promoted" split anywhere. "Promoted" is history rather than a category: it describes a string
    # that MOVED from app/... into :core:strings so the desktop renders the same words, and the only
    # place the word survives is the name of a test file.
    #
    # So this guard checks the two invariants those four tables must satisfy. Both have been broken.
    #
    #  RULE A - one language cannot lose a key. app EN and app TA must hold the SAME key set, and so
    #  must core EN and core TA. A key held by one language only is a screen that ships untranslated,
    #  and the count difference is the visible symptom of it.
    #  (app/src/debug/res and app/src/release/res are build-variant OVERLAYS with no Tamil counterpart
    #  by design, so they are deliberately not paired here rather than being silently skipped.)
    #
    #  RULE B - where the two tables hold the same key, they must agree. A key in both ships TWICE:
    #  once from the shared table the promoted ViewModels read, and once from the platform table the
    #  Android UI actually resolves. Measured 2026-09-29: 972 keys are held by both, in each language;
    #  re-measured 2026-10-04: 990 keys are held by both, still with 0 value differences. Treat the
    #  count as a dated snapshot - what the rule asserts is zero DIFFERENCES, not a particular count.
    #
    #  RULE B is the one that has actually failed in the field. settings_import_error_unreadable_db was
    #  reworded in the shared table while the app table kept "Export a plaintext backup from the source
    #  device, then re-import" - an instruction no Payanam version can satisfy, because Payanam's export
    #  IS the encrypted database file - so user-facing Android copy told users to do the impossible.
    #  The unit test that pinned the wording passed throughout, because it reads UiStrings.of(language),
    #  which is the shared table. A green test, a wrong APK: the PAIR is what needs checking, and only a
    #  guard that reads both files can check it.
    $pairs = @(
        @{ Name = "app"; En = "app/src/main/res/values/strings.xml"; Ta = "app/src/main/res/values-ta/strings.xml" },
        @{ Name = "core/strings"; En = "core/strings/src/main/res/values/strings.xml"; Ta = "core/strings/src/main/res/values-ta/strings.xml" }
    )

    $violations = @()
    $tables = @{}

    foreach ($pair in $pairs)
    {
        $enPath = Join-Path $projectRoot $pair.En
        $taPath = Join-Path $projectRoot $pair.Ta

        if (-not (Test-Path $enPath))
        {
            $violations += "[$($pair.Name)] EN table not found: $($pair.En)"
            continue
        }
        if (-not (Test-Path $taPath))
        {
            $violations += "[$($pair.Name)] TA table not found: $($pair.Ta)"
            continue
        }

        $enTable = Get-StringTable -Path $enPath
        $taTable = Get-StringTable -Path $taPath

        if ($null -eq $enTable -or $null -eq $taTable)
        {
            $violations += "[$($pair.Name)] could not parse one of the two language tables (invalid XML?)"
            continue
        }

        $tables["$($pair.Name)|en"] = $enTable
        $tables["$($pair.Name)|ta"] = $taTable

        $enOnly = @($enTable.Keys | Where-Object { -not $taTable.ContainsKey($_) } | Sort-Object)
        $taOnly = @($taTable.Keys | Where-Object { -not $enTable.ContainsKey($_) } | Sort-Object)

        if ($enOnly.Count -gt 0)
        {
            $shown = ($enOnly | Select-Object -First 10) -join ', '
            $suffix = if ($enOnly.Count -gt 10) { " (+$($enOnly.Count - 10) more)" } else { "" }
            $violations += "[$($pair.Name)] $($enOnly.Count) key(s) exist in EN but not in TA: $shown$suffix"
        }
        if ($taOnly.Count -gt 0)
        {
            $shown = ($taOnly | Select-Object -First 10) -join ', '
            $suffix = if ($taOnly.Count -gt 10) { " (+$($taOnly.Count - 10) more)" } else { "" }
            $violations += "[$($pair.Name)] $($taOnly.Count) key(s) exist in TA but not in EN: $shown$suffix"
        }
    }

    foreach ($language in @("en", "ta"))
    {
        $appTable = $tables["app|$language"]
        $coreTable = $tables["core/strings|$language"]

        if ($null -eq $appTable -or $null -eq $coreTable)
        {
            continue
        }

        $differing = 0
        foreach ($key in ($appTable.Keys | Sort-Object))
        {
            if (-not $coreTable.ContainsKey($key))
            {
                continue
            }

            if ($appTable[$key] -ne $coreTable[$key])
            {
                $differing++
                $appValue = $appTable[$key]
                $coreValue = $coreTable[$key]
                if ($appValue.Length -gt 110) { $appValue = $appValue.Substring(0, 110) + "..." }
                if ($coreValue.Length -gt 110) { $coreValue = $coreValue.Substring(0, 110) + "..." }

                $violations += "[$language] '$key' differs between the two tables:`n         app : $appValue`n         core: $coreValue"
            }
        }
    }

    if ($violations.Count -gt 0)
    {
        Write-LogWithTime "  ❌ String parity failed ($($violations.Count) issue(s)):" "Red"
        foreach ($violation in $violations)
        {
            Write-LogWithTime "     - $violation" "Red"
        }
        Write-LogWithTime "  EN and TA must hold the same keys in each table, and a key held by BOTH tables must carry the same value." "Yellow"
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
    # The canonical spec's state collection is `states`. There is no `nodes` key, and reading one was a
    # silent no-op: this script sets no Set-StrictMode, and @($null) is a ONE-element array, so a
    # missing key never produced an empty set. Filter the $null out so "absent" really means empty --
    # otherwise every node-level rule below (including the reviving BIOSEC check) covers nothing while
    # reporting green.
    $states = @(@($spec.states) | Where-Object { $null -ne $_ })
    $stateIds = @($states | ForEach-Object { [string]$_.id })
    $violations = @()

    if ($stateIds.Count -eq 0) {
        $violations += "DB flow spec exposes no states: the 'states' collection is missing, renamed or empty, so every node-level rule in this contract would silently cover nothing"
    }

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

    $legacyDisableNode = $states | Where-Object { [string]$_.id -eq "BIOSEC_DISABLE_VERIFY" }
    if ($legacyDisableNode.Count -gt 0)
    {
        $violations += "Legacy node BIOSEC_DISABLE_VERIFY must not exist in canonical flow"
    }

    # ---- The import door admits only the encrypted Payanam database -------------------------------
    # An unencrypted database is a file no Payanam version can produce, so accepting one on import would
    # make import the only route by which unencrypted data can enter the app. Both import paths refuse it
    # at runtime; these rules stop the canonical spec -- which is the flow's source of truth -- from
    # re-growing the path, and stop a check that covers nothing from reporting green.
    # One token set for all three rules. 'plain' is the load-bearing token: it covers both the PLAIN and the
    # PLAINTEXT spellings, and an earlier narrower set ('plaintext|...') silently missed state ids like
    # IMPORT_DB_PLAIN_SCREEN -- caught by build-tools/scripts/db-flow-guard-falsify.ps1, not by review.
    $plaintextTokens = 'plain|unencrypt|cleartext'
    $refusalStates = @('IMPORT_DB_UNUSABLE', 'IMPORT_DB_BAD')
    $encryptedVerifiedStates = @('IMPORT_ENCRYPTED_DETECTED', 'IMPORT_DB_ENC_SCREEN')
    # DECLARED, not merely matched. A relay state that merely looks like a door (IMPORT_DB_DETECT is a
    # process whose only outbound IS the next door) must not be treated as one, or the rules below are
    # unsatisfiable on the real spec.
    $doorIds = @('IMPORT_FORMAT_DETECT', 'IMPORT_DB_FORMAT')

    foreach ($door in $doorIds) {
        if (-not ($stateIds -contains $door)) {
            $violations += "Import door $door is missing from the DB flow spec; the classification step cannot be checked without it"
        }
    }
    foreach ($s in @($states | Where-Object { [string]$_.id -match '^IMPORT_.*(DETECT|FORMAT)$' -and [string]$_.type -eq 'decision' -and ($doorIds -notcontains [string]$_.id) })) {
        $violations += "State $([string]$s.id) looks like an import classification door but is not declared in this contract; declare it with its refusal branch, or rename it"
    }

    # Rule P -- no state named for a plaintext/cleartext import.
    foreach ($s in @($states | Where-Object { [string]$_.id -match '^IMPORT_' -and [string]$_.id -match $plaintextTokens })) {
        $violations += "State $([string]$s.id) is a plaintext import state; the import path accepts only the encrypted Payanam database"
    }

    # Rule G -- nothing lands on an import state on the strength of a plaintext guard, except a refusal.
    foreach ($t in @($transitions | Where-Object { [string]$_.to -match '^IMPORT_' -and [string]$_.guard -match $plaintextTokens })) {
        if ($refusalStates -notcontains [string]$t.to) {
            $violations += "Transition $([string]$t.id) routes a plaintext-guarded import step to $([string]$t.to); it must refuse -- allowed destinations: $($refusalStates -join ', ')"
        }
    }

    # Rule T -- a declared door may branch only to an encrypted-verified state or a refusal, and it must
    # keep a plaintext refusal branch. Without the second half, deleting the refusal leaves every rule green.
    foreach ($door in $doorIds) {
        if (-not ($stateIds -contains $door)) { continue }
        $doorOuts = @($transitions | Where-Object { [string]$_.from -eq $door })
        foreach ($t in $doorOuts) {
            if (($encryptedVerifiedStates -notcontains [string]$t.to) -and ($refusalStates -notcontains [string]$t.to)) {
                $violations += "Import door $door branches to $([string]$t.to) via $([string]$t.id); a door may branch only to an encrypted-verified state ($($encryptedVerifiedStates -join ', ')) or a refusal ($($refusalStates -join ', '))"
            }
        }
        $refusalOuts = @($doorOuts | Where-Object { [string]$_.guard -match $plaintextTokens -and ($refusalStates -contains [string]$_.to) })
        if ($refusalOuts.Count -eq 0) {
            $violations += "Import door $door has no plaintext refusal branch; the refusal was removed or re-worded, so the flow no longer states that an unencrypted database is refused"
        }
    }

    # Rule H -- the copy-side sinks exist, and only their encrypted-verified step reaches them. An
    # 'every inbound comes from X' loop over ZERO inbound covers nothing, hence the existence clause.
    $sinkPredecessors = @(
        [PSCustomObject]@{ Sink = 'IMPORT_DB_HANDOFF'; From = 'IMPORT_DB_ENC_PASS' }
        [PSCustomObject]@{ Sink = 'IMPORT_PREP_BOOT_STATE'; From = 'IMPORT_COPY_ENCRYPTED_RESULT' }
        [PSCustomObject]@{ Sink = 'IMPORT_DB_ENC_PASS'; From = 'IMPORT_DB_ENC_SCREEN' }
    )
    foreach ($pair in $sinkPredecessors) {
        $inbound = @($transitions | Where-Object { [string]$_.to -eq $pair.Sink })
        if ($inbound.Count -eq 0) {
            $violations += "Import sink $($pair.Sink) has no inbound transition at all; the reachability rule for it would cover nothing"
            continue
        }
        foreach ($t in $inbound) {
            if ([string]$t.from -ne $pair.From) {
                $violations += "Import sink $($pair.Sink) is reachable from $([string]$t.from) via $([string]$t.id); only $($pair.From) may reach it"
            }
        }
    }

    # Rule I -- every transition endpoint exists. The renderer prints dangling references as summary
    # text and throws only on invalid decisions and duplicate signatures, and no build script runs the
    # renderer, so this is the only place a transition pointing at a deleted state is caught.
    foreach ($t in $transitions) {
        foreach ($endpoint in @([string]$t.from, [string]$t.to)) {
            if (-not ($stateIds -contains $endpoint)) {
                $violations += "Transition $([string]$t.id) references state '$endpoint', which does not exist in the spec"
            }
        }
    }

    # Rule R -- a refusal must be escapable. A refusal with no outbound edge is a dead end the spec would
    # bless; the renderer's own dead-end check is only a summary line and is not run by any build script.
    foreach ($refusal in $refusalStates) {
        if (-not ($stateIds -contains $refusal)) { continue }
        $refusalOuts = @($transitions | Where-Object { [string]$_.from -eq $refusal })
        if ($refusalOuts.Count -eq 0) {
            $violations += "Refusal state $refusal has no outbound transition; a user refused here could not leave the screen"
        }
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

function Test-AppLoggerInstallContract
{
    # core/domain and core/scoring are platform-neutral modules: they log through AppLogger, which
    # defaults to a SILENT no-op. Unit tests cannot catch a missing install - a no-op logger is exactly
    # what a test expects - so if a platform entry point stops installing the real logger, every
    # diagnostic from those modules disappears with no error anywhere. This guard is what catches it.
    $entryPoints = @(
        "app/src/main/kotlin/io/payanam/PayanamApp.kt",
        "desktop/src/main/kotlin/io/payanam/desktop/DesktopMain.kt"
    )

    $missing = @()
    foreach ($relative in $entryPoints)
    {
        $full = Join-Path $projectRoot $relative
        if (-not (Test-Path $full))
        {
            Write-LogWithTime "  ❌ AppLogger install contract: entry point missing: $relative" "Red"
            return $false
        }
        if (-not (Select-String -Path $full -Pattern "^(?!\s*//).*AppLogger\.install\(" -Quiet))
        {
            $missing += $relative
        }
    }

    if ($missing.Count -gt 0)
    {
        Write-LogWithTime "  ❌ AppLogger install contract: no AppLogger.install(...) call found in:" "Red"
        foreach ($item in $missing)
        {
            Write-LogWithTime "       $item" "Red"
        }
        Write-LogWithTime "     Without that call AppLogger.current stays at the no-op default, so every" "Red"
        Write-LogWithTime "     core/domain and core/scoring log entry is discarded silently." "Red"
        return $false
    }

    return $true
}

function Test-CriticalLoggingCoverageContract
{
    $contract = @(
        [PSCustomObject]@{
            Path = "core/presentation/src/main/kotlin/io/payanam/presentation/database/DatabaseInitViewModel.kt"
            Patterns = @(
                "DatabaseInitViewModel\.checkDatabaseStatus",
                "DatabaseInitViewModel\.executeImportDatabase",
                "DatabaseInitViewModel\.resumeImportWithPassphrase",
                "import_reentry_rejected"
            )
        },
        [PSCustomObject]@{
            Path = "app/src/main/kotlin/io/payanam/feature/settings/SettingsEncryptedImportSupport.kt"
            Patterns = @(
                "SettingsViewModel\.importDatabase",
                "SettingsViewModel\.resumeImportWithPassphrase",
                "SettingsViewModel\.cancelImportPassphrase",
                "settings_import_reentry_rejected"
            )
        },
        [PSCustomObject]@{
            # The unlock ViewModel moved out of `:app` into `:core:presentation` so the Linux desktop can reuse
            # the unlock gate. The log keys are unchanged; only the file that carries them moved. When a
            # ViewModel is promoted like this, this path must follow it in the SAME change - the contract is
            # about the logging surviving the move, not about where the file lives.
            Path = "core/presentation/src/main/kotlin/io/payanam/presentation/database/DatabasePassphraseUnlockViewModel.kt"
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

# Reads an exact byte range from a seekable stream; a short read means the file changed mid-check.
function Read-ApkByteRange
{
    param(
        [System.IO.FileStream]$Stream,
        [long]$Offset,
        [int]$Count
    )

    $buffer = [byte[]]::new($Count)
    $Stream.Seek($Offset, [System.IO.SeekOrigin]::Begin) | Out-Null
    $read = 0
    while ($read -lt $Count)
    {
        $chunk = $Stream.Read($buffer, $read, $Count - $read)
        if ($chunk -le 0)
        {
            throw "Short read at offset $Offset ($read of $Count bytes)"
        }
        $read += $chunk
    }

    return , $buffer
}

# Sums each entry's declared local-header footprint and compares the total with the file size; the
# residue is bytes no central-directory entry and no declared APK region accounts for.
function Test-ApkArchiveFootprint
{
    param(
        [string]$ApkPath,
        [long]$MaxUnreferencedBytes = 4096
    )

    if (-not (Test-Path -LiteralPath $ApkPath))
    {
        Write-LogWithTime "  ❌ APK footprint guard: file not found: $ApkPath" "Red"
        return $false
    }

    $sourcePath = (Get-Item -LiteralPath $ApkPath).FullName
    $stream = [System.IO.FileStream]::new(
        $sourcePath,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    try
    {
        $fileSize = $stream.Length

        # The end-of-central-directory record sits behind a comment of up to 65535 bytes.
        $tailLength = [int][System.Math]::Min([long]65557, $fileSize)
        $tail = Read-ApkByteRange -Stream $stream -Offset ($fileSize - $tailLength) -Count $tailLength
        $eocdIndex = -1
        for ($i = $tailLength - 22; $i -ge 0; $i--)
        {
            if ($tail[$i] -ne 0x50 -or $tail[$i + 1] -ne 0x4B -or $tail[$i + 2] -ne 0x05 -or $tail[$i + 3] -ne 0x06)
            {
                continue
            }
            if (($i + 22 + [System.BitConverter]::ToUInt16($tail, $i + 20)) -le $tailLength)
            {
                $eocdIndex = $i
                break
            }
        }
        if ($eocdIndex -lt 0)
        {
            Write-LogWithTime "  ❌ APK footprint guard: no end-of-central-directory record in $ApkPath" "Red"
            return $false
        }

        $eocdSize = 22 + [System.BitConverter]::ToUInt16($tail, $eocdIndex + 20)
        $entryCount = [int][System.BitConverter]::ToUInt16($tail, $eocdIndex + 10)
        $centralDirectorySize = [long][System.BitConverter]::ToUInt32($tail, $eocdIndex + 12)
        $centralDirectoryOffset = [long][System.BitConverter]::ToUInt32($tail, $eocdIndex + 16)

        # ZIP64 keeps these three values in a separate record this guard does not read.
        if ($entryCount -eq 0xFFFF -or $centralDirectorySize -eq 0xFFFFFFFF -or $centralDirectoryOffset -eq 0xFFFFFFFF)
        {
            Write-LogWithTime "  ❌ APK footprint guard: ZIP64 archive, not supported: $ApkPath" "Red"
            return $false
        }
        if ($centralDirectorySize -gt [int]::MaxValue -or (($centralDirectoryOffset + $centralDirectorySize) -gt $fileSize))
        {
            Write-LogWithTime "  ❌ APK footprint guard: central directory lies outside the file: $ApkPath" "Red"
            return $false
        }

        $centralDirectory = Read-ApkByteRange -Stream $stream -Offset $centralDirectoryOffset -Count ([int]$centralDirectorySize)
        $entries = [System.Collections.Generic.List[object]]::new()
        $position = 0
        while ($position + 46 -le $centralDirectory.Length)
        {
            if ([System.BitConverter]::ToUInt32($centralDirectory, $position) -ne 0x02014b50)
            {
                break
            }
            $nameLength = [int][System.BitConverter]::ToUInt16($centralDirectory, $position + 28)
            $extraLength = [int][System.BitConverter]::ToUInt16($centralDirectory, $position + 30)
            $commentLength = [int][System.BitConverter]::ToUInt16($centralDirectory, $position + 32)
            $entries.Add([PSCustomObject]@{
                    Name = [System.Text.Encoding]::UTF8.GetString($centralDirectory, $position + 46, $nameLength)
                    Offset = [long][System.BitConverter]::ToUInt32($centralDirectory, $position + 42)
                    CompressedSize = [long][System.BitConverter]::ToUInt32($centralDirectory, $position + 20)
                })
            $position += 46 + $nameLength + $extraLength + $commentLength
        }
        if ($entries.Count -ne $entryCount)
        {
            Write-LogWithTime "  ❌ APK footprint guard: found $($entries.Count) of $entryCount central-directory records" "Red"
            return $false
        }

        $header = [byte[]]::new(30)
        $declaredBytes = 0L
        $declaredEnd = 0L
        foreach ($entry in $entries)
        {
            $stream.Seek($entry.Offset, [System.IO.SeekOrigin]::Begin) | Out-Null
            $read = 0
            while ($read -lt 30)
            {
                $chunk = $stream.Read($header, $read, 30 - $read)
                if ($chunk -le 0)
                {
                    Write-LogWithTime "  ❌ APK footprint guard: local header of '$($entry.Name)' is truncated at offset $($entry.Offset)" "Red"
                    return $false
                }
                $read += $chunk
            }
            if ([System.BitConverter]::ToUInt32($header, 0) -ne 0x04034b50)
            {
                Write-LogWithTime "  ❌ APK footprint guard: '$($entry.Name)' has no local file header at offset $($entry.Offset)" "Red"
                return $false
            }
            # Name and extra lengths come from the local header: zipalign pads the local extra field.
            $localFootprint = [long](30 + [System.BitConverter]::ToUInt16($header, 26) + [System.BitConverter]::ToUInt16($header, 28))
            $entry | Add-Member -NotePropertyName Footprint -NotePropertyValue ($localFootprint + $entry.CompressedSize)
            $declaredBytes += $entry.Footprint
            if (($entry.Offset + $entry.Footprint) -gt $declaredEnd)
            {
                $declaredEnd = $entry.Offset + $entry.Footprint
            }
        }

        # The APK signing block sits between the last entry and the central directory and is not a ZIP
        # entry; it counts as accounted-for bytes when its magic and size land clear of the last entry.
        $signingBlockSize = 0L
        if ($centralDirectoryOffset -ge 32)
        {
            $blockTail = Read-ApkByteRange -Stream $stream -Offset ($centralDirectoryOffset - 24) -Count 24
            if ([System.Text.Encoding]::ASCII.GetString($blockTail, 8, 16) -eq "APK Sig Block 42")
            {
                $reportedSize = [long][System.BitConverter]::ToUInt64($blockTail, 0) + 8
                if ($reportedSize -le $centralDirectoryOffset -and ($centralDirectoryOffset - $reportedSize) -ge $declaredEnd)
                {
                    $signingBlockSize = $reportedSize
                }
            }
        }

        $unreferencedBytes = $fileSize - $declaredBytes - $signingBlockSize - $centralDirectorySize - $eocdSize

        # First offset covered by no entry, and the two entries the region falls between.
        $coveredEnd = 0L
        $previousName = "<start of archive>"
        $gapStart = -1L
        $gapAfterEntry = $null
        $gapBeforeEntry = $null
        foreach ($entry in ($entries | Sort-Object Offset))
        {
            if ($entry.Offset -gt $coveredEnd -and $gapStart -lt 0)
            {
                $gapStart = $coveredEnd
                $gapAfterEntry = $previousName
                $gapBeforeEntry = $entry.Name
            }
            if (($entry.Offset + $entry.Footprint) -gt $coveredEnd)
            {
                $coveredEnd = $entry.Offset + $entry.Footprint
                $previousName = $entry.Name
            }
        }
        if ($gapStart -lt 0 -and (($declaredEnd + $signingBlockSize) -lt $centralDirectoryOffset))
        {
            $gapStart = $declaredEnd
            $gapAfterEntry = $previousName
            $gapBeforeEntry = "the central directory"
        }

        if ($unreferencedBytes -gt $MaxUnreferencedBytes)
        {
            $unreferencedMiB = [math]::Round($unreferencedBytes / 1MB, 2)
            Write-LogWithTime "  ❌ APK holds $unreferencedBytes unreferenced bytes ($unreferencedMiB MiB; limit $MaxUnreferencedBytes): $ApkPath" "Red"
            if ($gapStart -ge 0)
            {
                Write-LogWithTime "     Orphaned region starts at offset $gapStart, immediately after entry '$gapAfterEntry' and before '$gapBeforeEntry'." "Red"
            }
            Write-LogWithTime "     Delete app/build/outputs/apk and rebuild so the APK is written fresh instead of patched." "Yellow"
            return $false
        }

        Write-LogWithTime "  ℹ️ APK footprint: $($entries.Count) entries, $declaredBytes declared bytes, $signingBlockSize signing-block bytes, $centralDirectorySize central-directory bytes, $unreferencedBytes unreferenced bytes" "DarkGray"
        return $true
    } finally
    {
        $stream.Dispose()
    }
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
Write-LogWithTime "Daemon policy: STOP after build" "Yellow"

# Install is OPT-IN and value-driven: absent -InstallApk nothing probes for a device.
$installRequested = $PSBoundParameters.ContainsKey('InstallApk')
$runDeviceSmoke = $SmokePack.IsPresent
if ($runDeviceSmoke -and -not $installRequested)
{
    Write-LogWithTime "-SmokePack needs an install: pass -InstallApk upgrade or -InstallApk clean." "Red"
    Exit-WithCleanup 1
}

Write-LogWithTime "Device install: $(if ($installRequested) { $InstallApk } else { 'none (pass -InstallApk upgrade|clean)' })" "Yellow"

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
    $gradlePropertiesContent = Get-Content "gradle.properties" -Raw
    if ($gradlePropertiesContent -match 'org\.gradle\.java\.home=(.+)')
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

# Check 3: Temp directory capacity (must run BEFORE any Gradle invocation)
# Gradle, the Kotlin daemon, jpackage and dpkg-deb all stage large scratch files in the system
# temp directory. On a host where that directory is a small tmpfs with a per-user quota, those
# writes fail with "Disk quota exceeded" and the build dies for ENVIRONMENT reasons - there is
# no code defect anywhere to fix. Probe the temp directory ONCE, before Gradle starts, and move
# the whole run onto a bigger filesystem when it cannot hold the scratch space. On a healthy
# machine this changes NOTHING: no env var is touched, no daemon is stopped, and the only
# output is one line.
Write-LogWithTime "Checking temp directory capacity..." "Cyan"
$tempProbeRequiredBytes = 64 * 1024 * 1024
$tempProbeDirectory = if ($env:TMPDIR) { $env:TMPDIR } else { [System.IO.Path]::GetTempPath() }
if (Test-TempDirectoryCapacity -Directory $tempProbeDirectory -RequiredBytes $tempProbeRequiredBytes)
{
    Write-LogWithTime "  ✅ Temp directory usable: $tempProbeDirectory" "DarkGray"
} else
{
    $tempFallback = if ($env:PAYANAM_BUILD_TMP) { $env:PAYANAM_BUILD_TMP } else { "/var/tmp/payanam-build-tmp" }
    $tempFallbackReady = $false
    try
    {
        New-Item -ItemType Directory -Path $tempFallback -Force -ErrorAction Stop | Out-Null
        $tempFallbackReady = $true
    } catch
    {
        $tempFallbackReady = $false
    }
    if (-not $tempFallbackReady)
    {
        Write-LogWithTime "  ❌ Temp directory probe failed for $tempProbeDirectory and the fallback $tempFallback could not be created." "Red"
        Write-LogWithTime "     Set PAYANAM_BUILD_TMP to a writable directory on a larger filesystem, then re-run." "Yellow"
        Exit-WithCleanup 1
    }

    $env:TMPDIR = $tempFallback
    $javaTempOption = "-Djava.io.tmpdir=$tempFallback"
    if ($env:JAVA_TOOL_OPTIONS)
    {
        $env:JAVA_TOOL_OPTIONS = "$($env:JAVA_TOOL_OPTIONS) $javaTempOption"
    } else
    {
        $env:JAVA_TOOL_OPTIONS = $javaTempOption
    }

    # A daemon started before the probe still holds the OLD temp dir, so stop it: the next build
    # then starts a fresh daemon that picks the new one up.
    try
    {
        if ($IsWindows) {
            cmd /c ".\gradlew.bat --stop 2>nul" | Out-Null
        } else {
            ./gradlew --stop 2>/dev/null | Out-Null
        }
    } catch
    {
        # Best effort only: the daemon stop must never fail the build.
    }

    Write-LogWithTime "  ⚠️ Temp directory probe FAILED for $tempProbeDirectory (64 MB write); using $tempFallback for this run." "Yellow"
}

# Check 4: Core source files exist
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

# Declared version and artefact identity. gradle/version.properties is the only version source.
Write-LogWithTime "Reading declared version..." "Cyan"
$versionPropertiesPath = "gradle/version.properties"
if (-not (Test-Path $versionPropertiesPath))
{
    Write-LogWithTime "  ❌ $versionPropertiesPath not found - the version has one declared source." "Red"
    Exit-WithCleanup 1
}
$versionProperties = @{}
foreach ($versionLine in Get-Content $versionPropertiesPath)
{
    if ($versionLine -match '^\s*([A-Za-z][A-Za-z0-9_.]*)\s*=\s*(.+?)\s*$')
    {
        $versionProperties[$Matches[1]] = $Matches[2]
    }
}
$payanamVersion = $versionProperties["version"]
if ([string]::IsNullOrWhiteSpace($payanamVersion))
{
    Write-LogWithTime "  ❌ version= is missing from $versionPropertiesPath" "Red"
    Exit-WithCleanup 1
}

# Short sha for the artefact name; 'nogit' keeps source-tarball and extracted-zip builds working.
$shortSha = "nogit"
try
{
    $shaProbe = git rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($shaProbe))
    {
        $shortSha = $shaProbe.Trim()
    }
} catch
{
    $shortSha = "nogit"
}

$dateTimeStamp = Get-DateTimeStamp
$buildName = Get-PayanamGeneratedName -Prefix "Payanam" -Platform "Android" -Version $payanamVersion -Sha $shortSha -Type $buildType -Timestamp $dateTimeStamp
$gradleProps = "-PbuildPlatform=Android -PbuildTimestamp=$dateTimeStamp -PbuildShortSha=$shortSha"
Write-LogWithTime "  ✅ Version $payanamVersion, sha $shortSha" "Green"
Write-LogWithTime "Build Name: $buildName" "Cyan"

Write-LogWithTime "Checking Android guardrails (always-on static checks)... " "Cyan"
$moduleLineLimits = @{
    "app/src/main/kotlin" = 9700
    "core/common/src/main/kotlin" = 9400
    "core/database/src/main/kotlin" = 9650
    "core/domain/src/main/kotlin" = 9300
    "core/scoring/src/main/kotlin" = 9350
    # New JVM data-layer modules. Caps are initial generous bounds in the same spirit as the
    # existing entries (core/domain is 2,913 lines against a 9,300 cap), not tight limits:
    # core/database-model is 1,586 lines today, core/database-jvm 403 and about to grow as the 15
    # repository interfaces are implemented.
    "core/database-model/src/main/kotlin" = 4000
    "core/database-jvm/src/main/kotlin" = 8000
    # The shared UI layer. This one is DELIBERATELY large: it is where the whole shared UI is
    # heading (~50,700 lines of screens and view models), so the cap is a loose sanity bound
    # rather than a size target. 248 lines today.
    "core/ui/src/commonMain/kotlin" = 60000
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

Write-LogWithTime "Checking string parity (EN <-> TA keys, app <-> core/strings values)..." "Cyan"
if (-not (Test-StringParity))
{
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ String parity guard passed" "Green"

Write-LogWithTime "Checking DB auth flow security contract..." "Cyan"
if (-not (Test-DbAuthFlowSecurityContract))
{
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ DB auth flow security contract passed" "Green"

# The contract above is only worth having if its rules can FAIL: every rule is mutated and must fail.
Write-LogWithTime "Falsifying DB auth flow security contract (every rule must be able to fail)..." "Cyan"
& pwsh -NoProfile -File (Join-Path $PSScriptRoot "db-flow-guard-falsify.ps1") -SpecPath (Join-Path $projectRoot "docs/db/db-flow-boot-entry-flows.json")
if ($LASTEXITCODE -ne 0)
{
    Write-LogWithTime "  ❌ DB auth flow security contract is not falsifiable - a rule that cannot fail proves nothing" "Red"
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ DB auth flow security contract falsification passed" "Green"

Write-LogWithTime "Checking critical logging coverage contract..." "Cyan"
if (-not (Test-CriticalLoggingCoverageContract))
{
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ Critical logging coverage contract passed" "Green"

Write-LogWithTime "Checking AppLogger install contract..." "Cyan"
if (-not (Test-AppLoggerInstallContract))
{
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ AppLogger install contract passed" "Green"

# Check 5: Unit tests
# ── The task list is EXPLICIT, and it must name every module that has tests ─────────────────
# Named explicitly rather than derived because a derived list hides what runs. The cost is that a
# module can be added and never named - which is how :core:presentation (1194 tests, the largest
# module in the repo), :core:strings (11) and :core:backup (14) came to run in NO gate at all.
# The ratchet below compares this list against the modules that HAVE a test source set and fails on
# a gap, so the list cannot silently fall behind the tree again.
$testTaskList = @(
    ":app:testDebugUnitTest",
    ":core:backup:test",
    ":core:common:testDebugUnitTest",
    ":core:database:testDebugUnitTest",
    ":core:database-model:test",
    ":core:database-jvm:test",
    ":core:domain:test",
    ":core:presentation:test",
    ":core:scoring:test",
    ":core:shared:test",
    ":core:strings:test"
    # :core:ui is deliberately NOT named above. It is Kotlin Multiplatform with no withHostTest {},
    # so it has no Android host-test task (testDebugUnitTest does not exist in that project). Its
    # commonTest suite runs in the desktop phase via :core:ui:desktopTest, and because it has no
    # src/test directory the ratchet below does not require it to be named here.
)
$testModuleDirs = @(
    "app", "core/backup", "core/common", "core/database", "core/database-jvm", "core/domain",
    "core/presentation", "core/scoring", "core/shared", "core/strings"
)
$missingTestModules = @()
foreach ($moduleDir in $testModuleDirs)
{
    if (-not (Test-Path (Join-Path $moduleDir "src/test"))) { continue }
    $named = $false
    foreach ($task in $testTaskList) { if ($task -like ":$($moduleDir -replace '/', ':'):*") { $named = $true; break } }
    if (-not $named) { $missingTestModules += $moduleDir }
}
if ($missingTestModules.Count -gt 0)
{
    Write-LogWithTime "  ❌ Test ratchet: these modules have a src/test but are named by no task: $($missingTestModules -join ', ')" "Red"
    Write-LogWithTime "     Add each to `$testTaskList above - a module whose tests run in no gate ships unverified." "Red"
    Exit-WithCleanup 1
}

Write-LogWithTime "Running unit tests..." "Cyan"
$testRun = Invoke-GradleStreaming -GradleArgs "$($testTaskList -join ' ') $gradleProps" -StepLabel "Unit tests"
if ($testRun.ExitCode -ne 0)
{
    Write-LogWithTime "  ❌ Unit tests failed!" "Red"
    $testRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ Unit tests passed" "Green"

Write-LogWithTime "Running coverage check..." "Cyan"
$coverageRun = Invoke-GradleStreaming -GradleArgs "coverageCheck $gradleProps" -StepLabel "Coverage"
if ($coverageRun.ExitCode -ne 0)
{
    # FATAL. This previously warned and continued ("non-fatal on first public setup"), which let a
    # coverage floor sit below the real ratio indefinitely: core/domain was 0.95 against 0.78
    # measured, core/scoring 0.95 against 0.91, core/shared 0.94 against 0.93 - all failing
    # silently. A check that cannot fail is not a check.
    #
    # CAVEAT - this enforcement is only real for the JVM modules (core/shared, core/domain,
    # core/scoring, desktop). The two android.library modules still pass VACUOUSLY: core/common
    # (floor 0.95) and core/database (floor 0.50). Proven for core/common by forcing its floor to
    # 1.00 - unsatisfiable by real code - and watching it BUILD SUCCESSFUL. Their classDirectories
    # resolve empty, the same AGP-path defect the core/domain promotion exposed. Making those two
    # real is its own task; do not read a green coverage check as covering all six modules.
    Write-LogWithTime "  ❌ Coverage check failed - a module is below its floor. Fix before building." "Red"
    $coverageRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Exit-WithCleanup 1
} else {
    Write-LogWithTime "  ✅ Coverage check passed" "Green"
}

# Check 6: Static analysis (lint, detekt). FATAL: detekt enforces a KDoc baseline.
Write-LogWithTime "Running static analysis..." "Cyan"
$staticRun = Invoke-GradleStreaming -GradleArgs "staticAnalysisCheck $gradleProps" -StepLabel "Static analysis"
if ($staticRun.ExitCode -ne 0)
{
    Write-LogWithTime "  ❌ Static analysis failed (detekt/lint regression). Fix before building." "Red"
    Exit-WithCleanup 1
} else {
    Write-LogWithTime "  ✅ Static analysis passed" "Green"
}

# Spotless ratchet — blocks NEW formatting drift while the ~216 files of pre-existing drift stay
# untouched (see spotless-ratchet.ps1 for why this is a ratchet rather than a plain spotlessCheck).
# Invoked as a child process: the script does Set-Location, which must not leak into this session.
Write-LogWithTime "Running spotless ratchet..." "Cyan"
$ratchetOutput = & pwsh -NoProfile -File (Join-Path $PSScriptRoot "spotless-ratchet.ps1") 2>&1
$ratchetOutput | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0)
{
    Write-LogWithTime "  ❌ Spotless ratchet failed — changed .kt files are unformatted. Fix before building." "Red"
    Exit-WithCleanup 1
} else {
    Write-LogWithTime "  ✅ Spotless ratchet passed" "Green"
}

Write-LogWithTime "✅ All preflight checks passed!" "Green"

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

# Our own release builds must never emit an unsigned APK: the app module refuses to
# assemble a release variant without keystore-release.properties when this flag is set.
# Third-party source builds (F-Droid) do not pass it and are unaffected.
if ($Release)
{
    $gradleTask += " -Ppayanam.requireReleaseSigning=true"
    Write-LogWithTime "Release signing required: an unsigned release APK will not be produced." "Yellow"
}

if ($Arm64Only)
{
    $gradleTask += " -Parm64Only=true"
    Write-LogWithTime "Arm64-only: arm64-v8a ABI only (opt-in; default builds all ABIs)" "Yellow"
}

# AGP patches an existing APK in place, and a patched entry can leave the bytes it replaced orphaned.
# Clearing only this directory forces a new APK file; app/build and the mapping intermediates stay.
$apkOutputsDir = "app/build/outputs/apk"
if (Test-Path -LiteralPath $apkOutputsDir)
{
    try
    {
        Remove-Item -LiteralPath $apkOutputsDir -Recurse -Force -ErrorAction Stop
        Write-LogWithTime "Cleared $apkOutputsDir so the APK is written fresh, not patched." "DarkGray"
    } catch
    {
        Write-LogWithTime "  ❌ Could not clear $apkOutputsDir : $($_.Exception.Message)" "Red"
        Exit-WithCleanup 1
    }
}

Write-LogWithTime "Running: gradlew $gradleTask" "Cyan"

# In-process test mode builds unminified: a minified debug build breaks the androidTest APK.
if ($RunInProcess.IsPresent -and $gradleTask -eq "assembleDebug")
{
    $gradleTask = "$gradleTask -Ppayanam.noMinify=true"
    Write-LogWithTime "In-process mode: assembling unminified debug (minification breaks the androidTest APK)." "Yellow"
}

$buildRun = Invoke-GradleStreaming -GradleArgs "$gradleTask $gradleProps" -StepLabel "APK assembly"
if ($buildRun.ExitCode -ne 0)
{
    Write-LogWithTime "❌ Gradle build failed!" "Red"
    $buildRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Exit-WithCleanup 1
}
Write-LogWithTime "✅ APK build successful!" "Green"
if ($Arm64Only) {
    Write-LogWithTime "ABI filter: arm64-v8a only (~11 MB saved vs universal)" "Yellow"
} else {
    Write-LogWithTime "ABI filter: none (universal build, all ABIs included)" "Yellow"
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

Write-LogWithTime "Checking APK archive footprint (no unreferenced bytes)..." "Cyan"
if (-not (Test-ApkArchiveFootprint -ApkPath $apkSourcePath))
{
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ APK archive footprint guard passed" "Green"

# Copy to output with build name
if (-not (Test-Path "output/apks"))
{
    New-Item -ItemType Directory -Path "output/apks" -Force | Out-Null
}
$apkFinalName = "$buildName.apk"
$apkFinalPath = Join-Path "output/apks" $apkFinalName
Copy-Item $apkSourcePath $apkFinalPath -Force

$apkSize = [math]::Round((Get-Item $apkFinalPath).Length / 1MB, 2)
Write-LogWithTime "APK: $apkFinalPath ($apkSize MB)" "Cyan"

# Checksum sidecar written by the build itself; publish-release.ps1 verifies it instead of generating it.
# Format: uppercase hex, two spaces, bare filename.
$apkHash = (Get-FileHash -Path $apkFinalPath -Algorithm SHA256).Hash.ToUpper()
Set-Content -Path "$apkFinalPath.sha256" -Value "$apkHash  $apkFinalName" -Encoding UTF8
Write-LogWithTime "SHA256: $apkHash" "Cyan"
if ($Release)
{
    # Fail closed: a release artefact that cannot be verified fails the build.
    Invoke-ReleaseSecurityVerification -ApkPath $apkFinalPath -FailClosed
}

# Preserve R8 mapping file for stack-trace retrace:
# java -jar retrace.jar mapping.txt stacktrace.txt
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
        $mappingFinalPath = Join-Path "output/apks" $mappingFinalName
        Copy-Item $mappingSourcePath $mappingFinalPath -Force
        Write-LogWithTime "Mapping: $mappingFinalPath (for stack-trace retrace)" "Cyan"
    }
    else
    {
        Write-LogWithTime "⚠️ Mapping file not found (searched: $($mappingCandidates -join ', '))" "Yellow"
    }
}

# ============================================
# INSTALL TO DEVICE (-InstallApk <upgrade|clean>)
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "=== DEVICE INSTALLATION ===" "Magenta"

# The installed package name mirrors app/build.gradle.kts: the debug build type sets
# applicationIdSuffix = ".debug", so a debug build installs as io.payanam.debug and a release
# build as io.payanam. The broadcast action io.payanam.PING and io.payanam.MainActivity are
# not package names and stay untouched.
$installedPackageName = if ($buildType -eq "release")
{ "io.payanam"
} else
{ "io.payanam.debug"
}

if (-not $installRequested)
{
    Write-LogWithTime "No device queried: install is opt-in (-InstallApk upgrade|clean)." "Yellow"
    Write-LogWithTime "APK ready for manual install: $apkFinalPath" "Cyan"
    Write-LogWithTime "Target package for this $buildType build: $installedPackageName" "Cyan"
    Write-LogWithTime "  Upgrade, keeping data: adb install -r `"$apkFinalPath`"" "Cyan"
    Write-LogWithTime "  Clean install (removes app data): adb uninstall $installedPackageName, then adb install `"$apkFinalPath`"" "Cyan"
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

            if ($InstallApk -eq "clean")
            {
                Write-LogWithTime "Clean install: uninstalling $installedPackageName (app data is removed)..." "Yellow"
                adb uninstall $installedPackageName 2>$null | Out-Null
                $installResult = adb install $apkFinalPath 2>&1
                $installExitCode = $LASTEXITCODE
            } else
            {
                Write-LogWithTime "Upgrade install (keeping app data)..." "Yellow"
                $installResult = adb install -r $apkFinalPath 2>&1
                $installExitCode = $LASTEXITCODE
            }

            # Success needs BOTH: the tool reported `Success` and the exit code was zero.
            $installOutput = ($installResult | Out-String)
            $installReportedSuccess = ($installOutput -match '(?m)^\s*Success\s*$')
            if ($installReportedSuccess -and $installExitCode -eq 0)
            {
                Write-LogWithTime "✅ APK installed successfully!" "Green"

                # Only -SmokePack goes past the install itself; -InstallApk stops here.
                if ($runDeviceSmoke)
                {
                    Write-LogWithTime "" "White"
                    Write-LogWithTime "=== DEVICE SMOKE PACK ===" "Magenta"

                    $packageName = $installedPackageName
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
                        Write-LogWithTime "  - Or export all logs to Documents/Payanam/exported-logs/" "Yellow"
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

                    Invoke-DeviceSmokePack -PackageName $packageName -ActivityName $fullActivity -BuildName $buildName
                    Write-LogWithTime "✅ Device smoke pack complete!" "Green"
                }
            } else
            {
                if ($installResult -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                {
                    if ($runDeviceSmoke)
                    {
                        Write-LogWithTime "❌ Smoke pack was requested (-SmokePack) but the install was skipped (signature mismatch)." "Red"
                        Exit-WithCleanup 1
                    }
                    Write-LogWithTime "⚠️ APK installation skipped due to signature mismatch with installed app" "Yellow"
                    Write-LogWithTime "$installResult" "Yellow"
                    Write-LogWithTime "APK ready for manual install after uninstalling the incompatible app build: $apkFinalPath" "Cyan"
                    Write-LogWithTime "Incompatible package for this $buildType build: $installedPackageName (adb uninstall $installedPackageName)" "Cyan"
                } else
                {
                    Write-LogWithTime "❌ APK installation failed! (exit code: $installExitCode, package: $installedPackageName)" "Red"
                    Write-LogWithTime "Install output: $installOutput" "Red"
                    Exit-WithCleanup 1
                }
            }
        } else
        {
            if ($runDeviceSmoke)
            {
                Write-LogWithTime "❌ Smoke pack was requested (-SmokePack) but no Android device is present." "Red"
                Exit-WithCleanup 1
            }
            Write-LogWithTime "⚠️ Install requested but no Android device is connected." "Yellow"
            Write-LogWithTime "APK ready for manual install: $apkFinalPath" "Cyan"
        }
    } catch
    {
        if ($deviceConnected)
        {
            Write-LogWithTime "❌ Device verification failed: $($_.Exception.Message)" "Red"
            Exit-WithCleanup 1
        } elseif ($runDeviceSmoke)
        {
            Write-LogWithTime "❌ Smoke pack was requested (-SmokePack) but no device could be reached: $($_.Exception.Message)" "Red"
            Exit-WithCleanup 1
        } else
        {
            Write-LogWithTime "⚠️ Device check failed: $($_.Exception.Message)" "Yellow"
            Write-LogWithTime "APK ready for manual install: $apkFinalPath" "Cyan"
        }
    }
}

# ============================================
# DEVICE TEST TIER (-RunInProcess)
# ============================================
if ($RunInProcess.IsPresent)
{
    # In-process Compose UI test tier: the only E2E tier the pipeline runs.
    Write-LogWithTime "" "White"
    Write-LogWithTime "=== IN-PROCESS UI TESTS ===" "Magenta"
    Write-LogWithTime "Running :app:connectedDebugAndroidTest..." "Cyan"
    $inProcessRun = Invoke-GradleStreaming -GradleArgs ":app:connectedDebugAndroidTest -Ppayanam.noMinify=true $gradleProps" -StepLabel "In-process UI tests"
    if ($inProcessRun.ExitCode -ne 0)
    {
        Write-LogWithTime "  ❌ In-process tests failed — see app/build/reports/androidTests/connected/ and app/build/outputs/androidTest-results/connected/." "Red"
        $inProcessRun.Output | Select-Object -Last 40 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        Exit-WithCleanup 1
    }
    else
    {
        Write-LogWithTime "  ✅ In-process tests passed." "Green"
    }
}

# ============================================
# ARTIFACT RETENTION
# ============================================
Write-LogWithTime "" "White"
Write-LogWithTime "=== ARTIFACT RETENTION ===" "Magenta"
try
{
    Invoke-BuildArtifactRetention -TargetPath "output/apks" -ItemType File -Filter "*.apk" -KeepCount $MaxApkArtifacts -CurrentBuildName $buildName
    Remove-OrphanedArtifactChecksums -TargetPath "output/apks"
    Invoke-BuildArtifactRetention -TargetPath "output/apks" -ItemType File -Filter "*.mapping.txt" -KeepCount $MaxApkArtifacts -CurrentBuildName $buildName
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
Write-LogWithTime "  Version: $payanamVersion (sha: $shortSha)" "Cyan"
Write-LogWithTime "  APK Size: $apkSize MB" "Cyan"
Write-LogWithTime "  APK Path: $apkFinalPath" "Cyan"
Write-LogWithTime "========================================" "Green"

Exit-WithCleanup 0
