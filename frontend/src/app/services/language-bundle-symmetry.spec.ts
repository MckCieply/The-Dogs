import { describe, it, expect } from 'vitest';
import plBundle from '../../../public/assets/i18n/pl.json';
import enBundle from '../../../public/assets/i18n/en.json';

function extractKeys(obj: Record<string, unknown>, prefix = ''): string[] {
  return Object.keys(obj).flatMap((k) => {
    const full = prefix ? `${prefix}.${k}` : k;
    const val = obj[k];
    return typeof val === 'object' && val !== null
      ? extractKeys(val as Record<string, unknown>, full)
      : [full];
  });
}

function getNestedValue(
  obj: Record<string, unknown>,
  dotPath: string,
): unknown {
  return dotPath
    .split('.')
    .reduce<unknown>((acc, segment) => {
      if (acc !== null && typeof acc === 'object') {
        return (acc as Record<string, unknown>)[segment];
      }
      return undefined;
    }, obj);
}

describe('i18n bundle symmetry (AC-9)', () => {
  it('pl.json and en.json have identical key structure', () => {
    const plKeys = extractKeys(plBundle as unknown as Record<string, unknown>).sort();
    const enKeys = extractKeys(enBundle as unknown as Record<string, unknown>).sort();
    expect(plKeys).toEqual(enKeys);
  });
});

/**
 * shell.language_switcher labels — AC-11 depends on these being non-empty strings
 * because they supply aria-label text on the group element and visible button text.
 * An empty or missing value would create unlabelled interactive controls.
 */
describe('i18n bundle shell.language_switcher labels (AC-11 prerequisite)', () => {
  it('shell.language_switcher.label is a non-empty string in pl.json', () => {
    const value = getNestedValue(
      plBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.label',
    );
    expect(typeof value).toBe('string');
    expect((value as string).length).toBeGreaterThan(0);
  });

  it('shell.language_switcher.label is a non-empty string in en.json', () => {
    const value = getNestedValue(
      enBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.label',
    );
    expect(typeof value).toBe('string');
    expect((value as string).length).toBeGreaterThan(0);
  });

  it('shell.language_switcher.pl is a non-empty string in both bundles', () => {
    const plValue = getNestedValue(
      plBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.pl',
    );
    const enValue = getNestedValue(
      enBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.pl',
    );
    expect(typeof plValue).toBe('string');
    expect((plValue as string).length).toBeGreaterThan(0);
    expect(typeof enValue).toBe('string');
    expect((enValue as string).length).toBeGreaterThan(0);
  });

  it('shell.language_switcher.en is a non-empty string in both bundles', () => {
    const plValue = getNestedValue(
      plBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.en',
    );
    const enValue = getNestedValue(
      enBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.en',
    );
    expect(typeof plValue).toBe('string');
    expect((plValue as string).length).toBeGreaterThan(0);
    expect(typeof enValue).toBe('string');
    expect((enValue as string).length).toBeGreaterThan(0);
  });

  // The PL bundle's "pl" label is the user-visible button text when Polish is active.
  // Verify it carries the expected human-readable value from the spec.
  it('shell.language_switcher.pl renders "Polski" in pl.json', () => {
    const value = getNestedValue(
      plBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.pl',
    );
    expect(value).toBe('Polski');
  });

  // The EN bundle's "en" label is the user-visible button text when English is active.
  it('shell.language_switcher.en renders "English" in en.json', () => {
    const value = getNestedValue(
      enBundle as unknown as Record<string, unknown>,
      'shell.language_switcher.en',
    );
    expect(value).toBe('English');
  });
});

describe('i18n bundle required keys (AC-7)', () => {
  it('errors.codes.unknown exists and is a non-empty string in en.json', () => {
    const value = getNestedValue(
      enBundle as unknown as Record<string, unknown>,
      'errors.codes.unknown',
    );
    expect(typeof value).toBe('string');
    expect((value as string).length).toBeGreaterThan(0);
  });

  it('errors.codes.unknown exists and is a non-empty string in pl.json', () => {
    const value = getNestedValue(
      plBundle as unknown as Record<string, unknown>,
      'errors.codes.unknown',
    );
    expect(typeof value).toBe('string');
    expect((value as string).length).toBeGreaterThan(0);
  });

  // AC-7: backend errors[].code = "unauthorized" must render the exact copy
  // strings defined in the spec — "Brak autoryzacji" (PL) and "Unauthorized" (EN).
  it('errors.codes.unauthorized translates to "Brak autoryzacji" in pl.json (AC-7)', () => {
    const value = getNestedValue(
      plBundle as unknown as Record<string, unknown>,
      'errors.codes.unauthorized',
    );
    expect(value).toBe('Brak autoryzacji');
  });

  it('errors.codes.unauthorized translates to "Unauthorized" in en.json (AC-7)', () => {
    const value = getNestedValue(
      enBundle as unknown as Record<string, unknown>,
      'errors.codes.unauthorized',
    );
    expect(value).toBe('Unauthorized');
  });

  // AC-7 completeness: every errors.codes key present in the spec must exist
  // in both bundles so the toast pipeline never falls through to the handler.
  it.each([
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
  ])('errors.codes.%s is a non-empty string in both bundles (AC-7 completeness)', (code) => {
    const plValue = getNestedValue(
      plBundle as unknown as Record<string, unknown>,
      `errors.codes.${code}`,
    );
    const enValue = getNestedValue(
      enBundle as unknown as Record<string, unknown>,
      `errors.codes.${code}`,
    );
    expect(typeof plValue, `pl.json missing errors.codes.${code}`).toBe('string');
    expect((plValue as string).length, `pl.json errors.codes.${code} is empty`).toBeGreaterThan(0);
    expect(typeof enValue, `en.json missing errors.codes.${code}`).toBe('string');
    expect((enValue as string).length, `en.json errors.codes.${code} is empty`).toBeGreaterThan(0);
  });
});
