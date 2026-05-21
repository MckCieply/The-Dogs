# ADR-0010 — AI Team Consolidation (8 Roles)

- **Status:** Superseded by [ADR-0012](0012-automated-two-flow-pipeline.md)
- **Date:** 2026-05-03
- **Deciders:** Aleksander Torka (Owner)
- **Supersedes:** [ADR-0007](0007-ai-team-composition.md)

## Context

ADR-0007 defined a 10-agent team. A redundancy review against the actual scope of the project (small enterprise CRUD + scheduler PWA on a Spring Boot/Hibernate stack) identified two roles whose responsibilities are better placed inside adjacent roles than maintained separately:

- **db-engineer** — in a Hibernate-led shop with simple schemas (Dogs, Notes, Clients, Appointments, Users), the entity, repository, and migration are co-designed and co-changed. Splitting them creates handoff overhead with no information gain.
- **ux-reviewer** — `qa-engineer`'s Playwright + axe-core suite already programmatically covers the bulk of the UX review checklist. Adding live verification as a step inside `qa-engineer` is more efficient than maintaining a separate role.

`docs-writer` was reviewed as a candidate for replacement-by-skills but **retained** at owner request — having a dedicated agent that owns documentation hygiene end-to-end is valued.

## Decision

Reduce the AI team from 10 to **8 roles**:

| # | Role               | Owns                                                                                  |
| - | ------------------ | ------------------------------------------------------------------------------------- |
| 1 | tech-lead          | Specs, ADRs, decomposition                                                            |
| 2 | backend-engineer   | Spring Boot impl **+ Flyway migrations + schema design** (folded in from db-engineer) |
| 3 | frontend-engineer  | Angular PWA impl                                                                      |
| 4 | qa-engineer        | Tests **+ live PWA verification in Chrome** (folded in from ux-reviewer)              |
| 5 | security-reviewer  | Adversarial review, dependency CVE, license scan                                      |
| 6 | code-reviewer      | Project-checklist review                                                              |
| 7 | docs-writer        | OpenAPI snapshot, ARCHITECTURE/ADR/CHANGELOG upkeep                                   |
| 8 | release-manager    | CI/CD, branch protection, promotion, tags, hotfixes                                   |

### Where the folded responsibilities live now

- **Schema and migration discipline** (append-only, online-friendly DDL, destructive-change approval, no mixing data + schema migrations in one file, top-of-file SQL comment referencing spec/ADR) is preserved verbatim in `backend-engineer`'s "Schema and migrations" hard-rules section.
- **Live PWA verification checklist** (manifest + SW + offline + console + network + axe + Lighthouse + install prompt) is preserved verbatim in `qa-engineer`'s "Part B — Live PWA verification" section. `qa-engineer`'s tool allowlist is extended with the Claude in Chrome and Claude Preview MCPs.

## Consequences

- The per-feature workflow (see [AI_NATIVE.md §2.2](../AI_NATIVE.md)) drops from 10 numbered steps to 7, eliminating two handoff boundaries.
- All schema and UX-quality rules remain in force; they are now checklist items in `backend-engineer` and `qa-engineer` respectively.
- One file fewer per agent to maintain in `.claude/agents/`.

## Reintroducing the folded roles later

These roles can be re-introduced via a new ADR if scope grows:

- A standalone **db-engineer** becomes warranted if reporting/analytics, sharding, replication, query-plan tuning, or non-trivial Postgres extensions become regular work.
- A standalone **ux-reviewer** becomes warranted if exploratory/qualitative UX (beyond automatable checks) becomes a regular need — for instance if the product ships a customer-facing public surface.

## Notes

- ADR-0007 is marked Superseded but retained for history.
- The reviewer split (`code-reviewer` vs `security-reviewer`) is preserved — they apply different mindsets (correctness/conventions vs adversarial) and the cost of two opus passes is justified for a multi-tenant SaaS with auth.
- Mockups and design artifacts will live under `docs/design/`; tooling and format are not yet decided. We do not currently plan a `ui-designer` agent — see `docs/design/README.md`.
