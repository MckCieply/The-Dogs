# The-Dogs — Architecture

## 1. Overview

The-Dogs is **enterprise SaaS for dog trainers**. It is a Progressive Web App (PWA) with an Angular frontend and a Spring Boot backend, persisted in PostgreSQL, secured with JWT-based Spring Security + RBAC, and built end-to-end with an AI agent team (see [AI_NATIVE.md](AI_NATIVE.md)).

### Initial modules

| Module     | Surface                                       |
| ---------- | --------------------------------------------- |
| Dogs       | List + form CRUD                              |
| Notes      | List + form CRUD, scoped per dog              |
| Scheduler  | Calendar UI for client meetings (CRUD events) |

### High-level diagram

```
┌────────────────────┐    HTTPS / JSON     ┌─────────────────────┐    JDBC    ┌──────────────┐
│ Angular PWA (SPA)  │ ─────────────────▶  │ Spring Boot REST API│ ─────────▶ │  PostgreSQL  │
│  + ServiceWorker   │                     │  Spring Security    │            │   (17.x)     │
│  + IndexedDB cache │ ◀───  JWT bearer ── │  Hibernate / JPA    │            └──────────────┘
└────────────────────┘                     └─────────────────────┘
        ▲                                           │
        │                                           ▼
        └────── OpenAPI 3.1 (springdoc) ────────────┘
```

## 2. Languages & Versions

