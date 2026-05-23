---
slug: auth-register
status: ready
priority: normal
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0003  # auth: JWT + Spring Security + RBAC
  - ADR-0012  # automated pipeline
---

# AUTH-04 — Registration endpoint + password strength

## Summary

Adds `POST /api/v1/auth/register` for self-service account creation. Validates email format and uniqueness, enforces password strength server-side (zxcvbn score ≥ 3), bcrypt-hashes the password, assigns roles via a first-user-bootstrap rule (the very first registered user gets `ROLE_ADMIN`; every subsequent user gets `ROLE_TRAINER`), and auto-issues a token pair so the new user is logged in immediately. Anti-enumeration is intentionally **not** applied here — the user just told us the email; the only confidentiality risk is the duplicate-email path, where a generic 409 is acceptable.

## Motivation

- The pipeline cannot demo anything without a way to create users. Manual DB inserts via admin SQL are operationally fragile.
- Password strength has to be enforced on the server — frontend checks can be bypassed by anyone with curl. The same validator will be reused by `AUTH-05` (reset password) and `AUTH-07` (frontend live meter).
- The first-user-becomes-admin pattern bootstraps the admin role without needing a separate seed migration or `/init-admin` endpoint. Documented and unsurprising; revisited if the project ever ships a public sign-up version.

## Scope

### In scope
- `POST /api/v1/auth/register` taking `{ email, password, display_name }`.
- Email format validation (RFC 5322 via Jakarta `@Email` is sufficient; reject `+` aliasing? — keep allowed).
- Email uniqueness check (case-insensitive via `citext`).
- Server-side password strength check using zxcvbn-java. Minimum score 3 (0–4 scale).
- Additional hard requirements: length ≥ 10, ≤ 128 characters. No specific character-class rules (NIST 800-63B aligns with this; zxcvbn covers entropy properly).
- First-user-bootstrap: if `app_user` count = 0 before insert, assign `ROLE_ADMIN`; otherwise `ROLE_TRAINER`.
- Rate limit: 3 registrations per hour per IP (anti-spam).
- Auto-issue access JWT + refresh cookie on success (same pair shape as `/auth/login`).
- Integration tests: happy path, duplicate email, weak password, too short, too long, bad email format, rate limit, first-user gets admin, second-user gets trainer.

### Out of scope
- Email verification — no SMTP yet ([ADR-0013](../adr/0013-password-reset-without-smtp.md)). Field `email_verified` is **not** added to the schema; will land alongside `email-smtp-integration`.
- Invitation-only registration — current MVP is open registration; toggling to invitation-only is a future config flag.
- CAPTCHA — not added unless real spam appears.
- Profile fields beyond `display_name` (phone, avatar) — `profile-page` spec.
- Terms-of-service / privacy acceptance tracking — added when the legal docs exist.

## Acceptance criteria

- AC-1: `POST /auth/register` with valid `email`, `password` (score ≥ 3, length 10–128), `display_name` returns `201` with body `{ access_token, token_type, expires_in }` + `Set-Cookie: refresh_token=...`. New row in `app_user` with bcrypt-hashed password and exactly one role in `user_role`.
- AC-2: Duplicate email returns `409` with `errors[].code = "email_taken"`. No new row inserted.
- AC-3: Weak password (zxcvbn score < 3) returns `400` with `errors[].code = "password_too_weak"` and a `password_score: <int>` extension field on the error object so the frontend can show "score 1 of 4" without re-running zxcvbn.
- AC-4: Password length < 10 returns `400` `errors[].code = "field_too_short"` (field `password`). Password > 128 returns `field_too_long`. These checks run before zxcvbn.
- AC-5: Malformed email returns `400` `errors[].code = "field_invalid_format"` (field `email`).
- AC-6: Missing `display_name` or empty string returns `400` `errors[].code = "field_required"`. `display_name` length 1–80.
- AC-7: 4th registration from the same IP within an hour returns `429` `errors[].code = "too_many_attempts"` + `Retry-After`.
- AC-8: When `app_user` table is empty before the call, the new user has `ROLE_ADMIN` and only that role. When non-empty, the new user has `ROLE_TRAINER` and only that role.
- AC-9: Bcrypt cost is 12 (asserted by re-encoding a known password and comparing prefix `$2a$12$`).
- AC-10: No password (cleartext) and no JWT appears in any log line during integration tests.
- AC-11: `mvn verify` exits 0; OpenAPI snapshot regenerated. New error codes (`email_taken`, `password_too_weak`) added to `docs/api/error-codes.md`. `password_score` documented as an optional integer extension field on `errors[]`.

