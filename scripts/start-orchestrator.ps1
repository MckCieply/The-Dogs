#Requires -Version 7.0
<#
.SYNOPSIS
  Keeps the Flow A orchestrator alive. Loops run-features.ps1 indefinitely.
  Intended to be launched by Task Scheduler at system startup.

.NOTES
  Writes to orchestrator.log (same as run-features.ps1).
  Exit this process to stop the orchestrator loop.
#>

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$repoRoot       = Resolve-Path (Join-Path $PSScriptRoot '..')
$logPath        = Join-Path $repoRoot 'orchestrator.log'
$script         = Join-Path $PSScriptRoot 'run-features.ps1'
$IDLE_SLEEP_SEC = 600  # 10 min between runs when queue is drained

function Write-WrapperLog {
    param([string]$Msg)
    $line = "$(Get-Date -Format o) [WRAPPER] $Msg"
    Add-Content -Path $logPath -Value $line -Encoding utf8
    Write-Host $line
}

Write-WrapperLog "=== start-orchestrator.ps1 starting (pid $PID) ==="

while ($true) {
    Write-WrapperLog "Launching run-features.ps1"
    try {
        & pwsh -NoProfile -NonInteractive -File $script
    } catch {
        Write-WrapperLog "run-features.ps1 threw: $($_.Exception.Message)"
    }
    Write-WrapperLog "run-features.ps1 exited. Sleeping ${IDLE_SLEEP_SEC}s before next run."
    Start-Sleep -Seconds $IDLE_SLEEP_SEC
}
