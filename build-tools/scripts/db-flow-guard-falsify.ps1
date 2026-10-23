# Falsifies the DB-flow import contract: every rule in Test-DbAuthFlowSecurityContract must be shown to FAIL.
#
# WHY THIS EXISTS: a guard that has never failed is an untested assertion, and this repository has already
# shipped two checks that reported green while covering nothing (a node rule reading a key the spec does not
# have, and an aggregate static-analysis task with NO-SOURCE). This script is the standing proof that the
# import rules can fail, and it is re-runnable instead of a one-off claim in a commit body.
#
# The guard is EXTRACTED, not dot-sourced: build-android.ps1 does Set-Location at load and writes the build
# counter before Gradle runs, so dot-sourcing it would move the shell and mutate tracked state.
#
# Capture is 6>&1, never try/catch: the guard never throws, and Write-LogWithTime writes to the HOST stream,
# so a try/catch sees nothing and would read every mutant as a pass.
#
# Usage:  pwsh build-tools/scripts/db-flow-guard-falsify.ps1
# Exit:   0 = every mutant failed on its own rule and the control passed; 1 = anything else.

[CmdletBinding()]
param(
    [string]$SpecPath = "docs/db/db-flow-boot-entry-flows.json"
)

$ErrorActionPreference = 'Continue'   # the guard is asserted on its RETURN value, not on thrown errors
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $repoRoot

$guardSource = Join-Path $repoRoot 'build-tools/scripts/build-android.ps1'
$mutantRoot = Join-Path $repoRoot 'build-logs/db-flow-falsify'
New-Item -ItemType Directory -Force -Path $mutantRoot | Out-Null

# ---- extract the function under test (+ its only external dependency) ----------------------------
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($guardSource, [ref]$null, [ref]$parseErrors)
if ($parseErrors -and $parseErrors.Count -gt 0) { throw "cannot parse $guardSource : $($parseErrors[0].Message)" }
$wanted = @('Write-LogWithTime', 'Test-DbAuthFlowSecurityContract')
$defs = $ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $wanted -contains $node.Name
    }, $true)
if (@($defs).Count -ne $wanted.Count) {
    throw "expected $($wanted.Count) function(s) to extract, found $(@($defs).Count)"
}
Invoke-Expression ($defs | ForEach-Object { $_.Extent.Text } | Join-String -Separator "`n")

# ---- helpers ------------------------------------------------------------------------------------
function New-Mutant {
    param([string]$Name, [hashtable[]]$Ops)
    $text = [System.IO.File]::ReadAllText((Resolve-Path $SpecPath))
    foreach ($op in $Ops) {
        $hits = ([regex]::Matches($text, [string]$op.Pattern)).Count
        if ($hits -ne 1) { throw "mutant '$Name': pattern matched $hits time(s), expected exactly 1: $($op.Pattern)" }
        $text = [regex]::Replace($text, [string]$op.Pattern, [string]$op.Replace)
    }
    $path = Join-Path $mutantRoot "$Name.json"
    [System.IO.File]::WriteAllText($path, $text)
    return $path
}

function Invoke-GuardUnderTest {
    param([string]$Path)
    $out = Test-DbAuthFlowSecurityContract -SpecPath $Path 6>&1
    $text = (@($out | Where-Object { $_ -isnot [bool] } | ForEach-Object { $_.ToString() }) -join "`n")
    # Exactly ONE bool is returned per call, so taking the last one is safe; a second bool would be an
    # unassigned comparison leaking into the pipeline, which is why the guard assigns every comparison.
    $ret = @($out | Where-Object { $_ -is [bool] })[-1]
    return [PSCustomObject]@{ Return = [bool]$ret; Text = $text }
}

# Rule signatures - the message each rule emits. Isolation is asserted on these, never on a violation COUNT
# (several mutants trip more than one rule, which is expected and is not a failure of the harness).
$sig = @{
    B0        = 'exposes no states'
    DoorGone  = 'is missing from the DB flow spec'
    DoorUndecl = 'looks like an import classification door but is not declared'
    P         = 'is a plaintext import state'
    G         = 'routes a plaintext-guarded import step'
    TDoor     = 'branches to'
    TSource   = 'has no plaintext refusal branch'
    H         = 'has no inbound transition at all'
    I         = 'which does not exist in the spec'
    R         = 'has no outbound transition'
}

