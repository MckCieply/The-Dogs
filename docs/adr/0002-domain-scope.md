# ADR-0002 — Domain Scope

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Owner

## Context

We need a concrete domain to drive schema, UI, and the first feature specs.

## Decision

The-Dogs is **enterprise SaaS for dog trainers**. The MVP delivers three modules:

1. **Dogs** — list + form CRUD. Each dog belongs to a client and has metadata (breed, birthdate, sex, free-form metadata).
2. **Notes** — list + form CRUD, scoped per dog. A note is a timestamped text entry by a trainer.
3. **Scheduler** — calendar UI for booking client meetings. Supports per-trainer views, statuses (scheduled / completed / cancelled), and links to the relevant dog/client.

Cross-cutting:
- Multi-trainer tenancy via RBAC: a trainer sees only their own clients/dogs/notes/appointments. Admins see all.
- All modules are part of the PWA shell with offline-readable list views.

## Consequences

- Initial entities: `User` (trainer), `Client`, `Dog`, `Note`, `Appointment`. Initial migrations under `backend/src/main/resources/db/migration/V1__init.sql`.
- Frontend feature folders: `frontend/src/app/features/{dogs,notes,scheduler}`.
- The Scheduler module drives the choice of UI library (calendar widget needed) — see ADR-0005.

## Out of scope (for MVP)

- Billing / subscriptions
- Multi-tenant org accounts beyond per-trainer ownership
- Email / SMS reminders (planned post-MVP)
- Public client-facing portal
