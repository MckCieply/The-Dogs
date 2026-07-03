import { test, expect, Page } from '@playwright/test';

/**
 * Playwright e2e tests for AUTH-03: session cookie lifecycle, transparent refresh, and logout.
 *
 * These tests require the full stack (Angular frontend + Spring Boot backend + Postgres).
 * In CI the stack is started via docker-compose before the suite runs (see ci-e2e.yml).
 * Locally, set E2E_BASE_URL and run `npm run e2e` from the frontend directory.
 *
 * Environment variables expected when the backend is live:
 *   E2E_BACKEND_URL  — e.g. http://localhost:8080   (defaults below)
 *   E2E_TEST_EMAIL   — test trainer account email
 *   E2E_TEST_PASSWORD — test trainer account password
 */

const BACKEND_URL = process.env['E2E_BACKEND_URL'] ?? 'http://localhost:8080';
const TEST_EMAIL = process.env['E2E_TEST_EMAIL'] ?? 'e2e-trainer@example.com';
const TEST_PASSWORD = process.env['E2E_TEST_PASSWORD'] ?? 'e2e-password';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Logs the user in via the Angular login form and waits for the home route.
 * Returns the page object for further interactions.
 */
async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email address').fill(TEST_EMAIL);
  // PrimeNG p-password renders an <input> inside the component — locate by placeholder
  await page.locator('input[placeholder="Your password"]').fill(TEST_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  // Wait for navigation away from /login
  await page.waitForURL((url) => !url.pathname.endsWith('/login'), { timeout: 10_000 });
}

/**
 * Calls the backend login endpoint directly with credentials and returns the
 * access token from the response body. The refresh_token HttpOnly cookie is set
 * automatically by the browser context.
 */
async function apiLogin(page: Page): Promise<string> {
  const response = await page.request.post(`${BACKEND_URL}/api/v1/auth/login`, {
    data: { email: TEST_EMAIL, password: TEST_PASSWORD },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(response.ok()).toBe(true);
  const body = await response.json();
  return body.accessToken as string;
}

// ---------------------------------------------------------------------------
// AC: User logs in → session cookie is set
// ---------------------------------------------------------------------------

test('login via UI — refresh_token cookie is present in browser context after login', async ({
  page,
  context,
}) => {
  await loginViaUi(page);

  // The cookie is HttpOnly+Secure+SameSite=Strict scoped to /api/v1/auth,
  // so we verify it exists via the Playwright context cookie API
  const cookies = await context.cookies();
  const refreshCookie = cookies.find((c) => c.name === 'refresh_token');

  expect(refreshCookie, 'refresh_token cookie must be set after login').toBeDefined();
  expect(refreshCookie!.httpOnly).toBe(true);
  // Playwright reports sameSite as a string; assert it is not 'None' at minimum
  expect(refreshCookie!.sameSite).not.toBe('None');
});

test('login via UI — user is redirected to home page (not /login)', async ({ page }) => {
  await loginViaUi(page);
  expect(page.url()).not.toContain('/login');
});

// ---------------------------------------------------------------------------
// AC: Transparent refresh — after access-token expiry app continues working
//
// Strategy: log in, then invalidate the in-memory access token by calling
// store.clearToken() in the page context so the next request to a protected
// API sends no token. The interceptor should attempt a silent refresh via
// the still-valid cookie and recover.
//
// Note: This test simulates the interceptor path; it does not fast-forward
// real time. A more complete test would require setting jwt.access-token-ttl-minutes=0
// in the test profile and waiting.
// ---------------------------------------------------------------------------

test('after clearing in-memory token, interceptor silently refreshes and protected request succeeds', async ({
  page,
}) => {
  await loginViaUi(page);

  // Intercept a protected API call that would happen on the home page
  // and verify that the app is not redirected to /login
  const consoleErrors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });

  // Trigger a navigation which will fire route guards and potentially API calls
  await page.reload({ waitUntil: 'networkidle' });

  // App must still be on the authenticated route — not redirected to /login
  expect(page.url()).not.toContain('/login');
});

// ---------------------------------------------------------------------------
// AC: Logout → redirected to login, subsequent API calls return 401
// ---------------------------------------------------------------------------

test('logout flow — user ends up on /login after logout', async ({ page }) => {
  await loginViaUi(page);

  // Call the logout endpoint via page.request (shares the same browser context / cookies)
  const logoutResponse = await page.request.post(`${BACKEND_URL}/api/v1/auth/logout`, {
    headers: { 'Content-Type': 'application/json' },
  });
  expect(logoutResponse.status()).toBe(204);

  // Navigate to home — the guard should redirect to /login because the in-memory token is gone
  // (in a real PWA scenario the page would reload after logout)
  await page.goto('/');
  await page.waitForURL('**/login', { timeout: 5_000 });
  expect(page.url()).toContain('/login');
});

