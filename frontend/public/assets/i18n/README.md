# i18n Translation Bundles

## Namespace convention
- `common.*` — shared UI labels (Save, Cancel, etc.)
- `shell.*` — shell/layout labels (brand, language switcher)
- `errors.codes.<code>` — maps backend error codes (from AUTH-01) to UI strings
- `auth.*` — authentication screens (AUTH-06/07/08 extend this)
- `dogs.*` — dog management screens

## Rules
1. Every key must exist in BOTH pl.json and en.json with identical structure.
2. A Vitest test (`language.service.spec.ts`) asserts key-set symmetry — CI will fail if you drift.
3. Backend error codes map to `errors.codes.<code>`. Never use backend `detail` text in the UI.
4. Feature PRs MUST include new keys in both bundles in the same commit.
