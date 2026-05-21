# The-Dogs — AI-Native Development Guide

This project is built **fully with AI assistance** under a **harness-engineer model**: a human engineer directs and reviews while a coordinated team of AI agents implements. This document defines the team, the per-feature workflow, and the skills that automate it.

> Companion to [ARCHITECTURE.md](ARCHITECTURE.md). Read that first for the technical stack.

## 1. Principles

1. **Specs over chat.** Every non-trivial change starts from a spec in `docs/specs/` or an ADR in `docs/adr/`. The agent and human read the same source of truth.
2. **No legacy code.** Before generating any non-trivial code, the agent must consult **Context7 MCP** for up-to-date docs of the affected library (Angular, Spring Boot, Hibernate, PrimeNG, NgRx Signal Store, Lucide, etc.). Snippets older than the current major version are rejected at review.
3. **Small, verifiable steps.** Each commit is one logical change with passing tests. Many small commits beat one large one.
4. **Tests are the contract.** Bug fixes start with a failing test. Untested behavior is treated as undefined.
5. **Determinism beats cleverness.** Plain SQL migrations, explicit DTOs, MapStruct mappers, generated clients — easier for the next agent run to reason about.
6. **The agent stops at risky actions.** Pushing, force-pushing, deleting branches, dropping tables, or anything affecting shared state requires human confirmation.
7. **Memory is explicit.** Cross-conversation knowledge lives in `docs/`, ADRs, or skill files — never relies on session memory.

## 2. The Harness-Engineer Model

The human is the **harness engineer**: they don't write production code, they orchestrate agents, approve plans, and gate merges. Each feature is delivered by a small **AI team** with named roles. Roles are realised as Claude Code subagents (definition files under `.claude/agents/`) so they have isolated context and tailored tool allowlists.

### 2.1 The AI team

Per [ADR-0012](adr/0012-automated-two-flow-pipeline.md) (which superseded the 8-role consolidation in [ADR-0010](adr/0010-ai-team-consolidation.md), itself superseding the original 10-role split in [ADR-0007](adr/0007-ai-team-composition.md)), the team is now organised into **two automated flows** plus one out-of-flow role.

#### Flow A — runs for every feature in `features.json` (sequential)

| Agent (subagent_type) | Responsibility | Primary tools / MCPs |
| --------------------- | -------------- | -------------------- |
| `explorer`    | Reads the spec, gathers context (read-only), calls Context7. Also receives `git log --oneline` since last `flow-b-N` tag as docs-drift awareness. | Read, Grep, Glob, **Context7** |
| `implementer` | Backend + frontend implementation in one context (Spring Boot + Flyway + Lombok + MapStruct + Hibernate + Angular + NgRx Signal Store + PrimeNG + lucide-angular). Runs tests, repairs failures, commits WIP. | Read, Edit, Write, Bash (mvnw, npm, ng, git add/commit/diff), **Context7** |
| `test-writer` | Vitest + Testcontainers + Playwright tests + axe-core assertions. Does not run tests — `implementer` does. | Read, Edit, Write, Glob, Grep, **Context7** |
| `reviewer`    | Baseline code-and-security checklist on the feature diff: types, dead code, Lombok rules, auth + `@PreAuthorize` presence, no inline secrets, Context7 note, Conventional Commits. | Read, Grep, Glob, Bash (git diff, git log), **Context7** |

#### Flow B — runs Mon/Wed/Fri 02:00 (sequential)

| Agent (subagent_type) | Responsibility | Primary tools / MCPs |
| --------------------- | -------------- | -------------------- |
| `security-reviewer` | Adversarial audit of diff `dev` vs last `flow-b-N` tag; opens GitHub issues with `kind:security` + `severity:*` labels | Read, Grep, Bash (npm audit, OWASP, gh issue), WebSearch |
| `pwa-auditor`       | Live PWA verification against running `dev` stack (manifest + SW + offline + Lighthouse + axe + install prompt); opens issues with `kind:pwa` + `severity:*` | Read, Bash (docker compose, gh issue), **Claude in Chrome**, **Claude Preview** |
| `docs-writer`       | Updates `docs/ARCHITECTURE.md`, `docs/AI_NATIVE.md`, `CHANGELOG.md`; proposes ADRs; severity-tagged docs-drift report in PR body; opens issues only for `severity:critical`/`high` drift | Read, Edit, Write, Bash (gh issue) |

#### Out of both flows

