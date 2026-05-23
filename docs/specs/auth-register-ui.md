---
slug: auth-register-ui
status: ready
priority: normal
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0003  # auth: JWT + Spring Security + RBAC
  - ADR-0004  # frontend state
  - ADR-0005  # UI: PrimeNG + lucide
  - ADR-0006  # PWA
  - ADR-0012  # automated pipeline
---

# AUTH-07 — Registration UI + live password strength meter

## Summary

Adds the `/register` route with email, password, and display-name fields, a live client-side password strength meter (zxcvbn in the browser) that mirrors the backend gate from `AUTH-04`, and a success path that auto-logs-in the new user by reusing the same token-handling plumbing as `AUTH-06`. All copy goes through `I18N-01` translation keys. Duplicate-email and weak-password responses are rendered inline with translated error codes. The strength meter is **UX only** — the backend remains the security boundary.

## Motivation

- Self-service registration is the second piece (after login) that lets the product be demonstrated end-to-end without an admin pre-creating accounts.
- A live strength meter dramatically reduces the rate of `password_too_weak` server rejections — users see the score climb before submitting. Mirroring zxcvbn on the frontend keeps the verdicts consistent (same library, same dictionary).
- Reusing the auth interceptor and `AuthStore` from `AUTH-06` means this spec is mostly UI + one form — most of the dangerous logic was already vetted.

## Scope

### In scope
- `/register` route with `email`, `password`, `password_confirm`, `display_name` fields.
- Live password strength meter component using `zxcvbn` (browser-side, ~400 KB; lazy-loaded only on `/register` and `/reset-password`).
- Score visualisation: 5-segment bar (PrimeNG `<p-progressBar>` styled, or a custom 5-segment indicator), colour-coded weak → strong (Tailwind classes; respect WCAG contrast).
- Live feedback text from zxcvbn (suggestions + warnings) translated to PL via a wrapper — zxcvbn ships English-only feedback text. **Decision documented in Open questions**: ship our own PL feedback strings keyed by zxcvbn's feedback code rather than translating arbitrary English at runtime.
- `password_confirm` field with inline mismatch validation.
- Form-level submit disabled until: email valid, password length 10–128, zxcvbn score ≥ 3, password matches confirm, display_name 1–80 chars, terms checkbox ticked.
- Terms-of-service checkbox (links to `/terms` and `/privacy` — pages can be stub-routed for now; copy comes from a separate doc task).
- On `201`, the response carries access token + sets refresh cookie; reuse `AuthStore.acceptToken()` from `AUTH-06` to slot directly into the logged-in flow. Navigate to `/`.
- Translated error rendering for: `email_taken` (inline under email field), `password_too_weak` (under password, with backend `password_score` shown), `field_*` validation codes, `too_many_attempts` with countdown (same pattern as `AUTH-06`).
- Vitest unit tests for the meter (score 0–4 maps to expected segments + colour classes), the form (submit gating), and the auto-login wire-up.
- Playwright e2e: register happy path → auto-logged-in → land on `/`; duplicate email → inline error; weak password blocks submit; back-to-login link works; axe-core zero serious/critical.

### Out of scope
- Email verification UI — no SMTP per [ADR-0013](../adr/0013-password-reset-without-smtp.md).
- Magic-link / passwordless flows.
- Social-provider buttons.
- Show/hide password toggle: **in scope (small UX win)**, but limited to a PrimeNG `<p-password [toggleMask]="true">` — no custom eye icon implementation.
- Translating zxcvbn's full feedback corpus to every supported language (we map only the small set of `warning`/`suggestion` codes that actually appear; long tail falls back to a generic "Choose a stronger password" key).
- A separate "complete profile" step after register — display_name is captured on the same form.

## Acceptance criteria

