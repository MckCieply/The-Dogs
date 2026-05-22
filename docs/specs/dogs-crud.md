---
slug: dogs-crud
status: ready
priority: normal
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0001  # stack
  - ADR-0003  # auth + RBAC
  - ADR-0004  # Signal Store
  - ADR-0005  # PrimeNG + lucide-angular
  - ADR-0006  # PWA
  - ADR-0012  # automated pipeline (this spec is the first real Flow A feature)
---

# Dogs CRUD

## Summary

End-to-end CRUD for the `Dog` resource: a trainer manages a list of dogs (their clients' dogs). Backend exposes a REST resource at `/api/v1/dogs` with pagination, filtering, and archive semantics. Frontend ships a Dogs feature module with a paginated list, a create/edit form, and an archive flow — fully offline-readable for the list view per [ADR-0006](../adr/0006-pwa.md).

This is the **first real Flow A feature** under [ADR-0012](../adr/0012-automated-two-flow-pipeline.md). It exercises the full pipeline (explorer → implementer → test-writer → reviewer) end-to-end on a stack-typical CRUD with auth, RBAC, validation, migration, store slice, components, tests, and a11y.

## Motivation

- It is the first MVP module per [ARCHITECTURE.md §1](../ARCHITECTURE.md). Without it, the product is empty.
- Establishes the project's CRUD pattern that subsequent specs (Notes, Scheduler, Clients) will repeat.
- Validates the automated pipeline on a feature of realistic size before more ambitious work.

## Scope

### In scope
- Backend: `Dog` entity, repository, service, controller, DTO + MapStruct mapper, Flyway migration, integration tests, OpenAPI annotations.
- Frontend: feature module under `frontend/src/app/components/dogs/` with list and form components, Signal Store slice, routes, route guard for auth, generated TS types from OpenAPI.
- Tests: Vitest for store + components, Playwright for golden path, Testcontainers for backend integration, axe-core for a11y.
- PWA: `/dogs` list and detail routes contribute to `ngsw-config.json` for offline read.

### Out of scope
- Full Client CRUD — see "Open questions" for the denormalisation decision (`trainer_id` lives on `dog` directly for now; `client_id` becomes required when the Clients spec lands).
- Notes per dog — separate spec (`docs/specs/notes-crud.md`).
- Photo uploads, custom fields beyond `metadata jsonb`.
- Bulk operations (bulk archive, bulk delete, import/export).
- Audit log endpoint — separate concern.

## Acceptance criteria

A trainer logged in with `ROLE_TRAINER` can:

- AC-1: GET `/api/v1/dogs?cursor=&limit=&q=` returns a cursor-paginated page of **their own** dogs only (RBAC enforced at the service layer). Default `limit=25`, max `100`.
- AC-2: GET `/api/v1/dogs?archived=true` returns archived dogs; default excludes them.
- AC-3: GET `/api/v1/dogs/{id}` returns a single dog they own, `404` if not theirs (NOT `403` — do not leak existence).
- AC-4: POST `/api/v1/dogs` creates a dog with validated body, returns `201 Created` with `Location` header and the new resource.
- AC-5: PUT `/api/v1/dogs/{id}` updates editable fields; returns `200` with the updated resource; `409` on optimistic-lock conflict.
- AC-6: PATCH `/api/v1/dogs/{id}/archive` toggles `archived` boolean; returns `200`.
- AC-7: DELETE `/api/v1/dogs/{id}` hard-deletes only when `archived=true`; returns `409` otherwise (force-archive-first policy).
- AC-8: A `ROLE_ADMIN` user sees and acts on all dogs across all trainers (same endpoints).
- AC-9: An unauthenticated request returns `401` with RFC 7807 `application/problem+json`.
- AC-10: Validation errors return `400` with `errors[]` array per field.

A trainer using the frontend can:

- AC-F1: Navigate to `/dogs`, see a paginated list of their dogs with name, breed, birthdate, age (computed), and an archive toggle, sorted by name.
- AC-F2: Filter by free-text on name and breed via a debounced search box (300 ms).
- AC-F3: Toggle "show archived" to include archived dogs in the list.
- AC-F4: Click "Add dog" — opens a form (dialog or inline route) for name, breed, birthdate, sex, metadata (as collapsible JSON editor or simple key-value), with PrimeNG components and inline validation.
- AC-F5: Click an existing row — opens the same form pre-filled for edit.
- AC-F6: Archive/unarchive from the row's action menu (lucide icon `archive`).
- AC-F7: Delete an archived dog from the row's action menu, with a confirmation dialog.
- AC-F8: Offline (manifest + SW): the `/dogs` list renders from cache; mutations show a "queued — will sync when online" toast but do not crash the route (queue/replay is itself out of scope, but the UI must degrade gracefully).
- AC-F9: Lighthouse PWA ≥ 90 and Performance ≥ 80 on `/dogs`. axe-core reports zero serious/critical findings.

## Data model

### `dog` table (Flyway `V<N>__create_dog.sql`)

```sql
-- See docs/specs/dogs-crud.md — first MVP CRUD module.
CREATE TABLE dog (
    id           UUID PRIMARY KEY,
    trainer_id   UUID NOT NULL,        -- denormalised for RBAC scope; FK added when users table lands
    name         TEXT NOT NULL,
    breed        TEXT,
    birthdate    DATE,
    sex          TEXT CHECK (sex IN ('MALE', 'FEMALE', 'UNKNOWN')),
    metadata     JSONB DEFAULT '{}'::jsonb,
    archived     BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dog_trainer_archived ON dog (trainer_id, archived);
CREATE INDEX idx_dog_name_lower ON dog (LOWER(name));
```

### `Dog` entity (Lombok rules per ADR-0001)

```java
@Entity @Table(name = "dog")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(onlyExplicitlyIncluded = true)
public class Dog {
    @Id
    @ToString.Include
    private UUID id;

    @Column(nullable = false)
    private UUID trainerId;

    @ToString.Include
    @Column(nullable = false)
    private String name;

    private String breed;
    private LocalDate birthdate;

    @Enumerated(EnumType.STRING)
    private Sex sex;          // MALE | FEMALE | UNKNOWN

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(nullable = false)
    private boolean archived;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // equals/hashCode by id (stable post-persist; UUID v7 assigned client-side
    // via service-layer factory). See ADR-0001.
}
```

### DTOs and mapper (MapStruct 1.6)

- `DogResponse` — outbound; flattens `metadata` to a `Map<String, Object>`; includes `version` as ETag-equivalent.
- `DogCreateRequest` — `name` required (Jakarta `@NotBlank`, length 1..120), `breed` optional (max 80), `birthdate` optional (`@PastOrPresent`), `sex` optional enum, `metadata` optional.
- `DogUpdateRequest` — same shape, `version` required (optimistic lock).

## API surface

REST/JSON, base path `/api/v1`. springdoc annotations on every endpoint.

| Method | Path                          | Auth         | Notes |
| ------ | ----------------------------- | ------------ | ----- |
| GET    | `/api/v1/dogs`                | `ROLE_TRAINER` or `ROLE_ADMIN` | Query: `cursor`, `limit` (1–100), `q` (free text on `name`/`breed`), `archived` (default `false`). Returns `{ items: DogResponse[], next_cursor: string \| null }`. |
| GET    | `/api/v1/dogs/{id}`           | `ROLE_TRAINER` (own) or `ROLE_ADMIN` | `404` for non-existing or not-owned (no `403`). |
| POST   | `/api/v1/dogs`                | `ROLE_TRAINER` or `ROLE_ADMIN` | Body: `DogCreateRequest`. `trainer_id` derived from authenticated principal (NEVER from body). `201 Created` + `Location`. |
| PUT    | `/api/v1/dogs/{id}`           | `ROLE_TRAINER` (own) or `ROLE_ADMIN` | Body: `DogUpdateRequest` with `version`. `409` on version mismatch. |
| PATCH  | `/api/v1/dogs/{id}/archive`   | `ROLE_TRAINER` (own) or `ROLE_ADMIN` | Body: `{ "archived": true \| false }`. |
| DELETE | `/api/v1/dogs/{id}`           | `ROLE_TRAINER` (own) or `ROLE_ADMIN` | Hard delete only if `archived=true`; otherwise `409 Conflict` with message "archive first". |

Error format: RFC 7807 `application/problem+json` per [ARCHITECTURE.md §5](../ARCHITECTURE.md).

## UI surface

### Routes

```
/dogs            → DogsListPage (default; lazy module)
/dogs/new        → DogsFormPage (create)
/dogs/:id        → DogsFormPage (edit)
```

Auth guard: `authGuard` (existing; reject `null` token → redirect `/login`).
Role guard: not needed — backend enforces RBAC; frontend just doesn't render the page if 401/403 (interceptor redirects).

### Components (`frontend/src/app/components/dogs/`)

- `dogs-list.component.ts` — PrimeNG `<p-table>` with cursor pagination, debounced search input, "show archived" toggle, row action menu (edit / archive / delete).
- `dogs-form.component.ts` — PrimeNG `<p-card>` containing reactive form; submit calls store action; navigation back to `/dogs` on success.
- `dogs-confirm-delete.component.ts` — PrimeNG `<p-confirmDialog>` (or inline modal) for delete confirmation.

### Signal Store slice (`frontend/src/app/services/dogs.store.ts`)

- `withEntities()` for the visible list.
- `withMethods()` for `loadPage(cursor, q, archived)`, `create(dto)`, `update(id, dto)`, `archive(id, value)`, `remove(id)`.
- `withComputed()` for `loading`, `error`, `nextCursor`, `filtered`.
- `withHooks()` to load the first page on init.

### API service (`frontend/src/app/services/dogs.api.ts`)

- Thin wrapper over `HttpClient`. TS types regenerated from OpenAPI (`docs/api/openapi.yaml`) via `openapi-typescript`. No hand-written request/response shapes.

### Icons (lucide-angular)

- `dog`, `archive`, `archive-restore`, `trash-2`, `pencil`, `plus`.

## Security & RBAC

- All endpoints `@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")` at the **service layer** (not just controller) per [ADR-0003](../adr/0003-auth-jwt-rbac.md).
- For `ROLE_TRAINER`, every query scope-filters on `trainer_id = currentPrincipal().id`. Implementation: a Hibernate `@Filter` activated by an `@Aspect` reading `SecurityContextHolder`, or a `DogRepository` interface that always takes `trainerId` and never exposes a tenant-blind method.
- `ROLE_ADMIN` bypasses the trainer filter (service-layer branch).
- 404 instead of 403 for not-found-or-not-owned reads (no existence-leak).
- `trainer_id` on create/update **always** taken from the authenticated principal, NEVER from the request body.
- No logging of token claims, PII (name/birthdate are PII but logging the create event is fine without those values), or `metadata` contents.
- Standard headers (CSP, HSTS, X-Content-Type-Options, X-Frame-Options) preserved on all responses.

## Non-functional

- Backend p95 latency: list page ≤ 200 ms, single get ≤ 50 ms, write ≤ 150 ms (local Postgres, 10k rows).
- `Testcontainers` integration test boots Postgres 17, applies Flyway, exercises happy path + a 403/404/409 case per endpoint.
- Frontend Lighthouse PWA ≥ 90, Performance ≥ 80 on `/dogs`. WCAG 2.1 AA per [ADR-0006](../adr/0006-pwa.md).
- OpenAPI snapshot is updated on `dev` push by `ci-backend.yml`; no AI involvement (deterministic via springdoc).
- Conventional Commits in WIP commits: `feat(backend): ...`, `feat(frontend): ...`, `feat(db): create dog table`, `test(backend): dogs integration`, `test(frontend): dogs store + components`.

## Open questions

- **`client_id` deferral.** Per [ARCHITECTURE.md §4](../ARCHITECTURE.md) Dog belongs to Client which belongs to Trainer. For this first feature `trainer_id` is denormalised onto `dog` so we can ship without a Client CRUD dependency. When the Clients spec lands, a follow-up migration adds `client_id UUID NOT NULL`, backfills, and the RBAC scope query stays on `trainer_id` (still denormalised — both columns coexist for fast RBAC + clean owner navigation).
- **Metadata UI affordance.** The form should expose `metadata` as something usable. Decision deferred to implementer: simple key/value editor (suggested) or raw JSON textarea behind a "show advanced" toggle. Document the choice in PR body.
- **Cursor format.** Opaque base64 of `(name, id)` is the suggested default — keeps results stable under inserts. Implementer free to use a simpler offset scheme initially with a note for future migration.

## References

- [ARCHITECTURE.md §4 — Domain model](../ARCHITECTURE.md)
- [ARCHITECTURE.md §5 — API contract](../ARCHITECTURE.md)
- [ADR-0001 — Stack and build tools](../adr/0001-stack-and-build-tools.md)
- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [ADR-0004 — Frontend state: signals + NgRx Signal Store](../adr/0004-frontend-state.md)
- [ADR-0005 — UI component library: PrimeNG + lucide-angular](../adr/0005-ui-component-library.md)
- [ADR-0006 — PWA from day one](../adr/0006-pwa.md)
- [ADR-0012 — Automated two-flow pipeline](../adr/0012-automated-two-flow-pipeline.md)
