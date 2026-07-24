# Desktop Build Script for Payanam Windows app
# Format: Payanam_Windows_buildNumber_dateTimeStamp

param(
    [switch]$Clean,
    [switch]$CleanInstall,
    [switch]$SkipTests,
    [switch]$SkipInstall,
    [switch]$SkipInstallVerification,
    [switch]$KeepDaemons,
    [switch]$Release,
    [ValidateSet("auto", "quick", "normal", "full")] [string]$Profile = "auto",
    [string]$OutputDir = "output/windows",
    [string]$InstallDir = ""
)

$ErrorActionPreference = "Stop"

$MaxDesktopArtifacts = 2
$MaxWindowsSmokeArtifacts = 2

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
    $hoursSinceFull = Get-HoursSinceTimestamp -IsoDateTime $CounterState.lastWindowsFullRunDate
    $hoursSinceNormal = Get-HoursSinceTimestamp -IsoDateTime $CounterState.lastWindowsNormalRunDate

    if ($hoursSinceFull -ge $fullEveryHours)
    {
        return [PSCustomObject]@{
            Profile = "full"
            Reason = "hours_since_windows_full=$([math]::Round($hoursSinceFull, 2)) >= $fullEveryHours"
        }
    }
    if ($hoursSinceNormal -ge $normalEveryHours)
    {
        return [PSCustomObject]@{
            Profile = "normal"
            Reason = "hours_since_windows_normal=$([math]::Round($hoursSinceNormal, 2)) >= $normalEveryHours"
        }
    }
    return [PSCustomObject]@{
        Profile = "quick"
        Reason = "within_thresholds_windows_full=$([math]::Round($hoursSinceFull, 2))h normal=$([math]::Round($hoursSinceNormal, 2))h"
    }
}

function Get-BuildArtifactMetadata
{
    param([System.IO.FileSystemInfo]$Item)

    $artifactName = if ($Item.PSIsContainer)
    { $Item.Name
    } else
    { $Item.BaseName
    }
    $buildNumber = -1
    $timestamp = ""
    if ($artifactName -match '^Payanam_Windows_(\d+)_(\d{8}_\d{6})$')
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

function Update-FileContent
{
    param([string]$Path, [scriptblock]$Transform)
    $content = Get-Content $Path -Raw
    $updated = & $Transform $content
    Set-Content $Path $updated -Encoding UTF8 -NoNewline
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
        cmd /c ".\gradlew.bat --stop 2>nul" | Out-Null
    } catch
    {
        Write-LogWithTime "  ⚠️ Gradle daemon stop returned warning: $($_.Exception.Message)" "Yellow"
    }

    try
    {
        $javaProcesses = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'"
        $targetProcesses = $javaProcesses | Where-Object {
            $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon' -or
            $_.CommandLine -match 'org\.jetbrains\.kotlin\.daemon\.KotlinCompileDaemon'
        }
        foreach ($process in $targetProcesses)
        {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
        if ($targetProcesses.Count -gt 0)
        {
            Write-LogWithTime "  ✅ Stopped daemon JVM process(es): $($targetProcesses.Count)" "Green"
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

    $outFile = [System.IO.Path]::GetTempFileName()
    $errFile = [System.IO.Path]::GetTempFileName()
    $gradleProc = Start-Process -FilePath 'cmd' `
        -ArgumentList "/c .\gradlew.bat $GradleArgs --console=plain --info 2>&1" `
        -NoNewWindow -PassThru `
        -RedirectStandardOutput $outFile `
        -RedirectStandardError $errFile

    $reader = $null
    try
    {
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
                if ($line -match '^\> Task :|^BUILD |^FAILURE')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                } elseif ($line -match 'Kotlin compile|w: |e: ')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                } elseif ($line -match '^\s*(PASS|FAIL|Test |Executing test )' -or $line -match 'tests? (completed|started|passed|failed)')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                } elseif ($line -match 'Warning:|Error:|issues? found|Analyzing|analysis of')
                {
                    Write-Host "  $line" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                }
                $trimmed = $line.Trim()
                if ($trimmed.Length -gt 0)
                {
                    $lastHeartbeatLine = $trimmed
                }
            } else
            {
                if ($lastProgress.Elapsed.TotalSeconds -ge 15 -and $lastHeartbeatLine.Length -gt 0)
                {
                    Write-Host "  ... $lastHeartbeatLine" -ForegroundColor DarkGray
                    $lastProgress.Restart()
                }
                Start-Sleep -Milliseconds 500
            }
        }

        while ($null -ne ($line = $reader.ReadLine()))
        {
            $captured.Add($line)
            if ($line -match '^\> Task :|^BUILD |^FAILURE|Kotlin compile|w: |e: |Warning:|Error:|issues? found')
            {
                Write-Host "  $line" -ForegroundColor DarkGray
            }
        }
    } finally
    {
        if ($null -ne $reader)
        { $reader.Close()
        }
        Remove-Item -Path $outFile -Force -ErrorAction SilentlyContinue
        Remove-Item -Path $errFile -Force -ErrorAction SilentlyContinue
    }

    $gradleProc.WaitForExit()
    $procExitCode = $gradleProc.ExitCode
    $buildSucceeded = ($captured | Where-Object { $_ -match '^BUILD SUCCESSFUL' }).Count -gt 0
    $buildFailed = ($captured | Where-Object { $_ -match '^BUILD FAILED' }).Count -gt 0

    $exitCode = if ($buildSucceeded -and -not $buildFailed)
    {
        0
    } elseif ($buildFailed)
    {
        1
    } else
    {
        $procExitCode
    }

    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output = $captured
    }
}

