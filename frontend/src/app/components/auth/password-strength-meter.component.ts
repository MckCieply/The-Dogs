import {
  ChangeDetectionStrategy,
  Component,
  InjectionToken,
  Input,
  inject,
  output,
  signal,
} from '@angular/core';

/** Subset of the zxcvbn-ts result this component consumes. */
export interface StrengthResult {
  score: 0 | 1 | 2 | 3 | 4;
  warning: string;
  suggestions: string[];
}

export type ZxcvbnFn = (password: string, userInputs?: string[]) => StrengthResult;

/**
 * Injectable loader so unit tests can provide a deterministic stub instead of the real ~400 KB
 * dynamic import. The default factory performs the lazy import (AC-11: zxcvbn never lands in the
 * main bundle).
 */
export const ZXCVBN_LOADER = new InjectionToken<() => Promise<ZxcvbnFn>>('ZXCVBN_LOADER', {
  providedIn: 'root',
  factory: () => async () => {
    const [core, common, en] = await Promise.all([
      import('@zxcvbn-ts/core'),
      import('@zxcvbn-ts/language-common'),
      import('@zxcvbn-ts/language-en'),
    ]);
    core.zxcvbnOptions.setOptions({
      dictionary: { ...common.dictionary, ...en.dictionary },
      graphs: common.adjacencyGraphs,
      translations: en.translations,
    });
    return (pwd, inputs) => {
      const result = core.zxcvbn(pwd, inputs);
      return {
        score: result.score,
        warning: result.feedback.warning ?? '',
        suggestions: result.feedback.suggestions ?? [],
      };
    };
  },
});

/**
 * Live password strength meter (AUTH-07, reused by AUTH-08).
 *
 * <p>zxcvbn-ts is ~400 KB with dictionaries, so it is dynamic-import()ed on the first non-empty
 * password — route entry stays fast and the library never lands in the main bundle (AC-11).
 * Until the library resolves, the meter renders no verdict and emits score null.
 *
 * <p>The score contract is shared with the backend gate (AUTH-04): both require zxcvbn score >= 3.
 * If the threshold ever changes, frontend and backend must move together.
 */
@Component({
  selector: 'app-password-strength-meter',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mt-2" aria-live="polite">
      <div class="flex gap-1" role="img" [attr.aria-label]="ariaLabel()">
        @for (segment of [0, 1, 2, 3, 4]; track segment) {
          <div
            class="h-1.5 flex-1 rounded-full transition-colors"
            [class]="segmentClass(segment)"
          ></div>
        }
      </div>
      @if (label(); as verdict) {
        <p class="text-xs mt-1" [class]="labelClass()">{{ verdict }}</p>
      }
      @if (feedback(); as hint) {
        <p class="text-xs text-gray-500 mt-0.5">{{ hint }}</p>
      }
    </div>
  `,
})
export class PasswordStrengthMeterComponent {
  /** Extra penalty-dictionary inputs (email, display name) — mirrors the backend call. */
  @Input() userInputs: string[] = [];

  /**
   * The password to measure. A plain setter (not input()/effect()) so measurement is driven
   * directly by the binding update — no reliance on effect scheduling, which also makes the
   * component trivially testable in the zoneless TestBed.
   */
  @Input() set password(value: string) {
    if (!value) {
      this.score.set(null);
      this.warning.set('');
      this.scoreChange.emit(null);
      return;
    }
    this.measure(value, this.userInputs).catch((err) => {
      // Never let a scorer failure break the form — the backend re-validates anyway.
      console.error('password strength measurement failed', err);
      this.score.set(null);
      this.scoreChange.emit(null);
    });
  }

  /** Emits the current zxcvbn score, or null while the library loads / password is empty. */
  readonly scoreChange = output<number | null>();

  protected readonly score = signal<number | null>(null);
  protected readonly warning = signal<string>('');

  private readonly loader = inject(ZXCVBN_LOADER);
  private zxcvbn: ZxcvbnFn | null = null;
  private loading = false;

  private static readonly LABELS = [
    'Very weak',
    'Weak',
    'Fair',
    'Strong',
    'Very strong',
  ];

  private static readonly SEGMENT_COLORS = [
    'bg-red-500',
    'bg-orange-500',
    'bg-yellow-500',
    'bg-green-500',
    'bg-emerald-600',
  ];

  /** Latest requested measurement — keystrokes during the lazy load are not dropped. */
  private latest: { pwd: string; inputs: string[] } | null = null;

  private async measure(password: string, userInputs: string[]): Promise<void> {
    this.latest = { pwd: password, inputs: userInputs };
    if (!this.zxcvbn) {
      if (this.loading) {
        // A load is in flight; its completion will measure this.latest.
        return;
      }
      this.loading = true;
      this.zxcvbn = await this.loader();
      this.loading = false;
    }
    const snapshot = this.latest;
    if (!snapshot) return;
    // Project-related blocklist so "thedogs2026" cannot game the meter.
    const result = this.zxcvbn(snapshot.pwd, [
      ...snapshot.inputs,
      'dogs',
      'thedogs',
      'trainer',
    ]);
    this.score.set(result.score);
    this.warning.set(result.warning);
    this.scoreChange.emit(result.score);
  }

  protected segmentClass(segment: number): string {
    const s = this.score();
    if (s === null || segment > s) {
      return 'bg-gray-200';
    }
    return PasswordStrengthMeterComponent.SEGMENT_COLORS[s];
  }

  protected label(): string | null {
    const s = this.score();
    return s === null ? null : PasswordStrengthMeterComponent.LABELS[s];
  }

  protected labelClass(): string {
    const s = this.score();
    if (s === null) return 'text-gray-500';
    return s >= 3 ? 'text-green-700' : 'text-red-700';
  }

  protected feedback(): string | null {
    const s = this.score();
    if (s === null || s >= 3) return null;
    return this.warning() || 'Choose a longer or less predictable password.';
  }

  protected ariaLabel(): string {
    const s = this.score();
    return s === null
      ? 'Password strength: not measured'
      : `Password strength: ${PasswordStrengthMeterComponent.LABELS[s]} (${s} of 4)`;
  }
}
