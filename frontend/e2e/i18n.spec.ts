import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * i18n e2e smoke tests for I18N-01 (PL + EN, ngx-translate).
 *
 * Tests requiring an authenticated session are marked test.skip with a
 * linked issue comment. They will be enabled once Playwright auth fixtures
 * are wired up (pre-seeded localStorage token or cookie injection).
 */

test.describe('i18n — html lang attribute', () => {
  test('document.documentElement.lang is "pl" by default (no localStorage)', async ({ page }) => {
    await page.evaluate(() => localStorage.clear());

    await page.goto('/');

    // Wait for Angular to bootstrap — the redirect to /login confirms the app ran
    await page.waitForURL(/\/(login|$)/);

    const lang = await page.evaluate(() => document.documentElement.lang);
    expect(lang).toBe('pl');
  });

  /**
   * AC-5 + AC-6 e2e round-trip: a pre-seeded localStorage preference
   * (simulating the state after the user clicks the language toggle and reloads)
   * must survive the page load and drive html[lang] to the stored value.
   *
   * No auth is required because the Angular app sets html[lang] in the root App
   * component's constructor effect — this runs before the authGuard redirect.
   */
  test('html[lang] is "en" when localStorage.thedogs.language = "en" is pre-set before navigation (AC-5 + AC-6)', async ({ page }) => {
    // Navigate first so we have a page context to write into localStorage
    await page.goto('/');
    await page.waitForURL(/\/(login|$)/);

    // Seed the preference — mirrors what setLanguage() persists (AC-5)
    await page.evaluate(() => {
      localStorage.setItem('thedogs.language', 'en');
    });

    // Reload to simulate the "survives a page reload" requirement
    await page.reload();
    await page.waitForURL(/\/(login|$)/);

    const lang = await page.evaluate(() => document.documentElement.lang);
    expect(lang).toBe('en');
  });

  test('html[lang] is "pl" when localStorage.thedogs.language = "pl" is pre-set before navigation (AC-5 + AC-6)', async ({ page }) => {
    await page.goto('/');
    await page.waitForURL(/\/(login|$)/);

    await page.evaluate(() => {
      localStorage.setItem('thedogs.language', 'pl');
    });

    await page.reload();
    await page.waitForURL(/\/(login|$)/);

    const lang = await page.evaluate(() => document.documentElement.lang);
    expect(lang).toBe('pl');
  });
});

test.describe('i18n — language switcher visibility', () => {
  // The LanguageSwitcherComponent lives inside the home/shell route which
  // requires authentication. Without a pre-seeded auth token we cannot reach
  // it in e2e, so the active interaction test is skipped.
  // Issue: enable once Playwright auth state injection is set up.
  test.skip('language-switcher group is visible on the authenticated home route', async ({ page }) => {
    // Pre-condition: inject a valid JWT into localStorage to bypass authGuard.
    await page.goto('/');
    const switcher = page.locator('[role="group"]').filter({ hasText: /PL|EN|Polski|English/i });
    await expect(switcher).toBeVisible();
    const plBtn = switcher.locator('button').filter({ hasText: /PL|Polski/i });
    const enBtn = switcher.locator('button').filter({ hasText: /EN|English/i });
    await expect(plBtn).toBeVisible();
    await expect(enBtn).toBeVisible();
  });
});

test.describe('i18n — login page axe audit (wcag2a + wcag2aa)', () => {
  test('login page has zero critical/serious axe violations', async ({ page }) => {
    await page.goto('/login');

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze();

    const critical = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    );
    expect(
      critical,
      `Critical/serious axe violations on /login:\n${JSON.stringify(critical, null, 2)}`,
    ).toHaveLength(0);
  });
});

test.describe('i18n — keyboard and aria on language-switcher (AC-11)', () => {
  // Requires auth. Skipped until Playwright auth injection is configured.
  // Issue: link to auth-injection task before enabling.
  test.skip('PL/EN buttons have aria-pressed and are keyboard-reachable', async ({ page }) => {
    await page.goto('/');
    const switcher = page.locator('[role="group"]').filter({ hasText: /PL|EN|Polski|English/i });
    const plBtn = switcher.locator('button').filter({ hasText: /PL|Polski/i });

    await expect(plBtn).toHaveAttribute('aria-pressed', 'true');

    const enBtn = switcher.locator('button').filter({ hasText: /EN|English/i });
    await enBtn.focus();
    await enBtn.press('Enter');

    await expect(enBtn).toHaveAttribute('aria-pressed', 'true');
    await expect(plBtn).toHaveAttribute('aria-pressed', 'false');

    const lang = await page.evaluate(() => document.documentElement.lang);
    expect(lang).toBe('en');
  });
});
