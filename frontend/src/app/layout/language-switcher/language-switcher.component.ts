import { Component, inject } from '@angular/core';
import { LucideAngularModule, Globe } from 'lucide-angular';
import { TranslatePipe } from '@ngx-translate/core';
import { LanguageService } from '../../services/language.service';

@Component({
  selector: 'app-language-switcher',
  standalone: true,
  imports: [LucideAngularModule, TranslatePipe],
  template: `
    <div
      class="flex items-center gap-2"
      role="group"
      [attr.aria-label]="'shell.language_switcher.label' | translate"
    >
      <lucide-icon [img]="GlobeIcon" class="w-4 h-4 text-gray-500" />
      <button
        type="button"
        [attr.aria-label]="'shell.language_switcher.pl' | translate"
        [class.font-semibold]="langService.language() === 'pl'"
        [class.text-primary]="langService.language() === 'pl'"
        [attr.aria-pressed]="langService.language() === 'pl'"
        (click)="langService.setLanguage('pl')"
      >
        {{ 'shell.language_switcher.pl' | translate }}
      </button>
      <span class="text-gray-300" aria-hidden="true">|</span>
      <button
        type="button"
        [attr.aria-label]="'shell.language_switcher.en' | translate"
        [class.font-semibold]="langService.language() === 'en'"
        [class.text-primary]="langService.language() === 'en'"
        [attr.aria-pressed]="langService.language() === 'en'"
        (click)="langService.setLanguage('en')"
      >
        {{ 'shell.language_switcher.en' | translate }}
      </button>
    </div>
  `,
})
export class LanguageSwitcherComponent {
  protected readonly langService = inject(LanguageService);
  protected readonly GlobeIcon = Globe;
}