function Get-DesktopStateRoot
{
    $localAppData = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localAppData))
    {
        $localAppData = Join-Path $HOME "AppData\Local"
    }
    return Join-Path $localAppData "Payanam"
}

function Get-DesktopInstallRegistryEntry
{
    $registryPaths = @(
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )

    foreach ($registryPath in $registryPaths)
    {
        $match = Get-ItemProperty -Path $registryPath -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -eq "PayanamDesktop" } |
            Select-Object -First 1
        if ($null -ne $match)
        {
            return $match
        }
    }

    return $null
}

function Get-DesktopInstallRoot
{
    if (-not [string]::IsNullOrWhiteSpace($InstallDir))
    {
        return $InstallDir
    }
    $registryEntry = Get-DesktopInstallRegistryEntry
    if ($null -ne $registryEntry -and -not [string]::IsNullOrWhiteSpace($registryEntry.InstallLocation))
    {
        return $registryEntry.InstallLocation.TrimEnd('\')
    }
    $localAppData = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localAppData))
    {
        $localAppData = Join-Path $HOME "AppData\Local"
    }
    return Join-Path $localAppData "PayanamDesktop"
}

function Get-DesktopVerificationRoot
{
    return Join-Path (Get-DesktopStateRoot) "verification"
}

function Get-DesktopInstallLogPath
{
    param([string]$BuildName)
    $verificationRoot = Get-DesktopVerificationRoot
    New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null
    return Join-Path $verificationRoot "installer-$BuildName.log"
}

function Resolve-InstalledDesktopExe
{
    $installRoot = Get-DesktopInstallRoot
    if ([string]::IsNullOrWhiteSpace($installRoot))
    {
        return $null
    }
    $exePath = Join-Path $installRoot "PayanamDesktop.exe"
    if (Test-Path $exePath)
    {
        return $exePath
    }
    return $null
}

function Get-InstalledDesktopUninstallCommand
{
    $match = Get-DesktopInstallRegistryEntry
    if ($null -ne $match -and -not [string]::IsNullOrWhiteSpace($match.UninstallString))
    {
        return $match.UninstallString
    }

    return $null
}

function Get-DesktopShortcutPath
{
    $startMenuRoot = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
    if (-not (Test-Path $startMenuRoot))
    {
        return $null
    }
    $shortcut = Get-ChildItem -Path $startMenuRoot -Recurse -Filter "PayanamDesktop.lnk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $shortcut)
    {
        return $null
    }
    return $shortcut.FullName
}

function Get-AllDesktopShortcutPaths
{
    $shortcutPaths = [System.Collections.Generic.List[string]]::new()

    $primaryShortcut = Get-DesktopShortcutPath
    if (-not [string]::IsNullOrWhiteSpace($primaryShortcut))
    {
        $shortcutPaths.Add($primaryShortcut)
    }

    $userRoots = Get-ChildItem "C:\Users" -Directory -ErrorAction SilentlyContinue
    foreach ($userRoot in $userRoots)
    {
        try
        {
            $startMenuRoot = Join-Path $userRoot.FullName "AppData\Roaming\Microsoft\Windows\Start Menu\Programs"
            if (-not (Test-Path $startMenuRoot))
            {
                continue
            }
            $foundShortcuts = Get-ChildItem -Path $startMenuRoot -Recurse -Filter "PayanamDesktop.lnk" -ErrorAction SilentlyContinue
            foreach ($shortcut in $foundShortcuts)
            {
                if (-not $shortcutPaths.Contains($shortcut.FullName))
                {
                    $shortcutPaths.Add($shortcut.FullName)
                }
            }
        } catch
        {
            Write-LogWithTime "  ⚠️ Skipping inaccessible Start menu root for $($userRoot.FullName)" "Yellow"
        }
    }

    return @($shortcutPaths)
}

function Get-DesktopShortcutTarget
{
    $shortcutPath = Get-DesktopShortcutPath
    if ([string]::IsNullOrWhiteSpace($shortcutPath))
    {
        return $null
    }
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    return [PSCustomObject]@{
        ShortcutPath = $shortcutPath
        TargetPath = $shortcut.TargetPath
        Arguments = $shortcut.Arguments
    }
}

