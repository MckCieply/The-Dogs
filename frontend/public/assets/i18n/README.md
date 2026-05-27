# i18n Translation Bundles

Translation files for The-Dogs frontend internationalisation (I18N-01).

## Supported Languages

| File     | Language | Code |
|----------|----------|------|
| `pl.json`| Polish   | `pl` |
| `en.json`| English  | `en` |

## Namespace Convention

All keys follow a flat-namespace hierarchy using dot notation. Top-level namespaces map to feature domains:

| Namespace       | Description                                               |
|-----------------|-----------------------------------------------------------|
| `common.*`      | Shared UI labels used across all features (Save, Cancel, Delete, Search, Loading) |
| `shell.*`       | Application shell: brand name, navigation, language switcher |
| `errors.codes.*`| RFC-7807-style error codes returned by the backend API    |
| `auth.*`        | Authentication feature (login, logout, password reset)    |
| `dogs.*`        | Dogs feature (list, form, detail)                         |

## Adding a New Key

1. Add the key to **both** `pl.json` and `en.json` under the correct namespace.
2. Keep the key structure identical in both files — CI symmetry checks will fail otherwise.
3. Use dot-separated lowercase keys: `feature.subsection.label`.
4. Namespace new features by adding a top-level key matching the feature slug (e.g. `notes`, `scheduler`).

## Adding a New Language

1. Copy `en.json` to `<code>.json` (e.g. `de.json`).
2. Translate all values (do not change keys).
3. Register the new language code in `LanguageService` (`'pl' | 'en'` union type).
4. Add the flag/label to `shell.language_switcher` in all existing bundles.

## Missing Key Fallback

Missing keys are handled by `CustomMissingTranslationHandler`:
- A warning is logged to the console: `Missing translation key: <key>`.
- The value of `errors.codes.unknown` is returned.
- If `errors.codes.unknown` itself is missing, the hardcoded string `"Something went wrong / Wystapil blad"` is returned to prevent infinite recursion.
