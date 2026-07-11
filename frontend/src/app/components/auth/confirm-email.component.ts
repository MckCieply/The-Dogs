import { Component, inject, signal } from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { LucideAngularModule, Dog } from 'lucide-angular';
import { AuthService } from '../../services/auth.service';
import { ApiFieldError } from '../../models/auth.models';

/**
 * Email-confirmation page (AUTH-09). Accepts the out-of-band verification token — pre-filled from
 * `?token=...` when present — and marks the account's email as verified, unlocking login. Also
 * offers a resend form (pre-filled from `?email=...`) that rotates the token; like
 * forgot-password, resend never discloses whether the email exists.
 *
 * <p>Registration redirects here (`?registered=1`) so the new user immediately learns that the
 * next sign-in requires a confirmed email.
 *
 * <p>i18n note: strings are hardcoded English to match the other auth pages; they move to
 * translation keys when I18N-01 lands.
 */
@Component({
  selector: 'app-confirm-email',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    ButtonModule,
    InputTextModule,
    MessageModule,
    LucideAngularModule,
  ],
  template: `
    <div class="min-h-screen flex items-center justify-center bg-gray-50 px-4 py-8">
      <div class="w-full max-w-md">
        <div class="flex flex-col items-center mb-8">
          <lucide-icon [img]="DogIcon" class="w-12 h-12 text-primary mb-3" />
          <h1 class="text-3xl font-bold text-gray-900">The Dogs</h1>
          <p class="text-gray-500 mt-1">Confirm your email address</p>
        </div>

        <div class="bg-white rounded-2xl shadow-md p-8">
          @if (succeeded()) {
            <p-message
              severity="success"
              text="Email confirmed — your account is fully active. You can sign in any time."
              styleClass="w-full mb-4"
              role="status"
            />
            <p class="text-sm text-center">
              <a routerLink="/login" class="text-primary underline">Go to sign in</a>
            </p>
          } @else {
            @if (justRegistered()) {
              <p-message
                severity="info"
                text="Account created! You are signed in for this session, but you must confirm
                      your email before your next sign-in. Enter the verification token below —
                      your administrator can provide it."
                styleClass="w-full mb-4"
                role="status"
              />
            }

            <form [formGroup]="form" (ngSubmit)="onSubmit()" novalidate>
              <div class="flex flex-col gap-1 mb-6">
                <label for="token" class="text-sm font-medium text-gray-700">
                  Verification token
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
                    The verification token is required.
                  </small>
                }
              </div>

              @if (error(); as err) {
                <p-message severity="error" [text]="err" styleClass="w-full mb-4" role="alert" />
              }

              <p-button
                type="submit"
                label="Confirm email"
                styleClass="w-full"
                [loading]="loading()"
                [disabled]="loading()"
              />
            </form>

            <!-- Resend -->
            <div class="border-t border-gray-200 mt-6 pt-6">
              <p class="text-sm text-gray-600 mb-3">
                Token expired or lost? Request a fresh one — then ask your administrator for the
                new value.
              </p>
              <form [formGroup]="resendForm" (ngSubmit)="onResend()" novalidate>
                <div class="flex flex-col gap-1 mb-4">
                  <label for="resend-email" class="text-sm font-medium text-gray-700">
                    Email address
                  </label>
                  <input
                    pInputText
                    id="resend-email"
                    type="email"
                    formControlName="email"
                    autocomplete="email"
                    placeholder="trainer@example.com"
                    class="w-full"
                  />
                </div>

                @if (resendDone()) {
                  <p-message
                    severity="info"
                    text="If an unverified account exists for that address, a fresh token was
                          issued."
                    styleClass="w-full mb-4"
                    role="status"
                  />
                }
                @if (resendError(); as err) {
                  <p-message severity="error" [text]="err" styleClass="w-full mb-4" role="alert" />
                }

                <p-button
                  type="submit"
                  label="Request a new token"
                  severity="secondary"
                  [outlined]="true"
                  styleClass="w-full"
                  [loading]="resendLoading()"
                  [disabled]="resendForm.invalid || resendLoading()"
                />
              </form>
            </div>
          }

          <p class="text-sm text-gray-500 text-center mt-6">
            <a routerLink="/login" class="text-primary underline">Back to sign in</a>
          </p>
        </div>
      </div>
    </div>
  `,
})
export class ConfirmEmailComponent {
  private readonly authService = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  protected readonly DogIcon = Dog;
  protected readonly loading = signal(false);
  protected readonly succeeded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly justRegistered = signal(
    this.route.snapshot.queryParamMap.get('registered') === '1',
  );

  protected readonly resendLoading = signal(false);
  protected readonly resendDone = signal(false);
  protected readonly resendError = signal<string | null>(null);

  form: FormGroup = this.fb.group({
    token: [
      this.route.snapshot.queryParamMap.get('token') ?? '',
      [Validators.required],
    ],
  });

  resendForm: FormGroup = this.fb.group({
    email: [
      this.route.snapshot.queryParamMap.get('email') ?? '',
      [Validators.required, Validators.email],
    ],
  });

  async onSubmit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.loading()) return;

    this.loading.set(true);
    this.error.set(null);
    try {
      await this.authService.confirmEmail(
        (this.form.controls['token'].value as string).trim(),
      );
      this.succeeded.set(true);
    } catch (err: unknown) {
      this.error.set(this.messageFor(err));
    } finally {
      this.loading.set(false);
    }
  }

  async onResend(): Promise<void> {
    this.resendForm.markAllAsTouched();
    if (this.resendForm.invalid || this.resendLoading()) return;

    this.resendLoading.set(true);
    this.resendDone.set(false);
    this.resendError.set(null);
    try {
      await this.authService.resendConfirmation(
        this.resendForm.controls['email'].value as string,
      );
      this.resendDone.set(true);
    } catch (err: unknown) {
      this.resendError.set(
        err instanceof HttpErrorResponse && err.status === 429
          ? 'Too many requests — please try again later.'
          : 'Something went wrong. Please try again.',
      );
    } finally {
      this.resendLoading.set(false);
    }
  }

  private messageFor(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      const errors: ApiFieldError[] = err.error?.errors ?? [];
      if (errors.some((e) => e.code === 'invalid_verification_token')) {
        return 'This verification token is invalid or has expired. Request a new one below.';
      }
      if (err.status === 429) {
        return 'Too many attempts — please try again later.';
      }
    }
    return 'Email confirmation failed. Please try again.';
  }
}