Newest stable assumed at project bootstrap (May 2026). All version assumptions are validated against [**Context7 MCP**](#6-mcp-servers) before generating code so we don't ship legacy snippets.

### Frontend
| Tool             | Version  | Notes                                                         |
| ---------------- | -------- | ------------------------------------------------------------- |
| Angular          | 21.x     | Standalone components, **signals + NgRx Signal Store**, zoneless |
| TypeScript       | 5.7+     | `strict: true`                                                |
| Node.js          | 22 LTS   | Required for Angular CLI 21                                   |
| Package mgr      | **npm 10+** | Lockfile committed (`package-lock.json`)                  |
| RxJS             | 7.8+     | Used at boundaries; signals preferred for app state           |
| State            | NgRx Signal Store 21.x | Lightweight, signal-native, `withEntities` for lists; tracks Angular major |
| UI components    | **PrimeNG 21.x** (Apache-2.0) | Tables, forms, FullCalendar wrapper for scheduler; tracks Angular major |
| Icons            | **lucide-angular** (ISC) | Primary icon set; PrimeIcons available as fallback |
| Styling          | Tailwind CSS 4.x + PrimeNG theme tokens | Utility-first, JIT             |
| PWA              | `@angular/pwa` schematic | Service Worker, manifest, install prompt, offline cache |
| Testing          | Vitest (unit) + Playwright (e2e) + axe-core (a11y) |                     |

### Backend
| Tool                 | Version          | Notes                                              |
| -------------------- | ---------------- | -------------------------------------------------- |
| Java                 | 25 LTS           | Records, pattern matching, virtual threads         |
| Spring Boot          | 3.5.x            | Spring Framework 6.2                               |
| Build                | **Maven 3.9.x**  | `mvnw` wrapper committed                           |
| Web                  | Spring Web MVC   | Virtual-thread executor enabled                    |
| Persistence          | Spring Data JPA + **Hibernate 6.x** | PostgreSQL driver               |
| Boilerplate          | **Lombok 1.18.x**| `@Getter`/`@Setter`/`@Builder` (NOT `@Data` on JPA entities — see §7) |
| Migrations           | Flyway           | `backend/src/main/resources/db/migration`          |
| Validation           | Jakarta Validation (Hibernate Validator) |                          |
| Security             | Spring Security 6 | JWT bearer, RBAC via authorities (`ROLE_TRAINER`, `ROLE_ADMIN`) |
| Mapping              | MapStruct 1.6.x  | Compile-time DTO ↔ entity mappers                  |
| Testing              | JUnit 5, Testcontainers (Postgres), MockMvc, RestAssured |        |
| API docs             | springdoc-openapi 2.x | Generates OpenAPI 3.1                         |

### Infrastructure
| Tool                | Version | Purpose                                                        |
| ------------------- | ------- | -------------------------------------------------------------- |
| Docker              | latest  | Local Postgres, app images                                     |
| Docker Compose      | v2      | One-command local stack; `docker-compose.prod.yml` for prod overrides |
| PostgreSQL          | 17.x    | Primary datastore                                              |
| Cloudflare Tunnel   | latest  | Exposes local stack to internet — no port forwarding, free TLS, hides home IP |
| GitHub Actions      | n/a     | CI: build, test, lint, security scan, container build          |

## 3. Repository Layout

```
The-Dogs/
├── .gitignore
├── README.md
├── LICENSE                          # proprietary, all rights reserved (TBD — see ADR-0008)
├── docker-compose.yml               # postgres + backend + frontend (added during scaffold)
├── .github/workflows/               # CI (build, test, scan, container)
├── docs/
│   ├── ARCHITECTURE.md              # this file
│   ├── AI_NATIVE.md                 # agent team + workflow
│   ├── adr/                         # Architecture Decision Records
│   │   ├── 0001-stack-and-build-tools.md
│   │   ├── 0002-domain-scope.md
│   │   ├── 0003-auth-jwt-rbac.md
│   │   ├── 0004-frontend-state.md
│   │   ├── 0005-ui-component-library.md
│   │   ├── 0006-pwa.md
│   │   ├── 0007-ai-team-composition.md
│   │   └── 0008-license.md          # pending decision
│   ├── specs/                       # feature briefs, one file per feature
│   └── api/                         # openapi.yaml snapshots, schema.sql
├── frontend/                        # Angular workspace
│   ├── src/
│   │   ├── app/
│   │   │   ├── components/          # page-level & feature components (dogs, notes, scheduler)
│   │   │   ├── services/            # all injectable services incl. API resource services
│   │   │   ├── shared/              # reusable ui primitives, pipes, directives
│   │   │   ├── guards/              # route guards (auth, role)
│   │   │   ├── interceptors/        # HTTP interceptors (JWT, error handling)
│   │   │   ├── models/              # TS interfaces / types (generated from OpenAPI)
│   │   │   └── layout/              # shell, navbar, sidebar components
│   │   ├── assets/
│   │   ├── manifest.webmanifest
│   │   └── ngsw-config.json
│   ├── angular.json
│   ├── package.json
│   └── tsconfig*.json
└── backend/
    ├── src/main/java/com/thedogs/
    │   ├── ThedogsApplication.java
    │   ├── config/                  # security, openapi, jpa, virtual threads
    │   ├── common/                  # base entities, error handlers, utils
    │   └── modules/
    │       ├── dogs/                # controller, service, repo, entity, dto, mapper
    │       ├── notes/
    │       └── scheduler/
    ├── src/main/resources/
    │   ├── application.yml
    │   └── db/migration/V1__init.sql ...
    ├── src/test/java/...
    ├── pom.xml
    └── mvnw, mvnw.cmd, .mvn/
```

## 4. Domain Model (initial sketch)

```
User (trainer)        Client                Dog                  Note               Appointment
─────────────         ─────────             ─────                ─────              ───────────
id (uuid)             id                    id                   id                 id
email                 trainer_id            client_id            dog_id             trainer_id
password_hash         name                  name                 trainer_id         client_id
roles                 phone                 breed                title              starts_at
created_at            email                 birthdate            body (text)        ends_at
                      notes                 sex                  created_at         location
                                            metadata jsonb       updated_at         status
                                            archived                                notes
```

Refined per feature spec under `docs/specs/`. RBAC: `ROLE_TRAINER` sees only own clients/dogs; `ROLE_ADMIN` sees all.

## 5. API Contract

- REST/JSON over HTTPS, base path `/api/v1`.
- Resources: `/dogs`, `/dogs/{id}/notes`, `/clients`, `/appointments`, `/auth/login`, `/auth/refresh`, `/me`.
- OpenAPI 3.1 generated by springdoc, committed under `docs/api/openapi.yaml` on every backend release.
- Frontend types generated from the OpenAPI spec via `openapi-typescript` (skill `api-sync`).
- Pagination: cursor-based (`?cursor=…&limit=…`) for lists.
- Error format: RFC 7807 `application/problem+json`.

## 6. MCP Servers

Developer-time tooling inside the Claude Code harness — not the app runtime. Project-scope configuration lives in [`.mcp.json`](../.mcp.json) at the repo root.

| MCP Server              | Status         | Purpose                                                                  |
| ----------------------- | -------------- | ------------------------------------------------------------------------ |
| **Context7**            | ✅ Configured  | Pull up-to-date library docs (Angular, Spring, Hibernate, PrimeNG, NgRx, Lucide, Postgres, Flyway) before writing code so we never ship legacy APIs. **Mandatory call before any non-trivial code generation.** Configured at project scope in `.mcp.json`. |
| Filesystem (built-in)   | ✅ Built-in    | Read/Edit/Write/Glob/Grep on project files                               |
| Git (via Bash)          | ✅ Built-in    | Branching, commits, diffs                                                |
| Claude in Chrome        | ✅ User-scope  | Drive the Angular dev server in a real browser; verify PWA install, service worker, offline behavior. Used by `qa-engineer` for live PWA verification. |
| Claude Preview          | ✅ User-scope  | Lightweight headless preview during agent loops                          |
| GitHub (via `gh`)       | ✅ Built-in    | Issues, PRs, releases, CI status via the `gh` CLI                        |
| computer-use            | ✅ User-scope  | Native-app interactions (DB GUI) when no MCP exists                      |
| mcp-registry            | ✅ User-scope  | Discover and add new MCPs as the project grows                           |
| scheduled-tasks         | ✅ User-scope  | Nightly: dependency check, doc-drift check, license scan                 |
| **PostgreSQL MCP**      | ⏳ Deferred    | Schema introspection and read-only queries against the local Postgres. **Trigger to add:** after `backend-engineer` scaffolds the backend and `docker compose up` brings Postgres online. Recommended package: `@modelcontextprotocol/server-postgres` (read-only) or `crystaldba/postgres-mcp` (richer). |
| **GitHub MCP (server)** | ⏳ Deferred    | Richer PR/issue/branch-protection automation than the `gh` CLI. **Trigger to add:** when the GitHub repository exists and a personal access token is available. Recommended package: `github/github-mcp-server`. |
| Sentry, Figma, etc.     | ⏳ Conditional | Add if/when the matching milestone arrives (deployed env, design tooling decision, etc.). See [AI_NATIVE.md §8](AI_NATIVE.md). |

## 7. Coding Standards

- **Formatting:** Prettier (frontend), Spotless + google-java-format (backend). Enforced in CI.
- **Linting:** ESLint with Angular plugin (frontend); compile-time strictness preferred over runtime checks (backend).
- **Type safety:** TS `strict: true`. Java compiled with `-parameters`, `-Xlint:all`.
- **Lombok on JPA entities:** **NEVER `@Data` on entities.** Use `@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`. Override `equals`/`hashCode` manually using a stable business key or the JPA `@Id` only after persistence (see [ADR-0001](adr/0001-stack-and-build-tools.md)). `@ToString(exclude = ...)` to avoid LazyInitializationException and cycles.
- **Hibernate:** prefer `LAZY` associations everywhere; use DTO projections for reads to avoid N+1; explicit `@Transactional` on services.
- **Tests:** every backend endpoint has at least one Testcontainers-backed integration test; every frontend feature has Vitest unit + Playwright smoke; a11y assertions via `@axe-core/playwright`.
- **Commits:** Conventional Commits. One logical change per commit.
- **Branches:** three long-lived (`dev` / `staging` / `prod`) + short-lived `feat/*`, `fix/*`, `chore/*` from `dev`. See [ADR-0009](adr/0009-branching-strategy.md).
- **ADRs:** every non-obvious decision goes to `docs/adr/`.

## 8. PWA Standards

The frontend ships as an installable PWA from day one.

- **Manifest:** name, short_name, theme_color, background_color, icons (192/512/maskable), `display: standalone`, `start_url: /`.
- **Service Worker:** generated by `@angular/pwa`; runtime caching via `ngsw-config.json` — `freshness` for `/api/`, `performance` for static assets.
- **Offline:** read-only views (dog list, recent notes, today's schedule) work offline from cache; mutations queue and replay (later milestone, tracked in spec).
- **Lighthouse PWA score** ≥ 90 enforced in CI.
- **Accessibility:** WCAG 2.1 AA target; axe-core in CI.
- **Install prompt:** custom UI surface, not the browser default.

## 9. Security Baseline

- All backend endpoints authenticated by default; explicit `@PreAuthorize("permitAll()")` to opt out.
- JWT access tokens (15 min) + refresh tokens (7 days) — refresh stored as HttpOnly Secure SameSite=Strict cookie; access token in memory.
- Passwords: BCrypt, cost 12+.
- RBAC: `ROLE_TRAINER`, `ROLE_ADMIN` (extensible). Method-level `@PreAuthorize` on services, not just controllers.
- Secrets via env vars; `.env.example` documents required keys.
- CSP, HSTS, X-Content-Type-Options, X-Frame-Options on API responses.
- Dependency scanning: Dependabot + OWASP `dependency-check-maven` + `npm audit` in CI.
- License scanning: `license-checker` (npm) + `license-maven-plugin` to forbid copyleft (GPL/AGPL) drift.

## 10. CI/CD (GitHub Actions)

Workflows under `.github/workflows/`:

| Workflow             | Trigger              | Steps                                                              |
| -------------------- | -------------------- | ------------------------------------------------------------------ |
| `ci-backend.yml`     | PR + push            | Maven verify (Spotless, tests w/ Testcontainers, OWASP scan)       |
| `ci-frontend.yml`    | PR + push            | npm ci, lint, test (Vitest), build, Lighthouse + axe budgets       |
| `ci-e2e.yml`         | PR (label) + nightly | Docker Compose full stack + Playwright                             |
| `release.yml`        | tag `v*`             | Build container images, push to registry, draft GitHub Release     |
| `dependency-scan.yml`| nightly              | OWASP, npm audit, license scan; opens issues on findings           |

Branch protection (per [ADR-0009](adr/0009-branching-strategy.md)):

| Branch    | Required checks                                       | Merge type    |
| --------- | ----------------------------------------------------- | ------------- |
| `dev`     | `ci-backend`, `ci-frontend`                           | squash        |
| `staging` | `ci-backend`, `ci-frontend`, `ci-e2e`                 | merge commit  |
| `prod`    | `ci-backend`, `ci-frontend`, `ci-e2e`, manual approval | merge commit |

Default branch: `dev`. Force-push and deletions blocked on all three. Signed commits encouraged.

## 11. Environments

| Env   | Frontend                | Backend                        | DB                        |
| ----- | ----------------------- | ------------------------------ | ------------------------- |
| local | `npm start` :4200       | `./mvnw spring-boot:run` :8080 | docker-compose postgres   |
| ci    | headless build + tests  | mvn verify + Testcontainers    | ephemeral container       |
| prod  | nginx container (port 80) | Spring Boot container        | postgres container (internal only) |

### Production stack

Production runs on a spare laptop using Docker Compose (see [ADR-0011](adr/0011-hosting-strategy.md)):

```
Internet → Cloudflare Tunnel (cloudflared) → nginx:80 → Angular PWA
                                                       ↘ /api/ proxy → backend:8080 → postgres:5432
```

**Start prod:**
```bash
# Copy and fill .env (JWT_SECRET + CLOUDFLARE_TUNNEL_TOKEN)
cp .env.example .env

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

**What `docker-compose.prod.yml` adds:**
- `restart: unless-stopped` on all services
- Postgres and backend ports removed from host (internal Docker network only)
- `cloudflared` service that connects to Cloudflare's edge via the tunnel token
- Log rotation on all services

**Migration to a VPS** when needed: provision any Linux VPS, `git pull`, run the same command above. Point the Cloudflare Tunnel to the new host — no DNS changes required.

## 12. Architecture Decision Records

See [docs/adr/](adr/). The current set:

- [ADR-0001 — Stack and build tools](adr/0001-stack-and-build-tools.md)
- [ADR-0002 — Domain scope](adr/0002-domain-scope.md)
- [ADR-0003 — Auth: JWT + Spring Security + RBAC](adr/0003-auth-jwt-rbac.md)
- [ADR-0004 — Frontend state: signals + NgRx Signal Store](adr/0004-frontend-state.md)
- [ADR-0005 — UI component library: PrimeNG + lucide-angular](adr/0005-ui-component-library.md)
- [ADR-0006 — PWA from day one](adr/0006-pwa.md)
- [ADR-0007 — AI team composition (harness engineer model)](adr/0007-ai-team-composition.md) *(superseded by ADR-0010)*
- [ADR-0008 — License](adr/0008-license.md) — Proprietary, © Aleksander Torka
- [ADR-0009 — Branching strategy](adr/0009-branching-strategy.md) — `dev` / `staging` / `prod`
- [ADR-0010 — AI team consolidation (8 roles)](adr/0010-ai-team-consolidation.md) *(superseded by ADR-0012)*
- [ADR-0011 — Hosting strategy: self-hosted laptop + Cloudflare Tunnel](adr/0011-hosting-strategy.md)
- [ADR-0012 — Automated two-flow pipeline (Flow A per feature, Flow B 3×/week)](adr/0012-automated-two-flow-pipeline.md)
