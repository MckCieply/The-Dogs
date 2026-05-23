---
slug: auth-login-ui
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

# AUTH-06 — Login UI + auth interceptor + token storage

## Summary

First end-to-end usable auth experience. Adds the `/login` route, the global auth interceptor (attaches the access JWT to outbound requests and reacts to `401`), the token-storage abstraction (access JWT **in memory only** per [ADR-0003](../adr/0003-auth-jwt-rbac.md), refresh via HttpOnly cookie set by the backend), a silent-refresh queue (concurrent requests that race a single `401` share one refresh call), and the matching i18n keys for the auth namespace. Uses translation keys established by `I18N-01`.

## Motivation

- Without a login UI the entire backend auth stack is unreachable from the product. Every screen built afterwards assumes a working `/login`.
- The interceptor + silent-refresh logic is genuinely subtle (concurrent 401s, refresh-during-refresh, looping refresh failures). Doing it once, well, in this spec means dogs / notes / scheduler can ignore it entirely.
- Access tokens in `localStorage` are an XSS amplifier — per [ADR-0003](../adr/0003-auth-jwt-rbac.md) the access token must live in memory only. The refresh cookie is HttpOnly + Secure + SameSite=Strict; it survives reloads because the backend cookie is sent on the silent-refresh call after every page load.

## Scope

### In scope
- `/login` route with email + password form (PrimeNG `InputText`, `Password`, `Button`; reactive forms).
- `AuthStore` (Signal Store per [ADR-0004](../adr/0004-frontend-state.md)) holding the **in-memory** access token, decoded principal (`{ userId, email, roles, expiresAt }`), and loading/error state.
- `authInterceptor` (`HttpInterceptorFn`) that:
  - attaches `Authorization: Bearer <token>` when present,
  - on `401`, queues the failed request, kicks off `/auth/refresh` if not already in flight, and retries every queued request on success,
  - on refresh failure, clears the store and routes to `/login` with a return URL.
- `authGuard` (`CanMatchFn`) that allows route activation only when authenticated; otherwise redirects to `/login?returnUrl=<original>`.
- On app bootstrap, attempt one silent refresh — if the HttpOnly cookie is valid the user lands logged-in; if not, they see `/login`. Avoids the "always log in after reload" UX.
- Error rendering: map backend `errors[].code` to translation keys per `I18N-01` (already established for the auth codes from `AUTH-02`).
- Rate-limit awareness: when `429 too_many_attempts` arrives, render `Retry-After` as a live countdown ("Spróbuj ponownie za 02:34") and keep the submit button disabled until expiry.
- Logout button somewhere reachable (placeholder in the shell layout — full nav menu is a later concern).
- Test coverage:
  - Vitest unit tests for `AuthStore`, the interceptor (mock `HttpClient` + race two parallel 401s, assert one refresh call), `authGuard`.
  - Playwright e2e: login happy path, bad credentials, rate-limit countdown, logout, reload-stays-logged-in (silent refresh), reload-after-logout-stays-logged-out, axe-core zero serious/critical violations.

### Out of scope
- `/register`, `/forgot-password`, `/reset-password` routes — `AUTH-07` and `AUTH-08`.
- "Remember me" checkbox affecting refresh lifetime — backend cookie lifetime stays fixed at 7 days; can be revisited later.
- Social login / OIDC — not in MVP.
- Real-device biometric / WebAuthn — not in MVP.
- Multi-tab refresh coordination (BroadcastChannel) — accept the rare race where tab A refreshes while tab B's old token is in flight; surfaces as a one-off forced re-login. Tracked as a follow-up.
- Forced password rotation prompts — not a project requirement.

## Acceptance criteria

- AC-1: `/login` route renders with email + password fields, a primary submit button, and a `LanguageSwitcher` (from `I18N-01`) visible.
- AC-2: Submitting valid credentials calls `POST /api/v1/auth/login`, stores the access token in memory, decodes the principal (using `jwt-decode` or equivalent — no validation on the frontend), and navigates to `returnUrl` (or `/` if none).
- AC-3: After AC-2, refreshing the browser keeps the user logged in (silent refresh on bootstrap reads the HttpOnly cookie via `/auth/refresh`).
- AC-4: Submitting wrong credentials shows the translated error for `bad_credentials` inline below the form (toast also acceptable; both is fine). The form re-enables and focuses the password field.
- AC-5: After 5 failed attempts in 15 minutes, the backend returns `429`. The UI parses `Retry-After`, disables submit, shows a live countdown. After the countdown reaches zero, submit re-enables. Asserted by mocking the response in Vitest and visually in Playwright.
- AC-6: A protected route navigation while unauthenticated redirects to `/login?returnUrl=<encoded>`. After successful login the user lands on the original target.
- AC-7: A `401` response on any in-flight API call while authenticated triggers `/auth/refresh`. If refresh succeeds, the original request is retried automatically. If refresh fails, the store is cleared, the user is routed to `/login`, and a translated toast says "Sesja wygasła — zaloguj się ponownie".
- AC-8: Two concurrent `401`s on different API calls share one `/auth/refresh` call. Asserted by Vitest with two parallel mocked failures + one expected `/auth/refresh` invocation.
- AC-9: Logout: clicking the logout control calls `POST /auth/logout`, clears the store, clears all in-memory state, and routes to `/login`. Reloading after logout does not silently re-log-in.
- AC-10: The access token is **never** written to `localStorage`, `sessionStorage`, IndexedDB, or any cookie. Asserted by a Playwright test that inspects all storage after login.
- AC-11: axe-core zero serious/critical violations on `/login`. Lighthouse PWA ≥ 90 and Performance ≥ 80.
- AC-12: All user-visible strings on `/login` are translated via `I18N-01`. PL and EN keysets remain symmetric (the existing I18N test catches drift).
- AC-13: `npm run lint && npm test -- --run && npm run build` all exit 0.

