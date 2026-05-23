#Requires -Version 7.0
<#
.SYNOPSIS
  Flow A orchestrator — drains scripts/features.json one entry at a time,
  spawning a single `claude -p` invocation per feature attempt to walk the
  4-role pipeline (explorer -> implementer -> test-writer -> reviewer).

.DESCRIPTION
  Implements ADR-0012 §"Flow A — per feature":

  - Acquires file lock orchestrator.lock (rejects if Flow B is active).
  - Enforces hard daily cap (3 features / calendar day, Europe/Warsaw).
  - Enforces defensive per-Flow-B-window cap (10 features since last
    flow-b-N tag); triggers Flow B ad-hoc on hit.
  - Pauses 02:00-04:00 on Flow B days (Mon/Wed/Fri) to leave the window
    clean for run-flow-b.ps1.
  - Per feature: creates feat/<slug> branch off dev, invokes Claude with
    an orchestration prompt referencing all 4 subagents, parses the JSON
    verdict, squashes WIP commits, opens PR with `gh pr merge --squash
    --auto`, polls until merged, marks feature done.
  - On reviewer changes-requested: max 2 rounds, then status=needs_review.
  - On any exception: 1 retry, then status=failed; queue continues.

  This is the steady-state automation script. Run interactively for first
  validation, then via Task Scheduler (or just leave running in a terminal)
  for production cadence.

.PARAMETER QueuePath
  Path to features.json. Defaults to ../scripts/features.json relative to
  this script. The file is read+written atomically (via .tmp + Move-Item).

.PARAMETER MaxFeatures
  Optional override of how many features to process in one invocation.
  Default: drain until empty or a cap is hit.

.PARAMETER DryRun
  Don't actually invoke claude or modify git. Print what would happen.

.EXAMPLE
  pwsh scripts/run-features.ps1
  pwsh scripts/run-features.ps1 -MaxFeatures 1 -DryRun

.NOTES
  Requires: PowerShell 7+, claude CLI (logged in via `claude login`),
  gh CLI authenticated against the repo, git, jq optional.

  Reads/writes:
    - scripts/features.json   (the queue)
    - orchestrator.lock       (mutual exclusion with Flow B)
    - orchestrator.log        (append-only event log)

  Repo root is detected as the parent of this script.
