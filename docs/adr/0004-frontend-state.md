# ADR-0004 — Frontend State: Signals + NgRx Signal Store

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Owner

## Context

Owner asked for the "best" choice with no plan to revisit. The app is enterprise CRUD with a scheduler — non-trivial state across feature modules (lists, filters, forms, calendar view models, auth user, offline mutation queue).

## Decision

- **Component-local state:** native Angular signals.
- **Cross-component / feature state:** **NgRx Signal Store 21.x** with `withEntities`, `withComputed`, `withMethods`, `withHooks`. One store per feature slice (`dogs`, `notes`, `scheduler`, `auth`).
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

## Amendment — 2026-05-06

During the Angular 21 PWA scaffold (commit c76586e), it was confirmed that `@ngrx/signals` aligns its major version with Angular's major version. `@ngrx/signals@18` declares `@angular/core: "^18"` as a peer dependency and is therefore incompatible with Angular 21. The installed version is `@ngrx/signals@21.1.0`. The version reference in the Decision section above has been updated from 18.x to 21.x accordingly. Going forward: NgRx Signals major version = Angular major version.