$orphanState = @'
"states": [
  {
   "id": "IMPORT_DB_PLAIN_SCREEN",
   "label": "orphan plaintext import state",
   "type": "screen"
  },
'@

$mutants = @(
    @{ Name = 'control'; Ops = @(); Expect = $true; Must = @(); MustNot = @(); Why = 'the shipped spec must be green, or every other row is meaningless' }
    @{ Name = 'b0-key-renamed'; Ops = @(@{ Pattern = '"states": \['; Replace = '"statez": [' }); Expect = $false; Must = @($sig.B0); MustNot = @(); Why = 'an absent key must fire B0, not silently empty the state set' }
    @{ Name = 'b0-states-empty'; Ops = @(@{ Pattern = '"states": \[\n'; Replace = "`"states`": [`n  ],`n  `"states_real`": [`n" }); Expect = $false; Must = @($sig.B0); MustNot = @(); Why = 'an empty collection must fire B0' }
    @{ Name = 'p-orphan-plaintext-state'; Ops = @(@{ Pattern = '"states": \[\n'; Replace = $orphanState }); Expect = $false; Must = @($sig.P); MustNot = @($sig.G, $sig.TDoor, $sig.TSource, $sig.H, $sig.I, $sig.R); Why = 'a renamed/re-added plaintext state with no edges is caught by P alone' }
    @{ Name = 'g-plaintext-guard-on-a-passphrase-sink'; Ops = @(@{ Pattern = '("id": "TIMP019",(?s).*?"guard": )"[^"]*"'; Replace = '$1"Plaintext detected"' }); Expect = $false; Must = @($sig.G); MustNot = @($sig.P, $sig.TDoor, $sig.TSource, $sig.H, $sig.I, $sig.R); Why = 'a plaintext guard must not lead into a passphrase-collecting import step' }
    @{ Name = 't-door-declared-but-missing'; Ops = @(@{ Pattern = '"id": "IMPORT_DB_FORMAT",'; Replace = '"id": "IMPORT_DB_FORMAT_RENAMED",' }); Expect = $false; Must = @($sig.DoorGone); MustNot = @(); Why = 'retiring a door must be a violation, not a silent narrowing (Rule I also fires: expected)' }
    @{ Name = 't-undeclared-door'; Ops = @(@{ Pattern = '("id": "IMPORT_DB_DETECT",(?s).*?"type": )"process"'; Replace = '$1"decision"' }); Expect = $false; Must = @($sig.DoorUndecl); MustNot = @($sig.P, $sig.G, $sig.TDoor, $sig.TSource, $sig.H, $sig.I, $sig.R); Why = 'a new decision-shaped door must be declared, not ignored' }
    @{ Name = 't-allowed-door-branch'; Ops = @(@{ Pattern = '("id": "T004F",(?s).*?"to": )"[^"]*"'; Replace = '$1"IMPORT_PICKER"' }); Expect = $false; Must = @($sig.TDoor); MustNot = @($sig.P, $sig.G, $sig.TSource, $sig.H, $sig.I, $sig.R); Why = 'a door may branch only to an encrypted-verified state or a refusal' }
    @{ Name = 't-source-refusal-removed'; Ops = @(@{ Pattern = '("id": "T004F0",(?s).*?"guard": )"[^"]*"'; Replace = '$1"File opened without a key"' }, @{ Pattern = '("id": "T004F0",(?s).*?"to": )"[^"]*"'; Replace = '$1"IMPORT_ENCRYPTED_DETECTED"' }); Expect = $false; Must = @($sig.TSource); MustNot = @($sig.P, $sig.G, $sig.H, $sig.I, $sig.R); Why = 're-wording the guard while keeping the door green must still be caught - the encrypted arm is not a refusal' }
    @{ Name = 'h-sink-has-no-inbound'; Ops = @(@{ Pattern = '\n  \{\n   "id": "TIMP019",\n(?s).*?\n  \},'; Replace = '' }); Expect = $false; Must = @($sig.H); MustNot = @($sig.P, $sig.G, $sig.TDoor, $sig.TSource, $sig.I, $sig.R); Why = 'an "every inbound comes from X" rule must fire when there are none' }
    @{ Name = 'i-dangling-endpoint'; Ops = @(@{ Pattern = '("id": "T004J",(?s).*?"to": )"[^"]*"'; Replace = '$1"IMPORT_STATE_THAT_DOES_NOT_EXIST"' }); Expect = $false; Must = @($sig.I); MustNot = @($sig.P, $sig.G, $sig.TDoor, $sig.TSource, $sig.H, $sig.R); Why = 'the only check that catches a transition pointing at a deleted state' }
    @{ Name = 'r-refusal-is-a-dead-end'; Ops = @(@{ Pattern = '\n  \{\n   "id": "T004E1",\n(?s).*?\n  \},'; Replace = '' }); Expect = $false; Must = @($sig.R); MustNot = @($sig.P, $sig.G, $sig.TDoor, $sig.TSource, $sig.H, $sig.I); Why = 'a refusal the user cannot leave must fail the build' }
)

# ---- run ------------------------------------------------------------------------
$failures = @()
foreach ($m in $mutants) {
    try {
        $path = if ($m.Ops.Count -eq 0) { (Resolve-Path $SpecPath).Path } else { New-Mutant -Name $m.Name -Ops $m.Ops }
    } catch {
        $failures += "$($m.Name): could not build the mutant - $($_.Exception.Message)"
        continue
    }
    $r = Invoke-GuardUnderTest -Path $path
    $problems = @()
    if ($r.Return -ne $m.Expect) { $problems += "returned $($r.Return), expected $($m.Expect)" }
    foreach ($needle in $m.Must) { if ($r.Text -notlike "*$needle*") { $problems += "missing its own message ('$needle')" } }
    foreach ($needle in $m.MustNot) { if ($r.Text -like "*$needle*") { $problems += "also fired another rule ('$needle')" } }
    $verdict = if ($problems.Count -eq 0) { 'ok' } else { 'FAIL' }
    if ($problems.Count -gt 0) { $failures += "$($m.Name): $($problems -join '; ')" }
    Write-Host ("  [{0,-4}] {1,-40} {2}" -f $verdict, $m.Name, $m.Why)
    if ($problems.Count -gt 0) { foreach ($p in $problems) { Write-Host "         -> $p" } }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "db-flow guard falsification FAILED ($($failures.Count) row(s)):" -ForegroundColor Red
    foreach ($f in $failures) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}
Write-Host "db-flow guard falsification passed: $($mutants.Count) rows - control green, every rule shown to fail on its own message." -ForegroundColor Green
exit 0
