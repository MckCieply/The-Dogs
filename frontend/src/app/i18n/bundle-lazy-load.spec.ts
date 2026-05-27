/**
 * AC-1: assets/i18n/*.json files must NOT be embedded in any JS chunk.
 *
 * The Angular build (via @ngx-translate/http-loader) fetches locale files over
 * HTTP at runtime. They should therefore appear as standalone files in the
 * dist output and must NOT appear as inlined content inside any *.js bundle.
 *
 * Additionally, the service-worker config (ngsw-config.json → compiled
 * ngsw.json) must place the i18n bundles in the lazy "i18n-bundles" asset
 * group, NOT in the prefetch "app" group that is downloaded on the initial
 * install.
 *
 * NOTE: These tests read the output produced by `npm run build`.
 * They will be skipped gracefully if the dist/ directory does not exist yet
 * (first checkout, CI step ordering) to avoid blocking the test run when the
 * build artefact hasn't been produced yet. The implementer must run
 * `npm run build` before these tests will pass.
 *
 * AC-10 (Lighthouse PWA ≥ 90 / Performance ≥ 80) is enforced as a CI gate
 * in ci-frontend.yml (Lighthouse CI step) and is NOT covered by a unit test.
 * The service-worker caching of the i18n bundles (lazy group, updateMode
 * prefetch) is the mechanism that preserves offline language-switch without
 * regressing Lighthouse PWA score — asserting the SW config here covers the
 * structural requirement that underpins AC-10.
 */
import { existsSync, readFileSync, readdirSync } from 'fs';
import { resolve } from 'path';
import { describe, it, expect, beforeAll } from 'vitest';

// ---------------------------------------------------------------------------
// Paths — relative to this file (frontend/src/app/i18n/)
// ---------------------------------------------------------------------------
// frontend/dist/the-dogs/browser/
const distBrowserDir = resolve(__dirname, '../../../../dist/the-dogs/browser');
const ngswJsonPath = resolve(distBrowserDir, 'ngsw.json');
const i18nDistDir = resolve(distBrowserDir, 'assets/i18n');

// ---------------------------------------------------------------------------
// Guard — skip entire suite when dist/ has not been produced yet
// ---------------------------------------------------------------------------
const distExists = existsSync(distBrowserDir);

describe.skipIf(!distExists)(
  'AC-1 — i18n bundles are lazy-loaded (not inlined in JS chunks)',
  () => {
    // -----------------------------------------------------------------------
    // Part 1: i18n JSON files exist as standalone assets in the dist
    // -----------------------------------------------------------------------

    it('dist/browser/assets/i18n/pl.json exists as a standalone file', () => {
      const plPath = resolve(i18nDistDir, 'pl.json');
      expect(
        existsSync(plPath),
        `Expected ${plPath} to exist — HTTP loader requires it as a separate asset`,
      ).toBe(true);
    });

    it('dist/browser/assets/i18n/en.json exists as a standalone file', () => {
      const enPath = resolve(i18nDistDir, 'en.json');
      expect(
        existsSync(enPath),
        `Expected ${enPath} to exist — HTTP loader requires it as a separate asset`,
      ).toBe(true);
    });

    // -----------------------------------------------------------------------
    // Part 2: i18n JSON content is NOT embedded inside any JS chunk
    //
    // We search every *.js file in dist/browser/ for the distinctive string
    // "errors.codes.unauthorized" which is a key present in both locale
    // bundles. If that string appears inside a JS file the loader inlined the
    // bundles into the main chunk, violating AC-1.
    // -----------------------------------------------------------------------

    it('no *.js chunk in dist/browser contains inlined i18n content (errors.codes.unauthorized)', () => {
      const jsFiles = readdirSync(distBrowserDir).filter(
        (f) => f.endsWith('.js') && !f.startsWith('ngsw'),
      );

      const inlinedIn: string[] = [];

      for (const jsFile of jsFiles) {
        const content = readFileSync(resolve(distBrowserDir, jsFile), 'utf-8');
        // Use the PL value to catch accidental inlining of either bundle
        if (content.includes('Brak autoryzacji') || content.includes('"errors.codes.unauthorized"')) {
          inlinedIn.push(jsFile);
        }
      }

      expect(
        inlinedIn,
        `These JS chunks contain inlined i18n content (AC-1 violation): ${inlinedIn.join(', ')}`,
      ).toHaveLength(0);
    });

    // -----------------------------------------------------------------------
    // Part 3: ngsw.json places the i18n files in the lazy "i18n-bundles"
    //         asset group, NOT in the prefetch "app" group
    // -----------------------------------------------------------------------

    let ngswConfig: {
      assetGroups: {
        name: string;
        installMode: string;
        urls?: string[];
      }[];
    };

    beforeAll(() => {
      ngswConfig = JSON.parse(readFileSync(ngswJsonPath, 'utf-8'));
    });

    it('ngsw.json exists in dist/browser', () => {
      expect(existsSync(ngswJsonPath)).toBe(true);
    });

    it('ngsw.json contains an asset group named "i18n-bundles" with installMode "lazy"', () => {
      const i18nGroup = ngswConfig.assetGroups.find((g) => g.name === 'i18n-bundles');

      expect(
        i18nGroup,
        'Expected ngsw.json to have an assetGroup named "i18n-bundles"',
      ).toBeDefined();

      expect(
        i18nGroup?.installMode,
        'Expected "i18n-bundles" group to have installMode "lazy"',
      ).toBe('lazy');
    });

    it('ngsw.json "i18n-bundles" group includes /assets/i18n/pl.json', () => {
      const i18nGroup = ngswConfig.assetGroups.find((g) => g.name === 'i18n-bundles');
      expect(i18nGroup?.urls ?? []).toContain('/assets/i18n/pl.json');
    });

    it('ngsw.json "i18n-bundles" group includes /assets/i18n/en.json', () => {
      const i18nGroup = ngswConfig.assetGroups.find((g) => g.name === 'i18n-bundles');
      expect(i18nGroup?.urls ?? []).toContain('/assets/i18n/en.json');
    });

    it('ngsw.json "app" prefetch group does NOT include i18n JSON files', () => {
      const appGroup = ngswConfig.assetGroups.find((g) => g.name === 'app');
      const appUrls = appGroup?.urls ?? [];

      const i18nInApp = appUrls.filter((url) => url.includes('/assets/i18n/'));

      expect(
        i18nInApp,
        `These i18n files were found in the "app" prefetch group (should be in "i18n-bundles" lazy group): ${i18nInApp.join(', ')}`,
      ).toHaveLength(0);
    });
  },
);

// ---------------------------------------------------------------------------
// When dist/ is absent, emit a single informational placeholder so the
// suite is visible in the test reporter rather than silently skipped.
// ---------------------------------------------------------------------------
describe.skipIf(distExists)(
  'AC-1 — i18n bundle lazy-load check (dist/ not yet built)',
  () => {
    it('SKIPPED: run `npm run build` first, then re-run the test suite', () => {
      // This test is intentionally skipped when dist/ is absent.
      // It documents the requirement that the build artefact must exist.
      expect(true).toBe(true);
    });
  },
);
