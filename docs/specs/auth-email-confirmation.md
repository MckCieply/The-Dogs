# AUTH-09 — Email confirmation (admin-token flow, no SMTP)

- **Status:** Implemented
- **Depends on:** AUTH-04 (registration), AUTH-05 (password reset — ADR-0013 pattern)
- **Related:** [ADR-0013 — Password reset without SMTP](../adr/0013-password-reset-without-smtp.md)

## Summary

Self-registered accounts must confirm their email address before they can sign in.
There is still no SMTP (ADR-0013), so the verification token is delivered out-of-band:
it is retrievable by an admin (`GET /api/v1/admin/email-verification-tokens?email=...`,
ROLE_ADMIN, rotate-on-fetch) and logged at INFO in dev when
`app.email-verification.log-token-on-create` is true (false by default and in prod).

## Behaviour

- **Registration** (`POST /api/v1/auth/register`) creates the user with
  `email_verified = false` and mints a 24 h single-use verification token in the same
  transaction. The response contract is unchanged: the new user still receives the
  access-token + refresh-cookie pair (a *grace session*) so onboarding is not blocked.
- **Login** (`POST /api/v1/auth/login`) returns `401 email_not_verified` for correct
  credentials on an unverified account. The check runs strictly AFTER the password
  check so verification status cannot be probed without the password. Refresh of an
  existing grace session keeps working.
- **Confirm** (`POST /api/v1/auth/confirm-email {token}`) consumes the token
  (single-use, atomic `markUsedIfActive`) and sets `email_verified = true`. Unknown,
  used, and expired tokens are indistinguishable (`400 invalid_verification_token`).
  Rate limit: 10/IP/hour.
- **Resend** (`POST /api/v1/auth/resend-confirmation {email}`) rotates the token when
  the email belongs to an unverified account; always `204` with an 80 ms constant-time
  floor (anti-enumeration, same posture as forgot-password). Rate limit: 3/email/hour.
- **Admin fetch** rotates and returns a fresh token; `404` when the email is unknown,
  already verified, or has no active token. Tokens are stored as SHA-256 hashes only.
- **Grandfathering:** accounts existing before V6 are marked verified by the migration
  (`DEFAULT TRUE`); only new self-registrations start unverified.

## Data model

`V6__email_verification.sql`: `users.email_verified BOOLEAN NOT NULL DEFAULT TRUE` +
`email_verification_token` table (same shape as `password_reset_token`).

## UI

- `/confirm-email` page: token entry (pre-filled from `?token=`), resend form
  (pre-filled from `?email=`), success state linking to `/login`.
- Registration redirects to `/confirm-email?registered=1&email=...` so the user learns
  immediately that the next sign-in needs a confirmed email.
- Login shows a warning + link to `/confirm-email` on `email_not_verified`.

## Migration path to real email

Identical to ADR-0013: when SMTP lands, deliver the token by email, drop (or
dev-gate) the admin endpoint and the dev log. Token plumbing and endpoints stay.

## i18n

Strings are hardcoded English like the other auth pages; migrate to translation keys
when I18N-01 lands.
