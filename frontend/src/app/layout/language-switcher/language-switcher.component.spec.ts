import { TestBed } from '@angular/core/testing';
import { TranslateLoader, provideTranslateService } from '@ngx-translate/core';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as axe from 'axe-core';
import { of } from 'rxjs';
import { LanguageSwitcherComponent } from './language-switcher.component';
import { LanguageService } from '../../services/language.service';

/**
 * Unit tests for LanguageSwitcherComponent.
 *
 * AC-11: axe-core reports zero serious/critical violations on the rendered
 *        language-switcher element (jsdom environment, wcag2a + wcag2aa tags).
 *
 * Component-level DOM tests cover rendered button text, aria-pressed state,
 * and language-switch side-effects without requiring an authenticated session.
 *
 * A no-op TranslateLoader is used so no HTTP requests are made in jsdom.
 */
const noopLoader: TranslateLoader = { getTranslation: () => of({}) };

function buildTestBed(): void {
  TestBed.configureTestingModule({
    imports: [LanguageSwitcherComponent],
    providers: [
      provideTranslateService({
        fallbackLang: 'pl',
        loader: { provide: TranslateLoader, useValue: noopLoader },
      }),
      LanguageService,
    ],
  });
}

describe('LanguageSwitcherComponent — rendering', () => {
  beforeEach(() => {
    localStorage.clear();
    Object.defineProperty(navigator, 'language', {
      configurable: true,
      get: () => 'pl-PL',
    });
    buildTestBed();
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('renders a role="group" container element', () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement as HTMLElement;
    const group = el.querySelector('[role="group"]');
    expect(group).not.toBeNull();
  });

  it('renders exactly two buttons (PL and EN)', () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    expect(buttons).toHaveLength(2);
  });

  it('PL button has aria-pressed="true" when Polish is active', () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    // First button is PL
    expect(buttons[0].getAttribute('aria-pressed')).toBe('true');
    // Second button is EN
    expect(buttons[1].getAttribute('aria-pressed')).toBe('false');
  });

  it('EN button has aria-pressed="true" after setLanguage("en") is called', () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const langService = TestBed.inject(LanguageService);
    langService.setLanguage('en');
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    expect(buttons[0].getAttribute('aria-pressed')).toBe('false');
    expect(buttons[1].getAttribute('aria-pressed')).toBe('true');
  });

  it('clicking the EN button calls setLanguage("en") and updates the signal', () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const langService = TestBed.inject(LanguageService);
    const spy = vi.spyOn(langService, 'setLanguage');

    const el: HTMLElement = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    (buttons[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(spy).toHaveBeenCalledOnce();
    expect(spy).toHaveBeenCalledWith('en');
    expect(langService.language()).toBe('en');
  });

  it('clicking the PL button when EN is active calls setLanguage("pl")', () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const langService = TestBed.inject(LanguageService);
    langService.setLanguage('en');
    fixture.detectChanges();

    const spy = vi.spyOn(langService, 'setLanguage');

    const el: HTMLElement = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');
    (buttons[0] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(spy).toHaveBeenCalledOnce();
    expect(spy).toHaveBeenCalledWith('pl');
  });
});

/**
 * AC-11 keyboard accessibility: Tab-reachability.
 *
 * The component renders native <button type="button"> elements which are in the
 * natural tab order by default (tabIndex 0). Enter/Space activation is browser-
 * native for <button> elements (HTML spec §6.6.1) and is verified in the
 * Playwright e2e suite (frontend/e2e/i18n.spec.ts) — jsdom does not simulate the
 * native key-to-click mapping so those checks cannot run here.
 */
describe('LanguageSwitcherComponent — keyboard accessibility (AC-11)', () => {
  beforeEach(() => {
    localStorage.clear();
    Object.defineProperty(navigator, 'language', {
      configurable: true,
      get: () => 'pl-PL',
    });
    buildTestBed();
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('both buttons are in the natural tab order (tabIndex not -1)', () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button');

    expect((buttons[0] as HTMLButtonElement).tabIndex).toBeGreaterThanOrEqual(0);
    expect((buttons[1] as HTMLButtonElement).tabIndex).toBeGreaterThanOrEqual(0);
  });
});

describe('LanguageSwitcherComponent — AC-11 axe accessibility audit', () => {
  beforeEach(() => {
    localStorage.clear();
    Object.defineProperty(navigator, 'language', {
      configurable: true,
      get: () => 'pl-PL',
    });
    buildTestBed();
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('has zero serious/critical axe violations (wcag2a + wcag2aa) when Polish is active', async () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    // axe-core needs the element attached to the document body for full context
    document.body.appendChild(fixture.nativeElement as HTMLElement);

    try {
      const results = await axe.run(fixture.nativeElement as HTMLElement, {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] },
      });

      const critical = results.violations.filter(
        (v) => v.impact === 'critical' || v.impact === 'serious',
      );

      expect(
        critical,
        `Critical/serious axe violations on language-switcher:\n${JSON.stringify(critical, null, 2)}`,
      ).toHaveLength(0);
    } finally {
      document.body.removeChild(fixture.nativeElement as HTMLElement);
    }
  });

  it('has zero serious/critical axe violations (wcag2a + wcag2aa) when English is active', async () => {
    const fixture = TestBed.createComponent(LanguageSwitcherComponent);
    fixture.detectChanges();

    const langService = TestBed.inject(LanguageService);
    langService.setLanguage('en');
    fixture.detectChanges();

    document.body.appendChild(fixture.nativeElement as HTMLElement);

    try {
      const results = await axe.run(fixture.nativeElement as HTMLElement, {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] },
      });

      const critical = results.violations.filter(
        (v) => v.impact === 'critical' || v.impact === 'serious',
      );

      expect(
        critical,
        `Critical/serious axe violations on language-switcher (EN active):\n${JSON.stringify(critical, null, 2)}`,
      ).toHaveLength(0);
    } finally {
      document.body.removeChild(fixture.nativeElement as HTMLElement);
    }
  });
});