## Investigation notes (for the implementer)

- zxcvbn-java is available as `com.nulab-inc:zxcvbn`. The library is pure Java, no native deps, ~1 MB dictionary. Cache the `Zxcvbn` instance — its construction is non-trivial.
- Pass `display_name` and `email` as additional user-input dictionary entries to `zxcvbn.measure(password, List.of(email, displayName))` so users cannot use their own email/name as a password and still score ≥ 3.
- The first-user bootstrap must be race-safe. Wrap in a `@Transactional` block, count, insert, all in one SERIALIZABLE transaction — or use a DB-side `INSERT ... WHERE NOT EXISTS` pattern. With near-zero concurrency at first-user time this is over-engineering, but the integration test should still assert correctness under a small parallel race (2 simultaneous register calls when the table is empty → exactly one admin).
- The auto-login on success reuses `JwtService` and the same refresh-token mint logic as `AUTH-02` / `AUTH-03`. Refactor `AUTH-02` to expose a `TokenIssuer` component if needed — implementer should land that refactor alongside this feature.

## Data model

No schema changes beyond what `AUTH-01` already provides. Reuses `app_user`, `role`, `user_role`, `refresh_token`.

## API surface

| Method | Path                       | Auth        | Body                                                  | Response |
| ------ | -------------------------- | ----------- | ----------------------------------------------------- | -------- |
| POST   | `/api/v1/auth/register`    | `permitAll` | `{ "email": "...", "password": "...", "display_name": "..." }` | `201` + token + `Set-Cookie`, or `400`/`409`/`429`. |

## UI surface

None — `AUTH-07` is the matching frontend spec.

## Security & RBAC

- `permitAll`.
- Bcrypt cost 12.
- Password strength enforced server-side regardless of frontend. The frontend meter (`AUTH-07`) is UX, not security.
- Email stored in `citext` — case-insensitive comparisons throughout.
- No email-verification step before the user is granted `ROLE_TRAINER`; this is acceptable while there is no SMTP, but **explicitly documented** here so a reviewer of the email-smtp feature later knows to add verification gating.
- First-user-bootstrap is observable: anyone watching the deployment can register first and become admin. Operational mitigation: the owner registers immediately after first deployment. The risk is documented; an alternative `INITIAL_ADMIN_EMAIL` env-based bootstrap is mentioned in Open questions.
- Rate-limit storage: same in-process mechanism as `AUTH-02`. Same multi-instance caveat.

## Non-functional

- p95 latency ≤ 250 ms (bcrypt cost 12 + zxcvbn scoring ~50 ms + DB write).
- zxcvbn dictionary loading at startup ≤ 1 s (does not block readiness probe).
- Testcontainers integration suite under 30 s.

## Open questions

- **Initial admin bootstrap mechanism.** First-user-becomes-admin is pragmatic but exposes a small race window. Alternative: `INITIAL_ADMIN_EMAIL` env var; the first user matching that email gets admin, no one else does without explicit grant. Decision for v1: **first-user-becomes-admin** — env-var path defers to when an admin-grant endpoint exists. Document the trade-off in the ADR-0003 follow-up if it ever becomes a real concern.
- **Open registration vs. invitation-only.** Open for MVP. When/if invitation-only is needed, it becomes a config flag `app.registration.mode = open | invite-only` plus an `invitation_token` table — separate future spec.
- **`display_name` PII concerns.** Logged on registration (success) at INFO level along with `user_id`. This is acceptable in a B2B context but should be documented in any future privacy policy.

## References

- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [ADR-0013 — Password reset without SMTP](../adr/0013-password-reset-without-smtp.md) (related; explains why no email verification yet)
- [Spec AUTH-01 — Auth core](auth-core-jwt.md)
- [Spec AUTH-02 — Login endpoint](auth-login.md)
- [Spec AUTH-03 — Refresh + logout](auth-refresh-logout.md)
- NIST SP 800-63B — Memorised Secret guidance.
