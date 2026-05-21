# ADR-0012 — Automated Two-Flow Pipeline (Flow A per feature, Flow B 3×/week)

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** Aleksander Torka (Owner)
- **Supersedes:** [ADR-0010](0010-ai-team-consolidation.md)

## Context

ADR-0010 defined an 8-role AI team executed sequentially per feature with the human reviewing each PR. The project is moving to a **fully automated pipeline** driven by `run-features.ps1` that drains `features.json` without per-feature human interaction. Three assumptions of ADR-0010 no longer hold under full automation:

1. **Token budget is the binding constraint.** The Claude Pro weekly quota is exhausted in ~4 days at planned throughput. The 8-role pipeline burns ~115–210k tokens per fullstack feature; a leaner pipeline roughly doubles deliverable features per week.
2. **AI cannot reliably classify feature complexity.** A hybrid pipeline routing simple features to a shorter sequence and complex features to the full chain depends on a classifier that empirically degrades to "always full chain."
3. **Latency is no longer valued.** Without a human waiting on a PR, the parallelism rules in [AI_NATIVE.md §2.3](../AI_NATIVE.md) provide no business benefit.

At the same time, three responsibilities the 8-role team handles well — **documentation hygiene**, **adversarial security review**, and **live PWA verification** — degrade in quality when forced into per-feature scope. Per-feature `docs-writer` produces fragmented, often unnecessary updates. Per-feature `security-reviewer` misses cross-feature patterns. Per-feature live PWA verification through Claude in Chrome MCP is prohibitively expensive in tokens.

## Decision

Split the pipeline into two independent flows with separate schedules.

### Flow A — per feature (driven by `run-features.ps1`)

Four sub-agents, fully sequential, run for every entry in `features.json`. **The orchestrator auto-merges each feature's PR into `dev` before the next feature starts.**

| # | Agent | Owns | Source `.claude/agents/` file |
|---|-------|------|-------------------------------|
| 1 | `explorer` | Read-only context gathering, Context7 calls, spec interpretation. Receives `git log --oneline $LAST_FLOW_B_TAG..dev` + list of changed paths as docs-drift awareness input. | new file, derived from `tech-lead.md` |
| 2 | `implementer` | Backend + frontend implementation in one context, local WIP commits, test execution and repair | new file, merge of `backend-engineer.md` + `frontend-engineer.md` |
| 3 | `test-writer` | Vitest + Testcontainers + Playwright tests, axe-core assertions | derived from `qa-engineer.md` minus the live PWA pass |
| 4 | `reviewer` | Baseline code-and-security checklist on the feature diff: typing, dead code, Lombok rules, auth presence, `@PreAuthorize` on services, no inline secrets, Context7 verification note, Conventional Commits | new file, merge of `code-reviewer.md` + condensed `security-reviewer.md` baseline |

**Merge gate:** after Reviewer approves, the orchestrator:

1. Squashes WIP commits, force-pushes the cleaned `feat/<slug>` branch.
2. Opens PR to `dev` via `gh pr create`. If the feature's `source_issue` field is set, the PR body includes `Closes #<n>`.
3. Enables auto-merge: `gh pr merge --squash --auto`.
4. Polls until the PR is merged (branch protection per [ADR-0009](0009-branching-strategy.md) requires `ci-backend` and `ci-frontend` green).
5. **Only then** dequeues the next feature.

If a feature exits Flow A with status `failed` or `needs_review`, its branch remains open; the orchestrator continues with the next feature without merging. The merge-before-next-feature rule applies to the success path only.

### Flow B — scheduled 3×/week (driven by `run-flow-b.ps1` via Windows Task Scheduler)

Three sub-agents, sequential, run Monday / Wednesday / Friday at 02:00 local time (Europe/Warsaw):

| # | Agent | Owns | Source `.claude/agents/` file |
|---|-------|------|-------------------------------|
| 1 | `security-reviewer` | Adversarial audit of diff `dev` vs last `flow-b-N` tag; opens GitHub issues with labels `kind:security` + `severity:*`; does not block any PR | preserved from ADR-0010 |
| 2 | `pwa-auditor` | Spins up `docker compose` against `dev` state, runs Lighthouse + axe + manifest/SW/offline/install-prompt verification via Claude in Chrome MCP; opens issues with labels `kind:pwa` + `severity:*` | new file, derived from `qa-engineer.md` (live PWA verification section only) |
| 3 | `docs-writer` | Updates `docs/ARCHITECTURE.md`, `docs/AI_NATIVE.md`, `CHANGELOG.md`, proposes new ADRs based on diff; produces a severity-tagged docs-drift report in the PR description; opens issues only for `severity:critical` and `severity:high` drift items | preserved from ADR-0010 |

Flow B output:

