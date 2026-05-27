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
 *
 * ---------------------------------------------------------------------------
 * AC-10 NOTE (Lighthouse — CI gate, not covered by automated test here):
 * ---------------------------------------------------------------------------
 * AC-10 requires Lighthouse PWA ≥ 90 and Performance ≥ 80 on the shell route
 * after adding the i18n runtime. This is enforced as a Lighthouse CI step in
 * .github/workflows/ci-frontend.yml and is NOT replicated as a Playwright
 * test because Lighthouse requires a production build + stable server, which
 * is out of scope for the dev-server-backed e2e suite. The structural
 * underpinning (i18n bundles cached as a lazy SW asset group) is asserted in
 * frontend/src/app/i18n/bundle-lazy-load.spec.ts.
 *
 * ---------------------------------------------------------------------------
 * AC-12 NOTE (lint + test + build all exit 0 — process gate, not a test):
 * ---------------------------------------------------------------------------
 * AC-12 is verified by running `npm run lint && npm test -- --run &&
 * npm run build` in CI (ci-frontend.yml). No automated test is added here
 * because it is a meta-assertion on the process, not a behavioural assertion.
 * ---------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------
// AC-2 / AC-3 / AC-4 — First-visit browser-language detection
//
// Playwright lets us override navigator.language via the locale context
// option. The LanguageService reads navigator.language on first visit when
// no localStorage preference is stored.
// ---------------------------------------------------------------------------

test.describe('I18N — first visit browser-language detection (AC-2 / AC-3 / AC-4)', () => {
  // -------------------------------------------------------------------------
  // AC-2: pl-PL browser → Polish UI
  // -------------------------------------------------------------------------

  test('first visit with navigator.language pl-PL shows Polish (AC-2)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({
      locale: 'pl-PL',
      storageState: { cookies: [], origins: [] }, // no stored prefs
    });
    const page = await ctx.newPage();

    await page.goto('/login');

    // The PL button's translated label in Polish mode is "Polski"
    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polski');
    // html[lang] must be "pl"
    await expect(page.locator('html')).toHaveAttribute('lang', 'pl');

    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // AC-3: en-GB browser → English UI
  // -------------------------------------------------------------------------

  test('first visit with navigator.language en-GB shows English (AC-3)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({
      locale: 'en-GB',
      storageState: { cookies: [], origins: [] },
    });
    const page = await ctx.newPage();

    await page.goto('/login');

    // The PL button's translated label in English mode is "Polish"
    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polish');
    // html[lang] must be "en"
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');

    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // AC-4: de-DE browser → falls back to Polish
  // -------------------------------------------------------------------------

  test('first visit with navigator.language de-DE falls back to Polish (AC-4)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({
      locale: 'de-DE',
      storageState: { cookies: [], origins: [] },
    });
    const page = await ctx.newPage();

    await page.goto('/login');

    // Fallback to Polish: PL button label is "Polski", html[lang] is "pl"
    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polski');
    await expect(page.locator('html')).toHaveAttribute('lang', 'pl');

    await ctx.close();
  });
});

// ---------------------------------------------------------------------------
// AC-5 (extended) — localStorage value persists and is read back on reload
//
// The unit tests assert setItem is called. This e2e test asserts the full
// round-trip: the value written to localStorage is read by LanguageService
// on a fresh page load, producing the correct UI state.
// ---------------------------------------------------------------------------

test.describe('I18N — localStorage round-trip persistence (AC-5 extended)', () => {
  test('language pre-stored as "en" in localStorage is applied on cold load without any toggle click', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const page = await ctx.newPage();

    // Seed localStorage before the Angular app boots
    await page.addInitScript(() => {
      localStorage.setItem('thedogs.language', 'en');
    });

    await page.goto('/login');

    // Without clicking any button the UI should already be in English
    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polish');
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');

    await ctx.close();
  });

  test('language pre-stored as "pl" in localStorage overrides an en-GB browser language', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({
      locale: 'en-GB',
      storageState: { cookies: [], origins: [] },
    });
    const page = await ctx.newPage();

    // Stored preference is "pl" — should win over the en-GB browser locale
    await page.addInitScript(() => {
      localStorage.setItem('thedogs.language', 'pl');
    });

    await page.goto('/login');

    const plButton = page.locator('button[aria-pressed]').nth(0);
    await expect(plButton).toHaveText('Polski');
    await expect(page.locator('html')).toHaveAttribute('lang', 'pl');

    await ctx.close();
  });
});
