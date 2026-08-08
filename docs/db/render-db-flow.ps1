param(
    [string]$SpecPath = "docs/db/db-flow-boot-entry-flows.json",
    [string]$OutDir = "docs/db/generated"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-DirectoryIfMissing {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Get-EdgeLabel {
    param($Transition)
    $parts = @()
    if ($Transition.trigger) { $parts += [string]$Transition.trigger }
    if ($Transition.guard) { $parts += [string]$Transition.guard }
    return ($parts -join " / ")
}

function Escape-MermaidLabel {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    return (($Text -replace '"', "'") -replace '\|', '/')
}

function Escape-MermaidEdgeLabel {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    $safe = $Text
    $safe = $safe -replace '"', "'"
    $safe = $safe -replace '\|', '/'
    $safe = $safe -replace '\(', ''
    $safe = $safe -replace '\)', ''
    $safe = $safe -replace '\[', ''
    $safe = $safe -replace '\]', ''
    $safe = $safe -replace '\{', ''
    $safe = $safe -replace '\}', ''
    $safe = $safe -replace ':', ' -'
    $safe = $safe -replace ';', ','
    return $safe
}

function Get-MermaidNodeLine {
    param($State)

    $id = [string]$State.id
    $label = Escape-MermaidLabel ([string]$State.label)
    $type = [string]$State.type

    switch ($type) {
        "decision" { return "    $id{`"$label`"}" }
        "screen"   { return "    $id[[`"$label`"]]" }
        "handoff"  { return "    $id([`"$label`"])" }
        "context"  { return "    $id([`"$label`"])" }
        "terminal" { return "    $id([`"$label`"])" }
        "terminal_error" { return "    $id([`"$label`"])" }
        default    { return "    $id[`"$label`"]" }
    }
}

function Get-GuardPolarity {
    param([string]$Guard)

    if ([string]::IsNullOrWhiteSpace($Guard)) { return "neutral" }
    $g = $Guard.ToLowerInvariant()

    if ($g -match 'higher than app supported|update app required') { return "negative" }
    if ($g -match '\b(unknown|corrupt|unusable|unsupported|invalid|failed|failure|error)\b') { return "negative" }

    if ($g -match 'encrypted detected') { return "positive" }
    if ($g -match '^\s*(yes|true)\s*$') { return "positive" }
    if ($g -match '\b(success|valid|supported|readable|healthy)\b') { return "positive" }

    if ($g -match '^\s*(no|false)\s*$') { return "negative" }

    return "neutral"
}

function Get-BinaryOutcomeStrokeColor {
    param(
        [int]$Ordinal,
        [string]$Guard
    )

    $polarity = Get-GuardPolarity -Guard $Guard
    if ($polarity -eq "positive") { return "#2E7D32" }  # green
    if ($polarity -eq "negative") { return "#C62828" }  # red

    if ($Ordinal -eq 0) { return "#1565C0" }  # blue
    return "#EF6C00"                           # orange
}

function Get-StateGroupKey {
    param([string]$StateId)

    if ($StateId -match '^(RUNTIME_|RESET_SRC_RUNTIME_FORGOT$)') { return "runtime" }
    if ($StateId -match '^(AUTO_BK_|AUTO_BK_TRIGGER$)') { return "auto_backup" }
    if ($StateId -match '^(DATA_MGMT_|EXPORT_|IMPORT_(TYPE|DB_|MOD_|UH_))') { return "data_ops" }
    if ($StateId -match '^(RESET_|RESET_SRC_METADATA_REPAIR$|RESET_SRC_UNLOCK_LOCKOUT$)') { return "shared_reset" }
    if ($StateId -match '^(DEL_SRC_|DELETE_)') { return "shared_delete" }
    return "boot_entry"
}

function Get-StateGroupLabel {
    param([string]$GroupKey)

    switch ($GroupKey) {
        "boot_entry"    { return "Boot Entry Flow" }
        "shared_reset"  { return "Shared Reset Flow" }
        "shared_delete" { return "Shared Delete Confirmation" }
        "runtime"       { return "Runtime Session Flow" }
        "data_ops"      { return "Data Management (Export / Import)" }
        "auto_backup"   { return "Auto-Backup" }
        default         { return "Other" }
    }
}

$spec = Get-Content -Raw -Path $SpecPath | ConvertFrom-Json
New-DirectoryIfMissing -Path $OutDir
$outputBaseName = [System.IO.Path]::GetFileNameWithoutExtension($SpecPath)

$stateById = @{}
$duplicateStates = @()
foreach ($s in $spec.states) {
    if ($stateById.ContainsKey($s.id)) {
        $duplicateStates += $s.id
    } else {
        $stateById[$s.id] = $s
    }
}

$transitionById = @{}
$duplicateTransitions = @()
$transitionSignatureSeen = @{}
$duplicateTransitionSignatures = @()
foreach ($t in $spec.transitions) {
    if ($transitionById.ContainsKey($t.id)) {
        $duplicateTransitions += $t.id
    } else {
        $transitionById[$t.id] = $t
    }

    $sig = "{0}|{1}|{2}|{3}" -f ([string]$t.from), ([string]$t.trigger), ([string]$t.guard), ([string]$t.to)
    if ($transitionSignatureSeen.ContainsKey($sig)) {
        $duplicateTransitionSignatures += "$($t.id) duplicates $($transitionSignatureSeen[$sig]) signature=$sig"
    } else {
        $transitionSignatureSeen[$sig] = [string]$t.id
    }
}

$missingStateRefs = @()
foreach ($t in $spec.transitions) {
    if (-not $stateById.ContainsKey($t.from)) { $missingStateRefs += "transition $($t.id) missing from-state '$($t.from)'" }
    if (-not $stateById.ContainsKey($t.to)) { $missingStateRefs += "transition $($t.id) missing to-state '$($t.to)'" }
}

$outgoing = @{}
foreach ($s in $spec.states) { $outgoing[$s.id] = @() }
foreach ($t in $spec.transitions) {
    if ($outgoing.ContainsKey($t.from)) {
        $outgoing[$t.from] = @($outgoing[$t.from] + $t)
    }
}

$terminalTypes = @("handoff", "terminal", "terminal_error")
$deadEnds = @()
foreach ($s in $spec.states) {
    $isTerminal = $terminalTypes -contains ([string]$s.type)
    if (-not $isTerminal -and $outgoing[$s.id].Count -eq 0) {
        $deadEnds += $s.id
    }
}

$invalidDecisionNodes = @()
foreach ($s in $spec.states) {
    if ([string]$s.type -eq "decision" -and $outgoing[$s.id].Count -lt 2) {
        $invalidDecisionNodes += "$($s.id) (outgoing=$($outgoing[$s.id].Count))"
    }
}

$visited = [System.Collections.Generic.HashSet[string]]::new()
$queue = [System.Collections.Generic.Queue[string]]::new()
foreach ($entry in $spec.entry_states) {
    if ($stateById.ContainsKey($entry)) {
        if ($visited.Add($entry)) { $queue.Enqueue($entry) }
    }
}
while ($queue.Count -gt 0) {
    $current = $queue.Dequeue()
    foreach ($t in $outgoing[$current]) {
        if ($visited.Add([string]$t.to)) {
            $queue.Enqueue([string]$t.to)
        }
    }
}
$unreachable = @()
foreach ($s in $spec.states) {
    if (-not $visited.Contains($s.id)) {
        $unreachable += $s.id
    }
}

$transitionRows = foreach ($t in $spec.transitions) {
    $fromLabel = if ($stateById.ContainsKey($t.from)) { $stateById[$t.from].label } else { "" }
    $toLabel = if ($stateById.ContainsKey($t.to)) { $stateById[$t.to].label } else { "" }
    [pscustomobject]@{
        id = $t.id
        from_state = $t.from
        from_label = $fromLabel
        trigger = $t.trigger
        guard = $t.guard
        action = $t.action
        to_state = $t.to
        to_label = $toLabel
    }
}

$csvPath = Join-Path $OutDir "$outputBaseName.transitions.csv"
$transitionRows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

$mmdLines = [System.Collections.Generic.List[string]]::new()
$mmdLines.Add("flowchart TD")

$binaryDecisionOutgoing = @{}
foreach ($s in $spec.states) {
    if ([string]$s.type -eq "decision" -and $outgoing[$s.id].Count -eq 2) {
        $binaryDecisionOutgoing[$s.id] = @($outgoing[$s.id])
    }
}

$groupOrder = @("boot_entry", "shared_reset", "shared_delete", "runtime", "data_ops", "auto_backup")
$statesByGroup = @{}
foreach ($g in $groupOrder) {
    $statesByGroup[$g] = [System.Collections.Generic.List[object]]::new()
}
foreach ($s in $spec.states) {
    $g = Get-StateGroupKey -StateId ([string]$s.id)
    if (-not $statesByGroup.ContainsKey($g)) {
        $statesByGroup[$g] = [System.Collections.Generic.List[object]]::new()
        $groupOrder += $g
    }
    $statesByGroup[$g].Add($s)
}

foreach ($g in $groupOrder) {
    if (-not $statesByGroup.ContainsKey($g)) { continue }
    if ($statesByGroup[$g].Count -eq 0) { continue }

    $subgraphId = "SG_{0}" -f $g.ToUpperInvariant()
    $subgraphLabel = Escape-MermaidLabel (Get-StateGroupLabel -GroupKey $g)
    $mmdLines.Add("    subgraph $subgraphId[`"$subgraphLabel`"]")
    $mmdLines.Add("        direction TB")
    foreach ($s in $statesByGroup[$g]) {
        $mmdLines.Add("    " + ((Get-MermaidNodeLine -State $s).TrimStart()))
    }
    $mmdLines.Add("    end")
}

