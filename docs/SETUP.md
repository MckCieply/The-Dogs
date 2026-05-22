# The-Dogs — Operator Setup Guide

> One-time setup of the production machine that runs the automation pipeline ([ADR-0012](adr/0012-automated-two-flow-pipeline.md)) and hosts the prod stack ([ADR-0011](adr/0011-hosting-strategy.md)). After this guide, you can run Flow A on demand and Flow B on a schedule.
>
> Companion document: [`scripts/README.md`](../scripts/README.md) — script internals, flag reference, troubleshooting.

---

## 1. Prerequisites

### Hardware
- The spare laptop chosen per [ADR-0011](adr/0011-hosting-strategy.md). Stays powered 24/7.
- ≥ 16 GB RAM, ≥ 100 GB free disk (prod Docker volumes + AI cache + build artefacts).
- Stable internet (Cloudflare Tunnel + GitHub API + Claude API).

### Accounts
- **Claude Pro** subscription (active OAuth login).
- **GitHub** account with admin on `MckCieply/The-Dogs`.
- (Optional, later) **NVD API key** for OWASP enforcement — see [AI_NATIVE.md §8 #15](AI_NATIVE.md).

### Software (versions pinned)
| Tool | Required version | Install |
|---|---|---|
| PowerShell | 7.0+ (`pwsh`) | `winget install Microsoft.PowerShell` |
| Claude Code CLI | latest | `npm install -g @anthropic-ai/claude-code` |
| GitHub CLI | 2.40+ | `winget install GitHub.cli` |
| Docker Desktop | latest (with WSL2 backend) | `winget install Docker.DockerDesktop` |
| Git | any recent | `winget install Git.Git` |
| Node.js | 22 LTS | `winget install OpenJS.NodeJS.LTS` |
| Java | 25 (Temurin) | `winget install EclipseAdoptium.Temurin.25.JDK` |

> Restart your shell (or the whole machine) after `winget install` runs so PATH updates pick up.

---

## 2. One-time install + auth

Run each block in PowerShell **as the user who will own the orchestrator** (not Administrator — the lock file and logs live under the user's profile path).

### 2.1 Install the tools

```powershell
winget install --id Microsoft.PowerShell -e
winget install --id GitHub.cli -e
winget install --id Docker.DockerDesktop -e
winget install --id Git.Git -e
winget install --id OpenJS.NodeJS.LTS -e
winget install --id EclipseAdoptium.Temurin.25.JDK -e

# Restart PowerShell so PATH refresh takes effect, then:
npm install -g @anthropic-ai/claude-code
```

### 2.2 Allow PowerShell to run our scripts

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

### 2.3 Verify everything is on PATH

```powershell
pwsh -Version              # 7.x
claude --version
gh --version
git --version
node --version             # v22.x
java --version             # 25.x
docker --version
```

If any tool reports "not recognised", restart the shell once more, then re-check.

### 2.4 Authenticate

```powershell
# Claude — opens browser; sign in with your Pro account.
claude login

# GitHub — pick HTTPS + login with web browser; grant repo + workflow scopes.
gh auth login
gh auth status              # confirm scopes include: repo, workflow

# Docker — sign in via Docker Desktop UI; verify daemon is running.
docker run --rm hello-world
```

---

## 3. Clone the repo and verify

### 3.1 Clone

```powershell
mkdir D:\Code   # or wherever you want the repo
cd D:\Code
git clone https://github.com/MckCieply/The-Dogs.git
cd The-Dogs
```

### 3.2 Sanity check the queue and the scripts

```powershell
# Show the queue.
Get-Content scripts\features.json | ConvertFrom-Json | Select-Object next_id, @{n='count';e={$_.features.Count}}

# Should print: next_id=3, count=2  (F001 fix-ci-scaffold, F002 dogs-crud)
```

### 3.3 Dry-run Flow A on the first feature

```powershell
pwsh scripts\run-features.ps1 -MaxFeatures 1 -DryRun
```

What this does:
- Acquires `orchestrator.lock`.
- Checks daily cap (3), per-window cap (10), Flow B pause window.
- Picks the next `queued` feature (F001 fix-ci-scaffold — `priority:asap`).
- Logs the intent to invoke claude but does **not** actually call it or modify git remotely.
- Releases the lock.

Open `orchestrator.log` at the repo root to read what it would have done.

If the dry run errors out, **stop and fix before going further** — see `scripts/README.md` §Troubleshooting.

---

## 4. First real run (F001 → fix-ci-scaffold)

This is the first time the pipeline does live work. Expect it to take 10–30 minutes including Pro 5h-window rate limits.

### 4.1 Pull latest dev

```powershell
git checkout dev
git pull origin dev
```

### 4.2 Run one feature

```powershell
pwsh scripts\run-features.ps1 -MaxFeatures 1
```

What you'll see in real time:
1. `Lock acquired` — exclusive control of the orchestrator.
2. `=== Processing F001 (fix-ci-scaffold) ===` — feature dequeue.
3. Orchestrator branches `feat/fix-ci-scaffold` off `dev`, invokes `claude -p` with the Flow A prompt.
4. Claude walks through `explorer → implementer → test-writer → implementer (run tests) → reviewer` in one session.
5. On `verdict: approve`, orchestrator squashes WIP commits, pushes, opens PR with `--squash --auto`, polls until merged.
6. `F001 merged -> <PR URL>` — done.

### 4.3 If the run errors

| Symptom | Likely cause | Action |
|---|---|---|
| `claude_quota_hit` | Pro 5h window full | Script auto-sleeps 5.1h and retries. Let it run. |
| `gh pr merge --auto` rejected | "Allow auto-merge" repo setting off | `gh api -X PATCH /repos/MckCieply/The-Dogs -f allow_auto_merge=true` |
| Reviewer returns `changes-requested` after 2 rounds | implementer + reviewer disagreed | Status flips to `needs_review`; inspect the branch manually; queue moves on |
| `brief_missing: <path>` | spec file not on disk | Pull latest dev; verify `docs/specs/<slug>.md` exists |
| `Could not parse final JSON block` | Claude's output didn't end with the required JSON block | Re-run; if reproducible, file an issue — likely a prompt tuning bug |

Full details in [`scripts/README.md` §Troubleshooting](../scripts/README.md).

### 4.4 After F001 succeeds

```powershell
# Continue with F002 (dogs-crud).
pwsh scripts\run-features.ps1 -MaxFeatures 1
```

Or just `pwsh scripts\run-features.ps1` to drain the entire queue (subject to daily/window caps).

---

## 5. Continuous operation (Flow A)

Pick **one** of these models:

### Option A — Always-on terminal

Leave a terminal open running:
```powershell
pwsh scripts\run-features.ps1
```

The script sleeps through caps, quota limits, and Flow B pause windows. Restart it after machine reboots.

### Option B — Scheduled Task

Register a recurring task (runs at boot + every hour as a heartbeat):

```powershell
$repoRoot = 'D:\Code\The-Dogs'

$action = New-ScheduledTaskAction `
    -Execute 'pwsh.exe' `
    -Argument "-NoProfile -File `"$repoRoot\scripts\run-features.ps1`"" `
    -WorkingDirectory $repoRoot

$triggerBoot = New-ScheduledTaskTrigger -AtStartup
$triggerHourly = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
    -RepetitionInterval (New-TimeSpan -Hours 1) `
    -RepetitionDuration ([TimeSpan]::MaxValue)

Register-ScheduledTask `
    -TaskName 'the-dogs-flow-a' `
    -Description 'Flow A orchestrator (ADR-0012)' `
    -Action $action `
    -Trigger @($triggerBoot, $triggerHourly) `
    -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 6)) `
    -RunLevel Limited
```

