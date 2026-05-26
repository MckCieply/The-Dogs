import { TestBed } from '@angular/core/testing';
import { TranslateLoader, provideTranslateService } from '@ngx-translate/core';
import { DOCUMENT } from '@angular/common';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { of } from 'rxjs';
import { LanguageService } from './language.service';
import { App } from '../app';
import { provideRouter } from '@angular/router';

const STORAGE_KEY = 'thedogs.language';

const noopLoader: TranslateLoader = { getTranslation: () => of({}) };

function buildTestBed(): void {
  TestBed.configureTestingModule({
    providers: [
      provideTranslateService({
        fallbackLang: 'pl',
        loader: { provide: TranslateLoader, useValue: noopLoader },
      }),
      LanguageService,
    ],
  });
}

describe('LanguageService', () => {
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('reads stored language from localStorage on init (AC-2)', () => {
    localStorage.setItem(STORAGE_KEY, 'pl');
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'en-GB' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);

    expect(service.language()).toBe('pl');
  });

  it('falls back to pl when navigator.language is pl-PL and no localStorage value (AC-2)', () => {
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'pl-PL' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);

    expect(service.language()).toBe('pl');
  });

  it('resolves en when navigator.language is en-GB and no localStorage value (AC-3)', () => {
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'en-GB' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);

    expect(service.language()).toBe('en');
  });

  it('defaults to pl when navigator.language is an unsupported locale (AC-4)', () => {
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'de-DE' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);

    expect(service.language()).toBe('pl');
  });

  it('setLanguage persists the chosen language to localStorage and updates the signal (AC-5)', () => {
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'pl-PL' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);

    service.setLanguage('en');

    expect(localStorage.getItem(STORAGE_KEY)).toBe('en');
    expect(service.language()).toBe('en');
  });

  it('language set by setLanguage survives a service re-instantiation (AC-5 reload)', () => {
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'pl-PL' });

    buildTestBed();
    const first = TestBed.inject(LanguageService);
    first.setLanguage('en');

    TestBed.resetTestingModule();
    buildTestBed();
    const second = TestBed.inject(LanguageService);

    expect(second.language()).toBe('en');
  });

  it('ignores a corrupted/unrecognised localStorage value and falls back to navigator detection (AC-2/AC-5 defensive guard)', () => {
    // Simulate a stale or attacker-injected value that is neither "pl" nor "en"
    localStorage.setItem(STORAGE_KEY, 'xss<script>');
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'en-GB' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);

    // The stored garbage must be ignored; navigator says en-GB so result is 'en'
    expect(service.language()).toBe('en');
  });

  it('falls back to "pl" when navigator.language is an empty string and nothing is stored (AC-4 edge case)', () => {
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => '' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);

    expect(service.language()).toBe('pl');
  });

  it('setLanguage("pl") overwrites a previously stored "en" value in localStorage (AC-5 round-trip)', () => {
    localStorage.setItem(STORAGE_KEY, 'en');
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'en-GB' });

    buildTestBed();
    const service = TestBed.inject(LanguageService);
    expect(service.language()).toBe('en');

    service.setLanguage('pl');

    expect(localStorage.getItem(STORAGE_KEY)).toBe('pl');
    expect(service.language()).toBe('pl');
  });
});

describe('App — html lang binding (AC-6)', () => {
  beforeEach(() => {
    localStorage.clear();
    Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'pl-PL' });
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('sets document.documentElement.lang to the current language signal value', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        provideTranslateService({
          fallbackLang: 'pl',
          loader: { provide: TranslateLoader, useValue: noopLoader },
        }),
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const doc = TestBed.inject(DOCUMENT);
    expect(doc.documentElement.lang).toBe('pl');
  });

  it('updates document.documentElement.lang when language is switched to en', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        provideTranslateService({
          fallbackLang: 'pl',
          loader: { provide: TranslateLoader, useValue: noopLoader },
        }),
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const langService = TestBed.inject(LanguageService);
    langService.setLanguage('en');
    fixture.detectChanges();

    const doc = TestBed.inject(DOCUMENT);
    expect(doc.documentElement.lang).toBe('en');
  });
});