$edgeLinkStyles = [System.Collections.Generic.List[string]]::new()
$linkIndex = 0
foreach ($t in $spec.transitions) {
    $edge = Escape-MermaidEdgeLabel (Get-EdgeLabel -Transition $t)
    if ([string]::IsNullOrWhiteSpace($edge)) {
        $mmdLines.Add("    $($t.from) --> $($t.to)")
    } else {
        $mmdLines.Add("    $($t.from) -->|$edge| $($t.to)")
    }

    if ($binaryDecisionOutgoing.ContainsKey($t.from)) {
        $outTransitions = $binaryDecisionOutgoing[$t.from]
        $ordinal = 0
        for ($i = 0; $i -lt $outTransitions.Count; $i++) {
            if ([string]$outTransitions[$i].id -eq [string]$t.id) {
                $ordinal = $i
                break
            }
        }
        $strokeColor = Get-BinaryOutcomeStrokeColor -Ordinal $ordinal -Guard ([string]$t.guard)
        $edgeLinkStyles.Add("    linkStyle $linkIndex stroke:$strokeColor,stroke-width:2px")
    }
    $linkIndex++
}

foreach ($line in $edgeLinkStyles) {
    $mmdLines.Add($line)
}

foreach ($s in $spec.states) {
    switch ([string]$s.type) {
        "terminal_error" {
            $mmdLines.Add("    style $($s.id) fill:#B71C1C,color:#FFFFFF,stroke:#FFCDD2,stroke-width:2px")
        }
        "terminal" {
            $mmdLines.Add("    style $($s.id) fill:#1B5E20,color:#FFFFFF,stroke:#A5D6A7,stroke-width:2px")
        }
    }
}

