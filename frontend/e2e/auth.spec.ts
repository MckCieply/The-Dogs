import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * Auth e2e smoke tests.
 *
 * Tests that rely on a running backend are marked with test.skip and stubbed —
 * the backend isn't running in CI for this scaffold stage.
 * They will be enabled in a follow-up task once the full Docker stack is up.
 */

test.describe('Auth — unauthenticated redirect', () => {
  test('visiting / redirects to /login when unauthenticated', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login/);
  });

  test('login page renders email and password fields', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('input[type="email"]')).toBeVisible();
    // PrimeNG Password wraps the input — check by id
    await expect(page.locator('#password')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
  });

  test('login page has page title', async ({ page }) => {
    await page.goto('/login');
    await expect(page).toHaveTitle(/Sign in/i);
  });

  test('login page passes axe accessibility audit', async ({ page }) => {
    await page.goto('/login');
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze();
    // Log violations for debugging without failing hard (critical/serious thresholds apply)
    const critical = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    );
    expect(critical, `Critical/serious axe violations:\n${JSON.stringify(critical, null, 2)}`).toHaveLength(0);
  });

  test('submitting empty form shows validation messages', async ({ page }) => {
    await page.goto('/login');
    await page.locator('button[type="submit"]').click();
    // Validation messages should appear
    await expect(page.locator('[role="alert"]').first()).toBeVisible();
  });
});

test.describe('Auth — successful login flow (stubbed — backend required)', () => {
  test.skip('logs in and redirects to home page', async ({ page }) => {
    /**
     * This test requires a running backend at localhost:4200 → proxy → localhost:8080.
     * Enable and fill in credentials once the full stack is up.
     */
    await page.goto('/login');
    await page.locator('input[type="email"]').fill('trainer@example.com');
    await page.locator('#password').fill('Password123!');
    await page.locator('button[type="submit"]').click();
    await expect(page).toHaveURL('/');
    await expect(page.locator('text=Welcome')).toBeVisible();
  });

  test.skip('logout button clears session and redirects to /login', async ({ page }) => {
    // Precondition: logged in (requires backend)
    await page.goto('/');
    await page.locator('button[aria-label="Log out"]').click();
    await expect(page).toHaveURL(/\/login/);
  });
});