function Repair-DesktopShortcuts
{
    param([string]$InstalledExePath)

    $shortcutPaths = Get-AllDesktopShortcutPaths
    $repaired = 0
    foreach ($shortcutPath in $shortcutPaths)
    {
        try
        {
            $shell = New-Object -ComObject WScript.Shell
            $shortcut = $shell.CreateShortcut($shortcutPath)
            if (($shortcut.TargetPath.TrimEnd('\')) -ne ($InstalledExePath.TrimEnd('\')))
            {
                $shortcut.TargetPath = $InstalledExePath
                $shortcut.Arguments = ""
                $shortcut.Save()
                $repaired++
                Write-LogWithTime "Repaired Start menu shortcut target: $shortcutPath" "Yellow"
            }
        } catch
        {
            Write-LogWithTime "  ⚠️ Shortcut repair warning for ${shortcutPath}: $($_.Exception.Message)" "Yellow"
        }
    }
    return $repaired
}

function Remove-DesktopInstallResidue
{
    param(
        [string]$CanonicalInstallRoot,
        [switch]$RemoveShortcut
    )

    $localAppData = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localAppData))
    {
        $localAppData = Join-Path $HOME "AppData\Local"
    }

    $residueRoots = @(
        Join-Path $localAppData "Programs\PayanamDesktop",
        Join-Path $localAppData "PayanamDesktop"
    ) | Select-Object -Unique

    foreach ($residueRoot in $residueRoots)
    {
        if ((Test-Path $residueRoot) -and (($residueRoot.TrimEnd('\')) -ne ($CanonicalInstallRoot.TrimEnd('\'))))
        {
            Write-LogWithTime "Removing stale desktop install residue: $residueRoot" "Yellow"
            Remove-Item -LiteralPath $residueRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    $shortcutPath = Get-DesktopShortcutPath
    if ($RemoveShortcut -and -not [string]::IsNullOrWhiteSpace($shortcutPath) -and (Test-Path $shortcutPath))
    {
        Write-LogWithTime "Removing stale Start menu shortcut before installer refresh: $shortcutPath" "Yellow"
        Remove-Item -LiteralPath $shortcutPath -Force -ErrorAction SilentlyContinue
    }
}

function Get-RunningDesktopProcesses
{
    return @(Get-Process -Name "PayanamDesktop" -ErrorAction SilentlyContinue)
}

function Get-DesktopSingleInstanceMetadataPath
{
    return Join-Path (Join-Path (Get-DesktopStateRoot) "runtime") "desktop-single-instance.properties"
}

function Get-DesktopSingleInstanceMetadata
{
    $metadataPath = Get-DesktopSingleInstanceMetadataPath
    if (-not (Test-Path $metadataPath))
    {
        return $null
    }

    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $metadataPath -ErrorAction SilentlyContinue)
    {
        if ($line -match '^(?<key>[^=]+)=(?<value>.*)$')
        {
            $properties[$matches.key.Trim()] = $matches.value.Trim()
        }
    }

    if ($properties.Count -eq 0)
    {
        return $null
    }

    return [PSCustomObject]@{
        Path = $metadataPath
        ProcessId = $properties["processId"]
        BuildName = $properties["buildName"]
        VersionDisplayName = $properties["versionDisplayName"]
        AcquiredAt = $properties["acquiredAt"]
        LogFilePath = $properties["logFilePath"]
        ExecutablePath = $properties["executablePath"]
    }
}

function Invoke-DesktopArtifactVerification
{
    param(
        [string]$ExePath,
        [string]$MsiPath
    )

    if (-not (Test-Path $ExePath))
    {
        throw "Desktop EXE artifact missing at $ExePath"
    }
    if (-not (Test-Path $MsiPath))
    {
        throw "Desktop MSI artifact missing at $MsiPath"
    }
    $exeSizeMb = [math]::Round((Get-Item $ExePath).Length / 1MB, 2)
    $msiSizeMb = [math]::Round((Get-Item $MsiPath).Length / 1MB, 2)
    if ($exeSizeMb -le 0 -or $msiSizeMb -le 0)
    {
        throw "Desktop packaged artifacts look empty (exe=${exeSizeMb}MB, msi=${msiSizeMb}MB)."
    }
    Write-LogWithTime "Packaged EXE: $ExePath ($exeSizeMb MB)" "Cyan"
    Write-LogWithTime "Packaged MSI: $MsiPath ($msiSizeMb MB)" "Cyan"
}

function Invoke-DesktopInstall
{
    param(
        [string]$MsiPath,
        [bool]$CleanInstallRequested,
        [string]$BuildName
    )

    if (-not (Test-Path $MsiPath))
    {
        throw "Desktop MSI installer not found at $MsiPath"
    }

    $stateRoot = Get-DesktopStateRoot
    $installLogPath = Get-DesktopInstallLogPath -BuildName $BuildName

    $installRoot = Get-DesktopInstallRoot
    $runningProcesses = Get-RunningDesktopProcesses
    if ($runningProcesses.Count -gt 0)
    {
        $installedExe = Resolve-InstalledDesktopExe
        return [PSCustomObject]@{
            InstallPerformed = $false
            InstallSkipped = $true
            SkipReason = "Desktop app already running (count=$($runningProcesses.Count)). Please close it before installing the new build."
            InstalledExePath = $installedExe
        }
    }

    if ($CleanInstallRequested)
    {
        Write-LogWithTime "Clean install requested: uninstalling previous Windows install and clearing local desktop state..." "Yellow"
        $uninstallCommand = Get-InstalledDesktopUninstallCommand
        if (-not [string]::IsNullOrWhiteSpace($uninstallCommand))
        {
            $normalizedCommand = $uninstallCommand.Trim()
            if ($normalizedCommand -match 'MsiExec\.exe')
            {
                $normalizedCommand = $normalizedCommand -replace '(?i)/I', '/X'
                if ($normalizedCommand -notmatch '(?i)/qn')
                {
                    $normalizedCommand = "$normalizedCommand /qn /norestart"
                }
                Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $normalizedCommand -Wait -NoNewWindow | Out-Null
            }
        }
        if (Test-Path $stateRoot)
        {
            Remove-Item -LiteralPath $stateRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        Remove-DesktopInstallResidue -CanonicalInstallRoot $installRoot -RemoveShortcut
    } elseif ($null -ne (Resolve-InstalledDesktopExe))
    {
        Write-LogWithTime "Upgrading existing Windows app install (keeping data)..." "Yellow"
    } else
    {
        Write-LogWithTime "First time Windows app install..." "Yellow"
        Remove-DesktopInstallResidue -CanonicalInstallRoot $installRoot -RemoveShortcut
    }

    $installLogDirectory = Split-Path -Parent $installLogPath
    if (-not (Test-Path $installLogDirectory))
    {
        New-Item -ItemType Directory -Path $installLogDirectory -Force | Out-Null
    }

    $msiArguments = @(
        "/i"
        "`"$MsiPath`""
        "/qn"
        "/norestart"
        "/L*v"
        "`"$installLogPath`""
    )
    $installProcess = Start-Process -FilePath "msiexec.exe" -ArgumentList $msiArguments -Wait -PassThru -NoNewWindow
    if ($installProcess.ExitCode -ne 0)
    {
        throw "MSI installation failed with exit code $($installProcess.ExitCode). See $installLogPath"
    }

    $installedExe = Resolve-InstalledDesktopExe
    if ([string]::IsNullOrWhiteSpace($installedExe))
    {
        throw "MSI installation completed but installed desktop executable could not be located at the canonical install root: $installRoot"
    }
    Remove-DesktopInstallResidue -CanonicalInstallRoot $installRoot
    $repairedShortcutCount = Repair-DesktopShortcuts -InstalledExePath $installedExe
    if ($repairedShortcutCount -gt 0)
    {
        Write-LogWithTime "Updated Start menu shortcut target(s): $repairedShortcutCount" "Green"
    }

    Write-LogWithTime "✅ Desktop MSI installed successfully" "Green"
    Write-LogWithTime "Installed EXE: $installedExe" "DarkGray"
    Write-LogWithTime "Installer log: $installLogPath" "DarkGray"
    return [PSCustomObject]@{
        InstallPerformed = $true
        InstallSkipped = $false
        SkipReason = ""
        InstalledExePath = $installedExe
    }
}

function Wait-ForDesktopProcess
{
    param(
        [int]$ProcessId,
        [int]$MaxChecks = 10,
        [int]$SleepSeconds = 1
    )

    for ($attempt = 1; $attempt -le $MaxChecks; $attempt++)
    {
        $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if ($null -ne $process)
        {
            return $process
        }
        Start-Sleep -Seconds $SleepSeconds
    }

    return $null
}

function Wait-ForLatestDesktopLog
{
    param(
        [string]$LogsDirectory,
        [datetime]$BaselineTime,
        [int]$MaxChecks = 12,
        [int]$SleepSeconds = 1
    )

    for ($attempt = 1; $attempt -le $MaxChecks; $attempt++)
    {
        if (Test-Path $LogsDirectory)
        {
            $latest = Get-ChildItem -Path $LogsDirectory -File -Filter "*.log" | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
            if ($null -ne $latest -and $latest.LastWriteTime -ge $BaselineTime)
            {
                return $latest
            }
        }
        Start-Sleep -Seconds $SleepSeconds
    }

    return $null
}

function Invoke-DesktopSmokePack
{
    param(
        [string]$InstalledExePath,
        [string]$BuildName
    )

    Write-LogWithTime "" "White"
    Write-LogWithTime "=== WINDOWS SMOKE PACK ===" "Magenta"

    if (-not (Test-Path $InstalledExePath))
    {
        throw "Installed desktop executable not found at $InstalledExePath"
    }

    $smokeDir = Join-Path (Get-DesktopVerificationRoot) $BuildName
    New-Item -ItemType Directory -Path $smokeDir -Force | Out-Null

    $logsDirectory = Join-Path (Get-DesktopStateRoot) "logs"
    $baselineTime = Get-Date
    $runningProcessesBeforeLaunch = Get-RunningDesktopProcesses
    if ($runningProcessesBeforeLaunch.Count -gt 0)
    {
        Write-LogWithTime "Detected running desktop app instance(s) before smoke launch: $($runningProcessesBeforeLaunch.Count)" "Yellow"
        Write-LogWithTime "Proceeding without force-closing; single-instance handling must be surfaced by the app itself." "Yellow"
    }

    Write-LogWithTime "Launching installed desktop app..." "Cyan"
    $startedProcess = Start-Process -FilePath $InstalledExePath -PassThru
    $runningProcess = Wait-ForDesktopProcess -ProcessId $startedProcess.Id -MaxChecks 10 -SleepSeconds 1
    if ($null -eq $runningProcess -and $runningProcessesBeforeLaunch.Count -eq 0)
    {
        throw "Installed desktop app exited before runtime verification completed."
    }
    if ($null -ne $runningProcess)
    {
        Write-LogWithTime "  ✅ Installed app launch is active (PID: $($runningProcess.Id))" "Green"
    } else
    {
        Write-LogWithTime "  ℹ️ Secondary launch exited quickly while another instance was already running." "Yellow"
    }

    $metadataAfterLaunch = $null
    for ($attempt = 1; $attempt -le 12; $attempt++)
    {
        $metadataAfterLaunch = Get-DesktopSingleInstanceMetadata
        if ($null -ne $metadataAfterLaunch -and -not [string]::IsNullOrWhiteSpace($metadataAfterLaunch.LogFilePath) -and (Test-Path $metadataAfterLaunch.LogFilePath))
        {
            break
        }
        Start-Sleep -Seconds 1
    }

    $logSourcePath = $null
    if ($null -ne $metadataAfterLaunch -and -not [string]::IsNullOrWhiteSpace($metadataAfterLaunch.LogFilePath) -and (Test-Path $metadataAfterLaunch.LogFilePath))
    {
        $logSourcePath = $metadataAfterLaunch.LogFilePath
    } else
    {
        $latestLog = Wait-ForLatestDesktopLog -LogsDirectory $logsDirectory -BaselineTime $baselineTime -MaxChecks 12 -SleepSeconds 1
        if ($null -ne $latestLog)
        {
            $logSourcePath = $latestLog.FullName
        }
    }

    if ([string]::IsNullOrWhiteSpace($logSourcePath))
    {
        throw "Desktop session log could not be resolved after launch."
    }

    $logCopyPath = Join-Path $smokeDir "desktop-session.log"
    Copy-Item -LiteralPath $logSourcePath -Destination $logCopyPath -Force
    Write-LogWithTime "  ✅ Desktop session log captured: $logCopyPath" "Green"

    $logContents = Get-Content $logSourcePath -Raw
    if ($logContents -notmatch 'Desktop application starting')
    {
        throw "Desktop session log does not contain the startup marker."
    }
    $observedBuildNameMatch = [regex]::Match($logContents, 'buildName="([^"]+)"')
    $observedBuildName = $observedBuildNameMatch.Groups[1].Value
    if ($logContents -notmatch "buildName=""$([regex]::Escape($BuildName))""")
    {
        if ($runningProcessesBeforeLaunch.Count -gt 0 -or ($null -ne $metadataAfterLaunch -and -not [string]::IsNullOrWhiteSpace($metadataAfterLaunch.BuildName)))
        {
            $reportedBuildName = if ([string]::IsNullOrWhiteSpace($observedBuildName))
            { $metadataAfterLaunch.BuildName
            } else
            { $observedBuildName
            }
            throw "A running desktop instance (build '$reportedBuildName') prevented verification of installed build '$BuildName'. Close the existing app manually and rerun full verification."
        }
        throw "Desktop session log does not match the expected build name $BuildName."
    }
    $expectedBuildNumber = [regex]::Match($BuildName, '^Payanam_Windows_(\d+)_').Groups[1].Value
    if ([string]::IsNullOrWhiteSpace($expectedBuildNumber))
    {
        throw "Could not extract expected Windows build number from $BuildName."
    }
    if ($logContents -notmatch "platformBuildNumber=$expectedBuildNumber")
    {
        throw "Desktop session log does not contain the expected platform build number $expectedBuildNumber."
    }

    $shortcutPaths = Get-AllDesktopShortcutPaths
    if ($shortcutPaths.Count -eq 0)
    {
        throw "Desktop Start menu shortcut was not found after installer run."
    }
    foreach ($shortcutPath in $shortcutPaths)
    {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($shortcutPath)
        if (($shortcut.TargetPath.TrimEnd('\')) -ne ($InstalledExePath.TrimEnd('\')))
        {
            throw "Desktop Start menu shortcut target mismatch. Shortcut=$shortcutPath Target=$($shortcut.TargetPath) Expected=$InstalledExePath"
        }
    }

    Write-LogWithTime "  ✅ Desktop startup log marker confirmed" "Green"
    Write-LogWithTime "  ✅ Desktop build identity matches installed build $expectedBuildNumber" "Green"
    Write-LogWithTime "  ✅ Start menu shortcut(s) point to installed EXE" "Green"
}

Write-LogWithTime "========================================" "Cyan"
Write-LogWithTime "  Payanam Desktop Build (Compose JVM)" "Cyan"
Write-LogWithTime "========================================" "Cyan"

$buildType = if ($Release)
{ "release"
} else
{ "debug"
}
Write-LogWithTime "Build Type: $buildType" "Yellow"

if ($Clean)
{
    Write-LogWithTime "Clean mode: will remove previous desktop build outputs" "Yellow"
}
if ($CleanInstall)
{
    Write-LogWithTime "Clean install: will remove previous Windows install and local data" "Yellow"
}
if ($SkipInstall)
{
    Write-LogWithTime "Install stage: skipped by flag" "Yellow"
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
if (-not (Get-Member -InputObject $counter -Name "windowsBuilds" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "windowsBuilds" -Value 0
}
if (-not (Get-Member -InputObject $counter -Name "androidBuilds" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "androidBuilds" -Value 0
}
if (-not (Get-Member -InputObject $counter -Name "lastWindowsQuickRunDate" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "lastWindowsQuickRunDate" -Value ""
}
if (-not (Get-Member -InputObject $counter -Name "lastWindowsNormalRunDate" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "lastWindowsNormalRunDate" -Value ""
}
if (-not (Get-Member -InputObject $counter -Name "lastWindowsFullRunDate" -MemberType NoteProperty))
{
    $counter | Add-Member -MemberType NoteProperty -Name "lastWindowsFullRunDate" -Value ""
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

$runDesktopTests = $false
$runPostInstallVerification = $false
$runSmokePack = $false
$runFormatting = $true
$runDesktopInstall = $true
switch ($effectiveProfile)
{
    "quick"
    {
        $runDesktopTests = $false
        $runFormatting = $false
    }
    "normal"
    {
        $runDesktopTests = $true
    }
    "full"
    {
        $runDesktopTests = $true
        $runPostInstallVerification = $true
        $runSmokePack = $true
    }
}
if ($SkipTests)
{
    $runDesktopTests = $false
}
if ($SkipInstallVerification)
{
    $runPostInstallVerification = $false
    $runSmokePack = $false
}
if ($SkipInstall)
{
    $runDesktopInstall = $false
}

Write-LogWithTime "Desktop formatting: $(if ($runFormatting) { 'enabled' } else { 'disabled' })" "Yellow"
Write-LogWithTime "Desktop install: $(if ($runDesktopInstall) { 'enabled' } else { 'disabled' })" "Yellow"

Write-LogWithTime "" "White"
Write-LogWithTime "=== PREFLIGHT CHECKS ===" "Magenta"
Write-LogWithTime "Preflight order: environment -> structure -> installer/runtime inputs -> verification selection." "DarkGray"

Write-LogWithTime "Checking Java version..." "Cyan"
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME))
{
    Write-LogWithTime "  ⚠️ JAVA_HOME not set; Gradle may fall back to system Java." "Yellow"
} else
{
    Write-LogWithTime "  JAVA_HOME: $($env:JAVA_HOME)" "DarkGray"
}

Write-LogWithTime "Checking Gradle wrapper..." "Cyan"
if (-not (Test-Path ".\gradlew.bat"))
{
    Write-LogWithTime "  ❌ Gradle wrapper missing (.\\gradlew.bat)" "Red"
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ Gradle wrapper present" "Green"

Write-LogWithTime "Checking desktop source structure..." "Cyan"
$requiredPaths = @(
    "desktop/build.gradle.kts",
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopMain.kt",
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt",
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopBuildInfo.kt"
)
$missingPaths = @($requiredPaths | Where-Object { -not (Test-Path $_) })
if ($missingPaths.Count -gt 0)
{
    Write-LogWithTime "  ❌ Missing desktop source files:" "Red"
    foreach ($missing in $missingPaths)
    {
        Write-LogWithTime "     - $missing" "Red"
    }
    Exit-WithCleanup 1
}
Write-LogWithTime "  ✅ Desktop source structure present" "Green"

$desktopInstallRoot = Get-DesktopInstallRoot
$desktopStateRoot = Get-DesktopStateRoot
Write-LogWithTime "Checking desktop state roots..." "Cyan"
Write-LogWithTime "  Install root: $desktopInstallRoot" "DarkGray"
Write-LogWithTime "  App-data root: $desktopStateRoot" "DarkGray"
Write-LogWithTime "  ✅ Desktop install/runtime paths resolved" "Green"

Write-LogWithTime "Selecting desktop verification scope..." "Cyan"
Write-LogWithTime "  Tests: $(if ($runDesktopTests) { 'enabled' } else { 'disabled' })" "DarkGray"
Write-LogWithTime "  Install verification: $(if ($runPostInstallVerification) { 'enabled' } else { 'disabled' })" "DarkGray"
Write-LogWithTime "  Smoke pack: $(if ($runSmokePack) { 'enabled' } else { 'disabled' })" "DarkGray"
Write-LogWithTime "✅ All preflight checks passed!" "Green"

$counter.totalBuilds++
$counter.windowsBuilds++
$counter.lastBuildDate = Get-LocalDateTime
switch ($effectiveProfile)
{
    "quick"
    { $counter.lastWindowsQuickRunDate = $counter.lastBuildDate
    }
    "normal"
    {
        $counter.lastWindowsQuickRunDate = $counter.lastBuildDate
        $counter.lastWindowsNormalRunDate = $counter.lastBuildDate
    }
    "full"
    {
        $counter.lastWindowsQuickRunDate = $counter.lastBuildDate
        $counter.lastWindowsNormalRunDate = $counter.lastBuildDate
        $counter.lastWindowsFullRunDate = $counter.lastBuildDate
    }
}

$platformBuildNumber = [int]$counter.windowsBuilds
$overallBuildNumber = [int]$counter.totalBuilds
$dateTimeStamp = Get-DateTimeStamp
$buildName = "Payanam_Windows_${platformBuildNumber}_${dateTimeStamp}"
$versionDisplayName = "#W${platformBuildNumber} (${dateTimeStamp})"
$packageVersion = "0.1.$platformBuildNumber"

Write-LogWithTime "" "White"
Write-LogWithTime "=== BUILD COUNTER ===" "Magenta"
Write-LogWithTime "Windows Build #$platformBuildNumber (Total: $overallBuildNumber)" "Cyan"
Write-LogWithTime "Build Name: $buildName" "Cyan"

Write-CanonicalJsonFile -Path $counterPath -InputObject $counter
Write-LogWithTime "Build counter saved" "Green"

$desktopBuildInfoPath = "desktop/src/main/kotlin/io/payanam/desktop/DesktopBuildInfo.kt"
Update-FileContent -Path $desktopBuildInfoPath -Transform {
    param($content)
    $content = $content -replace 'const val PLATFORM_BUILD_NUMBER = \d+', "const val PLATFORM_BUILD_NUMBER = $platformBuildNumber"
    $content = $content -replace 'const val OVERALL_BUILD_NUMBER = \d+', "const val OVERALL_BUILD_NUMBER = $overallBuildNumber"
    $content = $content -replace 'const val BUILD_TIMESTAMP = "[^"]+"', "const val BUILD_TIMESTAMP = `"$dateTimeStamp`""
    $content = $content -replace 'const val VERSION_DISPLAY_NAME = "[^"]+"', "const val VERSION_DISPLAY_NAME = `"$versionDisplayName`""
    $content = $content -replace 'const val BUILD_NAME = "[^"]+"', "const val BUILD_NAME = `"$buildName`""
    return $content
}
Write-LogWithTime "Updated desktop build info constants" "Green"

$desktopBuildGradlePath = "desktop/build.gradle.kts"
Update-FileContent -Path $desktopBuildGradlePath -Transform {
    param($content)
    $content = $content -replace 'packageVersion = "[^"]+"', "packageVersion = `"$packageVersion`""
    return $content
}
Write-LogWithTime "Updated desktop packageVersion to $packageVersion" "Green"

if ($Clean)
{
    Write-LogWithTime "" "White"
    Write-LogWithTime "=== CLEANING ===" "Magenta"
    Write-LogWithTime "Stopping Gradle daemons..." "Yellow"
    cmd /c ".\gradlew.bat --stop 2>nul" | Out-Null
    Start-Sleep -Seconds 2

    if (Test-Path "desktop/build")
    {
        Write-LogWithTime "Removing desktop/build..." "Yellow"
        Remove-Item -Recurse -Force "desktop/build" -ErrorAction SilentlyContinue
    }

    $cleanRun = Invoke-GradleStreaming -GradleArgs "clean" -StepLabel "Clean"
    if ($cleanRun.ExitCode -ne 0)
    {
        Write-LogWithTime "  ⚠️ Gradle clean had issues" "Yellow"
    }
    Write-LogWithTime "✅ Clean completed" "Green"
}

Write-LogWithTime "" "White"
Write-LogWithTime "=== FORMATTING ===" "Magenta"
if ($runFormatting)
{
    $formatRun = Invoke-GradleStreaming -GradleArgs ":desktop:spotlessApply" -StepLabel "Desktop formatting"
    if ($formatRun.ExitCode -ne 0)
    {
        Write-LogWithTime "❌ Desktop formatting failed!" "Red"
        $formatRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        Exit-WithCleanup 1
    }
    Write-LogWithTime "✅ Desktop formatting passed" "Green"
} else
{
    Write-LogWithTime "Skipping desktop formatting for profile '$effectiveProfile'." "Yellow"
}

Write-LogWithTime "" "White"
Write-LogWithTime "=== DESKTOP VERIFICATION ===" "Magenta"
if ($runDesktopTests)
{
    $verificationRun = Invoke-GradleStreaming -GradleArgs ":desktop:detekt :desktop:spotlessCheck :desktop:test :desktop:jacocoTestReport :desktop:jacocoTestCoverageVerification" -StepLabel "Desktop verification"
    if ($verificationRun.ExitCode -ne 0)
    {
        Write-LogWithTime "❌ Desktop verification failed!" "Red"
        $verificationRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        Exit-WithCleanup 1
    }
    Write-LogWithTime "✅ Desktop verification passed (detekt, spotless, tests, coverage)" "Green"
    Write-LogWithTime "Coverage report: desktop\\build\\reports\\jacoco\\test\\html\\index.html" "DarkGray"
} else
{
    Write-LogWithTime "Skipping desktop tests/static checks for profile '$effectiveProfile'." "Yellow"
}

Write-LogWithTime "" "White"
Write-LogWithTime "=== BUILDING WINDOWS PACKAGE ===" "Magenta"
$packageTasks = if ($Release)
{
    ":desktop:createReleaseDistributable :desktop:packageReleaseDistributionForCurrentOS"
} else
{
    ":desktop:createDistributable :desktop:packageDistributionForCurrentOS"
}
$packageRun = Invoke-GradleStreaming -GradleArgs $packageTasks -StepLabel "Desktop packaging"
if ($packageRun.ExitCode -ne 0)
{
    Write-LogWithTime "❌ Desktop packaging failed!" "Red"
    $packageRun.Output | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Exit-WithCleanup 1
}
Write-LogWithTime "✅ Desktop packaging passed" "Green"

$composeOutputRoot = "desktop/build/compose/binaries"
if (-not (Test-Path $composeOutputRoot))
{
    Write-LogWithTime "❌ Desktop compose output not found at $composeOutputRoot" "Red"
    Exit-WithCleanup 1
}

$artifactTarget = Join-Path $OutputDir $buildName
New-Item -ItemType Directory -Path $artifactTarget -Force | Out-Null
Copy-Item -Path (Join-Path $composeOutputRoot "*") -Destination $artifactTarget -Recurse -Force
Write-LogWithTime "Copied desktop artifacts to $artifactTarget" "Green"

$packagedExePath = Join-Path $artifactTarget "main/exe/PayanamDesktop-$packageVersion.exe"
$packagedMsiPath = Join-Path $artifactTarget "main/msi/PayanamDesktop-$packageVersion.msi"
Invoke-DesktopArtifactVerification -ExePath $packagedExePath -MsiPath $packagedMsiPath

$installedExePath = ""
$installSkippedReason = ""

Write-LogWithTime "" "White"
Write-LogWithTime "=== WINDOWS INSTALLATION ===" "Magenta"
if ($runDesktopInstall)
{
    try
    {
        $installResult = Invoke-DesktopInstall -MsiPath $packagedMsiPath -CleanInstallRequested $CleanInstall.IsPresent -BuildName $buildName
        $installedExePath = [string]$installResult.InstalledExePath
        if ($installResult.InstallSkipped)
        {
            $installSkippedReason = [string]$installResult.SkipReason
            Write-LogWithTime "⚠️ $installSkippedReason" "Yellow"
            Write-LogWithTime "New desktop build completed successfully: Windows Build #$platformBuildNumber" "Cyan"
            Write-LogWithTime "Close the running app and rerun desktop install/verification when ready." "Yellow"
        } elseif ($runPostInstallVerification)
        {
            Invoke-DesktopSmokePack -InstalledExePath $installedExePath -BuildName $buildName
            Write-LogWithTime "✅ Post-install verification complete!" "Green"
        } else
        {
            Write-LogWithTime "Skipping post-install verification for profile '$effectiveProfile'." "Yellow"
        }
    } catch
    {
        Write-LogWithTime "❌ Windows install/verification failed: $($_.Exception.Message)" "Red"
        Exit-WithCleanup 1
    }
} else
{
    Write-LogWithTime "Skipping Windows install stage for profile '$effectiveProfile'. Packaged artifacts are ready at $artifactTarget" "Yellow"
}

Write-LogWithTime "" "White"
Write-LogWithTime "=== ARTIFACT RETENTION ===" "Magenta"
try
{
    Invoke-BuildArtifactRetention -TargetPath $OutputDir -ItemType Directory -KeepCount $MaxDesktopArtifacts -CurrentBuildName $buildName
    Invoke-BuildArtifactRetention -TargetPath (Get-DesktopVerificationRoot) -ItemType Directory -KeepCount $MaxWindowsSmokeArtifacts -CurrentBuildName $buildName
} catch
{
    Write-LogWithTime "  ⚠️ Artifact retention warning: $($_.Exception.Message)" "Yellow"
}

Write-LogWithTime "" "White"
Write-LogWithTime "========================================" "Cyan"
Write-LogWithTime "  DESKTOP BUILD COMPLETED SUCCESSFULLY!" "Cyan"
Write-LogWithTime "========================================" "Cyan"
Write-LogWithTime "  Build Name: $buildName" "Cyan"
Write-LogWithTime "  Windows Build #: $platformBuildNumber" "Cyan"
Write-LogWithTime "  Overall Build #: $overallBuildNumber" "Cyan"
Write-LogWithTime "  Build Profile: $effectiveProfile" "Cyan"
Write-LogWithTime "  Artifact Path: $artifactTarget" "Cyan"
Write-LogWithTime "  EXE Path: $packagedExePath" "Cyan"
Write-LogWithTime "  MSI Path: $packagedMsiPath" "Cyan"
if (-not $SkipInstall -and -not [string]::IsNullOrWhiteSpace($installSkippedReason))
{
    Write-LogWithTime "  Install Status: skipped" "Cyan"
    Write-LogWithTime "  Install Skip Reason: $installSkippedReason" "Cyan"
}
if (-not $SkipInstall -and -not [string]::IsNullOrWhiteSpace($installedExePath))
{
    Write-LogWithTime "  Installed App Path: $installedExePath" "Cyan"
    Write-LogWithTime "  Desktop Data Root: $desktopStateRoot" "Cyan"
    Write-LogWithTime "  Verification Root: $(Get-DesktopVerificationRoot)" "Cyan"
}
Write-LogWithTime "========================================" "Cyan"

Exit-WithCleanup 0