- Single PR `chore/flow-b-<YYYY-MM-DD>` targeting `dev` containing docs updates and the docs-drift report
- Tag `flow-b-<N>` placed on the `dev` tip the run started from (referenced by next Flow B and by Flow A's drift-awareness input)
- GitHub issues for findings from all three sub-agents

### Throughput limits

Two interlocking caps on Flow A:

- **Hard daily cap: 3 features per calendar day** (Europe/Warsaw). On reaching 3 successful Flow A completions in a day, the orchestrator sleeps until the next local midnight before dequeueing the next feature. This is the primary throttle keeping weekly consumption within the Pro budget with reserve headroom.

- **Defensive per-window cap: 10 features** between Flow B runs. The orchestrator counts `git rev-list --count $last_flow_b_tag..dev`; if the count reaches 10 before the next scheduled Flow B, Flow A pauses and Flow B is triggered ad-hoc (adaptive trigger). Acts as a safety net should the daily cap be misconfigured or bypassed.

The daily cap binds in practice (3/day × 2–3 days between Flow B runs = 6–9 features per window, comfortably under 10). The per-window cap is a defensive bound that should rarely fire.

Weekly load projection at these caps:

```
Flow A:    up to 21 features × ~90k tokens   = ~1.89M
Flow B:     3 runs           × ~110k tokens  = ~330k
─────────────────────────────────────────────────────
Committed:                                     ~2.22M
Reserve:                                        remainder of Pro weekly budget
```

Both caps are reviewed after 4 weeks of empirical data — see Open.

### Issue lifecycle

Findings from Flow B drive `features.json` via a controlled owner gate:

```
Flow B → opens issue (severity:* + kind:* labels, status:triage default)
       ↓
Owner reviews issue in GitHub
       ↓
Owner adds label status:approved          ← manual gate
       ↓
promote-issues.ps1 (run before each Flow A batch, or on cron)
  picks status:approved issues, prepends to features.json with:
    - priority: asap
    - source_issue: <issue_number>
    - status: queued
  Comments on issue: "Promoted to feature queue as F<NNN>"
  Replaces label: status:approved → status:queued
       ↓
Flow A picks the feature, includes "Closes #<n>" in PR body
       ↓
Before opening PR, orchestrator comments:
  "Resolved by upcoming PR; awaiting review"
  Adds label status:waiting-review
       ↓
On merge: GitHub auto-closes the issue
Owner reviews merged work; the issue stays closed
```

Label namespace:

| Label  | Values                                                              |
| ------ | ------------------------------------------------------------------- |
| `severity:` | `critical`, `high`, `medium`, `low`                             |
| `kind:`     | `security`, `pwa`, `docs-drift`, `bug`                          |
| `status:`   | `triage` (default), `approved`, `queued`, `waiting-review`      |

`severity:critical` and `severity:high` items are eligible for `status:approved`; `medium`/`low` may be ignored or batched at the owner's discretion.

### Out of both flows

- **`release-manager`** — preserved unchanged, triggered manually for promotions `dev → staging → prod` and hotfixes ([ADR-0009](0009-branching-strategy.md)).
- **OpenAPI snapshot** — generated deterministically by `springdoc` in `ci-backend.yml`, committed automatically when changed. No AI involvement.

### Coordination

- File lock `orchestrator.lock` (PID + flow name) prevents Flow A and Flow B running concurrently. Second to start waits.
- `run-features.ps1` pauses between 02:00 and 04:00 on Flow B days (Mon/Wed/Fri) to give Flow B a preferential window.
- On Pro quota hit, the active flow sleeps 5.1h and resumes. The other flow waits on the lock.

### Permissions added to orchestrator tools

`run-features.ps1` and `run-flow-b.ps1` invoke `gh` for:

| Operation | Used by |
|---|---|
| `gh pr create`, `gh pr merge --squash --auto` | Flow A orchestrator |
| `gh issue create`, `gh issue comment`, `gh issue edit --add-label` | Flow B sub-agents (security-reviewer, pwa-auditor, docs-writer) |
| `gh issue list --label status:approved`, `gh issue edit` | `promote-issues.ps1` |

Sub-agents themselves never call `gh pr merge`. Merge is the orchestrator's responsibility, gated by Reviewer's signal.

### Where the folded responsibilities live

- **Schema and migration discipline** (Flyway append-only, no mixing DDL with data, top-of-file SQL comment referencing spec/ADR) moves verbatim from `backend-engineer.md` into `implementer.md`.
- **PrimeNG + lucide-angular + NgRx Signal Store conventions** move verbatim from `frontend-engineer.md` into `implementer.md`.
- **Baseline security checks** (auth present, `@PreAuthorize` on services not just controllers, no inline secrets, no SQL string concatenation, no relaxed CORS/CSP) move into `reviewer.md` as a checklist section.
- **Live PWA verification checklist** (manifest + SW + offline + console + network + axe + Lighthouse + install prompt) moves verbatim from `qa-engineer.md` Part B into `pwa-auditor.md`.
- **Deep adversarial security audit** stays in `security-reviewer.md`, now invoked only by Flow B.

## Consequences

**Positive**

- Roughly **2× the feature throughput** per weekly Pro budget compared to ADR-0010.
- Sequential merge-before-next guarantees `dev` is always at a known-green state when each Flow A run starts → no merge conflicts between in-flight features.
- **Predictable weekly load.** Hard daily cap × 7 days = 21 features/week ceiling, regardless of how fast 5h rolling windows would otherwise allow. Pro weekly budget is not the primary throttle, giving the owner explicit control over pace rather than implicit budget-exhaustion behaviour.
- Documentation, security, and PWA audits benefit from cross-feature scope.
- Docs drift visible to Explorer via cheap `git log` input (~10–40k tokens/week total).
- Issue lifecycle provides traceability: every Flow B finding either has owner-approval-and-fix or owner-explicit-dismissal.

**Negative**

- **Documentation drift up to ~3 days** (worst case: Friday Flow B → Monday Flow B). Mitigated by OpenAPI snapshot auto-refresh in CI, severity-tagged docs report, and Explorer awareness input.
- **Security findings delayed up to ~3 days.** Mitigated by Reviewer's baseline checklist in Flow A and by `dev` being non-user-facing.
- **PWA regressions delayed up to ~3 days.** Mitigated by Playwright + axe-core assertions in Flow A's `test-writer` covering automatable checks.
- **`implementer.md` carries a larger prompt** (merged hard-rules from two roles). May approach context limits on unusually large features; mitigated by feature decomposition in `features.json`.
- **Manual gate on issue promotion** (owner adds `status:approved`) means automation halts on findings if owner is unavailable. Acceptable — owner triage is the intended quality gate.

## Alternatives considered

- **Keep ADR-0010 as-is, run 8 roles sequentially.** Rejected: ~2× cost, no compensating quality gain without human-per-PR review.
- **Single 4-role pipeline per feature, no Flow B.** Rejected: documentation drifts indefinitely; no adversarial pass.
- **Hybrid 4-or-8 routing per feature based on `scope`/`risk`.** Rejected: AI cannot classify reliably.
- **Per-feature `/pwa-audit` in Flow A.** Rejected: Claude-in-Chrome tokens prohibitive at per-feature cadence; Flow B 3×/week fits budget.
- **Auto-approve Flow B issues without owner gate.** Rejected: blurs accountability for what enters the feature queue; bypasses owner triage on adversarial findings.
- **Full diff as docs-drift mitigation input to Explorer.** Rejected after measurement: ~200–400k tokens/week vs ~10–40k for `git log` form, with marginal accuracy gain.
- **Soft daily target instead of hard cap.** Rejected: lets orchestrator push to Pro 5h-window maximum, exhausting weekly budget in ~4 days and removing reserve headroom. Hard cap is preferred precisely because it forces the reserve.
- **Flow B 2×/week.** Rejected: at 3 features/day, a 3.5-day window can accumulate ~10 features, sitting at the per-window cap with no margin. 3×/week (Mon/Wed/Fri) keeps the worst window at ~9 features with comfortable headroom.

## Open

- **Empirical re-tune after 4 weeks.** Daily cap (3) and per-window cap (10) are initial values derived from estimated diff sizes (~6–20k tokens per feature). Measure actual diff sizes and Flow B context utilisation after 4 weeks of operation; adjust caps via amendment to this ADR (no new ADR needed for numeric tuning, only for structural changes).

- **Designer role.** When/if a UI-designer agent is added, it gets its own ADR specifying: position in pipeline (separate flow vs step in Flow A), trigger condition, sub-agent definition, and explicit token reservation carved out from the implicit reserve maintained by this ADR.

## Reintroducing what was removed

These changes are easily reversed via a new ADR if the constraints shift:

- **Per-feature security review** — promote `security-reviewer` back into Flow A as a fifth step. Justified when production traffic and the auth surface stabilise, or when a real incident shows the baseline checklist is insufficient.
- **Per-feature docs writer** — re-add `docs-writer` to Flow A. Justified when external consumers read docs and drift becomes a coordination problem.
- **Per-feature PWA audit** — promote `pwa-auditor` into Flow A. Justified when PWA UX becomes a user-visible quality differentiator (post-MVP).
- **Hybrid routing** — once a reliable classifier exists (e.g., a deterministic rule on touched paths rather than AI judgment), the hybrid model from this ADR's alternatives section becomes viable.

## Notes

- ADR-0010 marked Superseded but retained for history. ADR-0007 remains Superseded by ADR-0010.
- AI_NATIVE.md §2.1 role table and §2.2 pipeline diagram updated to match this ADR in the same change.
- A UI-designer agent is not part of this ADR. Weekly Pro budget is sized with reserve headroom (committed ~2.22M tokens of estimated weekly budget via 21 Flow A features + 3 Flow B runs at projected averages) so a designer iteration loop can be introduced via a future ADR without immediately forcing a renegotiation of the throughput caps here. The reserve is implicit — enforced by the hard daily cap, not by an explicit token budget line.
- `run-features.ps1`, `run-flow-b.ps1`, and `promote-issues.ps1` are required new artifacts before first automated batch. They reference this ADR for cap values, cadence, and label namespace.
- Initial value of `$LAST_FLOW_B_TAG` for the first Flow A runs (before first Flow B has produced a tag) defaults to the merge base of `dev` and the project's initial commit — i.e., no drift awareness on the very first batch. Acceptable.
