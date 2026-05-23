---
slug: auth-login
status: ready
priority: normal
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0003  # auth: JWT + Spring Security + RBAC
  - ADR-0012  # automated pipeline
---

# AUTH-02 — Login endpoint (`POST /auth/login`)

## Summary

Adds the password-grant login endpoint. Verifies email + password against the `app_user` table, issues a short-lived access JWT (15 min), and sets a refresh-token HttpOnly cookie (refresh token plumbing itself lands in `AUTH-03`; this spec writes the cookie shape and reserves the field). Includes IP-based rate limiting to defend brute-force attacks, anti-enumeration on bad-credentials response, and detailed error codes for the frontend.

## Motivation

- Every UI-facing feature needs a way to obtain a token. Without `/auth/login`, the frontend cannot exercise any protected endpoint.
- Brute-force protection has to exist from day one — once the app is on the public internet (Cloudflare Tunnel) any endpoint is reachable.
- Anti-enumeration (same response for "wrong email" and "wrong password") is the cheapest meaningful privacy protection; building it in now is much easier than retrofitting.

## Scope

### In scope
- `POST /api/v1/auth/login` taking `{ email, password }` JSON.
- Bcrypt verify against `app_user.password_hash`.
- Issue access JWT via `JwtService` from `AUTH-01`.
- Set `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800` on success. The cookie value is a placeholder opaque string in `AUTH-02` (random 256-bit) — full refresh table + rotation lands in `AUTH-03`. Implementer must add a clear `// TODO(AUTH-03)` next to the placeholder.
- IP-based rate limit: 5 failed attempts per 15 minutes per IP, sliding window. In-memory store (Bucket4j or a simple `ConcurrentHashMap<String, Bucket>`); persisted store is `AUTH-03`+ scope.
- Audit log entry for every login attempt (success and failure), without password, without IP-only PII concerns documented inline. Failure log records `email_hash` (SHA-256) only, never the raw email.
- Integration test coverage: happy path, bad password, unknown email, disabled user, rate-limit hit, malformed body.

### Out of scope
- Refresh-token rotation, theft detection, persisted refresh store — `AUTH-03`.
- Account lockout beyond rate-limit (e.g., 10 failures → 30-minute lock) — deferred.
- MFA, WebAuthn — deferred.
- `Remember me` toggle affecting cookie lifetime — `AUTH-06` decides (frontend concern; backend cookie lifetime stays at 7 days).
- CAPTCHA — not added unless real attacks emerge.

## Acceptance criteria

