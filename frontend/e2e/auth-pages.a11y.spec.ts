import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * Accessibility assertions for auth-related pages (AUTH-03).
 *
 * Uses @axe-core/playwright to run axe against every rendered auth route.
 * Zero "serious" or "critical" violations are tolerated — any violation will fail the test.
 *
 * Routes covered:
 *  - /login  (unauthenticated entry point)
 *  - /login  with an error state (simulated wrong credentials to surface the error message)
 *
 * The home route (/) is guarded and redirects to /login when unauthenticated,
 * so it is effectively the same DOM as /login. A logged-in home-page a11y test
 * is deferred until there is non-trivial protected content to assert against.
 */

// ---------------------------------------------------------------------------
// /login — default (empty) state
// ---------------------------------------------------------------------------

test('login page has no serious or critical axe violations', async ({ page }) => {
  await page.goto('/login');
  // Wait for the Angular component to render fully
  await page.waitForSelector('form', { state: 'visible' });

  const results = await new AxeBuilder({ page })
    // Scope the scan to the main login card to exclude PrimeNG overlays that may not be rendered
    .include('body')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();

  const seriousOrCritical = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );

  expect(
    seriousOrCritical,
    `Found ${seriousOrCritical.length} serious/critical axe violations on /login:\n` +
      seriousOrCritical
        .map((v) => `  [${v.impact}] ${v.id}: ${v.description}`)
        .join('\n'),
  ).toHaveLength(0);
});

// ---------------------------------------------------------------------------
// /login — error state (wrong credentials submitted)
// ---------------------------------------------------------------------------

test('login page in error state has no serious or critical axe violations', async ({ page }) => {
  await page.goto('/login');
  await page.waitForSelector('form', { state: 'visible' });

  // Fill in credentials and submit to trigger the error message display
  await page.getByLabel('Email address').fill('wrong@example.com');
  await page.locator('input[placeholder="Your password"]').fill('wrong-password');
  await page.getByRole('button', { name: 'Sign in' }).click();

  // Wait for the error message to appear (p-message with severity="error")
  // The component renders the PrimeNG message when authStore.authError() is set
  await page.waitForTimeout(1_500); // allow the async login to resolve and error to render

  const results = await new AxeBuilder({ page })
    .include('body')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();

  const seriousOrCritical = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );

  expect(
    seriousOrCritical,
    `Found ${seriousOrCritical.length} serious/critical axe violations on /login (error state):\n` +
      seriousOrCritical
        .map((v) => `  [${v.impact}] ${v.id}: ${v.description}`)
        .join('\n'),
  ).toHaveLength(0);
});

// ---------------------------------------------------------------------------
// /login — validation error state (submitted with empty fields)
// ---------------------------------------------------------------------------

test('login page in validation-error state has no serious or critical axe violations', async ({
  page,
}) => {
  await page.goto('/login');
  await page.waitForSelector('form', { state: 'visible' });

  // Submit without filling in any fields — triggers inline validation messages
  await page.getByRole('button', { name: 'Sign in' }).click();

  // Wait for validation error <small> elements to appear
  await page.waitForSelector('[role="alert"]', { state: 'visible', timeout: 3_000 }).catch(() => {
    // validation messages may render without role=alert in some states — proceed anyway
  });

  const results = await new AxeBuilder({ page })
    .include('body')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();

  const seriousOrCritical = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );

  expect(
    seriousOrCritical,
    `Found ${seriousOrCritical.length} serious/critical axe violations on /login (validation state):\n` +
      seriousOrCritical
        .map((v) => `  [${v.impact}] ${v.id}: ${v.description}`)
        .join('\n'),
  ).toHaveLength(0);
});

// ---------------------------------------------------------------------------
// / (unauthenticated) — AUTH-03: guard redirects to /login; axe scan on landing
//
// After logout the in-memory token is gone. Navigating to / triggers the auth
// guard which redirects to /login. This test confirms that the redirected login
// page (as seen in the post-logout flow) has no serious/critical axe violations.
// ---------------------------------------------------------------------------

test('unauthenticated navigation to / redirects to /login with no serious or critical axe violations', async ({
  page,
}) => {
  // Navigate to the root path without any auth state — guard should redirect to /login.
  await page.goto('/');
  await page.waitForURL('**/login', { timeout: 5_000 });

  // Wait for the login form to be fully rendered.
  await page.waitForSelector('form', { state: 'visible' });

  const results = await new AxeBuilder({ page })
    .include('body')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();

  const seriousOrCritical = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );

  expect(
    seriousOrCritical,
    `Found ${seriousOrCritical.length} serious/critical axe violations on / (redirected to /login after logout):\n` +
      seriousOrCritical
        .map((v) => `  [${v.impact}] ${v.id}: ${v.description}`)
        .join('\n'),
  ).toHaveLength(0);
});
