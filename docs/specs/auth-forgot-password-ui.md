---
slug: auth-forgot-password-ui
status: ready
priority: normal
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0003  # auth: JWT + Spring Security + RBAC
  - ADR-0013  # password reset without SMTP
  - ADR-0006  # PWA
  - ADR-0012  # automated pipeline
---

# AUTH-08 — Forgot-password + reset-password UI

## Summary

Adds the user-facing reset flow that complements the backend in `AUTH-05`: a `/forgot-password` page that submits an email and **always** shows a neutral "if the email exists, your administrator was notified" confirmation (anti-enumeration), and a `/reset-password?token=...` page that accepts the admin-delivered token plus a new password (live strength meter from `AUTH-07`), then auto-logs-in on success. Both pages use the same i18n bundles and the same auth-form shell from `AUTH-06`.

## Motivation

- Closes the loop on the no-SMTP password recovery path ([ADR-0013](../adr/0013-password-reset-without-smtp.md)). Without these screens the backend endpoints are unreachable from the product.
- Reusing the strength meter from `AUTH-07` and the auto-login pattern from `AUTH-06` makes this the smallest of the auth UI specs — most code already exists.
- Anti-enumeration UX needs care: the confirmation copy must give the user a path forward (talk to your admin) without revealing whether the email is registered.

## Scope

### In scope
- `/forgot-password` route: single email field + submit. Always shows the neutral confirmation on response, regardless of `204` outcome shape. Submit is rate-limited client-side per `Retry-After` if the backend returns `429`.
- `/reset-password?token=<token>` route: reads the token from the query string, shows fields for `new_password` and `new_password_confirm`, reuses the strength meter from `AUTH-07`. On success, accepts the token pair (server logs the user in on reset by issuing a fresh access JWT + refresh cookie — see API surface), stores via `AuthStore.acceptToken()`, navigates to `/`.
- `/reset-password` without a `token` query param: shows a translated "Token wymagany — sprawdź wiadomość od administratora" message with a back-to-login link. Does not 404 — user-friendlier to land on the right page.
- Translated error rendering for: `invalid_reset_token` (expired / used / unknown — single message per `AUTH-05` AC-8/9), `password_too_weak` with backend score, `field_*` validation, `too_many_attempts` countdown.
- "Back to login" link on both pages.
- Vitest unit tests: submit gating, anti-enumeration neutral confirmation (asserted by mocking both 204 outcomes), token reading from URL.
- Playwright e2e: forgot-password happy path shows confirmation, reset-password with valid token logs the user in, reset-password with invalid token shows the translated error, axe-core zero serious/critical.

### Out of scope
- Email-delivery UX (no SMTP). The confirmation copy explicitly directs users to contact their administrator for the token.
- Display-name change / profile-edit forms — `profile-page` spec.
- "Change password while logged in" (different endpoint, different UX) — separate spec.
- Token-from-link UX for the eventual SMTP era. When email lands, the `/reset-password?token=...` route is already correctly shaped; only the entry path (clicking an email link instead of typing) changes.

## Acceptance criteria

- AC-1: `/forgot-password` renders with an email field, submit button, "back to login" link, and `LanguageSwitcher`.
- AC-2: Submitting any well-formed email always shows the same neutral confirmation: "Jeśli ten adres jest zarejestrowany, twój administrator otrzyma token resetu. Skontaktuj się z administratorem." Submit button disables for the response cycle, then re-enables with a 30 s soft-cooldown to discourage spam.
- AC-3: A malformed email returns to inline `field_invalid_format` rendering; submit is not sent.
- AC-4: On backend `429 too_many_attempts`, the rate-limit countdown UX from `AUTH-06` is reused.
- AC-5: `/reset-password?token=<token>` renders with `new_password`, `new_password_confirm`, submit, and the password strength meter from `AUTH-07`.
- AC-6: `/reset-password` (no token in URL) renders a translated "missing token" message + back-to-login link; the form is not shown.
- AC-7: Submitting a valid token + strong new password calls `POST /api/v1/auth/reset-password`. On success the response body carries `{ access_token, expires_in }` and sets the refresh cookie; the frontend feeds it into `AuthStore.acceptToken()` (same path as register/login) and navigates to `/` with a translated "Hasło zmienione" toast.
- AC-8: On `400 invalid_reset_token`, the translated error renders inline at the top of the card with a "Poproś o nowy token u administratora" hint and a `/forgot-password` link.
- AC-9: On `400 password_too_weak`, the strength meter re-asserts and an inline error renders under the password field (mirrors `AUTH-07` AC-8).
- AC-10: Weak password blocks submit client-side at score < 3 (same threshold as `AUTH-07`).
- AC-11: `password_confirm` mismatch blocks submit and shows an inline error.
- AC-12: All visible strings have keys in both `pl.json` and `en.json`. Key-symmetry test from `I18N-01` passes.
- AC-13: axe-core zero serious/critical violations on both routes. Lighthouse PWA ≥ 90 and Performance ≥ 80.
- AC-14: `npm run lint && npm test -- --run && npm run build` all exit 0.

