---
name: spec-new
description: Create a new feature spec under docs/specs/ pre-filled with the project's standard template (frontmatter + Summary, Motivation, Scope, Acceptance criteria, Data model, API surface, UI surface, Security & RBAC, Non-functional, Open questions, References). Use this skill whenever the user wants to draft, create, or start a feature spec, brief, or plan that the AI team will implement — even if they don't say the word "spec". Trigger on phrases like "write a spec for X", "draft the brief for Y", "let's plan the scheduler feature", "I need a feature doc for Z", or "start the docs/specs file for the trainer dashboard".
---

# spec-new

Create a new feature spec under `docs/specs/` for The-Dogs. The spec is the input to Flow A (per ADR-0012): `explorer` reads it, `implementer` works from it, `reviewer` checks against it.

## Steps

1. **Ask the user for** (skip if already obvious from the request):
   - Slug (kebab-case, becomes file name and the `slug` frontmatter field)
   - One-paragraph human description of what the feature does
   - Whether it's `priority: asap`, `normal`, or `later`
   - Whether it originated from a Flow B issue (`source_issue: <number>`) — if so, pre-fill it

2. **Read the relevant ADRs** before drafting — at minimum ADRs 0001, 0003, 0006, 0012; plus any ADR specifically affected by this feature. Specs must not contradict an Accepted ADR (propose a superseding ADR via `adr-new` first if needed).

3. **Consult Context7 MCP** for the relevant libraries (Angular, Spring Boot, Hibernate, PrimeNG, NgRx Signal Store) at the versions pinned in `docs/ARCHITECTURE.md`. Verify API references in the spec are current.

4. **Write the file** at `docs/specs/<slug>.md` with this exact template:

```markdown
---
slug: <slug>
status: ready
priority: <asap | normal | later>
owner: harness-engineer
created: <YYYY-MM-DD — today>
adrs:
  - ADR-0001
  - ADR-0003
  - ...  # list all ADRs this feature touches
source_issue: <null or issue number>
---

# <Title>

## Summary

<1-2 paragraphs. What this feature is, why now, the simplest framing of "what success looks like".>

## Motivation

- <bullet>

## Scope

### In scope
- Backend: <files / modules>
- Frontend: <files / modules>
- Migration: <V<N>__*.sql or "none">

### Out of scope
- <bullet>

## Acceptance criteria

A trainer logged in with `ROLE_TRAINER` can:

- AC-1: <testable behaviour>
- AC-2: ...

A trainer using the frontend can:

- AC-F1: ...
- AC-F2: ...

## Data model

### `<table>` table (Flyway `V<N>__create_<table>.sql`)

```sql
-- See docs/specs/<slug>.md — <one-line purpose>.
CREATE TABLE <table> (
    ...
);
```

### `<Entity>` (Lombok rules per ADR-0001)

```java
@Entity @Table(name = "<table>")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(onlyExplicitlyIncluded = true)
public class <Entity> {
    @Id
    @ToString.Include
    private UUID id;
    // ...
}
```

### DTOs and mapper (MapStruct 1.6)

- `<Entity>Response` — outbound.
- `<Entity>CreateRequest` — inbound for POST.
- `<Entity>UpdateRequest` — inbound for PUT, includes `version` for optimistic lock.

## API surface

| Method | Path | Auth | Notes |
| ------ | ---- | ---- | ----- |
| GET    | `/api/v1/<resource>` | `ROLE_TRAINER` or `ROLE_ADMIN` | cursor pagination |

## UI surface

### Routes
```
/<resource>          → <Resource>ListPage
/<resource>/new      → <Resource>FormPage (create)
/<resource>/:id      → <Resource>FormPage (edit)
```

### Components
- ...

### Signal Store slice
- `frontend/src/app/services/<resource>.store.ts` — `withEntities`, `withMethods`, `withComputed`, `withHooks`.

### Icons (lucide-angular)
- `<icon-name>`, ...

## Security & RBAC

- All endpoints `@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")` at the **service layer** per ADR-0003.
- For `ROLE_TRAINER`, every query scope-filters on `trainer_id = currentPrincipal().id`.
- `trainer_id` on writes ALWAYS from authenticated principal, NEVER from request body.
- ...

## Non-functional

- Backend p95: list ≤ 200 ms, single get ≤ 50 ms, write ≤ 150 ms.
- Lighthouse PWA ≥ 90, Performance ≥ 80 on touched routes.
- Conventional Commits per ARCHITECTURE.md §7.

## Open questions

- ...

## References

- [ARCHITECTURE.md §4 — Domain model](../ARCHITECTURE.md)
- [ADR-0001 — Stack and build tools](../adr/0001-stack-and-build-tools.md)
- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [ADR-0012 — Automated two-flow pipeline](../adr/0012-automated-two-flow-pipeline.md)
```

5. **Decide whether to add to features.json now.** If `priority` is set and the user signals "queue it":
   - Open `scripts/features.json`
   - Pick the next `F<NNN>` id (use `next_id`)
   - Append a queue entry (or prepend if `priority: asap`)
   - Increment `next_id`

## Quality checks before considering the spec done

- Every Acceptance Criterion is **testable** — a test can prove it pass or fail.
- The spec does not contradict any Accepted ADR (search `docs/adr/` for keywords from the spec).
- Open questions only cover items where implementer discretion is acceptable — not blocking ambiguities.
- Sections referencing types/tables show concrete schemas with Lombok rules from ADR-0001.
- Out-of-scope is explicit. Don't leave the reader guessing.
- Read the existing `docs/specs/dogs-crud.md` as a reference for tone, length, and depth.

## When NOT to use

- The user wants a one-off README change → just edit the file.
- The user wants an architectural decision → use `adr-new` skill first; spec follows.
- The change is bug-fix scope (already-merged feature has wrong behaviour) → write a test that reproduces it, no spec needed.
