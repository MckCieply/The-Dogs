import { inject, Injectable, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

type Lang = 'pl' | 'en';
const STORAGE_KEY = 'thedogs.language';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly translate = inject(TranslateService);
  readonly language = signal<Lang>(this.resolveInitialLang());

  constructor() {
    this.translate.use(this.language());
  }

  setLanguage(lang: Lang): void {
    localStorage.setItem(STORAGE_KEY, lang);
    this.language.set(lang);
    this.translate.use(lang);
  }

  private resolveInitialLang(): Lang {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'pl' || stored === 'en') return stored;
    const nav = navigator.language ?? '';
    return nav.startsWith('pl') ? 'pl' : nav.startsWith('en') ? 'en' : 'pl';
  }
}