- AC-1: `POST /auth/login` with valid email + password returns `200`, body `{ "access_token": "<jwt>", "token_type": "Bearer", "expires_in": 900 }`, and `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict`.
- AC-2: Wrong password returns `401` with body `{ ..., "errors": [{ "code": "bad_credentials" }] }` — **identical** to the response for an unknown email (anti-enumeration).
- AC-3: Unknown email returns `401` with the exact same body shape as AC-2. A timing-safe bcrypt comparison runs against a dummy hash so wall-clock timing does not leak existence (Spring's `BCryptPasswordEncoder.matches` against a fixed dummy hash is acceptable).
- AC-4: Disabled user (`enabled = false`) returns `401` with `errors[].code = "account_disabled"` — distinct from `bad_credentials` is acceptable since admin-disabled is an operator state, not a guess. **Reviewer must confirm this trade-off remains explicit in the PR body.**
- AC-5: 6th failed attempt within 15 minutes from the same IP returns `429` with `errors[].code = "too_many_attempts"` and `Retry-After: <seconds>` header. Successful attempts do **not** reset the counter for the same IP within the window.
- AC-6: Malformed body (missing `email` or `password`, invalid email format) returns `400` with the standard `field_required` / `field_invalid_format` error codes from `AUTH-01`.
- AC-7: The access JWT has `exp - iat == 900` and `roles` claim matches the user's roles from `user_role`.
- AC-8: Integration tests run under Testcontainers Postgres and assert all of AC-1 to AC-7.
- AC-9: No password, no token, no `Authorization` value appears in any log line during the integration test run (asserted by parsing captured log output).
- AC-10: `mvn verify` exits 0; OpenAPI snapshot regenerated.
- AC-11: New error codes added to `docs/api/error-codes.md`: `bad_credentials`, `account_disabled`, `too_many_attempts`.

## Investigation notes (for the implementer)

- Use `org.springframework.security.crypto.password.PasswordEncoder.matches` for bcrypt verify — it is the standard timing-safe path.
- For the dummy bcrypt comparison on unknown email: pre-compute one hash at startup (`encoder.encode("dummy-password-for-timing")`) and store it as a `@Value`-injected constant. Compare against it whenever the user lookup fails.
- For rate limiting: Bucket4j with `RefillIntermittently` gives clean semantics. Map key is the IP, taken from `X-Forwarded-For` if present and trusted (Cloudflare Tunnel always sets it) otherwise `RemoteAddr`. Document the `X-Forwarded-For` trust assumption in the PR body.
- Rate-limit storage is purely in-process for `AUTH-02`. When the app scales beyond one node the same key will need a shared store (Redis or Postgres) — note this in an inline comment so it does not silently break in multi-instance prod.
- `Set-Cookie` must include `Path=/api/v1/auth` so the cookie is sent only on auth endpoints, not on every API request. This reduces CSRF surface and matches the rotation pattern in `AUTH-03`.

## Data model

No new tables. Reads from `app_user` and `user_role` from `AUTH-01`. The refresh token cookie value is a placeholder until `AUTH-03` adds `refresh_token` table.

## API surface

| Method | Path                  | Auth        | Body                                | Response |
| ------ | --------------------- | ----------- | ----------------------------------- | -------- |
| POST   | `/api/v1/auth/login`  | `permitAll` | `{ "email": "...", "password": "..." }` | `200` + token + `Set-Cookie`, or `401`/`429`/`400` per AC. |

The dummy public stub from `AUTH-01` (`/api/v1/auth/some-public-stub`) is **removed** by this spec — `permitAll` lane is now exercised by `/auth/login`.

## UI surface

None. `AUTH-06` is the matching frontend spec.

## Security & RBAC

- `permitAll` on this single endpoint.
- Bcrypt verify only; never plaintext compare.
- Anti-enumeration via identical error body for unknown email vs. wrong password vs. identical timing path.
- IP-based rate limit; `X-Forwarded-For` trust documented.
- No PII in logs: log `email_hash` (SHA-256 of lowercased email) and bucket of IP (`/24` for IPv4, `/64` for IPv6) instead of raw email + raw IP. Reviewer enforces.
- Audit table not added here — the structured log is the audit trail until an audit-log feature lands.
- The refresh cookie placeholder must already be `HttpOnly + Secure + SameSite=Strict + Path=/api/v1/auth`. `Secure` is forced even in dev; tests use HTTPS-equivalent assertions on the cookie attributes.

## Non-functional

- p95 latency ≤ 200 ms (bcrypt cost 12 dominates; that is the floor).
- Rate-limit lookups are O(1) in-process; no extra DB hit per attempt.
- Testcontainers integration suite stays under 30 s wall-clock.

## Open questions

- **Rate-limit per email vs per IP.** Per IP only for now (simpler, harder to weaponise against a known target — an attacker can't lock out a victim by spamming their email from many IPs). When real abuse appears, add per-email throttling on top.
- **Trusted proxy header parsing.** Cloudflare's `CF-Connecting-IP` is more reliable than `X-Forwarded-For` behind their tunnel. Decision: use `CF-Connecting-IP` when present, fall back to `X-Forwarded-For`'s leftmost entry, fall back to `RemoteAddr`. Document the trust chain in the PR body.

## References

- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [Spec AUTH-01 — Auth core](auth-core-jwt.md)
- [ARCHITECTURE.md §9 — Security baseline](../ARCHITECTURE.md)