## Investigation notes (for the implementer)

- **API contract assumption for AUTH-05.** The current `AUTH-05` spec returns `204` on successful reset and does **not** auto-issue a token pair. AC-7 above assumes auto-login is included. **Reconcile in the implementer round for AUTH-05** — update `AUTH-05` AC-5 to return `200 { access_token, expires_in } + Set-Cookie refresh_token` (consistent with register/login). The implementer of `AUTH-08` should NOT change `AUTH-05` mid-flight; if `AUTH-05` lands first with `204`, this spec's AC-7 becomes "navigate to `/login` with a translated success toast" and a follow-up issue is filed to add auto-login. Document the chosen path in the PR body.
- Token reading: use `ActivatedRoute.snapshot.queryParamMap.get('token')` once; do not subscribe to query-param changes (no use case for re-reading mid-flow).
- Strength meter component is consumed unchanged from `AUTH-07`. Do not duplicate it.
- The neutral confirmation copy is the critical anti-enumeration surface — reviewer must verify the wording does not differ for any backend response (the frontend should not even branch on the response body for that page; just submit, await, render).

## Data model

None.

## API surface

Consumes:
- `POST /api/v1/auth/forgot-password` from `AUTH-05`.
- `POST /api/v1/auth/reset-password` from `AUTH-05` (with the AC-7 reconciliation above).

No new endpoints.

## UI surface

### Routes

```
/forgot-password                    → ForgotPasswordPage (lazy, standalone)
/reset-password                     → ResetPasswordPage  (lazy, standalone)
```

### Components (`frontend/src/app/components/auth/`)

- `forgot-password-page.component.ts`.
- `reset-password-page.component.ts`.

Both reuse the shell layout pattern and the password strength meter component from `AUTH-07`.

### i18n keys added to `auth.forgot.*` and `auth.reset.*`

`forgot.title`, `forgot.email_label`, `forgot.submit`, `forgot.confirmation`, `forgot.back_to_login`, `forgot.rate_limited` (with `{{ countdown }}`).
`reset.title`, `reset.missing_token`, `reset.new_password_label`, `reset.new_password_confirm_label`, `reset.submit`, `reset.success_toast`, `reset.invalid_token`, `reset.invalid_token_hint`, `reset.request_new_token_link`, `reset.back_to_login`, `reset.password_mismatch`, `reset.password_too_weak`.

PL + EN bundles updated in the same PR; key-symmetry test enforces parity.

## Security & RBAC

- Anti-enumeration via static confirmation copy regardless of backend response shape. Reviewer enforces by reading the template — there must be no `*ngIf` branch on the response.
- Token from URL is treated as opaque; never logged, never stored beyond the form submission scope.
- New password handled per `AUTH-07` rules (no storage, no logging).
- On `invalid_reset_token`, the message must not distinguish expired vs used vs unknown — same English/Polish copy.

## Non-functional

- Both routes' entry chunks ≤ 30 KB minified+gzipped (zxcvbn-ts lazy-loaded same as `AUTH-07`).
- Page-load p95 ≤ 1.2 s on 4G simulated.

## Open questions

- **Reconcile AUTH-05 auto-login behaviour.** See investigation note — AC-7 assumes the reset endpoint also issues a token pair. If `AUTH-05` ships with `204`-only, file a follow-up to add auto-login (small backend change), and adjust this spec's AC-7 to navigate to `/login` instead.
- **Anti-enumeration during the SMTP era.** Once email is added, the confirmation copy becomes "If this email is registered, you'll receive a reset link." Same shape, new wording. Tracked as a one-line copy change in the future spec.

## References

- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [ADR-0006 — PWA from day one](../adr/0006-pwa.md)
- [ADR-0013 — Password reset without SMTP](../adr/0013-password-reset-without-smtp.md)
- [Spec AUTH-05 — Password reset](auth-password-reset.md)
- [Spec AUTH-06 — Login UI](auth-login-ui.md)
- [Spec AUTH-07 — Registration UI + strength meter](auth-register-ui.md)
- [Spec I18N-01 — Frontend i18n](i18n-frontend.md)
