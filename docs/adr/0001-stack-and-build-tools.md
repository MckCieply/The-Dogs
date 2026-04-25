# ADR-0001 — Stack and Build Tools

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Owner

## Context

The-Dogs is a greenfield enterprise PWA. We need to lock the language, framework, and build-tool choices early so the scaffold and CI can be generated once and remain stable.

## Decision

**Frontend**
- Angular 21.x (standalone components, signals, zoneless where possible)
- TypeScript 5.7+, `strict: true`
- Node.js 22 LTS
- **npm 10+** as package manager (lockfile committed)
- Tailwind CSS 4.x for utility styling
- Vitest (unit) + Playwright (e2e) + axe-core (a11y)

**Backend**
- Java 25 LTS
- Spring Boot 3.5.x
- **Maven 3.9.x** (`mvnw` wrapper committed)
- **Hibernate 6.x** via Spring Data JPA
- **Lombok 1.18.x** for boilerplate reduction
- MapStruct 1.6.x for DTO ↔ entity mapping
- Flyway for SQL migrations
- JUnit 5 + Testcontainers (Postgres) for integration tests
- springdoc-openapi 2.x for OpenAPI 3.1 generation

**Database**
- PostgreSQL 17.x

## Consequences

**Positive**
- One LTS Java + one stable Spring Boot line means long support windows.
- Maven + npm are the most boring, most-documented choices — easiest for AI agents to find current docs (via Context7) and easiest for new humans to pick up.
- Hibernate + Lombok is the de-facto Spring stack; abundant examples for Context7 to verify against.
- Flyway + Testcontainers gives us reproducible DB state in CI without mocking.

**Negative / risks**
- **Lombok + JPA footgun:** `@Data` on entities causes infinite loops in `equals`/`hashCode`/`toString` via lazy associations. Mitigation: project rule (enforced by `code-reviewer` agent) — never `@Data` on entities; use `@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`; override `equals`/`hashCode` based on a stable business key; `@ToString(onlyExplicitlyIncluded = true)`.
- **Maven verbosity:** acceptable trade-off for ecosystem familiarity over Gradle's flexibility.
- **npm vs pnpm:** slightly slower installs, larger node_modules. Acceptable; npm is the lowest-friction choice for CI and contributors.

## Open

- Hosting target and production DB management are deferred — see AI_NATIVE.md §8.
