---
slug: i18n-frontend
status: ready
priority: asap
owner: harness-engineer
created: 2026-05-22
adrs:
  - ADR-0001  # stack
  - ADR-0004  # frontend state
  - ADR-0006  # PWA
  - ADR-0012  # automated pipeline
---

# I18N-01 — Frontend internationalisation (PL + EN, ngx-translate)

## Summary

Establishes the frontend internationalisation layer **before any user-facing screen is built**, so that every later feature (`AUTH-06`, `AUTH-07`, `AUTH-08`, dogs frontend, notes, scheduler) writes translated keys from day one and never needs a retroactive refactor. Installs `@ngx-translate/core` + `@ngx-translate/http-loader`, ships PL and EN bundles, a `LanguageSwitcher` shell component, persistence in localStorage, and the convention for mapping backend `errors[].code` values (from `AUTH-01`) to frontend translation keys.

Polish is the default language; English is the only secondary language for now. Future languages plug in by dropping a new JSON bundle — no code changes.

## Motivation

- Retrofitting i18n onto an already-built UI is the most expensive refactor a frontend takes. Every template literal, every PrimeNG label, every toast message must be hunted down and re-keyed. Doing it now (zero screens built) is essentially free.
- The backend already commits to a stable `errors[].code` convention (`AUTH-01`). Without this spec, the frontend would have to display the English `detail` field from the RFC 7807 response — a fallback that quickly drifts when product copy changes.
- Polish is the owner's native language and the primary market for the MVP; English is needed for the global pipeline (documentation, future demos, recruiting trainers abroad).

## Scope

### In scope
- Install `@ngx-translate/core` and `@ngx-translate/http-loader` at versions current for Angular 21 (verify via Context7).
- Configure `TranslateModule.forRoot` in the application bootstrap with the HTTP loader pointing at `assets/i18n/{lang}.json`.
- Create `assets/i18n/pl.json` and `assets/i18n/en.json` populated with the namespaces below (initial keys only; later features extend the bundles).
- Build a `LanguageService` (signal-based) that:
  - reads stored language from `localStorage.thedogs.language`,
  - falls back to `navigator.language` (`pl-*` → `pl`, else `en`),
  - falls back to `pl`,
  - persists every change.
- Build a `LanguageSwitcher` standalone component (top-right of the shell layout) — two-button toggle PL/EN with active state, lucide `globe` icon.
- Map backend `errors[].code` → translation keys under a single namespace (`errors.codes.<code>`). The translation pipe is the single rendering path for backend errors; no English fallback strings appear in any frontend component.
- Add `<html lang="{{ language() }}">` binding so screen readers announce the right language.
- Document the namespace convention in `frontend/src/assets/i18n/README.md`.
- Add ESLint rule (or a documented convention enforced in `reviewer`) against bare string literals in templates for user-visible text. ESLint plugin `eslint-plugin-i18next` or `@angular-eslint/template/no-unused-i18n` if available; otherwise a documented convention with reviewer responsibility.
- Vitest tests: service initialises with the persisted value, falls back correctly, persists changes, switching language updates `<html lang>`.
- Playwright smoke: language toggle visible, click switches the only existing text (the shell brand label), browser-language fallback works.