#>
[CmdletBinding()]
param(
    [string]$QueuePath,
    [int]$MaxFeatures = [int]::MaxValue,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# --- Paths --------------------------------------------------------------------

$repoRoot   = Resolve-Path (Join-Path $PSScriptRoot '..')
$queuePath  = if ($QueuePath) { Resolve-Path $QueuePath } else { Join-Path $PSScriptRoot 'features.json' }
$lockPath   = Join-Path $repoRoot 'orchestrator.lock'
$logPath    = Join-Path $repoRoot 'orchestrator.log'

# --- Constants from ADR-0012 --------------------------------------------------

$DAILY_CAP          = 3                                # features per calendar day
$WINDOW_CAP         = 10                               # features per Flow B window
$ROUND_LIMIT        = 2                                # reviewer review rounds before needs_review
$RETRY_LIMIT        = 1                                # retries after exception
$QUOTA_SLEEP_SEC    = 18360                            # 5.1 hours sleep on Pro quota hit
$LOCK_STALE_SEC     = 18900                            # stale lock (>5.25h) is force-removed
$POLL_PR_SECONDS    = 60                               # gh pr view polling interval
$POLL_PR_MAX        = 90                               # max polls (~90 min) before giving up on auto-merge
$FLOW_B_DAYS        = @('Monday', 'Wednesday', 'Friday')
$FLOW_B_PAUSE_START = '02:00'
$FLOW_B_PAUSE_END   = '04:00'
$TZ_WARSAW          = [System.TimeZoneInfo]::FindSystemTimeZoneById('Central European Standard Time')

# --- Logging ------------------------------------------------------------------

function Write-Log {
    param([string]$Level, [string]$Message)
    $ts = (Get-Date).ToString('o')
    $line = "$ts [$Level] $Message"
    Add-Content -Path $logPath -Value $line -Encoding utf8
    if ($Level -in 'ERROR', 'WARN') {
        Write-Host $line -ForegroundColor ($Level -eq 'ERROR' ? 'Red' : 'Yellow')
    } else {
        Write-Host $line
    }
}

# --- Lock ---------------------------------------------------------------------

function Acquire-Lock {
    while ($true) {
        if (-not (Test-Path $lockPath)) {
            "$PID flow-a $(Get-Date -Format o)" | Out-File $lockPath -Encoding utf8
            Write-Log 'INFO' "Lock acquired (pid $PID)"
            return
        }
        $lockAge = (Get-Date) - (Get-Item $lockPath).LastWriteTime
        if ($lockAge.TotalSeconds -gt $LOCK_STALE_SEC) {
            Write-Log 'WARN' "Stale lock (age $lockAge); removing"
            Remove-Item $lockPath -Force
            continue
        }
        $holder = (Get-Content $lockPath -Raw -ErrorAction SilentlyContinue) ?? '<unknown>'
        Write-Log 'INFO' "Lock held by $holder; sleeping 30s"
        Start-Sleep -Seconds 30
    }
}

function Release-Lock {
    if (Test-Path $lockPath) {
        Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
        Write-Log 'INFO' 'Lock released'
    }
}

# --- Queue IO -----------------------------------------------------------------

function Get-Queue {
    $raw = Get-Content $queuePath -Raw -Encoding utf8
    return $raw | ConvertFrom-Json
}

function Set-Queue {
    param($Queue)
    $tmp = "$queuePath.tmp"
    $Queue | ConvertTo-Json -Depth 12 | Out-File $tmp -Encoding utf8
    Move-Item -Force $tmp $queuePath
}

function Update-Feature {
    param([string]$Id, [hashtable]$Patch)
    $queue = Get-Queue
    foreach ($f in $queue.features) {
        if ($f.id -eq $Id) {
            foreach ($k in $Patch.Keys) {
                if ($f.PSObject.Properties.Name -contains $k) {
                    $f.$k = $Patch[$k]
                } else {
                    Add-Member -InputObject $f -MemberType NoteProperty -Name $k -Value $Patch[$k] -Force
                }
            }
            break
        }
    }
    Set-Queue $queue
}

# --- Time windows -------------------------------------------------------------

function Get-WarsawNow {
    return [System.TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $TZ_WARSAW)
}

function Test-FlowBPauseWindow {
    $now = Get-WarsawNow
    if ($now.DayOfWeek.ToString() -notin $FLOW_B_DAYS) { return $false }
    $start = [DateTime]::Parse($FLOW_B_PAUSE_START)
    $end   = [DateTime]::Parse($FLOW_B_PAUSE_END)
    $t     = $now.TimeOfDay
    return ($t -ge $start.TimeOfDay -and $t -lt $end.TimeOfDay)
}

function Get-DailyDoneCount {
    # Count features in the queue marked done with completed_at in today's
    # Europe/Warsaw calendar day.
    $today = (Get-WarsawNow).Date
    $count = 0
    foreach ($f in (Get-Queue).features) {
        if ($f.status -eq 'done' -and $f.completed_at) {
            $ts = [System.TimeZoneInfo]::ConvertTimeFromUtc(
                [DateTime]::Parse($f.completed_at).ToUniversalTime(), $TZ_WARSAW
            ).Date
            if ($ts -eq $today) { $count++ }
        }
    }
    return $count
}

function Get-LastFlowBTag {
    $tag = (git tag --list 'flow-b-*' --sort=-creatordate | Select-Object -First 1) 2>$null
    if ($tag) { return $tag.Trim() }
    # Fallback: merge base of dev and the project's initial commit
    $firstCommit = (git rev-list --max-parents=0 HEAD | Select-Object -First 1).Trim()
    return $firstCommit
}

function Get-FeaturesSinceLastFlowB {
    $tag = Get-LastFlowBTag
    [int]((git rev-list --count "$tag..dev") -replace '\s', '')
}

# --- Claude invocation --------------------------------------------------------

function Invoke-FlowAClaude {
    <#
        Single `claude -p` call. Prompt instructs Claude to walk the 4-role
        pipeline within ONE session. Returns the parsed JSON wrapper plus the
        agent's structured verdict.

        The prompt requires the agent to terminate with a final JSON block:
            ```json
            { "verdict": "approve" | "changes-requested" | "needs_review",
              "rounds": <int>, "branch": "feat/<slug>",
              "blocking_issues": [<strings>] }
            ```
    #>
    param(
        [Parameter(Mandatory)] [string]$FeatureId,
        [Parameter(Mandatory)] [string]$Slug,
        [Parameter(Mandatory)] [string]$BriefPath,
        [Parameter(Mandatory)] [string]$BranchName,
        [Parameter(Mandatory)] [string]$DriftAwareness
    )

    $prompt = @"
You are orchestrating Flow A of the The-Dogs automated pipeline (see
docs/adr/0012-automated-two-flow-pipeline.md). Process feature **$FeatureId**
in one session.

Brief: $BriefPath
Branch: $BranchName (already checked out by the orchestrator from dev)

Drift awareness (commits since last flow-b-N tag):
$DriftAwareness

Execute these steps in order, delegating to the named subagents:

1. **explorer agent** — gather context, call Context7, output a handoff
   (read-only, no commits).
2. **implementer agent** — implement backend + frontend in one context.
   Commit WIP after each logical change. Run `./mvnw verify` and
   `npm run lint && npm test && npm run build`; only commit when green.
3. **test-writer agent** — add Vitest + Testcontainers + Playwright + axe
   tests. Do NOT run them.
4. **implementer agent** — run the new tests; fix failures; commit WIP.
5. **reviewer agent** — review the diff $BranchName..dev using its
   baseline code + security checklist.

If reviewer returns Fail items:
6. **implementer agent** — address the findings, commit WIP.
7. **reviewer agent** — re-review.

Maximum 2 reviewer rounds total. After the 2nd Fail, stop.

When you finish, emit a single final fenced ```json``` block on its own
that conforms to this schema:

```json
{
  "verdict": "approve | changes-requested | needs_review",
  "rounds": <integer>,
  "branch": "$BranchName",
  "context7_libraries": ["spring-boot@3.5", "angular@21", ...],
  "blocking_issues": ["<file:line> — <issue>", ...],
  "warnings": ["<file:line> — <issue>", ...]
}
```

- verdict=approve   → reviewer accepted; orchestrator squashes + opens PR.
- verdict=changes-requested → after 2 rounds reviewer still has Fail items;
                               orchestrator marks the feature needs_review.
- verdict=needs_review → you cannot proceed (e.g. spec ambiguous beyond
                          recovery); orchestrator marks needs_review.

Do not push, open a PR, or merge. The orchestrator handles all remote git
operations after parsing your final JSON block.
"@

    if ($DryRun) {
        Write-Log 'INFO' "[DRY-RUN] Would invoke claude -p for $FeatureId on $BranchName"
        return @{
            verdict   = 'approve'
            rounds    = 1
            branch    = $BranchName
            cost_usd  = 0
            session_id = '<dry-run>'
            raw       = '<dry-run>'
        }
    }

    $tmpPrompt = New-TemporaryFile
    Set-Content -Path $tmpPrompt -Value $prompt -Encoding utf8

    try {
        # YOLO mode: --permission-mode bypassPermissions. The orchestrator
        # runs unattended overnight; any permission prompt would deadlock it.
        # Safety relies on three layers OUTSIDE the Claude session:
        #   1. Each subagent's `tools:` field in .claude/agents/*.md limits
        #      what Claude can even attempt to invoke.
        #   2. dev branch protection (ADR-0009) blocks force-push and the
        #      orchestrator is the only thing that calls gh pr merge --auto.
        #   3. The orchestrator itself never calls destructive git on
        #      protected branches (squash + push to feat/* only).
        # If you need a less-trusting mode, swap to `acceptEdits` and
        # populate .claude/settings.json with permissions.allow patterns.
        $jsonOutput = & claude -p (Get-Content $tmpPrompt -Raw) `
            --output-format json `
            --permission-mode bypassPermissions 2>&1
        $exit = $LASTEXITCODE
    } finally {
        Remove-Item $tmpPrompt -Force -ErrorAction SilentlyContinue
    }

    if ($exit -ne 0) {
        # Detect Pro quota exhaustion. The CLI returns an error mentioning
        # rate limits / quota / 429 / usage limit; sleep 5.1h and retry.
        if ($jsonOutput -match '(?i)(rate.?limit|quota|too.?many.?requests|429|usage.?limit|exceed)') {
            throw [System.TimeoutException]::new('claude_quota_hit')
        }
        throw "claude -p failed (exit $exit): $jsonOutput"
    }

    $wrapper = $jsonOutput | ConvertFrom-Json
    $resultText = $wrapper.result

    # Extract the LAST fenced ```json block (the agent might emit intermediate
    # JSON snippets in its narration; only the final one is the verdict).
    $jsonBlocks = [regex]::Matches($resultText, '(?s)```json\s*(\{.*?\})\s*```')
    if ($jsonBlocks.Count -eq 0) {
        # Fallback: a final bare JSON object on the last lines.
        if ($resultText -match '(?ms)(\{[^{}]*"verdict"\s*:[^{}]*\})\s*$') {
            $structured = $Matches[1] | ConvertFrom-Json
        } else {
            throw "Could not parse final JSON block from claude output. Raw:`n$resultText"
        }
    } else {
        $structured = $jsonBlocks[$jsonBlocks.Count - 1].Groups[1].Value | ConvertFrom-Json
    }

    return @{
        verdict     = $structured.verdict
        rounds      = $structured.rounds
        branch      = $structured.branch
        blocking    = $structured.blocking_issues
        warnings    = $structured.warnings
        ctx7        = $structured.context7_libraries
        cost_usd    = $wrapper.total_cost_usd
        session_id  = $wrapper.session_id
        raw         = $resultText
    }
}

# --- Git + PR helpers ---------------------------------------------------------

function Initialize-FeatureBranch {
    param([string]$Slug)
    $branch = "feat/$Slug"
    git checkout dev 2>&1 | Out-Null
    git pull origin dev 2>&1 | Out-Null
    git checkout -b $branch 2>&1 | Out-Null
    return $branch
}

function Complete-FeatureMerge {
    param(
        [Parameter(Mandatory)] [string]$Branch,
        [Parameter(Mandatory)] [string]$FeatureId,
        [Parameter(Mandatory)] [string]$Title,
        [Parameter(Mandatory)] $Result   # hashtable from Invoke-FlowAClaude
    )

    # 1. Squash WIP commits on the branch into a single commit.
    $mergeBase = (git merge-base $Branch dev).Trim()
    git reset --soft $mergeBase 2>&1 | Out-Null

    $ctx7 = if ($Result.ctx7) { ($Result.ctx7 -join ', ') } else { 'n/a' }
    $today = Get-Date -Format 'yyyy-MM-dd'
    $closesLine = ''
    $feature = (Get-Queue).features | Where-Object id -eq $FeatureId | Select-Object -First 1
    if ($feature.source_issue) {
        $closesLine = "`n`nCloses #$($feature.source_issue)"
    }

    $commitMsg = @"
feat($($FeatureId.ToLower())): $Title

Implemented by Flow A automation (ADR-0012) on $today.
Reviewer verdict: $($Result.verdict) after $($Result.rounds) round(s).

Verified against Context7 on ${today}: $ctx7.$closesLine

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
"@

    git commit -m $commitMsg 2>&1 | Out-Null
    git push --force-with-lease origin $Branch 2>&1 | Out-Null

    # 2. Open PR; let gh enable auto-merge with squash.
    $prBody = @"
## Summary
Automated delivery via Flow A (see [ADR-0012](docs/adr/0012-automated-two-flow-pipeline.md)).

- Feature: **$FeatureId** — $Title
- Reviewer verdict: $($Result.verdict)
- Reviewer rounds: $($Result.rounds)
- Cost (this session): \$$($Result.cost_usd)
- Context7 verification (${today}): $ctx7

## Reviewer warnings (non-blocking)
$( ($Result.warnings | ForEach-Object { "- $_" }) -join "`n" )
$closesLine

🤖 Generated with [Claude Code](https://claude.com/claude-code)
"@

    $prUrl = gh pr create --base dev --head $Branch `
        --title "feat($($FeatureId.ToLower())): $Title" `
        --body $prBody 2>&1
    if ($LASTEXITCODE -ne 0) { throw "gh pr create failed: $prUrl" }

    # Enable auto-merge (preferred path: GitHub merges as soon as branch
    # protection + required checks are satisfied).
    $autoOut = gh pr merge $prUrl --squash --auto 2>&1
    $autoExit = $LASTEXITCODE
    $autoEnabled = ($autoExit -eq 0)
    if (-not $autoEnabled) {
        # Fallback: --auto can be rejected if the repo doesn't allow auto-merge
        # or if the PR already qualifies for direct merge. Log and fall through
        # to polling — we'll attempt a plain --squash merge once CI is green.
        Write-Log 'WARN' "gh pr merge --auto rejected ($autoOut). Will poll for green CI then attempt direct squash."
    }

    # 3. Poll until merged.
    for ($i = 0; $i -lt $POLL_PR_MAX; $i++) {
        $state = (gh pr view $prUrl --json state -q '.state').Trim()
        if ($state -eq 'MERGED') { return $prUrl }
        if ((-not $autoEnabled) -and ($state -eq 'OPEN')) {
            # Without --auto, we must trigger the merge ourselves once CI is green.
            $rollup = (gh pr view $prUrl --json statusCheckRollup -q '.statusCheckRollup' 2>$null)
            if ($rollup -and ($rollup -notmatch '"status":"IN_PROGRESS"|"conclusion":"FAILURE"|"conclusion":"TIMED_OUT"|"conclusion":"CANCELLED"')) {
                $mergeOut = gh pr merge $prUrl --squash --delete-branch 2>&1
                if ($LASTEXITCODE -eq 0) {
                    Write-Log 'INFO' "Direct squash merge succeeded for $prUrl"
                } else {
                    Write-Log 'WARN' "Direct squash merge attempt failed: $mergeOut"
                }
            }
        }
        if ($state -eq 'CLOSED') { throw "PR $prUrl closed without merge" }
        Start-Sleep -Seconds $POLL_PR_SECONDS
    }
    throw "PR $prUrl did not merge within $(($POLL_PR_MAX * $POLL_PR_SECONDS) / 60) minutes"
}

function Sync-IssueOnDone {
    param([string]$FeatureId)
    $feature = (Get-Queue).features | Where-Object id -eq $FeatureId | Select-Object -First 1
    if (-not $feature.source_issue) { return }
    gh issue edit $feature.source_issue `
        --remove-label 'status:queued' `
        --add-label 'status:waiting-review' 2>&1 | Out-Null
    gh issue comment $feature.source_issue `
        --body "Resolved by feature $FeatureId; PR merged. Issue marked waiting-review for owner audit." 2>&1 | Out-Null
}

# --- Main loop ----------------------------------------------------------------

function Get-NextQueuedFeature {
    $queue = Get-Queue
    # Order: priority asap first, then by id.
    $sorted = $queue.features | Where-Object status -eq 'queued' | Sort-Object -Stable @{Expression={ if ($_.priority -eq 'asap') { 0 } elseif ($_.priority -eq 'normal') { 1 } else { 2 } }}
    return $sorted | Select-Object -First 1
}

function Get-DriftAwareness {
    $tag = Get-LastFlowBTag
    $log = git log --oneline "$tag..dev" 2>$null
    $files = git diff --name-only "$tag..dev" 2>$null | Sort-Object -Unique
    if (-not $log) { return "No commits since $tag." }
    return "Since ${tag}:`n" + ($log -join "`n") + "`n`nChanged files:`n" + ($files -join "`n")
}

function Trigger-FlowBAdHoc {
    Write-Log 'WARN' "Per-window cap ($WINDOW_CAP) reached. Triggering Flow B ad-hoc."
    Release-Lock
    & (Join-Path $PSScriptRoot 'run-flow-b.ps1')
    Acquire-Lock
}

function Recover-StaleInProgress {
    # On startup, any feature still marked `in_progress` is from a crashed
    # previous run (orchestrator never finishes a feature without flipping
    # status to done|failed|needs_review). Reset to queued so the next
    # iteration picks it back up; bump attempts so we don't infinite-loop.
    $queue = Get-Queue
    $changed = $false
    foreach ($f in $queue.features) {
        if ($f.status -eq 'in_progress') {
            Write-Log 'WARN' "Recovering stale in_progress feature $($f.id) (attempts: $($f.attempts))"
            $f.status = 'queued'
            $f.last_error = 'recovered_from_crash'
            $changed = $true
        }
    }
    if ($changed) { Set-Queue $queue }
}

# === Entry point ==============================================================

Write-Log 'INFO' '=== run-features.ps1 starting ==='
Acquire-Lock
Recover-StaleInProgress

try {
    $processed = 0
    while ($processed -lt $MaxFeatures) {

        # Throttles
        if (Test-FlowBPauseWindow) {
            Write-Log 'INFO' 'In Flow B preferential window (02:00-04:00). Sleeping until 04:01.'
            $now = Get-WarsawNow
            $resumeAt = $now.Date.AddHours(4).AddMinutes(1)
            $secs = [Math]::Max(60, [int]($resumeAt - $now).TotalSeconds)
            Start-Sleep -Seconds $secs
            continue
        }

        if ((Get-DailyDoneCount) -ge $DAILY_CAP) {
            Write-Log 'INFO' "Daily cap ($DAILY_CAP) reached. Sleeping until tomorrow 00:01 local."
            $now = Get-WarsawNow
            $resumeAt = $now.Date.AddDays(1).AddMinutes(1)
            $secs = [Math]::Max(60, [int]($resumeAt - $now).TotalSeconds)
            Start-Sleep -Seconds $secs
            continue
        }

        if ((Get-FeaturesSinceLastFlowB) -ge $WINDOW_CAP) {
            Trigger-FlowBAdHoc
            continue
        }

        $feature = Get-NextQueuedFeature
        if (-not $feature) {
            Write-Log 'INFO' 'Queue empty. Exiting.'
            break
        }

        # --- Per-feature work ---------------------------------------------
        $featureId = $feature.id
        $slug      = $feature.slug
        $brief     = Join-Path $repoRoot $feature.brief_path
        if (-not (Test-Path $brief)) {
            Write-Log 'ERROR' "Brief not found at $brief; marking $featureId failed."
            Update-Feature $featureId @{
                status     = 'failed'
                last_error = "brief_missing: $brief"
            }
            continue
        }

        Write-Log 'INFO' "=== Processing $featureId ($slug) ==="
        if (-not $DryRun) {
            Update-Feature $featureId @{
                status     = 'in_progress'
                started_at = (Get-Date).ToString('o')
                attempts   = ($feature.attempts ?? 0) + 1
            }
        }

        try {
            $branch  = Initialize-FeatureBranch -Slug $slug
            $drift   = Get-DriftAwareness
            $result  = $null

            # Inner retry loop for quota-hit only.
            $retried = $false
            while ($true) {
                try {
                    $result = Invoke-FlowAClaude `
                        -FeatureId $featureId `
                        -Slug $slug `
                        -BriefPath $feature.brief_path `
                        -BranchName $branch `
                        -DriftAwareness $drift
                    break
                } catch [System.TimeoutException] {
                    if ($_.Exception.Message -ne 'claude_quota_hit') { throw }
                    Write-Log 'WARN' "Pro quota hit; sleeping $($QUOTA_SLEEP_SEC / 60) minutes."
                    Start-Sleep -Seconds $QUOTA_SLEEP_SEC
                }
            }

            Update-Feature $featureId @{
                last_session_id = $result.session_id
                last_cost_usd   = $result.cost_usd
                last_verdict    = $result.verdict
            }

            switch ($result.verdict) {
                'approve' {
                    if ($DryRun) {
                        Write-Log 'INFO' "[DRY-RUN] Would merge $branch into dev."
                    } else {
                        $prUrl = Complete-FeatureMerge `
                            -Branch $branch -FeatureId $featureId `
                            -Title $feature.title -Result $result
                        Sync-IssueOnDone $featureId
                        Update-Feature $featureId @{
                            status       = 'done'
                            completed_at = (Get-Date).ToString('o')
                            merged_pr    = $prUrl
                        }
                        Write-Log 'INFO' "$featureId merged -> $prUrl"
                    }
                    $processed++
                }
                'changes-requested' {
                    Write-Log 'WARN' "$featureId hit reviewer round limit; needs_review."
                    Update-Feature $featureId @{ status = 'needs_review' }
                }
                'needs_review' {
                    Write-Log 'WARN' "$featureId returned needs_review verdict."
                    Update-Feature $featureId @{ status = 'needs_review' }
                }
                default {
                    throw "Unknown verdict: $($result.verdict)"
                }
            }
        } catch {
            $errMsg = $_.Exception.Message
            Write-Log 'ERROR' "$featureId attempt failed: $errMsg"
            if (($feature.attempts ?? 0) -lt $RETRY_LIMIT) {
                Update-Feature $featureId @{
                    status     = 'queued'
                    last_error = $errMsg
                }
                Write-Log 'INFO' "$featureId requeued for retry."
            } else {
                Update-Feature $featureId @{
                    status     = 'failed'
                    last_error = $errMsg
                }
                Write-Log 'ERROR' "$featureId marked failed after retry limit."
            }
        }
    }
} finally {
    Release-Lock
    Write-Log 'INFO' '=== run-features.ps1 stopping ==='
}
