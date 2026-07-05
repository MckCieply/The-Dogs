import { Component, inject, signal } from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { PasswordModule } from 'primeng/password';
import { MessageModule } from 'primeng/message';
import { LucideAngularModule, Dog } from 'lucide-angular';
import { AuthService } from '../../services/auth.service';
import { AuthStore } from '../../services/auth.store';
import { ApiFieldError } from '../../models/auth.models';
import { PasswordStrengthMeterComponent } from './password-strength-meter.component';

/**
 * Reset-password page (AUTH-08). Accepts the admin-delivered token — pre-filled from
 * `?token=...` when present — plus a new password gated by the shared strength meter (same
 * score >= 3 contract as the backend). On success the user is sent to /login to sign in with the
 * new password (the backend revoked every session, so a fresh login is required anyway).
 */
@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    ButtonModule,
    InputTextModule,
    PasswordModule,
    MessageModule,
    LucideAngularModule,
    PasswordStrengthMeterComponent,
  ],
  template: `
    <div class="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div class="w-full max-w-md">
        <div class="flex flex-col items-center mb-8">
          <lucide-icon [img]="DogIcon" class="w-12 h-12 text-primary mb-3" />
          <h1 class="text-3xl font-bold text-gray-900">The Dogs</h1>
          <p class="text-gray-500 mt-1">Choose a new password</p>
        </div>

        <div class="bg-white rounded-2xl shadow-md p-8">
          @if (succeeded()) {
            <p-message
              severity="success"
              text="Password updated. All previous sessions were signed out — please sign in with
                    your new password."
              styleClass="w-full mb-4"
              role="status"
            />
            <p class="text-sm text-center">
              <a routerLink="/login" class="text-primary underline">Go to sign in</a>
            </p>
          } @else {
            <form [formGroup]="form" (ngSubmit)="onSubmit()" novalidate>
              <div class="flex flex-col gap-1 mb-5">
                <label for="token" class="text-sm font-medium text-gray-700">
                  Reset token
                </label>
                <input
                  pInputText
                  id="token"
                  type="text"
                  formControlName="token"
                  autocomplete="off"
                  placeholder="Token provided by your administrator"
                  class="w-full font-mono text-sm"
                  aria-required="true"
                />
                @if (form.controls['token'].touched && form.controls['token'].invalid) {
                  <small class="text-red-700 text-xs" role="alert">
                    The reset token is required.
                  </small>
                }
              </div>

              <div class="flex flex-col gap-1 mb-6">
                <label for="newPassword" class="text-sm font-medium text-gray-700">
                  New password
                </label>
                <p-password
                  inputId="newPassword"
                  formControlName="newPassword"
                  [feedback]="false"
                  [toggleMask]="true"
                  autocomplete="new-password"
                  placeholder="At least 10 characters"
                  styleClass="w-full"
                  inputStyleClass="w-full"
                  aria-required="true"
                />
                <app-password-strength-meter
                  [password]="form.controls['newPassword'].value ?? ''"
                  (scoreChange)="meterScore.set($event)"
                />
              </div>

              @if (error(); as err) {
                <p-message severity="error" [text]="err" styleClass="w-full mb-4" role="alert" />
              }

              <p-button
                type="submit"
                label="Set new password"
                styleClass="w-full"
                [loading]="loading()"
                [disabled]="!canSubmit()"
              />
            </form>
          }

          <p class="text-sm text-gray-500 text-center mt-6">
            <a routerLink="/login" class="text-primary underline">Back to sign in</a>
          </p>
        </div>
      </div>
    </div>
  `,
})
export class ResetPasswordComponent {
  private readonly authService = inject(AuthService);
  protected readonly authStore = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  protected readonly DogIcon = Dog;
  protected readonly loading = signal(false);
  protected readonly succeeded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly meterScore = signal<number | null>(null);

  form: FormGroup = this.fb.group({
    token: [
      this.route.snapshot.queryParamMap.get('token') ?? '',
      [Validators.required],
    ],
    newPassword: [
      '',
      [Validators.required, Validators.minLength(10), Validators.maxLength(128)],
    ],
  });

  protected canSubmit(): boolean {
    return this.form.valid && (this.meterScore() ?? 0) >= 3 && !this.loading();
  }

  async onSubmit(): Promise<void> {
    this.form.markAllAsTouched();
    if (!this.canSubmit()) return;

    this.loading.set(true);
    this.error.set(null);
    try {
      await this.authService.resetPassword(
        this.form.controls['token'].value as string,
        this.form.controls['newPassword'].value as string,
      );
      this.succeeded.set(true);
    } catch (err: unknown) {
      this.error.set(this.messageFor(err));
    } finally {
      this.loading.set(false);
    }
  }

  private messageFor(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      const errors: ApiFieldError[] = err.error?.errors ?? [];
      if (errors.some((e) => e.code === 'invalid_reset_token')) {
        return 'This reset token is invalid or has expired. Request a new one.';
      }
      const weak = errors.find((e) => e.code === 'password_too_weak');
      if (weak) {
        return (
          'Password is too easy to guess' +
          (weak.password_score !== undefined
            ? ` (backend score: ${weak.password_score}/4).`
            : '.')
        );
      }
      if (err.status === 429) {
        return 'Too many attempts — please try again later.';
      }
    }
    return 'Password reset failed. Please try again.';
  }
}
