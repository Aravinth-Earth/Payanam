#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# spotless-ratchet.ps1 — fail if any Kotlin file CHANGED in this working tree violates spotless,
# while ignoring pre-existing drift elsewhere in the repo.
#
# Why a ratchet instead of a plain spotlessCheck:
#   The build gate has never run spotless, so 216 files across app/, core/common/, core/domain/,
#   core/scoring/ and core/database/ are currently unformatted. Wiring `spotlessCheck` in directly
#   would fail every build until all 216 are cleaned, and the only auto-fix (`spotlessApply`) DELETES
#   comments — it removed 10 comment strings repo-wide, including @Suppress justification comments.
#   So: block NEW drift now, and clean the backlog as its own reviewed task.
#
# Usage:  pwsh build-tools/scripts/spotless-ratchet.ps1 [-Base <git-ref>]
#   -Base defaults to HEAD (working tree vs HEAD). Pass a ref (e.g. origin/dev) to widen the scope.
#
# KNOWN LIMIT (found by testing, not by reading): with the default -Base HEAD this checks only
# UNCOMMITTED changes, so once unformatted code is committed the check goes quiet. It is an EDIT-LOOP
# guard, not a commit-time guard. Pass `-Base <branch-point>` to check a whole branch, and expect the
# backlog files to show up if you do. Commit-time enforcement belongs in a git pre-commit hook,
# which is currently not installed.

param(
    [string]$Base = "HEAD"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $repoRoot

function Write-Step([string]$Message, [string]$Colour = "Cyan") {
    Write-Host $Message -ForegroundColor $Colour
}

# --- 1. Which .kt files are part of this change? ------------------------------------------------
$changed = @()
$changed += (git diff --name-only $Base 2>$null)
$changed += (git diff --name-only 2>$null)          # unstaged
$changed += (git diff --name-only --cached 2>$null)  # staged
$changed += (git ls-files --others --exclude-standard 2>$null)
$changed = $changed |
    Where-Object { $_ -and $_ -match '\.kt$' } |
    ForEach-Object { ($_ -replace '\\', '/').Trim() } |
    Sort-Object -Unique

if ($changed.Count -eq 0) {
    Write-Step "spotless-ratchet: no .kt files changed vs $Base - nothing to check" "Green"
    exit 0
}

Write-Step "spotless-ratchet: checking $($changed.Count) changed .kt file(s) vs $Base" "Cyan"

# --- 2. Ask spotless (READ-ONLY) which files repo-wide violate ----------------------------------
# Never use spotlessApply here: the gate must not mutate the tree, and apply deletes comments.
$spotlessOut = (& ./gradlew spotlessCheck --continue --console=plain 2>&1 | Out-String)

# --- 3. Map spotless's module-relative paths onto repo paths ------------------------------------
# spotless prints `> Task :core:shared:spotlessKotlinCheck FAILED` then the offending files as
# `      src/main/kotlin/...`, relative to that module. Module `:a:b` lives at `a/b`.
$violating = New-Object System.Collections.Generic.HashSet[string]
$currentModule = $null
foreach ($line in ($spotlessOut -split "`r?`n")) {
    if ($line -match '^> Task :(.+?):spotless') {
        $currentModule = ($matches[1] -replace ':', '/')
        continue
    }
    if (-not $currentModule) { continue }
    if ($line -match '^\s+(src/.*\.kt)\s*$') {
        [void]$violating.Add("$currentModule/$($matches[1])")
    }
}

# --- 4. Intersect: did the author's own files violate? ------------------------------------------
$offenders = $changed | Where-Object { $violating.Contains($_) }

if ($offenders.Count -gt 0) {
    Write-Host ""
    Write-Step "  x spotless-ratchet FAILED - changed files are not formatted:" "Red"
    foreach ($f in $offenders) { Write-Host "      $f" -ForegroundColor Red }
    Write-Host ""
    Write-Step "  Fix: run './gradlew spotlessApply' on these files, then REVIEW the diff -" "Yellow"
    Write-Step "       spotlessApply can delete comments, so read every removed line before committing." "Yellow"
    Write-Step "       NOTE: apply can exit NON-ZERO while still having written its fixable files - it fails" "Yellow"
    Write-Step "       the task on the REMAINING unfixable ktlint errors (repo-wide backlog: 'KDoc not allowed" "Yellow"
    Write-Step "       inside modifier_list'). A non-zero exit is therefore NOT evidence it wrote nothing:" "Yellow"
    Write-Step "       run this ratchet again and read its file list to see what is left." "Yellow"
    Write-Step "       Run apply PER MODULE (:m:spotlessKotlinApply) - one combined invocation aborts on the" "Yellow"
    Write-Step "       pre-existing errors and can leave a later module unwritten." "Yellow"
    Write-Host ""
    exit 1
}

Write-Step "  v spotless-ratchet passed - all changed .kt files are formatted" "Green"
if ($violating.Count -gt 0) {
    Write-Step "    (note: $($violating.Count) pre-existing unformatted file(s) ignored - see build backlog)" "Yellow"
}
exit 0
