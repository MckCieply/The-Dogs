import { describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import {
  PasswordStrengthMeterComponent,
  StrengthResult,
  ZXCVBN_LOADER,
  ZxcvbnFn,
} from './password-strength-meter.component';

/**
 * Unit tests for the AUTH-07 strength meter. The zxcvbn loader is stubbed with a deterministic
 * scorer so the tests exercise the component's mapping (score → segments, labels, emissions) —
 * the real zxcvbn verdicts are covered by the backend integration tests (same library, same
 * score >= 3 contract) and were verified live in the browser.
 */
describe('PasswordStrengthMeterComponent', () => {
  /** Deterministic stub: score = min(4, floor(length / 6)), forced to 0 when pwd ∈ userInputs. */
  const stubZxcvbn: ZxcvbnFn = (pwd, inputs = []) => {
    const score = inputs.includes(pwd)
      ? 0
      : (Math.min(4, Math.floor(pwd.length / 6)) as StrengthResult['score']);
    return {
      score: score as StrengthResult['score'],
      warning: score < 3 ? 'stub-warning' : '',
      suggestions: [],
    };
  };

  function setup() {
    TestBed.configureTestingModule({
      providers: [{ provide: ZXCVBN_LOADER, useValue: async () => stubZxcvbn }],
    });
  }

  async function render(password: string, userInputs: string[] = []) {
    setup();
    const fixture = TestBed.createComponent(PasswordStrengthMeterComponent);
    const scores: (number | null)[] = [];
    fixture.componentInstance.scoreChange.subscribe((s) => scores.push(s));
    fixture.componentRef.setInput('userInputs', userInputs);
    fixture.componentRef.setInput('password', password);
    // The setter kicks an async measurement (stub loader resolves on a microtask).
    await vi.waitFor(() => {
      if (typeof scores.at(-1) !== 'number') {
        throw new Error('no numeric score emitted yet');
      }
    });
    fixture.detectChanges();
    return { fixture, scores };
  }

  it('emits null for an empty password', () => {
    setup();
    const fixture = TestBed.createComponent(PasswordStrengthMeterComponent);
    const scores: (number | null)[] = [];
    fixture.componentInstance.scoreChange.subscribe((s) => scores.push(s));
    fixture.componentRef.setInput('password', '');
    expect(scores).toEqual([null]);
  });

  it('emits a low score for a short password', async () => {
    const { scores } = await render('short');
    expect(scores.at(-1)).toBeLessThan(3);
  });

  it('emits a high score for a long password', async () => {
    const { scores } = await render('kR7#vetch-Quasar93!plinth');
    expect(scores.at(-1)).toBeGreaterThanOrEqual(3);
  });

  it('feeds userInputs into the scorer (own email is penalised)', async () => {
    const email = 'trainer@thedogs.example';
    const { scores } = await render(email, [email]);
    expect(scores.at(-1)).toBe(0);
  });

  it('renders five segments and fills them according to the score', async () => {
    const { fixture } = await render('kR7#vetch-Quasar93!plinth'); // stub score 4
    const segments: NodeListOf<HTMLElement> =
      fixture.nativeElement.querySelectorAll('[role="img"] > div');
    expect(segments.length).toBe(5);
    const colored = Array.from(segments).filter(
      (el) => !el.className.includes('bg-gray-200'),
    );
    expect(colored.length).toBe(5); // score 4 fills segments 0..4
  });

  it('shows the weak-password hint below the threshold', async () => {
    const { fixture } = await render('shortpw'); // stub score 1
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('stub-warning');
  });

  it('measures the latest password when keystrokes race the lazy load', async () => {
    setup();
    // A loader that resolves only when released — simulates the slow dynamic import.
    let release!: (fn: ZxcvbnFn) => void;
    const gate = new Promise<ZxcvbnFn>((r) => (release = r));
    TestBed.overrideProvider(ZXCVBN_LOADER, { useValue: () => gate });
    const fixture = TestBed.createComponent(PasswordStrengthMeterComponent);
    const scores: (number | null)[] = [];
    fixture.componentInstance.scoreChange.subscribe((s) => scores.push(s));

    fixture.componentRef.setInput('password', 'first-keystroke');
    fixture.componentRef.setInput('password', 'final-password-value!42x');
    release(stubZxcvbn);

    await vi.waitFor(() => {
      if (typeof scores.at(-1) !== 'number') throw new Error('not measured yet');
    });
    // 24 chars → stub score 4; had the stale 'first-keystroke' (15 chars) won, score would be 2.
    expect(scores.at(-1)).toBe(4);
  });
});
