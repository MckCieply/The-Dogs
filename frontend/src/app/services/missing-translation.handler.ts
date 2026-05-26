import { Injectable } from '@angular/core';
import {
  MissingTranslationHandler,
  MissingTranslationHandlerParams,
} from '@ngx-translate/core';

@Injectable()
export class AppMissingTranslationHandler implements MissingTranslationHandler {
  handle(params: MissingTranslationHandlerParams): string {
    console.warn(`[i18n] Missing translation key: "${params.key}"`);
    const fallback = params.translateService.instant('errors.codes.unknown');
    return fallback !== 'errors.codes.unknown' ? fallback : 'Something went wrong';
  }
}
