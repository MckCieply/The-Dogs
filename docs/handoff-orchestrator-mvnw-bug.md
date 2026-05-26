# Session Handoff — Orchestrator Post-Processing Bug

**Date:** 2026-05-26  
**Branch:** `feat/fix-ci-scaffold`  
**Severity:** High — all features fail after successful Claude completion

---

## What Happened

The orchestrator ran for ~55 continuous hours (2026-05-24 01:31 → 2026-05-26 07:52) across 10 Claude sessions. **Claude completed every feature with `verdict: "approve"`.** None of them made it into `dev`.

The failure happened in `Complete-FeatureMerge`, in the post-processing step that ran immediately after Claude returned. The orchestrator tried to run Spotless via the Maven wrapper:

```powershell
# scripts/.history/run-features_20260524185643.ps1:447
& (Join-Path $repoRoot 'mvnw') spotless:apply
```

Two bugs in one line:

1. **Wrong path.** `$repoRoot` resolves to the project root (`The-Dogs/`). The Maven wrapper lives at `backend/mvnw` and `backend/mvnw.cmd`, not the project root.
2. **Wrong file.** `mvnw` is a `#!/bin/bash` shell script. PowerShell on Windows cannot execute it. The Windows wrapper is `mvnw.cmd`.

The error PowerShell throws every time:
```
The term 'C:\Users\mwppl\Desktop\Code\The-Dogs\mvnw' is not recognized as a name
of a cmdlet, function, script file, or executable program.
```

This appeared in every single `feature_failed` event across all 10 sessions.

---

## Sessions and Cost Lost

| Feature | Sessions | Cost | Status |
|---|---|---|---|
| F001 fix-ci-scaffold | 1 | — | PR #14 did open and merge; orchestrator timed out polling (90 min). Done. |
| AUTH-01 auth-core-jwt | 1 | — | PR #16 did open and merge; same timeout. Done. |
| I18N-01 i18n-frontend | **4** | ~$12.26 | Formatter crashed before PR was created. Work in untracked files. |
| AUTH-02 auth-login | **3** | ~$12.22 | Same. Work on `feat/auth-login` branch. |
| AUTH-03 auth-refresh-logout | 1 (cut short by quota) | ~? | No `claude_done` event — Claude didn't finish. Partial work as untracked files. |

Logs: `orchestrator.log` and `logs/pipeline.jsonl` at project root.

---

## Current Working Tree State

The orchestrator left the working tree dirty. These files are Claude's work from AUTH-03 and I18N-01 sessions that were never committed:

**AUTH-03 partial (backend):**
- `backend/src/main/java/com/thedogs/modules/auth/RefreshToken.java`
- `backend/src/main/java/com/thedogs/modules/auth/RefreshTokenRepository.java`
- `backend/src/main/java/com/thedogs/modules/auth/RefreshTokenCleanupService.java`
- `backend/src/main/java/com/thedogs/modules/auth/RefreshTokenException.java`
- `backend/src/main/resources/db/migration/V3__refresh_token.sql`
- `backend/src/main/java/com/thedogs/modules/auth/AuthService.java` (modified, unstaged)

**I18N-01 partial (frontend):**
- `frontend/src/app/services/language.service.ts`
- `frontend/src/app/services/missing-translation.handler.ts`
- `frontend/src/app/layout/` (directory)
- `frontend/src/app/services/language.service.spec.ts`
- `frontend/src/app/services/language-bundle-symmetry.spec.ts`
- `frontend/src/app/services/error-translation.spec.ts`
- `frontend/src/app/services/missing-translation-handler.spec.ts`
- `frontend/e2e/i18n.spec.ts`
- `frontend/public/assets/` (directory)

---

## The Fix

### 1. Restore the formatter block with the correct call

The formatter block was **removed** from `scripts/run-features.ps1` as a workaround after the failures. It needs to be restored properly inside `Complete-FeatureMerge`, after the merge/squash step and before the commit.

Replace the old bad call with:

```powershell
Write-Log 'INFO' "Running formatters..."
$mvnCmd = Join-Path $repoRoot 'backend' 'mvnw.cmd'
& $mvnCmd -f (Join-Path $repoRoot 'backend' 'pom.xml') spotless:apply 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "spotless:apply failed (exit $LASTEXITCODE)"
}

$frontendDir = Join-Path $repoRoot 'frontend'
if (Test-Path (Join-Path $frontendDir 'package.json')) {
    & npm --prefix $frontendDir run lint -- --fix 2>&1 | Out-Null
}
```

Key changes:
- `backend\mvnw.cmd` — Windows-executable wrapper, correct subdirectory
- `-f backend/pom.xml` — ensures Maven finds the right POM regardless of `$PWD`
- Explicit exit code check so a real Spotless failure surfaces as an error, not silent

