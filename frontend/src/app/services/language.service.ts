import { inject, Injectable, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

const STORAGE_KEY = 'thedogs.language';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly translate = inject(TranslateService);

  readonly language = signal<'pl' | 'en'>('pl');

  constructor() {
    this.init();
  }

  private init(): void {
    const stored = localStorage.getItem(STORAGE_KEY) as 'pl' | 'en' | null;
    const lang = stored ?? this.detectBrowserLanguage();
    this.applyLanguage(lang);
  }

  private detectBrowserLanguage(): 'pl' | 'en' {
    const browserLang = navigator.language?.split('-')[0].toLowerCase();
    if (browserLang === 'pl') return 'pl';
    if (browserLang === 'en') return 'en';
    // Anything else (e.g. de-DE) → project default: 'pl'
    return 'pl';
  }

  setLanguage(lang: 'pl' | 'en'): void {
    this.applyLanguage(lang);
    localStorage.setItem(STORAGE_KEY, lang);
  }

  private applyLanguage(lang: 'pl' | 'en'): void {
    this.language.set(lang);
    this.translate.use(lang);
    document.documentElement.lang = lang;
  }
}