| Agent (subagent_type) | Responsibility | Primary tools / MCPs |
| --------------------- | -------------- | -------------------- |
| `release-manager` | Triggered **manually** for promotions `dev → staging → prod` and hotfixes; CI/CD scaffolding, branch protection, tags, container builds. Never invoked by Flow A or Flow B. | Bash (gh, mvn, npm, docker) |

The human (harness engineer) plays Product Manager + final approver: writes specs, approves Flow B issues with `status:approved` label, reviews Flow B PRs, triggers `release-manager` for promotion. Subagent definitions live in `.claude/agents/<role>.md`.

### 2.2 Two-flow pipeline

Under [ADR-0012](adr/0012-automated-two-flow-pipeline.md) the team runs **fully automated**: `run-features.ps1` drains `features.json` for Flow A; Windows Task Scheduler triggers `run-flow-b.ps1` Mon/Wed/Fri at 02:00 for Flow B. The human never sees an individual feature PR — only the Flow B aggregate PR and any `status:approved` issues to triage.

```
┌─ Flow A — per feature (run-features.ps1) ─────────────────────────────┐
│  0. Human writes brief in docs/specs/<feature>.md (or auto-promoted   │
│     from Flow B issue via promote-issues.ps1)                         │
│  1. explorer    — reads spec, gathers context, Context7, receives     │
│                   git log since last flow-b-N as drift awareness      │
│  2. implementer — backend + frontend in one context; entity → repo →  │
│                   service → controller → DTO/mapper + Flyway          │
│                   migration; Signal Store slice + PrimeNG components; │
│                   runs and repairs tests; WIP commits                 │
│  3. test-writer — Vitest + Testcontainers + Playwright + axe-core     │
│  4. reviewer    — baseline code + security checklist on diff          │
│       ├─ ok      → orchestrator squashes WIP, opens PR to dev,        │
│       │            enables auto-merge (--squash --auto), waits for    │
│       │            CI green per ADR-0009 branch protection, then      │
│       │            dequeues next feature                              │
│       ├─ notes   → back to implementer (max 2 rounds)                 │
│       └─ rejected after 2 rounds → status: needs_review, queue        │
│                                    continues with next feature        │
└───────────────────────────────────────────────────────────────────────┘

┌─ Flow B — Mon/Wed/Fri 02:00 (run-flow-b.ps1) ─────────────────────────┐
│  Input: diff `dev` vs last `flow-b-N` tag                             │
│  1. security-reviewer — adversarial pass; opens issues kind:security  │
│  2. pwa-auditor       — docker compose up, Lighthouse + axe + manifest│
│                         + SW + offline + install prompt; opens issues │
│                         kind:pwa                                      │
│  3. docs-writer       — updates ARCHITECTURE.md / AI_NATIVE.md /      │
│                         CHANGELOG; severity-tagged docs-drift report  │
│                         in PR body; opens issues only for severity    │
│                         critical/high                                 │
│  Output: PR chore/flow-b-<YYYY-MM-DD> to dev + tag flow-b-<N> on the  │
│          dev tip the run started from                                 │
└───────────────────────────────────────────────────────────────────────┘
```

**Throughput caps (Flow A, per ADR-0012):**

- Hard daily cap: **3 features / calendar day** (Europe/Warsaw). Reaching the cap pauses Flow A until next local midnight.
- Defensive per-window cap: **10 features** between Flow B runs. Hitting this triggers Flow B ad-hoc (adaptive trigger).

**Issue lifecycle (Flow B → features.json, per ADR-0012):**

```
Flow B opens issue → human adds status:approved → promote-issues.ps1
moves it to features.json with priority:asap and source_issue:<n> →
Flow A delivers, PR body has "Closes #<n>" → on merge GitHub auto-closes
the issue, orchestrator first adds status:waiting-review for audit trail.
```

### 2.3 Coordination and concurrency

Under automation everything runs **sequentially**. There is no parallel work between sub-agents within a flow, and the two flows hold a file lock (`orchestrator.lock`) so they never run concurrently. The parallelism rules of earlier ADRs were premised on human-in-the-loop latency mattering — under full automation latency is no longer valued, and sequential execution removes coordination complexity.

If both flows would run at once (e.g., a Flow B trigger lands while Flow A is mid-feature), the second to start waits on the lock. `run-features.ps1` proactively pauses 02:00–04:00 on Flow B days to give Flow B a clean window.

## 3. Roles: Human vs AI

