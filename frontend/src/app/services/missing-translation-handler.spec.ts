import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { MissingTranslationHandlerParams } from '@ngx-translate/core';
import { AppMissingTranslationHandler } from './missing-translation.handler';

function makeParams(
  key: string,
  instantReturn: string,
): MissingTranslationHandlerParams {
  return {
    key,
    translateService: {
      instant: vi.fn().mockReturnValue(instantReturn),
    } as unknown as MissingTranslationHandlerParams['translateService'],
  };
}

describe('AppMissingTranslationHandler', () => {
  let handler: AppMissingTranslationHandler;
  let warnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    handler = new AppMissingTranslationHandler();
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('emits a console.warn containing the missing key (AC-8)', () => {
    handler.handle(makeParams('some.missing.key', 'errors.codes.unknown'));

    expect(warnSpy).toHaveBeenCalledOnce();
    expect(warnSpy.mock.calls[0][0]).toContain('some.missing.key');
  });

  it('returns the errors.codes.unknown translation when it is loaded (AC-8 fallback)', () => {
    const result = handler.handle(makeParams('some.missing.key', 'Wystąpił błąd'));

    expect(result).toBe('Wystąpił błąd');
  });

  it('returns the hard-coded fallback string when TranslateService returns the key itself (AC-8 hard fallback)', () => {
    const result = handler.handle(makeParams('some.missing.key', 'errors.codes.unknown'));

    expect(result).toBe('Something went wrong');
  });
});
