import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { LucideAngularModule, Globe } from 'lucide-angular';
import { LanguageService } from '../../services/language.service';

@Component({
  selector: 'app-language-switcher',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslateModule, LucideAngularModule],
  template: `
    <div
      class="flex items-center gap-2"
      [attr.aria-label]="'shell.language_switcher.label' | translate"
      role="group"
    >
      <lucide-icon [img]="GlobeIcon" size="16" aria-hidden="true" />
      <button
        (click)="setLanguage('pl')"
        [attr.aria-pressed]="language() === 'pl'"
        [class.font-bold]="language() === 'pl'"
        class="cursor-pointer px-1"
      >{{ 'shell.language_switcher.pl' | translate }}</button>
      <button
        (click)="setLanguage('en')"
        [attr.aria-pressed]="language() === 'en'"
        [class.font-bold]="language() === 'en'"
        class="cursor-pointer px-1"
      >{{ 'shell.language_switcher.en' | translate }}</button>
    </div>
  `,
})
export class LanguageSwitcherComponent {
  private readonly languageService = inject(LanguageService);
  readonly language = this.languageService.language;
  readonly GlobeIcon = Globe;

  setLanguage(lang: 'pl' | 'en'): void {
    this.languageService.setLanguage(lang);
  }
}
