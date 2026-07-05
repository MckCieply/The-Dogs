import { Component, inject, signal } from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { PasswordModule } from 'primeng/password';
import { MessageModule } from 'primeng/message';
import { CheckboxModule } from 'primeng/checkbox';
import { LucideAngularModule, Dog } from 'lucide-angular';
import { AuthStore } from '../../services/auth.store';
import { AuthService } from '../../services/auth.service';
import { ApiFieldError } from '../../models/auth.models';
import { PasswordStrengthMeterComponent } from './password-strength-meter.component';

/**
 * Registration page (AUTH-07). Mirrors the backend contract from AUTH-04: the live zxcvbn meter is
 * UX only — the backend re-validates strength, uniqueness, and lengths, and its error codes are
 * rendered inline (email_taken, password_too_weak with backend score, too_many_attempts with
 * countdown).
 *
 * <p>i18n note: strings are hardcoded English to match the current login page; they move to
 * translation keys when I18N-01 lands.
 */
@Component({
  selector: 'app-register',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    ButtonModule,
    InputTextModule,
    PasswordModule,
    MessageModule,
    CheckboxModule,
    LucideAngularModule,
    PasswordStrengthMeterComponent,
  ],
  template: `
    <div class="min-h-screen flex items-center justify-center bg-gray-50 px-4 py-8">
      <div class="w-full max-w-md">
        <div class="flex flex-col items-center mb-8">
          <lucide-icon [img]="DogIcon" class="w-12 h-12 text-primary mb-3" />
          <h1 class="text-3xl font-bold text-gray-900">The Dogs</h1>
          <p class="text-gray-500 mt-1">Create your trainer account</p>
        </div>

        <div class="bg-white rounded-2xl shadow-md p-8">
          <form [formGroup]="form" (ngSubmit)="onSubmit()" novalidate>
            <!-- Email -->
            <div class="flex flex-col gap-1 mb-5">
              <label for="email" class="text-sm font-medium text-gray-700">
                Email address
              </label>
              <input
                pInputText
                id="email"
                type="email"
                formControlName="email"
                autocomplete="email"
                placeholder="trainer@example.com"
                class="w-full"
                aria-required="true"
                [attr.aria-describedby]="emailError() ? 'email-error' : null"
                (input)="clearServerError('email')"
              />
              @if (emailError(); as err) {
                <small id="email-error" class="text-red-700 text-xs" role="alert">
                  {{ err }}
                </small>
              }
            </div>

            <!-- Display name -->
            <div class="flex flex-col gap-1 mb-5">
              <label for="displayName" class="text-sm font-medium text-gray-700">
                Display name
              </label>
              <input
                pInputText
                id="displayName"
                type="text"
                formControlName="displayName"
                autocomplete="name"
                placeholder="Jane the Trainer"
                maxlength="80"
                class="w-full"
                aria-required="true"
              />
              @if (touchedInvalid('displayName')) {
                <small class="text-red-700 text-xs" role="alert">
                  Display name is required (max 80 characters).
                </small>
              }
            </div>

            <!-- Password -->
            <div class="flex flex-col gap-1 mb-2">
              <label for="password" class="text-sm font-medium text-gray-700">
                Password
              </label>
              <p-password
                inputId="password"
                formControlName="password"
                [feedback]="false"
                [toggleMask]="true"
                autocomplete="new-password"
                placeholder="At least 10 characters"
                styleClass="w-full"
                inputStyleClass="w-full"
                aria-required="true"
              />
              <app-password-strength-meter
                [password]="form.controls['password'].value ?? ''"
                [userInputs]="meterInputs()"
                (scoreChange)="meterScore.set($event)"
              />
              @if (passwordServerError(); as err) {
                <small class="text-red-700 text-xs" role="alert">{{ err }}</small>
              }
              @if (passwordTooShort()) {
                <small class="text-red-700 text-xs" role="alert">
                  Password must be 10–128 characters.
                </small>
              }
            </div>

            <!-- Confirm password -->
            <div class="flex flex-col gap-1 mb-5">
              <label for="passwordConfirm" class="text-sm font-medium text-gray-700">
                Confirm password
              </label>
              <p-password
                inputId="passwordConfirm"
                formControlName="passwordConfirm"
                [feedback]="false"
                [toggleMask]="true"
                autocomplete="new-password"
                placeholder="Repeat the password"
                styleClass="w-full"
                inputStyleClass="w-full"
                aria-required="true"
              />
              @if (confirmMismatch()) {
                <small class="text-red-700 text-xs" role="alert">
                  Passwords do not match.
                </small>
              }
            </div>

            <!-- Terms -->
            <div class="flex items-center gap-2 mb-6">
              <p-checkbox inputId="terms" formControlName="terms" [binary]="true" />
              <label for="terms" class="text-sm text-gray-600">
                I accept the
                <a routerLink="/terms" class="text-primary underline">terms of service</a>
                and
                <a routerLink="/privacy" class="text-primary underline">privacy policy</a>
              </label>
            </div>

            @if (rateLimitSeconds() > 0) {
              <p-message
                severity="warn"
                [text]="
                  'Too many attempts — try again in ' + rateLimitSeconds() + 's'
                "
                styleClass="w-full mb-4"
                role="alert"
              />
            } @else if (generalError(); as err) {
              <p-message severity="error" [text]="err" styleClass="w-full mb-4" role="alert" />
            }

            <p-button
              type="submit"
              label="Create account"
              styleClass="w-full"
              [loading]="loading()"
              [disabled]="!canSubmit()"
            />
          </form>

          <p class="text-sm text-gray-500 text-center mt-6">
            Already have an account?
            <a routerLink="/login" class="text-primary underline">Sign in</a>
          </p>
        </div>
      </div>
    </div>
  `,
})
export class RegisterComponent {
  protected readonly authStore = inject(AuthStore);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  protected readonly DogIcon = Dog;

