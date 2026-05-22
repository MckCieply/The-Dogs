#Requires -Version 7.0
<#
.SYNOPSIS
  Flow B orchestrator — Mon/Wed/Fri 02:00 (ADR-0012). Runs the 3-agent
  audit pass (security-reviewer -> pwa-auditor -> docs-writer) against
  the diff `dev` vs the last flow-b-N tag, opens findings as GitHub
  issues, and commits docs updates to a chore/flow-b-<date> PR.

.DESCRIPTION
  Implements ADR-0012 §"Flow B — scheduled 3x/week":

  - Acquires orchestrator.lock (waits if Flow A is mid-feature).
  - Boots `docker compose up -d` (full stack on dev branch state) for
    the pwa-auditor; tears down at the end.
  - Single `claude -p` invocation that walks the 3 Flow B agents in one
    session, instructing each to open GH issues for their findings.
  - Commits any docs-writer changes onto branch chore/flow-b-<YYYY-MM-DD>
    and opens a PR to dev.
  - Tags the dev tip the run started from as flow-b-<N> and pushes it.

.PARAMETER DryRun
  Don't actually invoke claude, modify git, or open issues.

.EXAMPLE
  pwsh scripts/run-flow-b.ps1

.NOTES
  Triggered by Windows Task Scheduler Mon/Wed/Fri 02:00 (Europe/Warsaw).
  See scripts/README.md for the schtasks registration command.

  May also be triggered ad-hoc by run-features.ps1 when the per-window
  cap of 10 features is hit before the next scheduled Flow B.
#>
[CmdletBinding()]
param([switch]$DryRun)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$lockPath = Join-Path $repoRoot 'orchestrator.lock'
$logPath  = Join-Path $repoRoot 'orchestrator.log'

$LOCK_STALE_SEC  = 18900
$QUOTA_SLEEP_SEC = 18360
$DOCKER_HEALTH_WAIT_SEC = 120
$TZ_WARSAW = [System.TimeZoneInfo]::FindSystemTimeZoneById('Central European Standard Time')

function Write-Log {
    param([string]$Level, [string]$Message)
    $ts = (Get-Date).ToString('o')
    Add-Content -Path $logPath -Value "$ts [$Level] $Message" -Encoding utf8
    if ($Level -in 'ERROR', 'WARN') {
        Write-Host "$ts [$Level] $Message" -ForegroundColor ($Level -eq 'ERROR' ? 'Red' : 'Yellow')
    } else {
        Write-Host "$ts [$Level] $Message"
    }
}

function Acquire-Lock {
    while ($true) {
        if (-not (Test-Path $lockPath)) {
            "$PID flow-b $(Get-Date -Format o)" | Out-File $lockPath -Encoding utf8
            Write-Log 'INFO' "Lock acquired (pid $PID)"
            return
        }
        $age = (Get-Date) - (Get-Item $lockPath).LastWriteTime
        if ($age.TotalSeconds -gt $LOCK_STALE_SEC) {
            Write-Log 'WARN' "Stale lock; removing"
            Remove-Item $lockPath -Force
            continue
        }
        Write-Log 'INFO' "Lock held; sleeping 30s"
        Start-Sleep -Seconds 30
    }
}

function Release-Lock {
    if (Test-Path $lockPath) {
        Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
    }
}

function Get-LastFlowBTag {
    $tag = (git tag --list 'flow-b-*' --sort=-creatordate | Select-Object -First 1) 2>$null
    if ($tag) { return $tag.Trim() }
    return (git rev-list --max-parents=0 HEAD | Select-Object -First 1).Trim()
}

function Get-NextFlowBNumber {
    $tags = git tag --list 'flow-b-*'
    if (-not $tags) { return 1 }
    $nums = $tags | ForEach-Object {
        if ($_ -match 'flow-b-(\d+)') { [int]$Matches[1] }
    } | Sort-Object -Descending
    return ($nums[0] + 1)
}

function Start-StackForAudit {
    Write-Log 'INFO' 'Bringing up docker compose for pwa-auditor'
    Push-Location $repoRoot
    try {
        docker compose -f docker-compose.yml up -d 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed"
        }
        # Naive health wait: poll up to 2 minutes for HTTP 200 on :4200.
        $deadline = (Get-Date).AddSeconds($DOCKER_HEALTH_WAIT_SEC)
        while ((Get-Date) -lt $deadline) {
            try {
                $r = Invoke-WebRequest -Uri 'http://localhost:4200' -TimeoutSec 5 -UseBasicParsing
                if ($r.StatusCode -eq 200) { return }
            } catch { Start-Sleep -Seconds 3 }
        }
        throw "Frontend did not become healthy within $DOCKER_HEALTH_WAIT_SEC s"
    } finally {
        Pop-Location
    }
}

