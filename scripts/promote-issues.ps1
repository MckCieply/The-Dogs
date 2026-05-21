#Requires -Version 7.0
<#
.SYNOPSIS
  Promotes owner-approved Flow B issues into scripts/features.json.

.DESCRIPTION
  Implements the issue lifecycle from ADR-0012:

  1. Lists GitHub issues with label `status:approved`.
  2. For each, generates the next F<NNN> id, prepends a feature entry to
     features.json with priority:asap and source_issue:<n>.
  3. Comments on the issue ("Promoted to feature queue as F<NNN>").
  4. Replaces label `status:approved` with `status:queued`.

  Safe to run repeatedly — re-running on an already-queued issue is a
  no-op (the label flip ensures it won't appear again).

.PARAMETER QueuePath
  Path to features.json.

.PARAMETER DryRun
  Show what would happen, don't write files or call gh.

.EXAMPLE
  pwsh scripts/promote-issues.ps1
  pwsh scripts/promote-issues.ps1 -DryRun

.NOTES
  Intended to be invoked at the start of each run-features.ps1 cycle (or
  via cron / Task Scheduler) so freshly-approved findings hit the queue
  promptly without manual editing of features.json.
#>
[CmdletBinding()]
param(
    [string]$QueuePath,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$queuePath = if ($QueuePath) { Resolve-Path $QueuePath } else { Join-Path $PSScriptRoot 'features.json' }

function Get-Queue { Get-Content $queuePath -Raw -Encoding utf8 | ConvertFrom-Json }

function Set-Queue {
    param($Queue)
    $tmp = "$queuePath.tmp"
    $Queue | ConvertTo-Json -Depth 12 | Out-File $tmp -Encoding utf8
    Move-Item -Force $tmp $queuePath
}

function Get-ApprovedIssues {
    $json = gh issue list --label 'status:approved' --state open `
        --json number,title,labels,url --limit 100 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "gh issue list failed: $json"
    }
    return $json | ConvertFrom-Json
}

function New-FeatureId {
    param([int]$NextId)
    return 'F' + $NextId.ToString('D3')
}

function ConvertTo-Slug {
    param([string]$Title)
    $slug = $Title.ToLower() `
        -replace '[^a-z0-9\s-]', '' `
        -replace '\s+', '-' `
        -replace '-+', '-'
    return $slug.Trim('-').Substring(0, [Math]::Min(50, $slug.Length))
}

# === Entry point ==============================================================

Write-Host "Loading queue from $queuePath"
$queue = Get-Queue
$nextId = [int]$queue.next_id

Write-Host "Querying GitHub for status:approved issues..."
$approved = Get-ApprovedIssues
if (-not $approved -or $approved.Count -eq 0) {
    Write-Host "No approved issues to promote."
    exit 0
}

Write-Host "Found $($approved.Count) approved issue(s)."

# Pre-compute the set of source_issue numbers already in features.json to
# avoid duplicating an issue that was promoted then re-labelled by mistake.
$alreadyQueued = @{}
foreach ($f in $queue.features) {
    if ($f.source_issue) { $alreadyQueued[[int]$f.source_issue] = $true }
}

$newEntries = @()
foreach ($issue in $approved) {
    $num = [int]$issue.number
    if ($alreadyQueued.ContainsKey($num)) {
        Write-Host "  #$num already in queue; will only flip label."
        if (-not $DryRun) {
            gh issue edit $num --remove-label 'status:approved' --add-label 'status:queued' 2>&1 | Out-Null
        }
        continue
    }

    $featureId = New-FeatureId -NextId $nextId
    $slug      = ConvertTo-Slug -Title $issue.title

    Write-Host "  Promoting #$num -> $featureId ($slug)"

    $entry = [PSCustomObject]@{
        id              = $featureId
        slug            = $slug
        title           = $issue.title
        brief_path      = "docs/specs/$slug.md"
        status          = 'queued'
        priority        = 'asap'
        source_issue    = $num
        attempts        = 0
        last_session_id = $null
        last_cost_usd   = $null
        last_verdict    = $null
        last_error      = $null
        created_at      = (Get-Date).ToString('o')
        started_at      = $null
        completed_at    = $null
        merged_pr       = $null
    }

    $newEntries += $entry
    $nextId++

    if (-not $DryRun) {
        gh issue comment $num `
            --body "Promoted to feature queue as $featureId (priority: asap). Will be picked up on the next run-features.ps1 cycle." 2>&1 | Out-Null
        gh issue edit $num `
            --remove-label 'status:approved' `
            --add-label 'status:queued' 2>&1 | Out-Null
    }
}

if ($newEntries.Count -gt 0) {
    # Prepend (asap entries first) by concatenating then re-assigning.
    $queue.features = @($newEntries) + @($queue.features)
    $queue.next_id  = $nextId

    if ($DryRun) {
        Write-Host "[DRY-RUN] Would add $($newEntries.Count) entries; next_id -> $nextId"
        $queue | ConvertTo-Json -Depth 12 | Write-Host
    } else {
        Set-Queue $queue
        Write-Host "Queue updated. Added $($newEntries.Count) entries; next_id is now $nextId."
    }
} else {
    Write-Host "No new entries added (all approved issues were already in the queue)."
}
