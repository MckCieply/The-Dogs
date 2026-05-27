/**
 * Unit tests for LanguageSwitcherComponent.
 *
 * Covers:
 * - AC-6: component exposes the active language signal which is used to bind
 *         [attr.aria-pressed] (the document.lang side-effect is tested in
 *         language.service.spec.ts).
 * - The component renders translated button labels via the translate pipe.
 * - Clicking a button calls LanguageService.setLanguage with the correct arg.
 * - aria-pressed reflects the active language correctly.
 * - The globe icon is present (aria-hidden so screen readers skip it).
 * - The wrapper div carries role="group".
 *
 * We use TestBed here because the component relies on TranslateModule's pipe
 * (transform is async — it subscribes to the translate loader). A mock
 * LanguageService is provided to keep the test hermetic.
 */
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  provideTranslateService,
  TranslateService,
  TranslateLoader,
} from '@ngx-translate/core';
import { Observable, of } from 'rxjs';
import { LanguageSwitcherComponent } from './language-switcher.component';
import { LanguageService } from '../../services/language.service';

// ---------------------------------------------------------------------------
// Fake translate loader — returns inline translations so no HTTP is needed
// ---------------------------------------------------------------------------

const PL_TRANSLATIONS = {
  shell: {
    language_switcher: {
      label: 'Zmień język',
      pl: 'Polski',
      en: 'English',
    },
  },
};

const EN_TRANSLATIONS = {
  shell: {
    language_switcher: {
      label: 'Change language',
      pl: 'Polish',
      en: 'English',
    },
  },
};

class FakeTranslateLoader implements TranslateLoader {
  getTranslation(lang: string): Observable<Record<string, unknown>> {
    return of(lang === 'en' ? EN_TRANSLATIONS : PL_TRANSLATIONS);
  }
}

// ---------------------------------------------------------------------------
// Mock LanguageService
// ---------------------------------------------------------------------------