The orchestrator's `orchestrator.lock` ensures only one instance runs at a time.

---

## 6. Continuous operation (Flow B)

Register Flow B for Mon/Wed/Fri at 02:00 Europe/Warsaw. **One-time, as Administrator:**

```powershell
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

Verify it shows up:

```powershell
Get-ScheduledTask -TaskName 'the-dogs-flow-b' | Get-ScheduledTaskInfo
```

To remove:
```powershell
Unregister-ScheduledTask -TaskName 'the-dogs-flow-b' -Confirm:$false
```

---

## 7. Promoting Flow B findings into the queue

When Flow B opens issues, triage in the GitHub UI: add the label `status:approved` to anything worth fixing. Then on the laptop:

```powershell
pwsh scripts\promote-issues.ps1
```

This pulls approved issues, generates `F<NNN>` ids, prepends entries to `features.json` with `priority:asap` and `source_issue:<n>`, and flips the issue label to `status:queued`. The next Flow A cycle picks them up at the head of the queue.

You can chain it inline:
```powershell
pwsh scripts\promote-issues.ps1; pwsh scripts\run-features.ps1
```

---

## 8. Production stack ([ADR-0011](adr/0011-hosting-strategy.md))

Separate from the orchestrator. Brings up the actual Dog-trainers SaaS via Cloudflare Tunnel:

```powershell
# Fill .env with JWT_SECRET and CLOUDFLARE_TUNNEL_TOKEN.
Copy-Item .env.example .env
notepad .env

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

