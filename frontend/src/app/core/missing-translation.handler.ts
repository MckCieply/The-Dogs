import { Injectable } from '@angular/core';
import {
  MissingTranslationHandler,
  MissingTranslationHandlerParams,
} from '@ngx-translate/core';

const FALLBACK_KEY = 'errors.codes.unknown';
const HARDCODED_FALLBACK = 'Something went wrong / Wystąpił błąd';

@Injectable()
export class CustomMissingTranslationHandler implements MissingTranslationHandler {
  handle(params: MissingTranslationHandlerParams): string {
    console.warn(`Missing translation key: ${params.key}`);

    if (params.key === FALLBACK_KEY) {
      return HARDCODED_FALLBACK;
    }

    return params.translateService.instant(FALLBACK_KEY);
  }
}
