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

| Agent (subagent_type) | Responsibility                                                                              | Primary tools / MCPs                                  |
| --------------------- | ------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `tech-lead`           | Reads the spec, drafts the plan, splits work into tickets, writes/updates the ADR if needed | Read, Grep, **Context7**, WebSearch                   |
| `backend-engineer`    | Spring Boot + Hibernate + Lombok implementation; Flyway migration; service tests            | Read, Edit, Write, Bash (mvnw), **Context7**          |
| `frontend-engineer`   | Angular feature module, NgRx Signal Store slice, PrimeNG components, Lucide icons           | Read, Edit, Write, Bash (npm), **Context7**           |
| `db-engineer`         | Schema design, indexes, Flyway migrations, query plans                                      | Read, Write, Bash (psql/Testcontainers), **Context7** |
| `qa-engineer`         | Vitest + Playwright + a11y (axe) + Testcontainers integration tests                         | Read, Edit, Write, Bash, **Claude in Chrome**         |
| `ux-reviewer`         | Walks the running PWA in Chrome; checks Lighthouse, install flow, offline, accessibility    | **Claude in Chrome**, **Claude Preview**              |
| `security-reviewer`   | Auth flows, RBAC enforcement, dependency/license scan, OWASP top-10 sanity check            | Read, Grep, Bash (npm audit, OWASP), **Context7**     |
| `code-reviewer`       | Independent diff review against the project checklist                                       | Read, Grep, Bash (git diff)                           |
| `docs-writer`         | Updates ARCHITECTURE.md, README, ADRs, OpenAPI snapshot, changelog                          | Read, Edit, Write                                     |
| `release-manager`     | Tags, release notes, container build, deploy gating                                         | Bash (gh, mvn, docker)                                |

The human (harness engineer) plays Product Manager + final approver. Subagent definitions live in `.claude/agents/<role>.md` and are added by the `bootstrap-ai-team` skill.

### 2.2 Per-feature workflow

```
   ┌──────────────────────────────────────────────────────────────────┐
   │  0. Human writes a brief in docs/specs/<feature>.md              │
   │  1. tech-lead expands the spec; drafts ADR if architectural      │
   │     ─ MUST consult Context7 for current API/version guidance     │
   │  2. db-engineer designs schema + Flyway migration                │
   │  3. backend-engineer implements API: entity → repo → service →   │
   │     controller → DTO + MapStruct mapper, with unit tests         │
   │  4. qa-engineer adds Testcontainers integration tests            │
   │  5. frontend-engineer implements feature module: Signal Store    │
   │     slice, PrimeNG-based components, routes, guards, tests       │
   │  6. qa-engineer adds Playwright smoke + a11y assertions          │
   │  7. ux-reviewer drives the PWA in Chrome; reports findings       │
   │  8. security-reviewer audits auth, RBAC, deps, licenses          │
   │  9. code-reviewer runs the project checklist on the diff         │
   │ 10. docs-writer updates OpenAPI snapshot, ARCHITECTURE if needed │
   │ 11. Human reviews PR; CI gates the merge                         │
   └──────────────────────────────────────────────────────────────────┘
```

Each agent commits incrementally on the feature branch. The orchestrator (a top-level skill, `feature-deliver`) sequences the team and aggregates reports.

### 2.3 Parallelism rules

- Steps 2 and 5 frontend-scaffold can run **in parallel** with step 3 once the API contract is locked in OpenAPI.
- Reviews (7, 8, 9) run **in parallel** before docs (10).
- Anything touching the same files runs **serially** to avoid merge conflicts.

## 3. Roles: Human vs AI

| Concern         | Human (harness engineer)                          | AI Team                                                  |
| --------------- | ------------------------------------------------- | -------------------------------------------------------- |
| Direction       | Defines features, priorities, deadlines           | Suggests breakdowns, flags risks                         |
| Design          | Approves architecture, picks between options      | `tech-lead` drafts ADRs; surfaces tradeoffs              |
| Implementation  | Spot review                                       | `backend-engineer`, `frontend-engineer`, `db-engineer`   |
| Verification    | Final UX sign-off                                 | `qa-engineer`, `ux-reviewer`, CI                         |
| Review          | Final approver on PR                              | `code-reviewer`, `security-reviewer`                     |
| Docs            | Approves                                          | `docs-writer`                                            |
| Ops             | Approves deploys, rotates secrets                 | `release-manager` prepares releases                      |

## 4. MCP Servers Used

(Canonical list in [ARCHITECTURE.md §6](ARCHITECTURE.md).) AI-native specifics:

- **Context7** — *primary defense against legacy code*. Every implementer agent calls Context7 with the relevant library + version (e.g. `angular@21`, `spring-boot@3.5`, `primeng@18`, `ngrx-signals@18`, `hibernate@6`) before generating non-trivial code, then includes a short "Verified against Context7 on `<date>`" note in the PR body.
- **Claude in Chrome** — `ux-reviewer`'s primary tool. Verifies install prompt, service worker registration, offline behavior, Lighthouse PWA score, axe results, and golden-path UX.
- **GitHub via `gh`** — `release-manager` and PR automation.
- **scheduled-tasks** — nightly `deps-bump`, `security-scan`, `docs-drift-check`.
- **mcp-registry** — when a new capability is needed, query the registry before writing custom code.

## 5. Skills (planned)

Skills live under `.claude/skills/` and are versioned in git. Order roughly reflects priority.

### Bootstrap

- **`bootstrap-ai-team`** — writes the subagent definitions under `.claude/agents/` (one per role in §2.1) with proper tool allowlists.
- **`scaffold-frontend`** — `ng new the-dogs --standalone --routing --style=scss --strict --ssr=false`, adds `@angular/pwa`, PrimeNG + theme, Tailwind 4, lucide-angular, NgRx Signal Store, Vitest, Playwright, ESLint, Prettier, axe.
- **`scaffold-backend`** — Spring Boot 3.5 Maven project with Java 25, web/data-jpa/security/validation/actuator, Hibernate, Lombok, MapStruct, Flyway, Postgres driver, springdoc, Testcontainers, Spotless.
- **`scaffold-feature`** — given a feature name, creates frontend module + backend module skeletons with passing skeleton tests and a Flyway migration stub.
- **`scaffold-ci`** — generates `.github/workflows/` matching ARCHITECTURE.md §10.

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