  form: FormGroup = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    displayName: ['', [Validators.required, Validators.maxLength(80)]],
    password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(128)]],
    passwordConfirm: ['', [Validators.required]],
    terms: [false, [Validators.requiredTrue]],
  });

  /** Live zxcvbn score from the meter; null until measured. */
  protected readonly meterScore = signal<number | null>(null);
  protected readonly loading = signal(false);
  protected readonly generalError = signal<string | null>(null);
  protected readonly rateLimitSeconds = signal(0);
  private rateLimitTimer: ReturnType<typeof setInterval> | null = null;

  private readonly serverFieldErrors = signal<Record<string, string>>({});

  protected meterInputs(): string[] {
    return [
      this.form.controls['email'].value ?? '',
      this.form.controls['displayName'].value ?? '',
    ];
  }

  protected touchedInvalid(control: string): boolean {
    const c = this.form.controls[control];
    return c.touched && c.invalid;
  }

  protected passwordTooShort(): boolean {
    const c = this.form.controls['password'];
    return c.touched && (c.hasError('minlength') || c.hasError('maxlength'));
  }

  protected confirmMismatch(): boolean {
    const pwd = this.form.controls['password'].value;
    const confirm = this.form.controls['passwordConfirm'].value;
    return !!confirm && pwd !== confirm;
  }

  protected emailError(): string | null {
    const server = this.serverFieldErrors()['email'];
    if (server) return server;
    const c = this.form.controls['email'];
    if (c.touched && c.invalid) return 'Please enter a valid email address.';
    return null;
  }

  protected passwordServerError(): string | null {
    return this.serverFieldErrors()['password'] ?? null;
  }

  protected clearServerError(field: string): void {
    const errors = { ...this.serverFieldErrors() };
    if (errors[field]) {
      delete errors[field];
      this.serverFieldErrors.set(errors);
    }
  }

  /** AC-1/AC-3/AC-4: submit gated on validity, score >= 3, matching confirm, terms. */
  protected canSubmit(): boolean {
    return (
      this.form.valid &&
      !this.confirmMismatch() &&
      (this.meterScore() ?? 0) >= 3 &&
      this.rateLimitSeconds() === 0 &&
      !this.loading()
    );
  }

  async onSubmit(): Promise<void> {
    this.form.markAllAsTouched();
    if (!this.canSubmit()) return;

    this.loading.set(true);
    this.generalError.set(null);
    this.serverFieldErrors.set({});

    try {
      const response = await this.authService.register(
        this.form.controls['email'].value as string,
        this.form.controls['password'].value as string,
        this.form.controls['displayName'].value as string,
      );
      // Same wire-up as login: token in memory, refresh cookie already set by the backend.
      this.authStore.setToken(response.accessToken, {
        email: response.email,
        displayName: response.displayName,
        roles: response.roles ?? [],
      });
      await this.router.navigate(['/']);
    } catch (err: unknown) {
      this.handleError(err);
    } finally {
      this.loading.set(false);
    }
  }

  private handleError(err: unknown): void {
    if (!(err instanceof HttpErrorResponse)) {
      this.generalError.set('Registration failed. Please try again.');
      return;
    }

    const errors: ApiFieldError[] = err.error?.errors ?? [];

    if (err.status === 409) {
      this.serverFieldErrors.set({ email: 'This email is already registered.' });
      document.getElementById('email')?.focus();
      return;
    }
    if (err.status === 429) {
      const retryAfter = Number(err.headers?.get?.('Retry-After') ?? 60);
      this.startRateLimitCountdown(Number.isFinite(retryAfter) ? retryAfter : 60);
      return;
    }
    if (err.status === 400) {
      const weak = errors.find((e) => e.code === 'password_too_weak');
      if (weak) {
        const score = weak.password_score;
        this.serverFieldErrors.set({
          password:
            'Password is too easy to guess' +
            (score !== undefined ? ` (backend score: ${score}/4).` : '.'),
        });
        return;
      }
      this.generalError.set('Please check the highlighted fields.');
      return;
    }
    this.generalError.set('Registration failed. Please try again.');
  }

  private startRateLimitCountdown(seconds: number): void {
    this.rateLimitSeconds.set(seconds);
    if (this.rateLimitTimer) clearInterval(this.rateLimitTimer);
    this.rateLimitTimer = setInterval(() => {
      const next = this.rateLimitSeconds() - 1;
      this.rateLimitSeconds.set(Math.max(0, next));
      if (next <= 0 && this.rateLimitTimer) {
        clearInterval(this.rateLimitTimer);
        this.rateLimitTimer = null;
      }
    }, 1000);
  }
}