## Investigation notes (for the implementer)

- Token storage abstraction lives in `AuthStore`. Do not stash the access JWT in any module-scoped variable outside the store — the store is the single source of truth.
- `jwt-decode` (~1 KB) is the standard for client-side claim reading. **Never** validate the signature on the frontend; the backend already did that. The frontend reads `exp`, `roles`, `email`, `sub` to populate UI state.
- `expiresAt` in the store should be used to **proactively** refresh ~30 seconds before expiry, not only reactively on `401`. Avoids the 15-minute click-then-spin UX.
- Silent refresh on bootstrap: send `POST /auth/refresh`. If `200`, populate store. If `401`, do nothing (user is correctly logged out). Do **not** redirect to `/login` here — that lets the unauthenticated user see public pages without bounce.
- Interceptor queue pattern: use a single `BehaviorSubject<boolean>` (or signal) `refreshInFlight`. Failed requests `await` it and retry on flip. The standard Angular pattern; verify the current 2026 idiom via Context7.
- For the rate-limit countdown: `Retry-After` is the wall-clock seconds. Decrement via `interval(1000)` driven by RxJS, disable submit while > 0.

## Data model

None on the frontend beyond the `AuthStore` shape:

```ts
type AuthState = {
  accessToken: string | null;
  principal: { userId: string; email: string; roles: string[]; expiresAt: number } | null;
  loading: boolean;
  error: { code: string; retryAfterSeconds?: number } | null;
};
```

## API surface

Consumes only existing backend endpoints (`/auth/login`, `/auth/refresh`, `/auth/logout`). No new endpoints.

## UI surface

### Route

```
/login                              → LoginPage (lazy, standalone)
```

### Components (`frontend/src/app/components/auth/`)

- `login-page.component.ts` — form, submit, error rendering, countdown.
- (Shared) `auth-form-shell.component.ts` — wraps the centered card layout reused by `AUTH-07` and `AUTH-08`. Implementer may inline if extracting feels premature.

### Services

- `frontend/src/app/services/auth.store.ts` — `AuthStore` (Signal Store).
- `frontend/src/app/services/auth.api.ts` — thin wrapper over `HttpClient`. Types from OpenAPI (regenerated via `api-sync` skill — implementer must run it).

### Interceptors / guards

- `frontend/src/app/interceptors/auth.interceptor.ts`.
- `frontend/src/app/guards/auth.guard.ts`.

### i18n keys added to `auth.login.*`

Minimum: `title`, `email_label`, `email_placeholder`, `password_label`, `password_placeholder`, `submit`, `submitting`, `forgot_link`, `register_link`, `rate_limited` (with `{{ countdown }}` interpolation), `session_expired_toast`. PL + EN bundles updated in the same PR.

## Security & RBAC

- Access JWT in memory only. Refresh cookie HttpOnly + Secure + SameSite=Strict + Path=/api/v1/auth.
- All `HttpClient` calls go through the interceptor — no manual `Authorization` header anywhere.
- `jwt-decode` reads claims for UI purposes only; security decisions are server-side.
- XSS risk surfaces: any error `detail` rendered to the user must go through Angular's default sanitisation (no `[innerHTML]` of backend text). Reviewer enforces.
- CSRF: refresh cookie is `SameSite=Strict` and `Path=/api/v1/auth`. Refresh is `POST` so a CSRF token is not required (per [ADR-0003](../adr/0003-auth-jwt-rbac.md)), but reviewer must double-check after this lands.

## Non-functional

- `/login` first paint < 1.2 s on 4G simulated network (Lighthouse).
- Refresh queue test must terminate deterministically — no flaky test driven by timing. Use deterministic mocking with `vi.useFakeTimers()`.

## Open questions

- **Logout placement.** The shell layout doesn't exist yet beyond a brand label. Implementer adds a minimal "Wyloguj" button somewhere visible (top-right of shell) — full nav menu is a separate concern.
- **Returning to a previously visited PWA install.** When the user opens the installed PWA after a long break, the refresh cookie may be expired. Bootstrap silent refresh handles this — landing on `/login` is the correct outcome.

## References

- [ADR-0003 — Auth: JWT + Spring Security + RBAC](../adr/0003-auth-jwt-rbac.md)
- [ADR-0004 — Frontend state: signals + NgRx Signal Store](../adr/0004-frontend-state.md)
- [ADR-0005 — UI component library: PrimeNG + lucide-angular](../adr/0005-ui-component-library.md)
- [ADR-0006 — PWA from day one](../adr/0006-pwa.md)
- [Spec AUTH-02 — Login endpoint](auth-login.md)
- [Spec AUTH-03 — Refresh + logout](auth-refresh-logout.md)
- [Spec I18N-01 — Frontend i18n](i18n-frontend.md)
