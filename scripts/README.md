# Automation Scripts

Orchestrator scripts implementing the two-flow pipeline from [ADR-0012](../docs/adr/0012-automated-two-flow-pipeline.md).

| Script | Role | Trigger |
| --- | --- | --- |
| `run-features.ps1` | Flow A — drains `features.json` one feature at a time | Manual / continuous run, or scheduled (any time outside the Flow B window) |
| `run-flow-b.ps1` | Flow B — adversarial audit + docs update against the diff since the last `flow-b-N` tag | Windows Task Scheduler, Mon/Wed/Fri 02:00 Europe/Warsaw. Also triggered ad-hoc by `run-features.ps1` when the per-window cap of 10 features fires early. |
| `promote-issues.ps1` | Promotes owner-approved Flow B issues into `features.json` | Run before each `run-features.ps1` cycle (or on cron) |
| `features.json` | The Flow A queue | Edited by `promote-issues.ps1`, by hand, and updated in place by `run-features.ps1` |

## Prerequisites

- **Windows 11**, the same laptop that runs prod per [ADR-0011](../docs/adr/0011-hosting-strategy.md). Other Windows boxes work too — only the Task Scheduler step is host-specific.
- **PowerShell 7+** (`pwsh`). The scripts use ternary, null-coalescing, and other 7+ features. Windows PowerShell 5.1 will refuse to run them.
- **Claude Code CLI** (`claude`). Log in interactively once with `claude login` — the OAuth token is persisted and the orchestrator reuses it. The user must have an active Claude Pro subscription.
- **GitHub CLI** (`gh`). Run `gh auth login` once against this repo.
- **Docker Desktop** (for `run-flow-b.ps1` only — it boots `docker compose up` to give `pwa-auditor` a live stack).
- **Git** on PATH (any recent version).

### Claude Pro and the June 15 2026 cutoff

Until **2026-06-15**, headless `claude -p` calls draw from your Pro weekly quota via OAuth — no API key needed. After that date, Anthropic moves headless / Agent SDK usage onto a separate monthly credit pool. Re-check the support article ([Use the Claude Agent SDK with your Claude plan](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan)) before the cutoff and either top up the Agent SDK credit or set `ANTHROPIC_API_KEY` to a regular API key.

## First-time setup

```powershell
# 1. Verify dependencies.
pwsh -Version    # expect 7.x
claude --version
gh --version
git --version
docker --version

# 2. Authenticate.
claude login         # opens browser; follows OAuth.
gh auth login        # GitHub CLI against this repo.

# 3. Sanity check — dry-run Flow A on the example feature.
pwsh scripts/run-features.ps1 -MaxFeatures 1 -DryRun
```

## Running Flow A

Flow A enforces caps from ADR-0012:

- **Hard daily cap:** 3 features per calendar day (Europe/Warsaw).
- **Per-window cap:** 10 features between Flow B runs; on hit triggers Flow B ad-hoc.
- **Flow B preferential window:** Mon/Wed/Fri 02:00–04:00 — Flow A pauses.

```powershell
# Drain the queue continuously (sleeps when caps hit).
pwsh scripts/run-features.ps1

# Process at most one feature for testing.
pwsh scripts/run-features.ps1 -MaxFeatures 1
```

The script writes:

- `orchestrator.log` — append-only event log at repo root.
- `orchestrator.lock` — PID + flow name (acquired/released around work).
- `features.json` — in-place updates to feature status / session_id / cost.

### What it does per feature

1. Acquires `orchestrator.lock`. Waits if Flow B is mid-run.
2. Checks caps. Sleeps to next local midnight on daily cap hit; triggers Flow B on per-window cap hit.
3. Dequeues the next `queued` feature (priority `asap` first, then by id).
4. Branches `feat/<slug>` off `dev`.
5. Calls `claude -p` once with a prompt that walks through:
   - explorer → implementer → test-writer → implementer (run tests) → reviewer
   - Loops back to implementer on reviewer Fail (max 2 rounds).
6. Parses the final JSON block from the agent (`verdict`, `rounds`, `branch`, …).
7. On `approve`: squashes WIP, opens PR via `gh pr create`, enables auto-merge with `gh pr merge --squash --auto`, polls until merged.
8. Updates `features.json` with `done` / `failed` / `needs_review`.
9. If the feature had `source_issue`, comments on the issue and adds label `status:waiting-review`.

## Running Flow B

Manually (e.g. first verification):

```powershell
pwsh scripts/run-flow-b.ps1
```

### Scheduling Flow B with Task Scheduler

Register a scheduled task to run Flow B Mon/Wed/Fri at 02:00 (Europe/Warsaw). Run **once**, as Administrator:

```powershell
# Adjust the path to where you cloned The-Dogs.
$repoRoot = 'D:\Code\The-Dogs'

$action = New-ScheduledTaskAction `
    -Execute 'pwsh.exe' `
    -Argument "-NoProfile -File `"$repoRoot\scripts\run-flow-b.ps1`"" `
    -WorkingDirectory $repoRoot