function buildLanguageServiceMock(initial: 'pl' | 'en' = 'pl') {
  const lang = signal<'pl' | 'en'>(initial);
  return {
    language: lang,
    setLanguage: vi.fn((l: 'pl' | 'en') => lang.set(l)),
  };
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

describe('LanguageSwitcherComponent', () => {
  let langServiceMock: ReturnType<typeof buildLanguageServiceMock>;

  async function createComponent(initialLang: 'pl' | 'en' = 'pl') {
    langServiceMock = buildLanguageServiceMock(initialLang);

    await TestBed.configureTestingModule({
      imports: [LanguageSwitcherComponent],
      providers: [
        { provide: LanguageService, useValue: langServiceMock },
        provideTranslateService({
          loader: {
            provide: TranslateLoader,
            useClass: FakeTranslateLoader,
          },
        }),
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(LanguageSwitcherComponent);

    // Set the active language in the TranslateService so the pipe resolves
    const translate = TestBed.inject(TranslateService);
    translate.setDefaultLang('pl');
    translate.use(initialLang);

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    return fixture;
  }

  afterEach(() => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  // -------------------------------------------------------------------------
  // Structural / DOM
  // -------------------------------------------------------------------------

  it('renders a container div with role="group"', async () => {
    const fixture = await createComponent();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="group"]')).toBeTruthy();
  });

  it('renders exactly two buttons with aria-pressed attributes', async () => {
    const fixture = await createComponent();
    const el = fixture.nativeElement as HTMLElement;
    const buttons = el.querySelectorAll('button[aria-pressed]');
    expect(buttons).toHaveLength(2);
  });

  it('renders the globe icon with aria-hidden="true"', async () => {
    const fixture = await createComponent();
    const el = fixture.nativeElement as HTMLElement;
    // lucide-angular renders an <lucide-icon> host element; the SVG inside
    // should be aria-hidden. At minimum the lucide-icon element must exist.
    const icon = el.querySelector('lucide-icon');
    expect(icon).toBeTruthy();
    // The [aria-hidden="true"] is set on the host in the template
    expect(icon?.getAttribute('aria-hidden')).toBe('true');
  });

  // -------------------------------------------------------------------------
  // Translated labels — PL mode (AC-6 + AC-2 cross-check)
  // -------------------------------------------------------------------------

  it('PL button shows "Polski" when language is pl', async () => {
    const fixture = await createComponent('pl');
    const el = fixture.nativeElement as HTMLElement;
    const plButton = el.querySelectorAll('button[aria-pressed]')[0];
    expect(plButton.textContent?.trim()).toBe('Polski');
  });

  it('EN button shows "English" when language is pl', async () => {
    const fixture = await createComponent('pl');
    const el = fixture.nativeElement as HTMLElement;
    const enButton = el.querySelectorAll('button[aria-pressed]')[1];
    expect(enButton.textContent?.trim()).toBe('English');
  });

  // -------------------------------------------------------------------------
  // Translated labels — EN mode (AC-3 cross-check)
  // -------------------------------------------------------------------------

  it('PL button shows "Polish" when language is en', async () => {
    const fixture = await createComponent('en');
    const el = fixture.nativeElement as HTMLElement;
    const plButton = el.querySelectorAll('button[aria-pressed]')[0];
    expect(plButton.textContent?.trim()).toBe('Polish');
  });

  it('EN button shows "English" when language is en', async () => {
    const fixture = await createComponent('en');
    const el = fixture.nativeElement as HTMLElement;
    const enButton = el.querySelectorAll('button[aria-pressed]')[1];
    expect(enButton.textContent?.trim()).toBe('English');
  });

  // -------------------------------------------------------------------------
  // aria-pressed state — AC-6 (component mirrors the language signal)
  // -------------------------------------------------------------------------

  it('PL button has aria-pressed="true" when language is pl', async () => {
    const fixture = await createComponent('pl');
    const el = fixture.nativeElement as HTMLElement;
    const plButton = el.querySelectorAll('button[aria-pressed]')[0];
    expect(plButton.getAttribute('aria-pressed')).toBe('true');
  });

  it('EN button has aria-pressed="false" when language is pl', async () => {
    const fixture = await createComponent('pl');
    const el = fixture.nativeElement as HTMLElement;
    const enButton = el.querySelectorAll('button[aria-pressed]')[1];
    expect(enButton.getAttribute('aria-pressed')).toBe('false');
  });

  it('EN button has aria-pressed="true" when language is en', async () => {
    const fixture = await createComponent('en');
    const el = fixture.nativeElement as HTMLElement;
    const enButton = el.querySelectorAll('button[aria-pressed]')[1];
    expect(enButton.getAttribute('aria-pressed')).toBe('true');
  });

  it('PL button has aria-pressed="false" when language is en', async () => {
    const fixture = await createComponent('en');
    const el = fixture.nativeElement as HTMLElement;
    const plButton = el.querySelectorAll('button[aria-pressed]')[0];
    expect(plButton.getAttribute('aria-pressed')).toBe('false');
  });

  // -------------------------------------------------------------------------
  // Interaction — clicking calls LanguageService.setLanguage
  // -------------------------------------------------------------------------

  it('clicking the EN button calls setLanguage("en")', async () => {
    const fixture = await createComponent('pl');
    const el = fixture.nativeElement as HTMLElement;
    const enButton = el.querySelectorAll('button[aria-pressed]')[1] as HTMLButtonElement;

    enButton.click();
    fixture.detectChanges();

    expect(langServiceMock.setLanguage).toHaveBeenCalledWith('en');
  });

  it('clicking the PL button calls setLanguage("pl")', async () => {
    const fixture = await createComponent('en');
    const el = fixture.nativeElement as HTMLElement;
    const plButton = el.querySelectorAll('button[aria-pressed]')[0] as HTMLButtonElement;

    plButton.click();
    fixture.detectChanges();

    expect(langServiceMock.setLanguage).toHaveBeenCalledWith('pl');
  });

  // -------------------------------------------------------------------------
  // Signal reactivity — aria-pressed updates after language changes
  // -------------------------------------------------------------------------

  it('aria-pressed updates on PL button after switching to EN', async () => {
    const fixture = await createComponent('pl');
    const el = fixture.nativeElement as HTMLElement;

    // Simulate clicking EN
    const enButton = el.querySelectorAll('button[aria-pressed]')[1] as HTMLButtonElement;
    enButton.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const plButton = el.querySelectorAll('button[aria-pressed]')[0];
    expect(plButton.getAttribute('aria-pressed')).toBe('false');
    expect(enButton.getAttribute('aria-pressed')).toBe('true');
  });

  it('aria-pressed updates on EN button after switching back to PL', async () => {
    const fixture = await createComponent('en');
    const el = fixture.nativeElement as HTMLElement;

    // Simulate clicking PL
    const plButton = el.querySelectorAll('button[aria-pressed]')[0] as HTMLButtonElement;
    plButton.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const enButton = el.querySelectorAll('button[aria-pressed]')[1];
    expect(plButton.getAttribute('aria-pressed')).toBe('true');
    expect(enButton.getAttribute('aria-pressed')).toBe('false');
  });
});
