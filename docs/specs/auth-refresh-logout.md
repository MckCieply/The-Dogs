---
slug: auth-refresh-logout
status: ready
priority: normal
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0003  # auth: JWT + Spring Security + RBAC
  - ADR-0012  # automated pipeline
---

# AUTH-03 — Refresh tokens + logout (rotation + theft detection)

## Summary

Adds the persistent refresh-token store and the two endpoints that exercise it: `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`. Implements rotation on every refresh (each use invalidates the previous token and issues a new pair) and reuse-detection (if a revoked refresh is presented, the entire family is revoked — the textbook defence against stolen-cookie replay). Replaces the placeholder cookie from `AUTH-02` with a real token bound to a database row.

## Motivation

- 15-minute access tokens are unusable without a refresh path — users would re-login every quarter hour.
- Refresh rotation is the OWASP-recommended baseline. Reuse detection turns refresh-token theft from "attacker has 7 days of access" into "next legitimate refresh exposes the breach".
- A persistent revocation list is needed to make logout actually invalidate sessions (stateless JWTs can't be revoked, but refresh tokens can — and once the refresh is gone, the 15-minute access expiry bounds the exposure).

## Scope

### In scope
- Flyway migration creating `refresh_token` table.
- `POST /api/v1/auth/refresh`: reads `refresh_token` cookie, validates, issues new access JWT + new refresh token, revokes the old one, sets a new `Set-Cookie`.
- `POST /api/v1/auth/logout`: reads the cookie, revokes the token and its whole family, clears the cookie (`Set-Cookie: refresh_token=; Max-Age=0`).
- Rotation: every refresh issues a new token, marks the old as `used_at=now()`, links the new one via `parent_id`.
- Theft detection: if a token with `used_at IS NOT NULL` is presented, revoke every token in the family (same `family_id`) and return `401` `errors[].code = "refresh_reused"`. Force a re-login.
- Replace `AUTH-02`'s placeholder cookie value with a real DB-backed token.
- Integration tests: happy refresh, rotation, logout, reuse detection, expired refresh, unknown refresh, refresh after logout.

### Out of scope
- Per-device refresh management UI (`/me/sessions`) — separate later spec.
- Sliding-window refresh expiry (refresh fixed at 7 days from issue; further extension requires re-login).
- IP/UA binding (security/UX trade-off; defer until first incident).
- Cleanup job for expired/revoked tokens — implementer adds a `@Scheduled` task with TODO inline; the actual sweep policy is decided when table grows.

## Acceptance criteria

- AC-1: `POST /auth/refresh` with a valid, unused, unexpired refresh cookie returns `200` + new access JWT + new `Set-Cookie` (different value from the previous one).
- AC-2: After AC-1, the previous refresh token has `used_at` set in the database; presenting it again triggers theft detection.
- AC-3: Reusing a `used_at IS NOT NULL` refresh token returns `401` `errors[].code = "refresh_reused"`. Every token in the same `family_id` is marked `revoked_at = now()`. The cookie is cleared in the response.
- AC-4: `POST /auth/logout` with a valid refresh cookie returns `204`, sets `Set-Cookie: refresh_token=; Max-Age=0; HttpOnly; Secure; SameSite=Strict`, and revokes the entire family in the DB.
- AC-5: `POST /auth/refresh` after `/auth/logout` returns `401` `errors[].code = "refresh_revoked"`.
- AC-6: Refresh token past its 7-day `expires_at` returns `401` `errors[].code = "refresh_expired"`.
- AC-7: Unknown refresh token (well-formed cookie but no DB row) returns `401` `errors[].code = "invalid_refresh"`. Timing-safe lookup so existence cannot be inferred from response time.
- AC-8: `/auth/login` from `AUTH-02` now creates a real `refresh_token` row (replacing the placeholder); subsequent `/auth/refresh` works end-to-end.
- AC-9: Refresh tokens are stored as a SHA-256 hash, not in plaintext — DB compromise does not expose live tokens.
- AC-10: No refresh token value, no cookie value, no SHA-256 input appears in any log line during integration tests.
- AC-11: `mvn verify` exits 0; OpenAPI snapshot regenerated. New error codes (`refresh_reused`, `refresh_revoked`, `refresh_expired`, `invalid_refresh`) added to `docs/api/error-codes.md`.

## Investigation notes (for the implementer)

- Refresh token value (sent to client in the cookie) is 256 random bits, base64url-encoded. The DB stores the SHA-256 hash. Lookup: hash the incoming value, then `SELECT` by hash. Hash collisions on 256-bit input are not a concern.
- `family_id` is a UUID v7 created on first login. Every rotation inherits the parent's `family_id`. Logout / theft detection revokes by `family_id`.
- Use `Optional<RefreshToken> findActiveByHash(String hash)` returning rows where `revoked_at IS NULL AND expires_at > now() AND used_at IS NULL`. A separate `findAnyByHash` is used for theft detection (which needs to see `used_at IS NOT NULL` rows).
- Wrap the rotation in a single `@Transactional` method. Concurrent refreshes of the same token must produce one new token and one theft-detection failure, not two new tokens. Use a `SELECT ... FOR UPDATE` on the old row to serialize.

## Data model

### `refresh_token` table

```sql
CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY,
    family_id   UUID NOT NULL,
    parent_id   UUID REFERENCES refresh_token(id) ON DELETE SET NULL,
    user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,   -- hex SHA-256; VARCHAR preferred over CHAR for portability
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    user_agent  TEXT,
    ip_bucket   TEXT  -- /24 for v4, /64 for v6; nullable
);

CREATE INDEX idx_refresh_token_hash ON refresh_token (token_hash);
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_user_active
    ON refresh_token (user_id) WHERE revoked_at IS NULL;
```

### Entity (Lombok rules per [ADR-0001](../adr/0001-stack-and-build-tools.md))

`RefreshToken` follows the same Lombok pattern as `User`: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder @ToString(onlyExplicitlyIncluded = true)`. Include only `id` and `userId` in `toString` — never `tokenHash`.

## API surface

| Method | Path                    | Auth                  | Notes |
| ------ | ----------------------- | --------------------- | ----- |
| POST   | `/api/v1/auth/refresh`  | `permitAll`           | Reads `refresh_token` cookie; returns `{ access_token, token_type, expires_in }` + new `Set-Cookie`. |
| POST   | `/api/v1/auth/logout`   | `permitAll`           | Reads cookie, revokes family, clears cookie. Idempotent — re-calling after revoke is `204` not `401`. |

`POST /auth/login` from `AUTH-02` now also writes a `refresh_token` row.

## UI surface

None.

## Security & RBAC

- Refresh token hashed at rest (SHA-256, no salt — the token itself is 256 random bits so it has full entropy already).
- Rotation + reuse detection per OWASP Cheat Sheet "JSON Web Token for Java".
- Cookie attributes: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800`. The `Path` constraint means the cookie is never sent to other API endpoints — minimises CSRF and accidental logging.
- `SameSite=Strict` plus `Path=/api/v1/auth` means the user must initiate a navigation to the same origin for the cookie to apply — acceptable trade-off for a PWA where the frontend is same-origin.
- Logout is idempotent and safe to call without auth state (returns `204` regardless).
- `user_agent` and `ip_bucket` are stored on issue for future forensics; not used for binding (no enforcement, just metadata).
- Reviewer must verify: no controller method logs the raw refresh value, the SHA-256 hash, or the cookie header. Asserted by AC-10.

## Non-functional

- p95 latency for `/auth/refresh` ≤ 50 ms (single DB roundtrip + JWT signing).
- Refresh rotation under concurrent calls (two browser tabs simultaneously refreshing) must produce exactly one success and one `refresh_reused` failure — race tested in the integration suite.
- Refresh table growth: with rotation, every active session generates ~96 rows / day (15-min access × 96 refreshes). A daily cleanup of rows with `revoked_at < now() - INTERVAL '30 days'` is acceptable scope for a follow-up task (TODO inline).

## Open questions

- **Concurrent refresh handling.** Two tabs racing the same refresh: one succeeds (rotates), the other sees the now-used token and triggers theft detection. That logs out the user unfairly. Decision for v1: accept the false positive — the user re-logs in. The "second tab fetches the new token from a local broadcast channel" UX fix is `AUTH-06` frontend territory.
- **Cleanup cadence.** Sweeping revoked/expired tokens is a `@Scheduled` task; daily at 03:00 is a reasonable default. Implementer adds the scaffold + a `TODO(ops)` so the schedule can be tuned without a code change.

## References

- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [Spec AUTH-01 — Auth core](auth-core-jwt.md)
- [Spec AUTH-02 — Login endpoint](auth-login.md)
- OWASP Cheat Sheet — JWT for Java, Refresh-token rotation.