$trigger = New-ScheduledTaskTrigger `
    -Weekly -DaysOfWeek Monday, Wednesday, Friday `
    -At '02:00'

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -RunOnlyIfNetworkAvailable `
    -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Hours 4)

Register-ScheduledTask `
    -TaskName 'the-dogs-flow-b' `
    -Description 'Flow B audit pass (ADR-0012)' `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -User "$env:USERDOMAIN\$env:USERNAME" `
    -RunLevel Limited
```

To verify or remove:

```powershell
Get-ScheduledTask -TaskName 'the-dogs-flow-b' | Get-ScheduledTaskInfo
Unregister-ScheduledTask -TaskName 'the-dogs-flow-b' -Confirm:$false
```

### What Flow B does

1. Acquires `orchestrator.lock`. Waits if Flow A is mid-feature.
2. `git checkout dev && git pull`. Captures the dev SHA as the future `flow-b-<N>` anchor.
3. `docker compose up -d`; polls `http://localhost:4200` until healthy (max 2 min).
4. Creates branch `chore/flow-b-<YYYY-MM-DD>` (for docs-writer commits).
5. Calls `claude -p` once with a prompt walking through:
   - security-reviewer → pwa-auditor → docs-writer
   - Each agent opens GitHub issues for its findings (`gh issue create` with `kind:*` and `severity:*` labels).
6. Parses the final JSON block (`security_issues`, `pwa_issues`, `docs_issues`, `docs_files_changed`, `summary`).
7. If docs-writer made changes, commits onto `chore/flow-b-<date>`, pushes, opens a PR to `dev`.
8. Tags the captured dev SHA as `flow-b-<N>`, pushes the tag.
9. Tears down `docker compose down`. Releases the lock.

## Promoting Flow B findings into the queue

After Flow B opens issues, the owner triages them in the GitHub UI and adds the label `status:approved` to anything worth fixing.

Then:

```powershell
pwsh scripts/promote-issues.ps1
```

This:

1. Lists open issues with label `status:approved`.
2. For each, picks the next `F<NNN>` id and prepends an entry to `features.json` with `priority:asap` and `source_issue:<n>`.
3. Comments on the issue and flips `status:approved` → `status:queued`.

The next `run-features.ps1` cycle picks them up at the head of the queue.

You can also wire this into a chained run:

```powershell
pwsh scripts/promote-issues.ps1; pwsh scripts/run-features.ps1
```

## Authoring a feature brief

`run-features.ps1` reads each entry's `brief_path` from `features.json`. For an entry created by `promote-issues.ps1` (or by hand), make sure a brief exists before the orchestrator dequeues it:

```
docs/specs/<slug>.md
```

Use the [`/spec-new` skill](../docs/AI_NATIVE.md#5-skills-planned) to scaffold one.

If the brief is missing when `run-features.ps1` reaches the feature, the feature is marked `failed` with `last_error: brief_missing: <path>` and the queue continues.

## Troubleshooting

### Lock won't release

If a script crashed without releasing the lock and the wait is stuck:

```powershell
Get-Content .\orchestrator.lock    # see which PID held it
Remove-Item .\orchestrator.lock    # only if you're sure no script is running
```

Locks older than 5h 15min are auto-removed by either script on next start.

### Claude returns a quota error

Both scripts catch `rate.limit|quota|too.many.requests` substrings, sleep 5.1h, and retry. If the actual message doesn't match, the script throws and exits — open `orchestrator.log`, find the raw error, and adjust the regex in the `Invoke-Flow*Claude` function.

### `gh pr merge --auto` reports auto-merge isn't allowed

`dev` branch protection per [ADR-0009](../docs/adr/0009-branching-strategy.md) needs at least:

- `ci-backend` and `ci-frontend` as required status checks
- "Allow auto-merge" enabled in repo settings (Settings → General → Pull Requests)

Without those, `--auto` fails immediately. If you don't have CI workflows yet, drop `--auto` from `Complete-FeatureMerge` (in `run-features.ps1`) and rely on a normal `gh pr merge --squash` post-poll — easier to revert later.

### Docker compose isn't healthy in time

`run-flow-b.ps1` polls `http://localhost:4200` for 2 minutes. If the stack needs longer (cold cache, fresh `npm install`), bump `DOCKER_HEALTH_WAIT_SEC` at the top of the script.

### Pro quota burned faster than expected

Check actual cost in `orchestrator.log` (every claude call logs `cost_usd` from the JSON wrapper). If sustained burn exceeds the budget reserved by ADR-0012 (~2.22M tokens/week committed), either:

- Lower the daily cap (default 3 in `run-features.ps1`).
- Skip features more aggressively from `features.json` (re-prioritize).
- Consider switching to `ANTHROPIC_API_KEY` mode after the 2026-06-15 cutoff.

## File map

```
scripts/
├── README.md             # this file
├── features.json         # Flow A queue
├── run-features.ps1      # Flow A driver
├── run-flow-b.ps1        # Flow B driver
└── promote-issues.ps1    # issue -> queue promotion

# At repo root, written by the scripts at runtime:
├── orchestrator.lock     # PID + flow name during runs (ephemeral)
└── orchestrator.log      # append-only event log
```

Add both `orchestrator.lock` and `orchestrator.log` to `.gitignore` — they are runtime artifacts, not source.