| Concern         | Human (harness engineer)                          | AI Team                                                  |
| --------------- | ------------------------------------------------- | -------------------------------------------------------- |
| Direction       | Defines features, priorities, deadlines           | Suggests breakdowns, flags risks                         |
| Design          | Approves architecture, picks between options      | `tech-lead` drafts ADRs; surfaces tradeoffs              |
| Implementation  | Spot review                                       | `backend-engineer` (incl. schema + migrations), `frontend-engineer` |
| Verification    | Final UX sign-off                                 | `qa-engineer` (tests + live PWA verification), CI        |
| Review          | Final approver on PR                              | `code-reviewer`, `security-reviewer`                     |
| Docs            | Approves                                          | `docs-writer`                                            |
| Ops             | Approves deploys, rotates secrets                 | `release-manager` prepares releases                      |

## 4. MCP Servers Used

(Canonical list in [ARCHITECTURE.md §6](ARCHITECTURE.md).) AI-native specifics:

- **Context7** — *primary defense against legacy code*. Every implementer agent calls Context7 with the relevant library + version (e.g. `angular@21`, `spring-boot@3.5`, `primeng@18`, `ngrx-signals@18`, `hibernate@6`, `postgresql@17`) before generating non-trivial code, then includes a short "Verified against Context7 on `<date>`" note in the PR body.
- **Claude in Chrome** — `qa-engineer`'s tool for the live PWA verification pass. Verifies install prompt, service worker registration, offline behavior, Lighthouse PWA score, axe results, and golden-path UX.
- **GitHub via `gh`** — `release-manager` and PR automation.
- **scheduled-tasks** — nightly `deps-bump`, `security-scan`, `docs-drift-check`.
- **mcp-registry** — when a new capability is needed, query the registry before writing custom code.

## 5. Skills (planned)

Skills live under `.claude/skills/` and are versioned in git. Order roughly reflects priority.

### Bootstrap

> **Note:** Skills are for **repeatable** workflows. One-shot bootstrap tasks (writing the agent definitions, scaffolding the Angular workspace, scaffolding the Spring Boot project, generating the initial CI workflows) are performed once by the matching role and then never repeated — they are **not** skills. They appear in the project plan as direct tasks for the relevant agent (`tech-lead` writes the agent definitions; `frontend-engineer` scaffolds the frontend; `backend-engineer` scaffolds the backend; `release-manager` writes the initial CI workflows).

- **`scaffold-feature`** — given a feature name, creates frontend module + backend module skeletons with passing skeleton tests and a Flyway migration stub. (Repeatable per feature, hence a skill.)

### Daily development

- **`feature-deliver`** — orchestrator: takes a path to `docs/specs/<feature>.md` and runs the team through the steps in §2.2.
- **`api-sync`** — regenerates `docs/api/openapi.yaml` from the running backend; refreshes frontend TypeScript clients via `openapi-typescript`.
- **`migration-new`** — creates a numbered Flyway migration with a sensible prefix; reminds about reversibility and online-DDL constraints.
- **`adr-new`** — drops a numbered ADR template into `docs/adr/`.
- **`spec-new`** — drops a feature-spec template into `docs/specs/` and links it from the index.
- **`context7-check`** — utility skill agents call before generating code; takes `(library, version, topic)` and returns the fresh doc snippet.

### Verification

- **`verify-frontend`** — npm lint, type-check, Vitest, Lighthouse PWA + a11y budgets, build.
- **`verify-backend`** — Spotless check, `mvn verify`, full test suite incl. Testcontainers, coverage delta.
- **`verify-all`** — composes both. Mandatory before any PR.
- **`smoke-e2e`** — Docker Compose full stack + Playwright suite.
- **`pwa-audit`** — `ux-reviewer`'s checklist: manifest valid, SW registered, offline list view works, install prompt fires, axe clean.

### Maintenance

- **`deps-bump`** — bumps Angular, Spring Boot, and supporting libs to latest compatible versions; runs `verify-all`; opens a PR with a changelog summary.
- **`security-scan`** — npm audit, OWASP `dependency-check-maven`, license scan; files issues for findings above threshold.
- **`license-scan`** — fails the build if any dependency adopts GPL/AGPL.
- **`db-introspect`** — read-only schema dump + diff against `docs/api/schema.sql`.
- **`docs-refresh`** — re-renders auto-generated sections of README and ARCHITECTURE from `package.json` / `pom.xml` (versions, modules).

### Release & ops

