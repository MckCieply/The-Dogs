# ADR-0004 — Frontend State: Signals + NgRx Signal Store

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Owner

## Context

Owner asked for the "best" choice with no plan to revisit. The app is enterprise CRUD with a scheduler — non-trivial state across feature modules (lists, filters, forms, calendar view models, auth user, offline mutation queue).

## Decision

- **Component-local state:** native Angular signals.
- **Cross-component / feature state:** **NgRx Signal Store 18.x** with `withEntities`, `withComputed`, `withMethods`, `withHooks`. One store per feature slice (`dogs`, `notes`, `scheduler`, `auth`).
- **Async / event streams:** RxJS at the boundary (HTTP, websockets), converted to signals via `toSignal` for consumption.
- **No legacy NgRx Store / Effects / Actions.** Signal Store is the chosen path; classic NgRx is not added.

## Consequences

- Lightweight, signal-native, low boilerplate vs classic NgRx.
- Aligns with Angular's strategic direction (zoneless, signal-based change detection).
- Consistent pattern across the codebase — easier for AI agents to generate new feature slices that look like existing ones.
- `frontend-engineer` agent template includes a Signal Store slice in every new feature scaffold.

## Alternatives considered

- **Pure signals only** — would require ad-hoc service singletons for shared state; harder to reason about as the app grows.
- **Classic NgRx Store** — heavier, action/reducer ceremony, going against the framework's signal direction.
- **Akita / Elf** — smaller communities; risk of abandonment.