$mmdPath = Join-Path $OutDir "$outputBaseName.mmd"
$mmdLines -join [Environment]::NewLine | Set-Content -Path $mmdPath -Encoding UTF8

$mmdcExe = if ($IsWindows) { "mmdc.cmd" } else { "mmdc" }
$mmdcPath = Join-Path $PSScriptRoot "node_modules\.bin\$mmdcExe"
if (-not (Test-Path $mmdcPath)) {
    throw "Mermaid CLI not found at '$mmdcPath'. Install it first: npm --prefix docs/db install --save-dev @mermaid-js/mermaid-cli"
}

if ($invalidDecisionNodes.Count -gt 0) {
    throw "Invalid decision nodes (must have at least 2 outgoing transitions): $($invalidDecisionNodes -join '; ')"
}
if ($duplicateTransitionSignatures.Count -gt 0) {
    throw "Duplicate transition signatures detected: $($duplicateTransitionSignatures -join '; ')"
}

$mmdConfigPath = Join-Path $OutDir "$outputBaseName.mermaid-config.json"
[System.IO.File]::WriteAllText($mmdConfigPath, '{"maxTextSize":500000}')

$tmpSvg = Join-Path $OutDir "$outputBaseName.validation.svg"
$mmdcOutput = @()
try {
    $mmdcOutput = & $mmdcPath -i $mmdPath -o $tmpSvg --configFile $mmdConfigPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        $fullOutput = ($mmdcOutput | Out-String)
        throw "Mermaid CLI validation failed with exit code $LASTEXITCODE.`n$fullOutput"
    }
    if (-not (Test-Path $tmpSvg)) {
        throw "Mermaid CLI returned success but did not produce validation SVG at '$tmpSvg'."
    }
    $svgCheck = Get-Content $tmpSvg -Raw
    if ($svgCheck -match 'Maximum text size') {
        throw "Mermaid CLI produced an error SVG (text size limit exceeded). Increase maxTextSize in mermaid-config.json or reduce diagram size."
    }
} catch {
    $captured = if ($mmdcOutput.Count -gt 0) { ($mmdcOutput | Out-String) } else { "" }
    if ($captured) {
        throw "$($_.Exception.Message)`nMermaid CLI output:`n$captured"
    }
    throw
} finally {
    if (Test-Path $tmpSvg) { Remove-Item $tmpSvg -Force }
}