### Out of scope
- Right-to-left languages — no Arabic/Hebrew planned in the MVP; the layout system will need RTL audit when one lands.
- Pluralisation rules beyond what ngx-translate provides out of the box (ICU MessageFormat is a separate later spec if needed — Polish plurals are 3-form, ngx-translate's `TranslateMessageFormatCompiler` plugin handles this if we add it then).
- Date / number / currency formatting — done via Angular's `DatePipe` / `CurrencyPipe` with the bound `LOCALE_ID`; ngx-translate does **not** own those.
- Backend-side i18n of error `detail` text — backend always returns English `detail` (devs only). Translation lives entirely in the frontend bundle, keyed by `code`.
- Translation management UI / external service (Crowdin, Lokalise, Phrase) — JSON files in repo are the source of truth.

## Acceptance criteria

- AC-1: `npm run build` produces a bundle that lazy-loads `assets/i18n/pl.json` and `assets/i18n/en.json` on language switch — they are not in the main chunk.
- AC-2: First visit with no stored preference and `navigator.language = 'pl-PL'` displays the app in Polish.
- AC-3: First visit with no stored preference and `navigator.language = 'en-GB'` displays the app in English.
- AC-4: First visit with no stored preference and `navigator.language = 'de-DE'` falls back to Polish (project default).
- AC-5: After clicking the language toggle, the new language persists to `localStorage.thedogs.language` and survives a reload.
- AC-6: `<html lang>` attribute reflects the active language at all times.
- AC-7: A backend `errors[].code = "unauthorized"` from `AUTH-01` renders as the PL string `"Brak autoryzacji"` (or current copy) in Polish, `"Unauthorized"` in English, when displayed via the standard toast pipeline.
- AC-8: A `code` value that has no corresponding translation key falls back to a generic key `errors.codes.unknown` (rendered as `"Wystąpił błąd"` / `"Something went wrong"`) **and** logs a `warn` in the browser console naming the missing key. Vitest asserts both.
- AC-9: Both `pl.json` and `en.json` have **identical** key structure — a Vitest test that loads both and diffs the keysets asserts zero asymmetry.
- AC-10: Lighthouse PWA score ≥ 90 and Performance ≥ 80 on the shell route after adding the i18n runtime (the libraries are tree-shakeable; this asserts we did not regress).
- AC-11: axe-core reports zero serious/critical issues on the language switcher.
- AC-12: `npm run lint && npm test -- --run && npm run build` all exit 0.

## Investigation notes (for the implementer)

- Angular 21 supports both ngx-translate and Angular's built-in `@angular/localize`. We pick ngx-translate because (a) it allows runtime language switching without a reload, (b) JSON bundles are easier to diff in PRs than XLIFF, (c) Signal Store integration is straightforward. Document the trade-off in the PR body. Revisit if/when Angular's localize gains runtime switching.
- The HTTP loader fetches `assets/i18n/{lang}.json`. The service worker (`ngsw-config.json` from [ADR-0006](../adr/0006-pwa.md)) must cache those files with `performance` strategy so offline language switch works.
- Bundle structure (proposed top-level namespaces — flat per feature):
  ```
  {
    "common": { "save": "...", "cancel": "...", "delete": "...", "loading": "...", "search": "..." },
    "shell":  { "brand": "The-Dogs", "language_switcher": { "pl": "Polski", "en": "English" } },
    "errors": {
      "codes": {
        "unknown":            "...",
        "unauthorized":       "...",
        "token_expired":      "...",
        "invalid_token":      "...",
        "forbidden":          "...",
        "not_found":          "...",
        "field_required":     "...",
        "field_too_short":    "...",
        "field_too_long":     "...",
        "field_invalid_format": "..."
      }
    },
    "auth": { /* extended by AUTH-06/07/08 */ },
    "dogs": { /* extended by dogs frontend */ }
  }
  ```
- Feature specs that follow this one **must** include their new keys in both bundles in the same PR. Reviewer enforces (key-symmetry assertion is in this spec's test suite and will catch drift).
- For the missing-key warn: ngx-translate has a `MissingTranslationHandler` API — register a custom handler that returns the `errors.codes.unknown` value and `console.warn`s the missing key.

## Data model

None.

## API surface

None.

## UI surface

### `LanguageSwitcher` component

- Lives under `frontend/src/app/layout/language-switcher/`.
- Two `<button>` elements (PL, EN) rendered with PrimeNG `<p-selectButton>` or a plain styled toggle.
- ARIA: `aria-label="{{ 'shell.language_switcher.label' | translate }}"` on the group, `aria-pressed` on each button.
- Keyboard: Tab + Space/Enter switch the active language.
- Icon: `lucide-angular` `globe` to the left of the toggle.

### `LanguageService` (signal-based, per [ADR-0004](../adr/0004-frontend-state.md))

```ts
@Injectable({ providedIn: 'root' })
export class LanguageService {
  readonly language = signal<'pl' | 'en'>('pl');
  setLanguage(lang: 'pl' | 'en'): void { /* persist + apply to TranslateService */ }
  // initialisation reads localStorage → navigator.language → 'pl'
}
```

Component code uses `{{ 'errors.codes.unauthorized' | translate }}` in templates and `translate.instant(...)` in services (for toasts).

## Security & RBAC

- `localStorage` is per-origin and not sensitive — language preference does not require auth.
- No PII in language bundles; bundles are public assets.
- Service worker caches bundles same-origin only.

## Non-functional

- Bundle size budget: each language file ≤ 30 KB minified. Initial keys in this spec are ~3 KB — plenty of headroom for future features.
- Language switch must be instant (< 50 ms perceived) — ngx-translate caches loaded bundles in memory.
- Offline language switch must work after first online visit (both bundles cached by SW).

## Open questions

- **ICU MessageFormat plugin.** Polish needs 3 plural forms ("1 pies / 2 psy / 5 psów"). ngx-translate's default does not support ICU; `ngx-translate-messageformat-compiler` adds it. Decision: **not in this spec** — add when the first feature actually needs a pluralised string (likely a list counter in dogs/notes UI). Tracked as an open follow-up.
- **Backend `Accept-Language` honouring.** Currently the backend ignores `Accept-Language` and always returns English `detail`. Frontend translates via `code`. If/when backend produces user-facing strings (e.g., admin emails once SMTP lands), backend-side i18n is a separate concern.
- **Translation completeness gate in CI.** Right now the symmetric-keys test runs in unit tests. We could escalate to a `ci-frontend` hard fail; pragmatically, the unit-test failure is already a `ci-frontend` failure. Leave as-is.

## References

- [ADR-0001 — Stack and build tools](../adr/0001-stack-and-build-tools.md)
- [ADR-0004 — Frontend state: signals + NgRx Signal Store](../adr/0004-frontend-state.md)
- [ADR-0006 — PWA from day one](../adr/0006-pwa.md)
- [Spec AUTH-01 — Auth core](auth-core-jwt.md) — defines the `errors[].code` convention this spec consumes.
- ngx-translate documentation (verify version via Context7).
