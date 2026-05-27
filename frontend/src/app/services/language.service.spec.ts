import { TestBed } from '@angular/core/testing';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { TranslateService } from '@ngx-translate/core';
import { EMPTY } from 'rxjs';
import { LanguageService } from './language.service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function buildTranslateServiceMock() {
  return {
    use: vi.fn().mockReturnValue(EMPTY),
    instant: vi.fn((key: string) => key),
    get: vi.fn().mockReturnValue(EMPTY),
    onLangChange: { subscribe: vi.fn() },
    onTranslationChange: { subscribe: vi.fn() },
    onDefaultLangChange: { subscribe: vi.fn() },
  };
}

const STORAGE_KEY = 'thedogs.language';

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

describe('LanguageService', () => {
  let service: LanguageService;
  let translateMock: ReturnType<typeof buildTranslateServiceMock>;

  // Preserve originals so we can restore them after every test
  const originalDocLang = document.documentElement.lang;

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    document.documentElement.lang = originalDocLang;
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  function createService(
    localStorageValue: string | null,
    navigatorLanguage: string,
  ): void {
    // Stub localStorage.getItem before the service is constructed
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation((key: string) =>
      key === STORAGE_KEY ? localStorageValue : null,
    );
    vi.spyOn(Storage.prototype, 'setItem');

    // Stub navigator.language
    vi.stubGlobal('navigator', {
      ...navigator,
      language: navigatorLanguage,
    });

    translateMock = buildTranslateServiceMock();

    TestBed.configureTestingModule({
      providers: [
        LanguageService,
        { provide: TranslateService, useValue: translateMock },
      ],
    });

    service = TestBed.inject(LanguageService);
  }

  // -------------------------------------------------------------------------
  // Initialization — stored language
  // -------------------------------------------------------------------------

  it('initializes with stored language "en" when localStorage returns "en"', () => {
    createService('en', 'pl');

    expect(service.language()).toBe('en');
  });

  it('sets document.documentElement.lang to "en" when stored language is "en"', () => {
    createService('en', 'pl');

    expect(document.documentElement.lang).toBe('en');
  });

  it('calls translate.use("en") during initialization when stored language is "en"', () => {
    createService('en', 'pl');

    expect(translateMock.use).toHaveBeenCalledWith('en');
  });

  it('initializes with stored language "pl" when localStorage returns "pl"', () => {
    createService('pl', 'en');

    expect(service.language()).toBe('pl');
  });

  // -------------------------------------------------------------------------
  // Initialization — invalid localStorage value (security guard)
  // -------------------------------------------------------------------------

  it('falls back to browser language when localStorage contains an invalid value (e.g. "<script>")', () => {
    createService('<script>alert(1)</script>', 'en-US');

    expect(service.language()).toBe('en');
  });

  it('falls back to browser language when localStorage contains an arbitrary string', () => {
    createService('de', 'pl-PL');

    expect(service.language()).toBe('pl');
  });

  it('falls back to project default "pl" when localStorage contains an invalid value and browser lang is unknown', () => {
    createService('fr', 'de-DE');

    expect(service.language()).toBe('pl');
  });

  // -------------------------------------------------------------------------
  // Initialization — browser language fallback (no localStorage)
  // -------------------------------------------------------------------------

  it('falls back to "pl" when navigator.language is "pl-PL" and nothing is stored (AC-2)', () => {
    createService(null, 'pl-PL');

    expect(service.language()).toBe('pl');
  });

  it('falls back to "en" when navigator.language is "en-GB" and nothing is stored (AC-3)', () => {
    createService(null, 'en-GB');

    expect(service.language()).toBe('en');
  });

  it('falls back to project default "pl" when navigator.language is "de-DE" and nothing is stored (AC-4)', () => {
    createService(null, 'de-DE');

    expect(service.language()).toBe('pl');
  });

  // -------------------------------------------------------------------------
  // setLanguage — persistence
  // -------------------------------------------------------------------------

  it('setLanguage("en") writes "en" to localStorage under the correct key (AC-5)', () => {
    createService(null, 'pl');
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem');

    service.setLanguage('en');

    expect(setItemSpy).toHaveBeenCalledWith(STORAGE_KEY, 'en');
  });

  it('setLanguage("pl") writes "pl" to localStorage under the correct key (AC-5)', () => {
    createService(null, 'en-GB');
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem');

    service.setLanguage('pl');

    expect(setItemSpy).toHaveBeenCalledWith(STORAGE_KEY, 'pl');
  });

  // -------------------------------------------------------------------------
  // setLanguage — signal update
  // -------------------------------------------------------------------------

  it('setLanguage("en") updates the language signal to "en"', () => {
    createService(null, 'pl');

    service.setLanguage('en');

    expect(service.language()).toBe('en');
  });

  it('setLanguage("pl") updates the language signal to "pl"', () => {
    createService(null, 'en-GB');

    service.setLanguage('pl');

    expect(service.language()).toBe('pl');
  });

  // -------------------------------------------------------------------------
  // setLanguage — DOM + TranslateService
  // -------------------------------------------------------------------------

  it('setLanguage("en") updates document.documentElement.lang to "en" (AC-6)', () => {
    createService(null, 'pl');

    service.setLanguage('en');

    expect(document.documentElement.lang).toBe('en');
  });

  it('setLanguage("pl") updates document.documentElement.lang to "pl" (AC-6)', () => {
    createService(null, 'en-GB');

    service.setLanguage('pl');

    expect(document.documentElement.lang).toBe('pl');
  });

  it('setLanguage("en") calls translate.use("en")', () => {
    createService(null, 'pl');
    // Reset so we only observe the call made by setLanguage, not by init()
    translateMock.use.mockClear();

    service.setLanguage('en');

    expect(translateMock.use).toHaveBeenCalledWith('en');
    expect(translateMock.use).toHaveBeenCalledTimes(1);
  });

  it('setLanguage("pl") calls translate.use("pl")', () => {
    createService(null, 'en-GB');
    translateMock.use.mockClear();

    service.setLanguage('pl');

    expect(translateMock.use).toHaveBeenCalledWith('pl');
    expect(translateMock.use).toHaveBeenCalledTimes(1);
  });
});
