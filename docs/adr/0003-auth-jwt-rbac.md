# ADR-0003 — Auth: JWT + Spring Security + RBAC

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Owner

## Context

We need authentication and authorization for trainers (multi-user, role-separated) without taking on an external IdP yet.

## Decision

- **Self-issued JWT** access tokens, signed with HS256 or RS256 (secret/key in env).
- **Spring Security 6** with `oauth2-resource-server` configured for JWT validation.
- **Refresh tokens** delivered as **HttpOnly Secure SameSite=Strict cookie** with a `/api/v1/auth/refresh` endpoint. Access tokens kept in memory on the frontend (never localStorage).
- **Token lifetimes:** access 15 min, refresh 7 days, with refresh rotation on each use.
- **Password storage:** BCrypt with cost ≥ 12.
- **RBAC:** roles `ROLE_TRAINER` and `ROLE_ADMIN` to start. Method-level `@PreAuthorize` on services (not just controllers) so authorization is enforced even when methods are reused.
- **Tenancy:** every query filters by `trainer_id` for `ROLE_TRAINER`. Enforced via a Hibernate filter or a base-repository pattern (decided during scaffold).

## Consequences

- No external IdP dependency for MVP.
- Future migration to OIDC (Auth0/Keycloak/Cognito) is straightforward because we already use the resource-server model.
- Frontend `core/auth` module owns token lifecycle: HTTP interceptor, route guards, silent refresh.
- Security agent verifies on every PR: every endpoint has explicit auth annotation; tenancy filter is applied; no token in localStorage; no logging of tokens.

## Alternatives considered

- **Sessions / cookies all the way** — simpler for a single web client, but harder if we add a mobile app later.
- **External OIDC from day one** — eliminates password handling but adds a hard external dependency for local dev.
