# ADR-0013 — Password Reset Without SMTP (Admin-Token Flow)

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** Owner

## Context

The MVP needs a forgotten-password flow but has **no email infrastructure**:

- Hosting is a self-hosted laptop behind Cloudflare Tunnel ([ADR-0011](0011-hosting-strategy.md)); there is no SMTP relay configured.
- Adding transactional email (SES, Postmark, Mailgun) introduces a paid third-party dependency, a billing relationship, and a new secret in the env — all premature at zero real users.
- Self-hosting Postfix on the same laptop is operationally heavy (SPF/DKIM/DMARC, IP reputation, abuse handling) and degrades deliverability vs. a hosted relay.

Skipping password reset entirely is not an option — users forget passwords and the only alternative is contacting the admin to reset by hand.

## Decision

**Implement a token-based password reset flow that does not send email.** The flow is:

1. User submits email to `POST /api/v1/auth/forgot-password`. The endpoint **always** returns `204 No Content` regardless of whether the email exists (anti-enumeration).
2. If the email exists, the backend creates a row in `password_reset_token (token, user_id, expires_at, used_at)`. Token is a 256-bit random value, hex-encoded, expires in 1 hour, single-use.
3. The token is **not** delivered by email. Instead it is retrievable by an admin via `GET /api/v1/admin/password-reset-tokens?email=<email>` (requires `ROLE_ADMIN`). In dev, the token is also logged at `INFO` level on creation for operator convenience — never in prod (config gate).
4. Admin communicates the token to the user out-of-band (chat, phone, in person).
5. User submits token + new password to `POST /api/v1/auth/reset-password`. On success the token is marked used and the password is updated.

## Migration path

When email infrastructure lands (separate future feature, likely `email-smtp-integration`):

1. Add an SMTP client (Spring `JavaMailSender` with hosted relay credentials in env).
2. Change `forgot-password` to send the token via email and **remove** the admin retrieval endpoint (or restrict to dev profile).
3. The token table, token generation, and `reset-password` endpoint stay unchanged — only delivery channel swaps.

No data migration is needed and no API contract for password reset itself changes.

## Consequences

**Positive**
- Zero third-party dependency for MVP.
- Anti-enumeration property is preserved (response shape is identical regardless of email existence).
- Same token plumbing will be reused unchanged when email is added.
- No SMTP-related secrets to manage during early dev.

**Negative / risks**
- Manual admin step is a UX gap — acceptable at zero users, painful at scale.
- The admin endpoint is itself an attack surface; mitigated by `ROLE_ADMIN` only, RBAC at service layer, and audit logging on every retrieval. **Reviewer must flag any path that exposes this endpoint to non-admins.**
- Dev-mode `INFO`-level token logging must be gated by Spring profile — a leaked prod log with reset tokens would compromise every user with an outstanding token. Gate is `app.password-reset.log-token-on-create: false` in `application-prod.yml`, `true` in `application-dev.yml`. CI test asserts the prod default.

## Alternatives considered

- **Skip reset entirely** — operator-only password change via DB. Rejected: makes onboarding fragile, every forgotten password becomes a hand-edited bcrypt insertion.
- **Hosted SMTP from day one (Postmark/SES free tier)** — adds a third-party dependency before there is any user. Deferred until first real users.
- **Magic-link login (passwordless)** — better UX but same email problem; same migration path applies. Tracked as future direction, not MVP.

## References

- [ADR-0003 — Auth: JWT + Spring Security + RBAC](0003-auth-jwt-rbac.md)
- [ADR-0011 — Hosting strategy: self-hosted laptop + Cloudflare Tunnel](0011-hosting-strategy.md)
- Spec: `docs/specs/auth-password-reset.md` (AUTH-05)

## Open

- Hosted SMTP provider choice (Postmark vs. SES vs. Mailgun) — decide when email becomes load-bearing.
- Whether to also send a confirmation email on successful password change once SMTP lands.