$summary = [pscustomobject]@{
    spec = $spec.name
    states = $spec.states.Count
    transitions = $spec.transitions.Count
    duplicate_states = ($duplicateStates -join "; ")
    duplicate_transitions = ($duplicateTransitions -join "; ")
    duplicate_transition_signatures = ($duplicateTransitionSignatures -join "; ")
    missing_state_refs = ($missingStateRefs -join " || ")
    dead_ends_non_terminal = ($deadEnds -join "; ")
    invalid_decision_nodes = ($invalidDecisionNodes -join "; ")
    unreachable_states = ($unreachable -join "; ")
    csv = $csvPath
    mermaid = $mmdPath
    mermaid_cli_validation = "passed"
}

$svgPath  = Join-Path $OutDir "$outputBaseName.svg"
$htmlPath = Join-Path $OutDir "$outputBaseName.viewer.html"

# Generate permanent dark-themed SVG
$svgOutput = @()
try {
    $svgOutput = & $mmdcPath -i $mmdPath -o $svgPath --theme dark --backgroundColor '#1e1e2e' --configFile $mmdConfigPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "SVG render failed (non-blocking): $($svgOutput | Out-String)"
        $svgPath = $null
    } elseif (Test-Path $svgPath) {
        $svgCheckRender = Get-Content $svgPath -Raw
        if ($svgCheckRender -match 'Maximum text size') {
            Write-Warning "SVG render produced error SVG (text size limit). Viewer HTML will not be generated."
            Remove-Item $svgPath -Force
            $svgPath = $null
        }
    }
} catch {
    Write-Warning "SVG render error (non-blocking): $($_.Exception.Message)"
    $svgPath = $null
}