### 2. Fix the zero-trust compilation check (same file, same function)

The `.history` version also had a second `mvnw` call for a compile check:

```powershell
# Old (broken)
$buildOut = & (Join-Path $repoRoot 'mvnw') clean test-compile 2>&1

# Fixed
$buildOut = & (Join-Path $repoRoot 'backend' 'mvnw.cmd') `
    -f (Join-Path $repoRoot 'backend' 'pom.xml') `
    clean test-compile 2>&1
```

### 3. Correct the Claude prompt in `run-features.ps1` (line 261)

The prompt text currently tells Claude to run `./mvnw verify`. Claude runs from the project root where there is no `mvnw`. Update the instruction:

```
# Old
Run `./mvnw verify` and `npm run lint && npm test && npm run build`

# Fixed  
Run `cd backend && ./mvnw verify` and, in the frontend dir, `npm run lint && npm test && npm run build`
```

---

## Other Places That May Need Patching

### `scripts/run-flow-b.ps1`
Currently has no `mvnw` calls — Flow B is audit-only (security-reviewer, pwa-auditor, docs-writer). No fix needed here.

### `.github/workflows/*.yml`
All CI workflows use `./mvnw -B ...` — these run on Linux GitHub-hosted runners where the bash `mvnw` is executable. **No change needed** for CI.

### `scripts/README.md`
The setup guide likely has `./mvnw` examples for Windows users. Should note that on Windows you use `mvnw.cmd` or run inside WSL/Git Bash.

---

## `features.json` Needs Updating

The file was reset to all-queued/attempts-0 after the run. Based on the logs, the correct state is:

| Feature | Correct status | Why |
|---|---|---|
| F001 | `done` | PR #14 merged — confirmed in `git log` |
| AUTH-01 | `done` | PR #16 merged — confirmed in `git log` |
| AUTH-02 | `queued` (reset attempts to 0) | Claude finished work 3×, formatter killed it. Work on `feat/auth-login` — needs fresh run. |
| AUTH-03 | `queued` (attempts 0) | Claude session was cut short by quota. Partial untracked files should be discarded (stash or delete) before re-run. |
| I18N-01 | `queued` (reset attempts to 0) | Claude finished 4×, formatter killed it every time. Untracked files should be discarded before re-run. |
| AUTH-04 onwards | `queued` | Untouched — correct as-is |

---

## Recommended Steps to Resume

1. Fix `Complete-FeatureMerge` in `scripts/run-features.ps1` as described above.
2. Update `scripts/features.json`: mark F001 and AUTH-01 `done`, reset AUTH-02/AUTH-03/I18N-01 to `queued` with `attempts: 0`.
3. Discard or stash the partial untracked files (they will be regenerated cleanly by the next orchestrator run).
4. Fix Flow B's docker issue (separate problem — `docker compose up` failed on first ad-hoc trigger; orchestrator will retry Flow B again when the window cap is next hit).
5. Re-run: `pwsh scripts/run-features.ps1`

---

## Resolution

Resolved on 2026-05-26 on branch `feat/fix-ci-scaffold`.

### What was done

- **`scripts/run-features.ps1`**: `Complete-FeatureMerge` fully rewritten.
  - Calls `backend\mvnw.cmd` with `-f backend/pom.xml` (was: `$repoRoot/mvnw`).
  - Drops local `git reset --soft` squash — Claude's WIP commits + a formatter
    commit flow into the PR; `gh pr merge --squash` collapses at merge time.
  - Pushes Claude's WIP commits to origin immediately after Claude returns so
    progress is visible before post-processing steps run.
  - Added `Write-Step` helper (pairs INFO log with a pipeline.jsonl event);
    substep events: `branch_init_start`, `branch_pushed`, `formatter_*`,
    `compile_check_*`, `pr_create*`, `pr_auto_merge_*`, `pr_poll_heartbeat`,
    `pr_merged`.
  - Heartbeat every 5 poll cycles and every 5 min during quota/flow-b waits.
  - `Write-Log` INFO lines now print cyan to console.
  - `Initialize-FeatureBranch` pushes the empty branch immediately on creation.
  - Claude prompt updated: `cd backend && ./mvnw verify` instead of `./mvnw verify`.

- **`scripts/run-flow-b.ps1`**: docker compose output unmuted in
  `Start-StackForAudit` and `Stop-StackAfterAudit`; real error messages now
  surfaced when `docker compose up` fails.

- **`scripts/features.json`**: F001 and AUTH-01 marked `done` with correct
  `merged_pr` URLs and `completed_at` timestamps.

- **`rescue/orchestrator-2026-05-26-partials`**: AUTH-03 and I18N-01 partial
  untracked files committed to a rescue branch before cleaning the working tree.
  Working tree is clean; next orchestrator run starts fresh from AUTH-02.
