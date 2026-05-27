import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { MissingTranslationHandlerParams } from '@ngx-translate/core';
import { CustomMissingTranslationHandler } from './missing-translation.handler';

// ---------------------------------------------------------------------------
// Constants mirrored from the implementation (kept private there)
// ---------------------------------------------------------------------------
const FALLBACK_KEY = 'errors.codes.unknown';
const HARDCODED_FALLBACK = 'Something went wrong / Wystąpił błąd';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeParams(key: string, instantResult = 'fallback-translation'): MissingTranslationHandlerParams {
  return {
    key,
    translateService: {
      instant: vi.fn().mockReturnValue(instantResult),
    } as unknown as MissingTranslationHandlerParams['translateService'],
  };
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

describe('CustomMissingTranslationHandler', () => {
  let handler: CustomMissingTranslationHandler;
  let warnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    handler = new CustomMissingTranslationHandler();
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // -------------------------------------------------------------------------
  // Normal missing key
  // -------------------------------------------------------------------------

  it('logs a console.warn that names the missing key (AC-8)', () => {
    const params = makeParams('some.missing.key');

    handler.handle(params);

    expect(warnSpy).toHaveBeenCalledOnce();
    const [message] = warnSpy.mock.calls[0] as [string];
    expect(message).toContain('some.missing.key');
  });

  it('returns the errors.codes.unknown translation for a normal missing key (AC-8)', () => {
    const params = makeParams('some.missing.key', 'Wystąpił błąd');

    const result = handler.handle(params);

    expect(result).toBe('Wystąpił błąd');
    expect((params.translateService as { instant: ReturnType<typeof vi.fn> }).instant).toHaveBeenCalledWith(FALLBACK_KEY);
  });

  it('delegates to translateService.instant(errors.codes.unknown) for a normal missing key', () => {
    const params = makeParams('auth.login.title', 'Wystąpił błąd');

    handler.handle(params);

    const instantMock = (params.translateService as { instant: ReturnType<typeof vi.fn> }).instant;
    expect(instantMock).toHaveBeenCalledWith(FALLBACK_KEY);
  });

  // -------------------------------------------------------------------------
  // Guard against infinite recursion — key IS errors.codes.unknown
  // -------------------------------------------------------------------------

  it('returns the hardcoded fallback string when the missing key is errors.codes.unknown (AC-8 edge case)', () => {
    const params = makeParams(FALLBACK_KEY);

    const result = handler.handle(params);

    expect(result).toBe(HARDCODED_FALLBACK);
  });

  it('does NOT call translateService.instant when the missing key is errors.codes.unknown', () => {
    const params = makeParams(FALLBACK_KEY);

    handler.handle(params);

    const instantMock = (params.translateService as { instant: ReturnType<typeof vi.fn> }).instant;
    expect(instantMock).not.toHaveBeenCalled();
  });

  it('still logs a console.warn when the missing key is errors.codes.unknown', () => {
    const params = makeParams(FALLBACK_KEY);

    handler.handle(params);

    expect(warnSpy).toHaveBeenCalledOnce();
    const [message] = warnSpy.mock.calls[0] as [string];
    expect(message).toContain(FALLBACK_KEY);
  });
});
