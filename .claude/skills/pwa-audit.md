---
name: pwa-audit
description: Run a manual PWA audit against the locally-running stack — manifest, service worker, offline list view, install prompt, Lighthouse score, axe-core a11y. Use this before promoting `dev` → `staging` (per ADR-0009), or whenever you want to verify a UX-touching change in a real browser before relying on Flow B's scheduled `pwa-auditor`. Trigger on phrases like "audit the PWA", "lighthouse check", "verify offline mode", "test the install prompt", or "is the PWA still healthy".
---

# pwa-audit

A short, repeatable PWA verification you can run on demand. Same scope as Flow B's `pwa-auditor` (per ADR-0012) but invoked by a human rather than the scheduler.

## Prerequisites

- Local prod-like stack up: `docker compose -f docker-compose.yml up -d`
- Frontend reachable at `http://localhost:4200` (or wherever your local config points)
- Chrome available (the Claude in Chrome MCP needs a browser instance)

## Steps

### 1. Confirm stack health

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:4200 > /dev/null
```

Both must return 200 / non-empty.

### 2. Identify touched routes

Routes to audit = routes added or changed since the last manual audit (or since `flow-b-<N>` if you want symmetry with the scheduled run):

```bash
LAST_TAG=$(git tag --list 'flow-b-*' --sort=-creatordate | head -n1)
git diff --name-only "$LAST_TAG..dev" -- frontend/src/app/components/
```

Or interactively: pick the routes you want to spot-check.

### 3. Run the checklist (one pass per route)

For each route in scope, drive Chrome via the `mcp__Claude_in_Chrome__*` tools:

1. **Manifest** — open `/manifest.webmanifest`; verify HTTP 200 and required keys (`name`, `short_name`, `theme_color`, `background_color`, `icons` with 192/512/maskable, `display: standalone`, `start_url: /`).
2. **Service worker** — open DevTools → Application; confirm SW is `activated`. Cross-check the route against `frontend/ngsw-config.json`.
3. **Offline** — toggle offline; the route's read-only view must still render from cache.
4. **Console + network** — zero new errors/warnings; no 4xx/5xx on golden path.
5. **axe-core** — inject axe via the JS tool; zero `serious` or `critical`.
6. **Lighthouse** — PWA ≥ 90, Performance ≥ 80. Use the `mcp__Claude_Preview__*` tools or Playwright fixture.
7. **Install prompt** — the custom install UI fires when eligible (not the browser default).

### 4. Open issues for findings

For Fail or Warn items, open GitHub issues:

```bash
gh issue create \
  --title "<route>: <one-line summary>" \
  --label "kind:pwa,severity:<critical|high|medium|low>,status:triage" \
  --body "<route, what was tested, what was observed, screenshot or console excerpt; manual audit on <YYYY-MM-DD>>"
```

Severity rubric (PWA scope):
- **critical** — route fully broken offline, SW not registering, manifest invalid.
- **high** — install prompt does not fire, golden path 5xx, axe critical, Lighthouse PWA < 80.
- **medium** — Lighthouse Performance 60–79, axe serious, console warnings.
- **low** — cosmetic, minor a11y.

Check for duplicates first (`gh issue list --label kind:pwa`); comment on the existing issue instead of creating a new one.

### 5. Report

Produce a markdown report (same shape as `pwa-auditor`'s Flow B output):

```
## PWA Audit — manual <YYYY-MM-DD>

### Touched routes audited
- /dogs
- /scheduler

### Pass
- /dogs: manifest, SW, offline, axe (0 serious/critical), Lighthouse 92/85

### Fail (issue opened)
- /scheduler: install prompt does not fire — issue #<n>

### Lighthouse summary
- /dogs:      PWA 92, Perf 85
- /scheduler: PWA 91, Perf 82
```

### 6. Tear down (if you brought the stack up just for this)

```bash
docker compose -f docker-compose.yml down
```

If the stack was already running for development, leave it.

## When to run

- **Before `dev` → `staging` promotion** (per ADR-0009). Even if Flow B's `pwa-auditor` ran recently, a fresh manual pass on the exact `dev` HEAD you're about to promote is cheap insurance.
- **After a UX-touching feature lands** if you want faster feedback than the next scheduled Flow B.
- **When debugging a regression report** — the structured output makes it easy to compare against a previous report.

## When NOT to use

- Backend-only changes (no UI touched) — Lighthouse and axe have nothing to verify; skip.
- Mid-feature, before tests pass — Playwright + axe-core in CI cover most of this; manual run is for the cross-cutting verification, not for unit-test-shaped checks.
- Right after a Flow B run (last 12h) — duplicate work.
