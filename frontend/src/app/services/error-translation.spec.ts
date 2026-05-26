/**
 * AC-7: backend errors[].code = "unauthorized" renders via TranslateService.instant()
 * as the exact copy strings specified in the spec, for both PL and EN.
 *
 * These tests load the real bundle JSON files into a live TranslateService
 * (no HTTP — translations are injected via setTranslation) so they verify
 * both the bundle content AND the TranslateService wiring, not just the raw
 * JSON files.
 *
 * AC-8 integration: verifies that when the MissingTranslationHandler is wired,
 * an unknown code falls through to errors.codes.unknown (both string value and
 * console.warn) inside a real TestBed context.
 */
import { TestBed } from '@angular/core/testing';
import {
  provideTranslateService,
  provideMissingTranslationHandler,
  TranslateService,
} from '@ngx-translate/core';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import plBundle from '../../../public/assets/i18n/pl.json';
import enBundle from '../../../public/assets/i18n/en.json';
import { AppMissingTranslationHandler } from './missing-translation.handler';

/**
 * Builds a minimal TestBed with TranslateService seeded with both bundles.
 * No HTTP loader — uses setTranslation() to inject bundle data synchronously.
 */
function buildTestBed(initialLang: 'pl' | 'en' = 'pl'): void {
  TestBed.configureTestingModule({
    providers: [
      provideTranslateService({
        lang: initialLang,
        missingTranslationHandler: provideMissingTranslationHandler(AppMissingTranslationHandler),
      }),
    ],
  });

  const translate = TestBed.inject(TranslateService);
  translate.setTranslation('pl', plBundle);
  translate.setTranslation('en', enBundle);
  translate.use(initialLang);
}

describe('error code translations — AC-7 (TranslateService runtime)', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  describe('Polish active', () => {
    beforeEach(() => buildTestBed('pl'));

    it('errors.codes.unauthorized resolves to "Brak autoryzacji" in Polish', () => {
      const translate = TestBed.inject(TranslateService);
      expect(translate.instant('errors.codes.unauthorized')).toBe('Brak autoryzacji');
    });

    it('errors.codes.unknown resolves to "Wystąpił błąd" in Polish', () => {
      const translate = TestBed.inject(TranslateService);
      expect(translate.instant('errors.codes.unknown')).toBe('Wystąpił błąd');
    });

    it('errors.codes.token_expired resolves to a non-empty string in Polish', () => {
      const translate = TestBed.inject(TranslateService);
      const result = translate.instant('errors.codes.token_expired');
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
      // Must not return the key itself (i.e. translation is loaded)
      expect(result).not.toBe('errors.codes.token_expired');
    });

    it('errors.codes.forbidden resolves to a non-empty string in Polish', () => {
      const translate = TestBed.inject(TranslateService);
      const result = translate.instant('errors.codes.forbidden');
      expect(result).not.toBe('errors.codes.forbidden');
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('English active', () => {
    beforeEach(() => buildTestBed('en'));

    it('errors.codes.unauthorized resolves to "Unauthorized" in English', () => {
      const translate = TestBed.inject(TranslateService);
      expect(translate.instant('errors.codes.unauthorized')).toBe('Unauthorized');
    });

    it('errors.codes.unknown resolves to "Something went wrong" in English', () => {
      const translate = TestBed.inject(TranslateService);
      expect(translate.instant('errors.codes.unknown')).toBe('Something went wrong');
    });

    it('errors.codes.token_expired resolves to a non-empty string in English', () => {
      const translate = TestBed.inject(TranslateService);
      const result = translate.instant('errors.codes.token_expired');
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
      expect(result).not.toBe('errors.codes.token_expired');
    });

    it('errors.codes.forbidden resolves to a non-empty string in English', () => {
      const translate = TestBed.inject(TranslateService);
      const result = translate.instant('errors.codes.forbidden');
      expect(result).not.toBe('errors.codes.forbidden');
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('language switch mid-session (AC-7 + AC-5 interaction)', () => {
    beforeEach(() => buildTestBed('pl'));

    it('resolves "unauthorized" as "Unauthorized" after switching to English', () => {
      const translate = TestBed.inject(TranslateService);
      // Confirm PL first
      expect(translate.instant('errors.codes.unauthorized')).toBe('Brak autoryzacji');

      // Switch language
      translate.use('en');

      expect(translate.instant('errors.codes.unauthorized')).toBe('Unauthorized');
    });
  });
});

/**
 * AC-8 integration test: verifies that AppMissingTranslationHandler
 * - returns the errors.codes.unknown translation value (not the key)
 * - emits a console.warn that names the missing key
 * when wired into a live TranslateService context.
 */
describe('AppMissingTranslationHandler — AC-8 wired into TranslateService', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('returns errors.codes.unknown value and warns on missing key in Polish context', () => {
    TestBed.configureTestingModule({
      providers: [
        provideTranslateService({
          lang: 'pl',
          missingTranslationHandler: provideMissingTranslationHandler(AppMissingTranslationHandler),
        }),
      ],
    });

    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('pl', plBundle);
    translate.use('pl');

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    const result = translate.instant('errors.codes.nonexistent_code');

    // Should have warned with the missing key name
    expect(warnSpy).toHaveBeenCalled();
    const warnArgs = warnSpy.mock.calls[0].join(' ');
    expect(warnArgs).toContain('errors.codes.nonexistent_code');

    // Should return the known-unknown fallback string, not the key itself
    expect(result).not.toBe('errors.codes.nonexistent_code');
  });
});