# Generate HTML pan-zoom viewer if SVG was produced
if ($svgPath -and (Test-Path $svgPath)) {
    $svgContent = Get-Content $svgPath -Raw
    $svgContent = $svgContent -replace '^\s*<\?xml[^>]*\?>\s*', ''
    $htmlContent = @"
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>$($spec.name)</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;overflow:hidden;background:#1e1e2e;font-family:monospace}
#tb{position:fixed;top:10px;right:14px;z-index:99;display:flex;gap:6px;align-items:center;color:#cdd6f4;font-size:13px}
#tb button{background:#313244;color:#cdd6f4;border:1px solid #45475a;border-radius:4px;padding:4px 10px;cursor:pointer;font-size:13px}
#tb button:hover{background:#45475a}
#tb span{background:#313244;padding:4px 8px;border-radius:4px;border:1px solid #45475a;min-width:54px;text-align:center}
#hint{position:fixed;bottom:10px;left:14px;z-index:99;font-size:12px;color:#6c7086}
#cv{width:100%;height:100%;overflow:hidden;position:relative;cursor:grab}
#cv.drag{cursor:grabbing}
#pt{transform-origin:0 0;position:absolute;top:0;left:0;display:inline-block}
#pt svg{display:block}
</style>
</head>
<body>
<div id="tb">
  <button onclick="zIn()">+</button>
  <button onclick="zOut()">&#8722;</button>
  <button onclick="goHome()" title="Reading zoom at diagram top — T or dbl-click">Home</button>
  <button onclick="fitAll()" title="Fit entire diagram in viewport — F">Fit</button>
  <span id="zl">—</span>
</div>
<div id="hint">Scroll = zoom at cursor &nbsp;&bull;&nbsp; Drag = pan &nbsp;&bull;&nbsp; T / dbl-click = home &nbsp;&bull;&nbsp; F = fit all &nbsp;&bull;&nbsp; Arrows = nudge</div>
<div id="cv"><div id="pt">$svgContent</div></div>
<script>
(function(){
var cv=document.getElementById('cv'),pt=document.getElementById('pt'),zl=document.getElementById('zl');
var sc=1,tx=0,ty=0,drag=false,lx=0,ly=0;
var MIN=0.005,MAX=200;
var svgW=0,svgH=0,homeSc=1;

function readSvgDims(){
  if(svgW>0)return;
  var svg=pt.querySelector('svg');
  var vb=svg&&svg.viewBox&&svg.viewBox.baseVal;
  svgW=vb&&vb.width>1?vb.width:27435;
  svgH=vb&&vb.height>1?vb.height:18763;
}
function apply(){pt.style.transform='translate('+tx+'px,'+ty+'px) scale('+sc+')';zl.textContent=Math.round(sc*100)+'%';}

function fitAll(){
  readSvgDims();
  var cw=cv.clientWidth,ch=cv.clientHeight;
  sc=Math.min(cw/svgW,ch/svgH)*0.90;
  tx=(cw-svgW*sc)/2;ty=(ch-svgH*sc)/2;apply();
}
function goHome(){
  readSvgDims();
  sc=homeSc;
  tx=(cv.clientWidth-svgW*sc)/2;
  ty=20;apply();
}
window.fitAll=fitAll;window.goHome=goHome;
window.zIn=function(){zAt(cv.clientWidth/2,cv.clientHeight/2,1.2);};
window.zOut=function(){zAt(cv.clientWidth/2,cv.clientHeight/2,1/1.2);};

function zAt(cx,cy,f){
  var ns=Math.min(MAX,Math.max(MIN,sc*f));
  tx=cx-(cx-tx)*ns/sc;
  ty=cy-(cy-ty)*ns/sc;
  sc=ns;apply();
}
cv.addEventListener('wheel',function(e){
  e.preventDefault();
  var r=cv.getBoundingClientRect();
  zAt(e.clientX-r.left,e.clientY-r.top,e.deltaY<0?1.15:1/1.15);
},{passive:false});
cv.addEventListener('mousedown',function(e){if(e.button!==0)return;drag=true;lx=e.clientX;ly=e.clientY;cv.classList.add('drag');});
window.addEventListener('mousemove',function(e){if(!drag)return;tx+=e.clientX-lx;ty+=e.clientY-ly;lx=e.clientX;ly=e.clientY;apply();});
window.addEventListener('mouseup',function(){drag=false;cv.classList.remove('drag');});
cv.addEventListener('dblclick',goHome);
document.addEventListener('keydown',function(e){
  var k=e.key;
  if(k==='+'||k==='=')zIn();
  else if(k==='-'||k==='_')zOut();
  else if(k.toLowerCase()==='f')fitAll();
  else if(k.toLowerCase()==='t'||k==='0')goHome();
  else if(k==='ArrowLeft'){tx-=150;apply();}
  else if(k==='ArrowRight'){tx+=150;apply();}
  else if(k==='ArrowUp'){ty-=150;apply();}
  else if(k==='ArrowDown'){ty+=150;apply();}
});
window.addEventListener('load',function(){
  readSvgDims();
  var fitSc=Math.min(cv.clientWidth/svgW,cv.clientHeight/svgH)*0.90;
  homeSc=fitSc*9;
  goHome();
});
</script>
</body></html>
"@
    $htmlContent | Set-Content -Path $htmlPath -Encoding UTF8
}

$summary | Format-List | Out-String | Write-Output
if ($svgPath -and (Test-Path $svgPath)) {
    Write-Output "svg             : $svgPath"
    Write-Output "html_viewer     : $htmlPath"
}
