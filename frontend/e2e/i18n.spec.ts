import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * I18N-01 e2e smoke tests — AC-11
 *
 * These tests drive the live Angular dev server. The language-switcher
 * component is mounted in app-root (outside the router-outlet), so it is
 * reachable on any route — including /login, which is where unauthenticated
 * visitors land. All assertions below therefore target /login so no
 * authentication stub is required.
 */

test.describe('I18N — language switcher smoke (AC-11)', () => {
  test.beforeEach(async ({ page }) => {
    // Clear persisted language so each test starts from a known state
    await page.addInitScript(() => {
      localStorage.removeItem('thedogs.language');
    });
    await page.goto('/login');
  });

  // -------------------------------------------------------------------------
  // Visibility
  // -------------------------------------------------------------------------

  test('language switcher group is visible on the login page', async ({ page }) => {
    const switcher = page.locator('[role="group"]').filter({
      has: page.locator('button', { hasText: /Polski|Polish/i }),
    });
    await expect(switcher).toBeVisible();
  });

  test('PL and EN buttons are both visible', async ({ page }) => {
    // Buttons rendered by LanguageSwitcherComponent via translate pipe.
    // In 'pl' mode (default) they render "Polski" and "English".
    // In 'en' mode they render "Polish" and "English".
    // We match on the aria-pressed attribute to locate them regardless of locale.
    const plButton = page.locator('button[aria-pressed]').nth(0);
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await expect(plButton).toBeVisible();
    await expect(enButton).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // Toggle behaviour — clicking EN changes button labels
  // -------------------------------------------------------------------------

  test('clicking the EN button switches button labels to English translations', async ({
    page,
  }) => {
    // Before click: default language is 'pl' → PL button shows "Polski"
    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polski');

    // Click the EN button (second aria-pressed button)
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await enButton.click();

    // After click: language is 'en' → PL button now shows "Polish"
    await expect(plButton).toHaveText('Polish');
  });

  test('clicking the PL button after EN restores Polish labels', async ({ page }) => {
    // Switch to English first
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await enButton.click();

    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polish');

    // Switch back to Polish
    await plButton.click();
    await expect(plButton).toHaveText('Polski');
  });

  // -------------------------------------------------------------------------
  // aria-pressed reflects the active language
  // -------------------------------------------------------------------------

  test('PL button has aria-pressed="true" when PL is active', async ({ page }) => {
    // Default language is pl
    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveAttribute('aria-pressed', 'true');
  });

  test('EN button has aria-pressed="true" after switching to EN', async ({ page }) => {
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await enButton.click();
    await expect(enButton).toHaveAttribute('aria-pressed', 'true');
  });

  test('PL button has aria-pressed="false" after switching to EN', async ({ page }) => {
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await enButton.click();
    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveAttribute('aria-pressed', 'false');
  });

  // -------------------------------------------------------------------------
  // Persistence — language survives page reload
  // -------------------------------------------------------------------------

  test('chosen language EN persists across a page reload', async ({ page }) => {
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await enButton.click();

    // Reload without clearing localStorage this time
    await page.reload();

    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polish');
  });

  // -------------------------------------------------------------------------
  // document.lang attribute reflects chosen language
  // -------------------------------------------------------------------------

  test('html[lang] is "pl" by default', async ({ page }) => {
    await expect(page.locator('html')).toHaveAttribute('lang', 'pl');
  });

  test('html[lang] becomes "en" after clicking EN button', async ({ page }) => {
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await enButton.click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  });

  // -------------------------------------------------------------------------
  // Accessibility — axe on the /login route with language switcher (AC-11)
  // -------------------------------------------------------------------------

  test('login page with language switcher in PL mode has zero serious/critical axe violations', async ({
    page,
  }) => {
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze();

    const critical = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    );
    expect(
      critical,
      `Critical/serious axe violations on /login (PL):\n${JSON.stringify(critical, null, 2)}`,
    ).toHaveLength(0);
  });

  test('login page with language switcher in EN mode has zero serious/critical axe violations', async ({
    page,
  }) => {
    // Switch to EN before running axe
    const enButton = page.locator('button[aria-pressed]').nth(1);
    await enButton.click();

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze();

    const critical = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    );
    expect(
      critical,
      `Critical/serious axe violations on /login (EN):\n${JSON.stringify(critical, null, 2)}`,
    ).toHaveLength(0);
  });
});
