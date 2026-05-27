/**
 * AC-7 / AC-8 guard: verify that known error-code keys resolve to the correct
 * translated strings in both locale bundles.
 *
 * The bundles are read directly from the filesystem (Node.js `fs` — available
 * in Vitest's jsdom environment) so there is no HTTP layer or Angular DI
 * required. This keeps the tests fast and independent of the Angular runtime
 * while still asserting the real production asset content.
 */
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { describe, it, expect } from 'vitest';

// ---------------------------------------------------------------------------
// Load bundles from disk
// ---------------------------------------------------------------------------
// __dirname is: frontend/src/app/i18n/
// Bundles live at: frontend/public/assets/i18n/
const bundleDir = resolve(__dirname, '../../../public/assets/i18n');

interface ErrorCodes {
  unknown: string;
  unauthorized: string;
  token_expired: string;
  invalid_token: string;
  forbidden: string;
  not_found: string;
  field_required: string;
  field_too_short: string;
  field_too_long: string;
  field_invalid_format: string;
}

interface Bundle {
  errors: {
    codes: ErrorCodes;
  };
}

const pl = JSON.parse(readFileSync(resolve(bundleDir, 'pl.json'), 'utf-8')) as Bundle;
const en = JSON.parse(readFileSync(resolve(bundleDir, 'en.json'), 'utf-8')) as Bundle;

// ---------------------------------------------------------------------------
// AC-7: known error-code translations have correct values in both bundles
// ---------------------------------------------------------------------------

describe('error-code translations — pl.json (AC-7)', () => {
  it('errors.codes.unauthorized translates to "Brak autoryzacji" in Polish', () => {
    expect(pl.errors.codes.unauthorized).toBe('Brak autoryzacji');
  });

  it('errors.codes.unknown translates to "Wystąpił błąd" in Polish', () => {
    expect(pl.errors.codes.unknown).toBe('Wystąpił błąd');
  });

  it('errors.codes.token_expired translates to "Sesja wygasła" in Polish', () => {
    expect(pl.errors.codes.token_expired).toBe('Sesja wygasła');
  });

  it('errors.codes.forbidden translates to "Brak dostępu" in Polish', () => {
    expect(pl.errors.codes.forbidden).toBe('Brak dostępu');
  });

  it('errors.codes.not_found translates to "Nie znaleziono" in Polish', () => {
    expect(pl.errors.codes.not_found).toBe('Nie znaleziono');
  });
});

describe('error-code translations — en.json (AC-7)', () => {
  it('errors.codes.unauthorized translates to "Unauthorized" in English', () => {
    expect(en.errors.codes.unauthorized).toBe('Unauthorized');
  });

  it('errors.codes.unknown translates to "Something went wrong" in English', () => {
    expect(en.errors.codes.unknown).toBe('Something went wrong');
  });

  it('errors.codes.token_expired translates to "Session expired" in English', () => {
    expect(en.errors.codes.token_expired).toBe('Session expired');
  });

  it('errors.codes.forbidden translates to "Access denied" in English', () => {
    expect(en.errors.codes.forbidden).toBe('Access denied');
  });

  it('errors.codes.not_found translates to "Not found" in English', () => {
    expect(en.errors.codes.not_found).toBe('Not found');
  });
});

// ---------------------------------------------------------------------------
// AC-8 fallback guard: errors.codes.unknown must exist and be non-empty in
// both bundles — the CustomMissingTranslationHandler delegates to this key.
// If it were missing or empty the fallback itself would recurse.
// ---------------------------------------------------------------------------

describe('errors.codes.unknown fallback guard (AC-8)', () => {
  it('pl.json errors.codes.unknown is a non-empty string', () => {
    expect(typeof pl.errors.codes.unknown).toBe('string');
    expect(pl.errors.codes.unknown.trim().length).toBeGreaterThan(0);
  });

  it('en.json errors.codes.unknown is a non-empty string', () => {
    expect(typeof en.errors.codes.unknown).toBe('string');
    expect(en.errors.codes.unknown.trim().length).toBeGreaterThan(0);
  });

  it('pl.json and en.json errors.codes.unknown values are distinct (not accidentally identical)', () => {
    // Both bundles carry different translations — this guards against copy-paste
    // where the Polish value was never translated.
    expect(pl.errors.codes.unknown).not.toBe(en.errors.codes.unknown);
  });
});

// ---------------------------------------------------------------------------
// Structural guard: every defined error-code key has a non-empty value
// in both bundles (catches future keys added with placeholder text)
// ---------------------------------------------------------------------------

describe('all error-code keys are non-empty in both bundles', () => {
  const errorCodeKeys: (keyof ErrorCodes)[] = [
    'unknown',
    'unauthorized',
    'token_expired',
    'invalid_token',
    'forbidden',
    'not_found',
    'field_required',
    'field_too_short',
    'field_too_long',
    'field_invalid_format',
  ];

  for (const key of errorCodeKeys) {
    it(`pl.json errors.codes.${key} is a non-empty string`, () => {
      const value = pl.errors.codes[key];
      expect(typeof value, `pl.json errors.codes.${key} should be a string`).toBe('string');
      expect(
        (value as string).trim().length,
        `pl.json errors.codes.${key} should not be empty`,
      ).toBeGreaterThan(0);
    });

    it(`en.json errors.codes.${key} is a non-empty string`, () => {
      const value = en.errors.codes[key];
      expect(typeof value, `en.json errors.codes.${key} should be a string`).toBe('string');
      expect(
        (value as string).trim().length,
        `en.json errors.codes.${key} should not be empty`,
      ).toBeGreaterThan(0);
    });
  }
});
