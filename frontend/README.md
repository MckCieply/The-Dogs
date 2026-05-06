# The Dogs — Frontend

Angular 21 PWA for The Dogs dog-trainer management platform.

## Prerequisites

- Node.js v24+ (see `engines` in `package.json`)
- npm 11+

## Quick start

```bash
# Install dependencies
npm install

# Start dev server (proxies /api → localhost:8080)
npm start
# Open http://localhost:4200
```

## Available commands

| Command | Description |
|---|---|
| `npm start` | Start Angular dev server on port 4200 with proxy to backend |
| `npm test` | Run Vitest unit tests via Angular's test builder |
| `npm run build` | Production build (output: `dist/the-dogs/`) |
| `npm run lint` | ESLint on all `.ts` and `.html` source files |
| `npm run e2e` | Run Playwright e2e tests (requires dev server on :4200) |
| `npm run e2e:install` | Install Playwright Chromium browser (run once) |
| `npm run watch` | Build in watch mode for development |

## Project structure

```
src/
  app/
    components/
      auth/          # Login page (email + password, PrimeNG form components)
      home/          # Protected placeholder home — shows trainer email + logout
    guards/          # authGuard — redirects unauthenticated requests to /login
    interceptors/    # authInterceptor — attaches Bearer token, handles 401 -> refresh
    models/          # TypeScript interfaces (will be generated from OpenAPI)
    services/        # AuthService (HTTP calls) + AuthStore (NgRx Signal Store slice)
    shared/          # Reusable UI primitives (future)
    layout/          # Shell/navbar components (future)
  styles.css         # Tailwind CSS 4 + PrimeNG theme imports
e2e/
  auth.spec.ts       # Playwright smoke tests for auth flow (+ axe-core a11y audit)
```

## Architecture decisions

- **State:** NgRx Signal Store 21.x (`AuthStore`) — no legacy NgRx Store/Effects/Actions.
- **Tokens:** Access token lives in memory only (never `localStorage`/`sessionStorage`). Refresh token is an HttpOnly cookie managed by the backend (ADR-0003).
- **HTTP:** All API calls via `HttpClient` + `authInterceptor`. No raw `fetch`/XHR.
- **Components:** Standalone only. No `NgModule`.
- **PWA:** Service Worker + Web App Manifest via `@angular/pwa`. `ngsw-config.json` uses `freshness` for `/api/v1/**` and `performance` for static assets.

## Environment

Backend expected at `http://localhost:8080` during development. The dev server proxy (`proxy.conf.json`) forwards `/api/**` requests there.

Production: nginx serves the built SPA and proxies `/api/` to the Spring Boot container (see `nginx.conf`).