The Cloudflare Tunnel handles HTTPS + DDoS + IP-hiding. No port forwarding needed.

To stop:
```powershell
docker compose -f docker-compose.yml -f docker-compose.prod.yml down
```

Backups (planned, per [ADR-0011](adr/0011-hosting-strategy.md) Open):
- Daily `pg_dump` to Backblaze B2 or equivalent. Not yet scripted. **Do not store real user data before this lands.**

---

## 9. Day-to-day reference

| Action | Command |
|---|---|
| See what's in the queue | `Get-Content scripts\features.json` |
| Single-feature run | `pwsh scripts\run-features.ps1 -MaxFeatures 1` |
| Drain queue (cap-throttled) | `pwsh scripts\run-features.ps1` |
| One-off Flow B (e.g. testing) | `pwsh scripts\run-flow-b.ps1` |
| Promote approved issues | `pwsh scripts\promote-issues.ps1` |
| Tail the log | `Get-Content orchestrator.log -Tail 50 -Wait` |
| See last 5 PRs | `gh pr list --base dev --state merged --limit 5` |
| List `flow-b-N` tags | `git tag --list 'flow-b-*'` |
| Force-release stale lock | `Remove-Item orchestrator.lock` (only when no script is running) |

---

## 10. After 4 weeks

Per [ADR-0012 §"Open — Empirical re-tune after 4 weeks"](adr/0012-automated-two-flow-pipeline.md), revisit:

- Daily cap (currently 3) — bump up or down based on actual Pro budget burn.
- Per-window cap (currently 10) — bump up if Flow B review context is comfortably under 150k tokens.
- Flow B cadence (currently 3×/week) — tune based on observed docs-drift and security finding rate.
- `pwa-auditor` cost — if Claude in Chrome MCP tokens dominate Flow B, consider gating it on touched-route count.

Open a PR with the proposed numeric changes; the ADR explicitly allows numeric tuning via amendment without a new ADR.

---

## See also

- [`scripts/README.md`](../scripts/README.md) — script internals, flag reference, troubleshooting
- [`docs/AI_NATIVE.md`](AI_NATIVE.md) — agent team and pipeline overview
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — stack and conventions
- [`docs/adr/0012-automated-two-flow-pipeline.md`](adr/0012-automated-two-flow-pipeline.md) — pipeline ADR
- [`docs/adr/0011-hosting-strategy.md`](adr/0011-hosting-strategy.md) — laptop + Cloudflare Tunnel
