<#
Reads SyncEquip debug lines from a Paper log and checks that every player in a
sequence got each step on the same server tick. Needs debug.log-sequence-ticks: true.

Usage:
  powershell -File scripts\check-sync-log.ps1
  powershell -File scripts\check-sync-log.ps1 -Log D:\syncequip-test-server\logs\latest.log
  powershell -File scripts\check-sync-log.ps1 -Out sync-report.txt
#>
param(
    [string]$Log = "D:\syncequip-test-server\logs\latest.log",
    [string]$Out = ""
)

if (-not (Test-Path $Log)) { Write-Error "Log not found: $Log"; exit 2 }

$pattern = 'sequence #(\d+) step (\d+)/(\d+) (\w+)\s+player=(\S+)\s+scheduled=(\d+) actual=(\d+) drift=(-?\d+) result=(\w+)'
$rows = foreach ($line in Get-Content $Log) {
    if ($line -match '\[debug\]' -and $line -match $pattern) {
        [pscustomobject]@{
            Seq = [int]$Matches[1]; Step = [int]$Matches[2]; Name = $Matches[4]; Player = $Matches[5]
            Scheduled = [long]$Matches[6]; Actual = [long]$Matches[7]; Drift = [int]$Matches[8]; Result = $Matches[9]
        }
    }
}

if (-not $rows) { Write-Host "No SyncEquip debug lines found in $Log"; exit 1 }

$report = New-Object System.Collections.Generic.List[string]
$failed = $false
foreach ($seq in ($rows | Group-Object Seq)) {
    $players = ($seq.Group | Select-Object -ExpandProperty Player -Unique).Count
    $report.Add("Sequence #$($seq.Name) ($players players)")
    $firstTick = $null
    foreach ($step in ($seq.Group | Group-Object Step | Sort-Object { [int]$_.Name })) {
        $ticks = @($step.Group | Select-Object -ExpandProperty Actual -Unique)
        $same = $ticks.Count -eq 1
        if (-not $same) { $failed = $true }
        if ($null -eq $firstTick) { $firstTick = $ticks[0] }
        $offset = $ticks[0] - $firstTick
        $maxDrift = ($step.Group | Measure-Object Drift -Maximum).Maximum
        $status = if ($same) { "same tick" } else { "DIFFERENT TICKS: " + ($ticks -join ",") }
        $report.Add(("  step {0} {1,-10} tick {2} (+{3})  players {4}  max drift {5}  {6}" -f $step.Name, $step.Group[0].Name, $ticks[0], $offset, $step.Count, $maxDrift, $status))
    }
}
$report.Add("")
$report.Add($(if ($failed) { "RESULT: at least one step ran on different ticks for different players." } else { "RESULT: every step ran on a single tick for all players in its sequence." }))
$report.Add("This shows server-side timing only. It does not show what any client displayed.")

$report | ForEach-Object { Write-Host $_ }
if ($Out) { $report | Set-Content -Encoding UTF8 $Out }
if ($failed) { exit 1 }