function Stop-StackAfterAudit {
    Write-Log 'INFO' 'Tearing down docker compose'
    Push-Location $repoRoot
    try {
        docker compose -f docker-compose.yml down 2>&1 | Out-Null
    } finally {
        Pop-Location
    }
}

function Invoke-FlowBClaude {
    param(
        [Parameter(Mandatory)] [string]$LastTag,
        [Parameter(Mandatory)] [string]$NextTagName,
        [Parameter(Mandatory)] [string]$BranchName
    )

    $diffStat = git diff --stat "$LastTag..dev" 2>$null
    $log      = git log --oneline "$LastTag..dev" 2>$null

    $prompt = @"
You are orchestrating Flow B of the The-Dogs automated pipeline (see
docs/adr/0012-automated-two-flow-pipeline.md). Run the audit pass for the
window since the last Flow B run.

Last Flow B tag: $LastTag
Next tag to create: $NextTagName
Branch (for docs-writer commits): $BranchName (already checked out from dev)

Commits in scope:
$log

Diff stat:
$diffStat

Execute these steps in order, delegating to the named subagents:

1. **security-reviewer agent** — adversarial audit of the diff $LastTag..dev.
   Open GitHub issues for findings using `gh issue create` with labels
   kind:security and severity:critical|high|medium|low.

2. **pwa-auditor agent** — the orchestrator already brought up
   docker compose; the frontend is live at http://localhost:4200.
   Verify manifest, service worker, offline, Lighthouse, axe, install
   prompt on routes touched in the diff. Open issues with labels
   kind:pwa and severity:*.

3. **docs-writer agent** — update docs/ARCHITECTURE.md, docs/AI_NATIVE.md,
   CHANGELOG.md based on the diff and the reports above. Produce a
   severity-tagged docs-drift report. Open issues only for severity
   critical/high docs drift. Commit changes to branch $BranchName.

When you finish, emit a single final fenced ```json``` block:

```json
{
  "security_issues":  [<issue urls or "#N">],
  "pwa_issues":       [<issue urls or "#N">],
  "docs_issues":      [<issue urls or "#N">],
  "docs_files_changed": [<repo-relative paths>],
  "pwa_pre_flight_ok": true | false,
  "summary": "<one line>"
}
```

Do not push, open the Flow B PR, or create the tag. The orchestrator
handles all remote git operations after parsing your final JSON block.
"@

    if ($DryRun) {
        Write-Log 'INFO' "[DRY-RUN] Would invoke claude -p for Flow B against $LastTag..dev"
        return @{
            security_issues   = @()
            pwa_issues        = @()
            docs_issues       = @()
            docs_files_changed = @()
            cost_usd          = 0
            session_id        = '<dry-run>'
        }
    }

    $tmpPrompt = New-TemporaryFile
    Set-Content -Path $tmpPrompt -Value $prompt -Encoding utf8

    try {
        # YOLO mode — see run-features.ps1 for the safety rationale.
        $jsonOutput = & claude -p (Get-Content $tmpPrompt -Raw) `
            --output-format json `
            --permission-mode bypassPermissions 2>&1
        $exit = $LASTEXITCODE
    } finally {
        Remove-Item $tmpPrompt -Force -ErrorAction SilentlyContinue
    }

    if ($exit -ne 0) {
        if ($jsonOutput -match '(?i)(rate.?limit|quota|too.?many.?requests|429|usage.?limit|exceed)') {
            throw [System.TimeoutException]::new('claude_quota_hit')
        }
        throw "claude -p failed (exit $exit): $jsonOutput"
    }

    $wrapper = $jsonOutput | ConvertFrom-Json
    $resultText = $wrapper.result

    # Last fenced ```json block (skip intermediate snippets in narration).
    $jsonBlocks = [regex]::Matches($resultText, '(?s)```json\s*(\{.*?\})\s*```')
    if ($jsonBlocks.Count -eq 0) {
        throw "Could not parse final JSON block from claude output. Raw:`n$resultText"
    }
    $structured = $jsonBlocks[$jsonBlocks.Count - 1].Groups[1].Value | ConvertFrom-Json

    return @{
        security_issues    = $structured.security_issues
        pwa_issues         = $structured.pwa_issues
        docs_issues        = $structured.docs_issues
        docs_files_changed = $structured.docs_files_changed
        summary            = $structured.summary
        cost_usd           = $wrapper.total_cost_usd
        session_id         = $wrapper.session_id
    }
}