- AC-1: `/register` route renders with all four fields (`email`, `password`, `password_confirm`, `display_name`) and the terms checkbox. Submit button is disabled by default.
- AC-2: Typing in the password field updates the strength meter live with no perceptible lag (zxcvbn measured under 50 ms for typical inputs). The meter never blocks form rendering — zxcvbn is lazy-loaded.
- AC-3: When the score is < 3, submit remains disabled and a translated message ("Hasło zbyt słabe — wybierz dłuższe lub mniej oczywiste") appears under the field.
- AC-4: When `password_confirm` does not match `password`, an inline mismatch error appears and submit is disabled. Once they match, the error clears.
- AC-5: When the email is not a valid format, an inline error appears (`field_invalid_format` key from `I18N-01`).
- AC-6: Submitting valid data calls `POST /api/v1/auth/register`. On `201`, the access token is stored via `AuthStore.acceptToken()`, the user is navigated to `/`, and a translated welcome toast is shown.
- AC-7: On `409 email_taken`, the inline error appears under the email field. Other fields retain their values. Focus moves to the email field.
- AC-8: On `400 password_too_weak`, the inline error appears under the password field and includes the backend `password_score` ("Backend: 2/4"). Frontend already blocked the submit when score < 3, so this only arises if the user pasted a weak password and bypassed the live check — surface it cleanly.
- AC-9: On `429`, the rate-limit countdown from `AUTH-06`'s pattern is reused — submit disabled, live countdown, re-enable on zero.
- AC-10: All visible strings have translation keys in both `pl.json` and `en.json`. The key-symmetry test from `I18N-01` passes.
- AC-11: `zxcvbn` import is lazy — verify via `npm run build -- --stats-json` (or `--source-map-explorer`) that it does **not** appear in the main entry chunk.
- AC-12: axe-core zero serious/critical violations on `/register`. Lighthouse PWA ≥ 90 and Performance ≥ 80 (despite the larger zxcvbn payload; lazy-loaded so it doesn't hurt the route entry).
- AC-13: `npm run lint && npm test -- --run && npm run build` all exit 0.

## Investigation notes (for the implementer)

- Use the `zxcvbn-ts` package (TypeScript port, tree-shakeable, supports dictionary lazy-loading) rather than the original `zxcvbn` — the original ships its full dictionary in the main bundle. With `zxcvbn-ts`, load `@zxcvbn-ts/core` plus only the `@zxcvbn-ts/language-common` and `@zxcvbn-ts/language-en` packs (Polish dictionary not officially available, English plus common is the practical floor). Confirm package versions via Context7.
- Lazy-load pattern: dynamic `import()` inside the `/register` route's resolver or the password meter component's `OnInit`. The meter shows "—" / no segments until loaded; first keystroke kicks loading; subsequent strokes use the loaded instance.
- Pass `email`, `display_name`, and a small set of project-related blocklist words ("dogs", "thedogs", "trainer") to zxcvbn so users can't trivially game the meter with `thedogs2026`.
- The frontend meter and the backend zxcvbn must agree on the same threshold (score ≥ 3). Document the contract in the PR body. If we ever bump the threshold, both bundles must move together.
- Don't store the password in the `AuthStore` even briefly — pass it from the form straight into `auth.api.register()` and let it fall out of scope.
- For Polish feedback strings: map zxcvbn-ts's `feedback.warning` and `feedback.suggestions[]` codes to project keys under `auth.register.password_feedback.*`. The set of codes is small (under 20). Implementer must enumerate them in `pl.json` + `en.json`.

## Data model

None.

## API surface

Consumes `POST /api/v1/auth/register` from `AUTH-04`. No new endpoints.

## UI surface

### Route

```
/register                           → RegisterPage (lazy, standalone)
```

### Components (`frontend/src/app/components/auth/`)

- `register-page.component.ts` — form, submit, error rendering.
- `password-strength-meter.component.ts` — reusable; also consumed by `AUTH-08`'s reset-password page.

### i18n keys added to `auth.register.*`

`title`, `email_label`, `password_label`, `password_confirm_label`, `display_name_label`, `password_mismatch`, `password_too_weak`, `terms_label`, `terms_link_terms`, `terms_link_privacy`, `submit`, `submitting`, `login_link`, `welcome_toast`, `password_feedback.<code>` (a sub-namespace for the zxcvbn feedback codes), `rate_limited` (interpolation `{{ countdown }}`).

### Stub routes

`/terms` and `/privacy` exist as empty placeholder pages with a translated "Soon" message. Real copy is out of scope; routes exist so the register-form links don't 404.

## Security & RBAC

- Frontend strength meter is **UX only**. The backend is the security boundary — `AUTH-04` re-evaluates the same score server-side and rejects weak passwords regardless of what the frontend allowed.
- Password value passed to `auth.api.register()` directly from the form; no intermediate storage, no logging.
- On success, the token-acceptance path is identical to `AUTH-06`'s login path — same security properties (access in memory, refresh as HttpOnly cookie).
- `password_confirm` is never sent to the backend; it exists only as a frontend UX guard.

## Non-functional

- Lazy chunk for `/register` ≤ 250 KB minified+gzipped (including zxcvbn-ts core + language-common + language-en).
- Strength meter recomputation under 80 ms on typing (zxcvbn-ts measurement; not a hard CI gate, asserted manually).
- The route entry chunk (without zxcvbn) ≤ 30 KB minified+gzipped.

## Open questions

- **Polish password feedback corpus.** zxcvbn-ts has no official Polish language pack. We translate only the feedback `code` set we observe in practice; long-tail codes fall back to a generic "Wybierz silniejsze hasło". Document in the PR body which codes were keyed. If a Polish dictionary appears, swap in.
- **Terms / privacy copy.** Real text is a legal task, not engineering. Placeholder pages keep the form clickable; full content lands in a separate spec when the project has a draft.
- **Email-verification gating.** Once `email-smtp-integration` lands, the welcome toast should change to "Confirm your email to activate" and a portion of features can be gated. Out of scope until SMTP exists.

## References

- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [ADR-0004 — Frontend state: signals + NgRx Signal Store](../adr/0004-frontend-state.md)
- [ADR-0005 — UI component library: PrimeNG + lucide-angular](../adr/0005-ui-component-library.md)
- [ADR-0006 — PWA from day one](../adr/0006-pwa.md)
- [ADR-0013 — Password reset without SMTP](../adr/0013-password-reset-without-smtp.md)
- [Spec AUTH-04 — Registration endpoint](auth-register.md)
- [Spec AUTH-06 — Login UI + auth interceptor](auth-login-ui.md)
- [Spec I18N-01 — Frontend i18n](i18n-frontend.md)
