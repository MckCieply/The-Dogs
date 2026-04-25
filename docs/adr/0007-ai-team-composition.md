# ADR-0007 — AI Team Composition (Harness-Engineer Model)

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Owner

## Context

Owner asked to work in a "harness engineer" theme: a single human directs, reviews, and approves while a team of AI agents implements each feature. We need a stable team composition so each feature flows through the same well-known pipeline.

## Decision

Adopt a fixed AI team realised as Claude Code subagents (definition files under `.claude/agents/`), each with a narrow responsibility and a tailored tool allowlist. Roles:

| Role                | Subagent file                                       |
| ------------------- | --------------------------------------------------- |
| Tech Lead           | `.claude/agents/tech-lead.md`                       |
| Backend Engineer    | `.claude/agents/backend-engineer.md`                |
| Frontend Engineer   | `.claude/agents/frontend-engineer.md`               |
| DB Engineer         | `.claude/agents/db-engineer.md`                     |
| QA Engineer         | `.claude/agents/qa-engineer.md`                     |
| UX Reviewer         | `.claude/agents/ux-reviewer.md`                     |
| Security Reviewer   | `.claude/agents/security-reviewer.md`               |
| Code Reviewer       | `.claude/agents/code-reviewer.md`                   |
| Docs Writer         | `.claude/agents/docs-writer.md`                     |
| Release Manager     | `.claude/agents/release-manager.md`                 |

The orchestrator skill `feature-deliver` sequences the team per the workflow in [AI_NATIVE.md §2.2](../AI_NATIVE.md). Reviews run in parallel; implementation steps run in parallel only when they don't touch the same files.

**Mandatory MCP usage:**
- `tech-lead`, `backend-engineer`, `frontend-engineer`, `db-engineer` MUST consult **Context7 MCP** before generating non-trivial code. Each PR notes "Verified against Context7 on `<date>`" with the libraries checked.
- `ux-reviewer` MUST drive the running PWA via **Claude in Chrome** for any UI-touching change.

## Consequences

- Clear responsibilities — easier to debug "why was this done this way" by mapping back to the responsible role.
- Subagents have isolated context windows, so the orchestrator avoids context bloat.
- Tool allowlists per role limit blast radius (e.g., `code-reviewer` cannot Write).
- Adding a new role later (e.g., `performance-engineer`) is additive — no refactor needed.

## Alternatives considered

- **Single generalist agent per feature** — bloated context, less specialization, weaker reviews.
- **Pair-programming model** (one human, one agent) — slower; doesn't take advantage of parallelism.

## Open

- Whether to add a `data-engineer` role later if reporting/analytics becomes a module.