- **`release-notes`** — drafts release notes from commits since the last tag, grouped by Conventional Commit type.
- **`changelog-update`** — appends to `CHANGELOG.md`.
- **`container-build`** — builds and tags backend + frontend container images.

### Review

- **`review-pr`** — runs the project's review checklist: tests added? migration reversible? OpenAPI updated? frontend types regenerated? ADR if architectural? Lombok not on `@Data` for entities? Context7 check noted in PR body?

## 6. Conventions the AI Team Follows

- **Branching:** three long-lived branches (`dev` default, `staging`, `prod`); short-lived `feat/<slug>`, `fix/<slug>`, `chore/<slug>` from `dev`, PR back to `dev`. Promotion is `dev → staging → prod`. See [ADR-0009](adr/0009-branching-strategy.md).
- **Commits:** Conventional Commits, imperative mood, body explains *why*. Each agent commits its own steps; no bundled monsters.
- **PRs:** title matches commit style; body has Summary, Test Plan, links to spec/ADR, and the Context7-verification note.
- **Code comments:** only when *why* is non-obvious. Identifiers carry the *what*.
- **Logging:** structured (JSON in prod), correlation IDs end-to-end.
- **Secrets:** never inline. `.env.example` documents required keys.
- **Lombok on JPA entities:** never `@Data`. See ARCHITECTURE.md §7.

## 7. What the AI Team Will *Not* Do Without Human Confirmation

- `git push --force`, `git reset --hard`, deleting branches, amending pushed commits.
- Dropping tables, destructive migrations, truncating data.
- Adding paid-tier dependencies or services.
- Changing CI/CD pipelines that affect deploys.
- Bumping major versions of Angular, Spring Boot, Java, Postgres, PrimeNG, NgRx.
- Sending external messages (email, Slack, GitHub comments outside its own PR).
- Adding any dependency under a GPL/AGPL license.

## 8. Open Decisions for the Human

Tracked here until each becomes an ADR. Cross-referenced in [ARCHITECTURE.md](ARCHITECTURE.md).

| #  | Decision                | Status                                                          |
| -- | ----------------------- | --------------------------------------------------------------- |
| 1  | Domain scope            | ✅ Decided — see [ADR-0002](adr/0002-domain-scope.md)            |
| 2  | Auth                    | ✅ Decided — JWT + Spring Security + RBAC ([ADR-0003](adr/0003-auth-jwt-rbac.md)) |
| 3  | Hosting target          | ⏳ Deferred — generic container build for now                   |
| 4  | Production database     | ⏳ Deferred — managed Postgres TBD when hosting is picked       |
| 5  | Frontend state          | ✅ Decided — signals + NgRx Signal Store ([ADR-0004](adr/0004-frontend-state.md)) |
| 6  | UI component library    | ✅ Decided — PrimeNG + lucide-angular ([ADR-0005](adr/0005-ui-component-library.md)) |
| 7  | PWA                     | ✅ Decided — Angular PWA from day one ([ADR-0006](adr/0006-pwa.md)) |
| 8  | License                 | ✅ Decided — Proprietary, © Aleksander Torka ([ADR-0008](adr/0008-license.md)) |
| 9  | Build tools             | ✅ Decided — Maven (backend), npm (frontend) ([ADR-0001](adr/0001-stack-and-build-tools.md)) |
| 10 | CI provider             | ✅ Decided — GitHub Actions                                     |
| 11 | Branching strategy      | ✅ Decided — `dev` / `staging` / `prod` ([ADR-0009](adr/0009-branching-strategy.md)) |
| 12 | AI team composition     | ✅ Decided — Flow A (4 roles) + Flow B (3 roles) + manual `release-manager` ([ADR-0012](adr/0012-automated-two-flow-pipeline.md), supersedes [ADR-0010](adr/0010-ai-team-consolidation.md) which superseded [ADR-0007](adr/0007-ai-team-composition.md)) |
| 13 | Design tooling / mockups | ⏳ Deferred — artifacts will live under [docs/design/](design/); tool & format TBD |
| 14 | Automation pipeline     | ✅ Decided — two-flow with auto-merge, throughput caps, issue lifecycle ([ADR-0012](adr/0012-automated-two-flow-pipeline.md)) |

## 9. Getting Started (after scaffold skills run)

```bash
# Backend
cd backend
./mvnw spring-boot:run

# Frontend (separate terminal)
cd frontend
npm install
npm start

# Full stack
docker compose up
```

Until the scaffold skills run, `frontend/` and `backend/` are intentionally empty.