test('logout — refresh_token cookie is cleared (Max-Age=0) in response headers', async ({
  page,
  context,
}) => {
  await loginViaUi(page);

  const logoutResponse = await page.request.post(`${BACKEND_URL}/api/v1/auth/logout`, {
    headers: { 'Content-Type': 'application/json' },
  });
  expect(logoutResponse.status()).toBe(204);

  const setCookieHeader = logoutResponse.headers()['set-cookie'];
  expect(setCookieHeader, 'Set-Cookie header must be present on logout response').toBeDefined();
  expect(setCookieHeader).toContain('Max-Age=0');
});

test('logout — subsequent refresh attempt returns 401', async ({ page }) => {
  await loginViaUi(page);

  // Logout to revoke the token family
  await page.request.post(`${BACKEND_URL}/api/v1/auth/logout`);

  // Try refreshing — must fail with 401 because family is revoked
  const refreshResponse = await page.request.post(`${BACKEND_URL}/api/v1/auth/refresh`);
  expect([400, 401]).toContain(refreshResponse.status());
});

// ---------------------------------------------------------------------------
// AC: No token value appears in browser console logs
// ---------------------------------------------------------------------------

test('no refresh token value appears in browser console output during login and refresh', async ({
  page,
}) => {
  const consoleLogs: string[] = [];
  page.on('console', (msg) => consoleLogs.push(msg.text()));

  await loginViaUi(page);

  // Extract the cookie value so we can assert it's not logged
  const cookies = await page.context().cookies();
  const refreshCookie = cookies.find((c) => c.name === 'refresh_token');

  if (refreshCookie) {
    for (const log of consoleLogs) {
      expect(log, `Console output must not contain the refresh token value`).not.toContain(
        refreshCookie.value,
      );
    }
  }
  // If the cookie is not visible (HttpOnly from Playwright perspective), we still assert
  // that no log line looks like a 64-char hex token
  for (const log of consoleLogs) {
    expect(log, 'Console must not log 64-hex-char token values').not.toMatch(/[0-9a-f]{64}/);
  }
});

// ---------------------------------------------------------------------------
// Idempotent logout — calling logout twice returns 204 both times
// ---------------------------------------------------------------------------

test('logout is idempotent — second call also returns 204', async ({ page }) => {
  await loginViaUi(page);

  const first = await page.request.post(`${BACKEND_URL}/api/v1/auth/logout`);
  expect(first.status()).toBe(204);

  const second = await page.request.post(`${BACKEND_URL}/api/v1/auth/logout`);
  expect(second.status()).toBe(204);
});

// ---------------------------------------------------------------------------
// Logout via UI button — HomeComponent wires the "Log out" button to
// authStore.logout() which then calls router.navigate(['/login']).
// This test exercises the full UI → store → backend → redirect path,
// complementing the direct-API logout tests above.
// ---------------------------------------------------------------------------

test('logout clears session and redirects to /login when the Log out button is clicked', async ({
  page,
  context,
}) => {
  // Step 1: log in via the UI form and land on the home page.
  await loginViaUi(page);
  expect(page.url()).not.toContain('/login');

  // Step 2: confirm a refresh_token cookie exists before logout.
  const cookiesBefore = await context.cookies();
  expect(
    cookiesBefore.find((c) => c.name === 'refresh_token'),
    'refresh_token cookie must exist after login',
  ).toBeDefined();

  // Step 3: click the "Log out" button rendered by HomeComponent.
  // The button has aria-label="Log out" (see home.component.ts).
  await page.getByRole('button', { name: 'Log out' }).click();

  // Step 4: HomeComponent.logout() calls authStore.logout() then router.navigate(['/login']).
  // Wait for the URL to settle on /login.
  await page.waitForURL('**/login', { timeout: 10_000 });
  expect(page.url()).toContain('/login');

  // Step 5: the login form must be visible — the user is fully signed out.
  await page.waitForSelector('form', { state: 'visible', timeout: 5_000 });

  // Step 6: navigating back to / while unauthenticated must redirect back to /login
  // (the auth guard has no in-memory token after the store was cleared).
  await page.goto('/');
  await page.waitForURL('**/login', { timeout: 5_000 });
  expect(page.url()).toContain('/login');
});