function Complete-FlowBPR {
    param(
        [Parameter(Mandatory)] [string]$Branch,
        [Parameter(Mandatory)] [string]$TagName,
        [Parameter(Mandatory)] $Result
    )

    $hasChanges = (git status --porcelain) -ne $null
    if ($hasChanges) {
        git add -A 2>&1 | Out-Null
        $today = Get-Date -Format 'yyyy-MM-dd'
        $commitMsg = @"
docs: $TagName updates

Flow B audit pass (ADR-0012) on $today.

Summary: $($Result.summary)

Cost (this session): \$$($Result.cost_usd)
Issues opened: security=$($Result.security_issues.Count), pwa=$($Result.pwa_issues.Count), docs=$($Result.docs_issues.Count)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
"@
        git commit -m $commitMsg 2>&1 | Out-Null
        git push -u origin $Branch 2>&1 | Out-Null

        $prBody = @"
## Summary
Flow B audit pass per [ADR-0012](docs/adr/0012-automated-two-flow-pipeline.md).

- Tag: \`$TagName\`
- Cost (this session): \$$($Result.cost_usd)
- Summary: $($Result.summary)

## Issues opened
- security: $(($Result.security_issues | ForEach-Object { "#$_" }) -join ', ')
- pwa: $(($Result.pwa_issues | ForEach-Object { "#$_" }) -join ', ')
- docs-drift: $(($Result.docs_issues | ForEach-Object { "#$_" }) -join ', ')

## Test plan
- [ ] Review each opened issue; add \`status:approved\` label to promote critical/high findings to features.json (via scripts/promote-issues.ps1).
- [ ] Merge this PR — it contains the docs-writer's drift-resolution commits.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
"@
        gh pr create --base dev --head $Branch `
            --title "docs: $TagName updates" `
            --body $prBody 2>&1 | Out-Null
    } else {
        Write-Log 'INFO' 'docs-writer made no changes; skipping PR.'
    }
}

function Complete-FlowBTag {
    param([Parameter(Mandatory)] [string]$TagName)
    git tag $TagName 2>&1 | Out-Null
    git push origin $TagName 2>&1 | Out-Null
    Write-Log 'INFO' "Tagged $TagName at $(git rev-parse $TagName)"
}

# === Entry point ==============================================================

Write-Log 'INFO' '=== run-flow-b.ps1 starting ==='
Acquire-Lock

$stackUp = $false

try {
    git checkout dev 2>&1 | Out-Null
    git pull origin dev 2>&1 | Out-Null

    $devSha = (git rev-parse HEAD).Trim()
    $lastTag = Get-LastFlowBTag
    $nextNum = Get-NextFlowBNumber
    $nextTagName = "flow-b-$nextNum"
    $branchName = "chore/flow-b-$(Get-Date -Format 'yyyy-MM-dd')"

    Write-Log 'INFO' "Last tag: $lastTag; next: $nextTagName; branch: $branchName; dev sha: $devSha"

    # Branch for docs-writer's commits.
    git checkout -b $branchName 2>&1 | Out-Null

    # Stack up for pwa-auditor.
    if (-not $DryRun) {
        Start-StackForAudit
        $stackUp = $true
    }

    # Single claude -p with retry on quota.
    $result = $null
    while ($true) {
        try {
            $result = Invoke-FlowBClaude `
                -LastTag $lastTag -NextTagName $nextTagName -BranchName $branchName
            break
        } catch [System.TimeoutException] {
            if ($_.Exception.Message -ne 'claude_quota_hit') { throw }
            Write-Log 'WARN' "Pro quota hit; sleeping $($QUOTA_SLEEP_SEC / 60) minutes."
            Start-Sleep -Seconds $QUOTA_SLEEP_SEC
        }
    }

    if (-not $DryRun) {
        Complete-FlowBPR -Branch $branchName -TagName $nextTagName -Result $result
        # Tag the dev SHA we started from (NOT the chore PR branch).
        git checkout dev 2>&1 | Out-Null
        Complete-FlowBTag -TagName $nextTagName
    }

    Write-Log 'INFO' "Flow B run complete. $($result.summary)"

} catch {
    Write-Log 'ERROR' "Flow B failed: $($_.Exception.Message)"
    throw
} finally {
    if ($stackUp) { Stop-StackAfterAudit }
    Release-Lock
    Write-Log 'INFO' '=== run-flow-b.ps1 stopping ==='
}
