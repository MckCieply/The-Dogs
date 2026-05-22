---
slug: fix-ci-scaffold
status: ready
priority: asap
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0001  # stack (Maven, Vitest)
  - ADR-0009  # branching (required checks)
  - ADR-0012  # automated pipeline
---

# Fix CI scaffold (unblock `ci-backend` + `ci-frontend`)

## Summary

The first Flow A feature. Repairs the backend + frontend scaffolds so the two required GitHub Actions checks (`ci-backend / Maven verify` and `ci-frontend / Lint + test + build`) turn green. Without those, branch protection per [ADR-0009](../adr/0009-branching-strategy.md) refuses every subsequent merge and the entire Flow A pipeline jams.

This is intentionally narrow — **fix only what's broken**, do not add features.

## Motivation

CI failures observed on PR #4 (the workflow scaffolding PR itself):

- **`ci-backend` / Verify Spotless formatting** → `./mvnw: Permission denied` (exit 126). The Maven wrapper has no executable bit in the git index.
- **`ci-frontend` / Unit tests (Vitest)** → `ng test --run` → `Error: Unknown argument: run`. The `package.json` `test` script invokes `ng test` (Karma) but Vitest 4 is in `devDependencies` per [ADR-0001](../adr/0001-stack-and-build-tools.md). Mismatch.

Both are pure-scaffold bugs from earlier setup commits — no business logic involved.

## Scope

### In scope
- Backend: set the executable bit on `backend/mvnw` in git (`git update-index --chmod=+x`). Verify `./mvnw spotless:check` and `./mvnw verify` run end-to-end on a Linux CI runner.
- Backend: if `mvn verify` requires at least one test class to pass, add the smallest possible Spring Boot smoke test (`@SpringBootTest` that just loads context, or `assertTrue(true)`).
- Backend: if Spotless plugin is referenced in the pom but configuration is incomplete or formatting is non-conformant, either complete the config or run `./mvnw spotless:apply` and commit the result.
- Frontend: replace `"test": "ng test"` with the Vitest invocation appropriate for Angular 21 (likely `"test": "vitest"`, with a minimal `vitest.config.ts` if not present).
- Frontend: confirm `npm run lint`, `npm test -- --run`, and `npm run build` each exit 0 on a clean `npm ci`. Add a minimal smoke test if no test files exist yet.
- Frontend: verify `e2e` script still works conceptually (does not need to run in this feature; CI e2e is on a different trigger).

### Out of scope
- Any actual application feature (no entities, components, endpoints, business logic).
- Migration to a non-default test framework. Vitest is already in `devDependencies` per ADR-0001 — use what's there.
- Lighthouse / axe / Playwright coverage beyond what already exists.
- Reformatting the entire codebase if Spotless reveals issues — apply Spotless conventions but do not rename files, restructure packages, etc.

## Acceptance criteria

- AC-1: `cd backend && ./mvnw -B spotless:check` exits 0 on a fresh Ubuntu CI runner.
- AC-2: `cd backend && ./mvnw -B verify` exits 0, running at least one test (Testcontainers config not yet required — a non-Testcontainers Spring Boot context-load test is sufficient).
- AC-3: `cd frontend && npm ci && npm run lint && npm test -- --run && npm run build -- --configuration=production` all exit 0 in sequence on a fresh Ubuntu CI runner.
- AC-4: The `ci-backend / Maven verify` check on the resulting PR is green.
- AC-5: The `ci-frontend / Lint + test + build` check on the resulting PR is green.
- AC-6: No files are touched outside `backend/`, `frontend/`, and (if needed) `.github/workflows/` for tuning. No edits under `docs/`, `scripts/`, or `.claude/agents/`.
- AC-7: Any new test added is a real smoke test (not `@Disabled` / `.skip`) and is documented as such with a one-line comment.

## Investigation notes (for the implementer)

- The Maven wrapper executable bit is set with `git update-index --chmod=+x backend/mvnw`. After the commit, `git ls-tree HEAD backend/mvnw` should show mode `100755`. Verify locally before pushing.
- For Vitest on Angular 21, the official builder is `@angular/build:unit-test` (Angular ≥ 20) or direct `vitest` invocation against `src/**/*.spec.ts`. Pick the simpler path — `vitest` directly works for Vitest-style specs, and the `package.json` already declares `vitest` 4 as a devDep. Angular components can be unit-tested with `@analogjs/vitest-angular` if needed, but a non-component smoke test is fine for AC-3.
- A minimal `vitest.config.ts` is typically:
  ```ts
  import { defineConfig } from 'vitest/config';
  export default defineConfig({
    test: { globals: true, environment: 'jsdom' },
  });
  ```
- Check whether `eslint.config.js` already exists and `npm run lint` works locally. If it errors on uncommitted test files, those errors should be fixed in this feature.
- If Spotless config is referenced in `pom.xml` but missing the `<configuration>` block, add a minimal Google Java Format ruleset:
  ```xml
  <java>
    <googleJavaFormat><version>1.27.0</version></googleJavaFormat>
    <removeUnusedImports/>
  </java>
  ```

## API surface

None. This feature does not change any HTTP API.

## UI surface

None.

## Security & RBAC

No code changes touch auth or RBAC. The added smoke tests should not introduce hardcoded credentials or secrets.

## Non-functional

- The added backend test must run in under 30 seconds on the CI runner (no Testcontainers in this feature).
- The frontend test invocation must run in under 30 seconds on the CI runner.
- After this feature merges, subsequent Flow A features (starting with F002 `dogs-crud`) must be able to land green without additional scaffold fixes.

## Open questions

- None. Both diagnoses are precise and the fixes are bounded. If the implementer discovers a third scaffold issue not listed here, fix it in the same PR with a one-line note in the PR body — do not split into a new feature.

## References

- [PR #4 — CI workflows scaffolding](https://github.com/MckCieply/The-Dogs/pull/4) — origin of the observed failures.
- [ADR-0001 — Stack and build tools](../adr/0001-stack-and-build-tools.md)
- [ADR-0009 — Branching strategy](../adr/0009-branching-strategy.md)
- [ADR-0012 — Automated two-flow pipeline](../adr/0012-automated-two-flow-pipeline.md)
