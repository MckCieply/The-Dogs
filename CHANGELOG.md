# Changelog

All notable changes to this project are documented here.
Entries are grouped by [Conventional Commit](https://www.conventionalcommits.org/) type and appended by the `docs-writer` agent on each Flow B run.

Format per section:

```
## [flow-b-N] — YYYY-MM-DD

### feat
### fix
### chore
### refactor
### docs
### test
### ci
```

---

## [flow-b-1] — 2026-05-27

Covers commits from `flow-b-0` to HEAD (tag `flow-b-1` applied after this PR merges).
Security findings in this window: issues #21–#30 (opened by `security-reviewer`).
PWA findings in this window: issues #17–#20 (opened by `pwa-auditor`).

### feat

- **feat(auth-01):** Auth core — Spring Security 6 stateless JWT resource server, `TokenService` (HS256, JJWT 0.12.6), `AuthController` (`POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `GET /api/v1/auth/some-public-stub`), `JwtAuthenticationEntryPoint` (RFC 7807 JSON on 401), `SecurityConfig` (CSRF disabled, HSTS, path-based authorization rules), `GlobalExceptionHandler` (hardened error codes, static `detail` messages), `V2__auth_core.sql` Flyway migration (users + roles tables, citext extension). New doc: `docs/api/error-codes.md`. (#16)
- **feat(f001):** Fix CI scaffold — unblocked `ci-backend` and `ci-frontend` workflows that were failing due to missing compilation prerequisites. (#14)
- **feat(logging):** Token tracking per feature — `run-features.ps1` now captures `input_tokens`, `output_tokens`, `cache_write_tokens`, and `cache_read_tokens` from each Claude invocation and emits them to `logs/pipeline.jsonl`.

### fix

- **fix(ci):** Use weekly-rotating cache key for OWASP NVD database — prevents stale CVE data accumulation and reduces NVD throttling impact. (#15)
- **fix(ci):** Fix Lighthouse server start — replaced `http-server` npm package with `python3 -m http.server`; added a 30-iteration readiness poll to eliminate the race condition between server start and Lighthouse run.
- **fix(ci):** Run Spotless under JDK 21, compile under JDK 25 — `google-java-format` uses internal `javac` APIs removed in JDK 25; the CI workflow now installs JDK 21 first, runs `spotless:check`, then switches to JDK 25 for `mvn verify`.
- **fix(ci):** Upgrade Spotless to 2.46.1 and google-java-format to 1.25.2 for JDK 25 compatibility.
- **fix(ci):** Apply google-java-format to all backend sources.
- **fix(ci):** Switch frontend test runner from `ng test` (Karma) to Vitest 4.
- **fix(ci):** Set executable bit on `backend/mvnw`.
- **fix(orchestrator):** Fix priority sort, DryRun status mutation, and tag interpolation in `run-features.ps1`.
- **fix(orchestrator):** Repair `mvnw` post-processing, add verbose logging, and adjust WIP push cadence.

### chore

- **chore(orchestrator):** Reduce quota sleep from 5 h to 1 h; add `start-orchestrator.ps1` wrapper script that loops `run-features.ps1` indefinitely with a 10-minute idle sleep between runs.
