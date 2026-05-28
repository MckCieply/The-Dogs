# ADR-0015 — Frontend unit test runner: Vitest 4 instead of Karma/ng test

- **Status:** Proposed
- **Date:** 2026-05-27
- **Deciders:** Owner (review required)
- **Proposed by:** docs-writer (flow-b-1, implicit in fix/ci commit "switch frontend test runner from ng test to Vitest 4")

## Context

[ADR-0001](0001-stack-and-build-tools.md) listed "Vitest (unit)" as the frontend testing tool. The original CI scaffold used `ng test` (Karma + Jasmine), which contradicted the ADR. Angular 21 still ships Karma as the default test runner for `ng test`, but Karma is in maintenance mode and requires a browser binary in CI, adding complexity and flakiness.

## Decision

Replace `ng test` with **Vitest 4** for frontend unit tests.

- `vitest.config.ts` configures JSDOM as the test environment and `@analogjs/vitest-angular` (or equivalent Angular test utilities) as the framework preset.
- `frontend/src/test-setup.ts` provides the Angular test harness bootstrap.
- CI runs: `npm test -- --run` (non-watch, exits with code 0/1).
- Playwright remains the e2e runner (unchanged).

## Consequences

- No browser binary required for unit tests in CI — faster, more reliable.
- Test syntax shifts from Jasmine matchers to Vitest `expect` (vi.fn(), vi.spyOn(), etc.). Existing tests must be migrated; new tests must use the Vitest API.
- `ng test` is no longer a valid local command for unit tests. Developers run `npm test` instead.
- Coverage reports are produced by `@vitest/coverage-v8`; the coverage threshold and upload steps will be added in a follow-up.

## Alternatives considered

- **Jest** — functionally equivalent to Vitest for this use case but slower (no native ESM) and requires more Angular-specific configuration. Vitest's native ESM support and Vite integration are better aligned with modern Angular builds.
- **Keep Karma** — contradicts ADR-0001; Karma is unmaintained and requires a Chrome/Chromium binary in CI.
