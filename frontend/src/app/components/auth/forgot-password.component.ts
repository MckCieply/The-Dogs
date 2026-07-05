import { Component, inject, signal } from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { LucideAngularModule, Dog } from 'lucide-angular';
import { AuthService } from '../../services/auth.service';

/**
 * Forgot-password page (AUTH-08). Anti-enumeration by design: whatever the email, the page shows
 * the same neutral confirmation — the backend likewise always answers 204. Token delivery is
 * out-of-band via an administrator (ADR-0013, no SMTP).
 */
@Component({
  selector: 'app-forgot-password',
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
    <div class="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div class="w-full max-w-md">
        <div class="flex flex-col items-center mb-8">
          <lucide-icon [img]="DogIcon" class="w-12 h-12 text-primary mb-3" />
          <h1 class="text-3xl font-bold text-gray-900">The Dogs</h1>
          <p class="text-gray-500 mt-1">Reset your password</p>
        </div>

        <div class="bg-white rounded-2xl shadow-md p-8">
          @if (submitted()) {
            <p-message
              severity="success"
              text="If that email has an account, your administrator has been notified and will
                    provide you with a reset token."
              styleClass="w-full mb-4"
              role="status"
            />
            <p class="text-sm text-gray-500 text-center">
              Received a token already?
              <a routerLink="/reset-password" class="text-primary underline">
                Set a new password
              </a>
            </p>
          } @else {
            <form [formGroup]="form" (ngSubmit)="onSubmit()" novalidate>
              <div class="flex flex-col gap-1 mb-6">
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
                />
                @if (form.controls['email'].touched && form.controls['email'].invalid) {
                  <small class="text-red-700 text-xs" role="alert">
                    Please enter a valid email address.
                  </small>
                }
              </div>

              @if (rateLimitError()) {
                <p-message
                  severity="warn"
                  text="Too many requests for this email — please try again later."
                  styleClass="w-full mb-4"
                  role="alert"
                />
              }

              <p-button
                type="submit"
                label="Request reset"
                styleClass="w-full"
                [loading]="loading()"
                [disabled]="loading()"
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
export class ForgotPasswordComponent {
  private readonly authService = inject(AuthService);
  private readonly fb = inject(FormBuilder);

  protected readonly DogIcon = Dog;
  protected readonly loading = signal(false);
  protected readonly submitted = signal(false);
  protected readonly rateLimitError = signal(false);

  form: FormGroup = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  async onSubmit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;

    this.loading.set(true);
    this.rateLimitError.set(false);
    try {
      await this.authService.forgotPassword(
        this.form.controls['email'].value as string,
      );
      this.submitted.set(true);
    } catch (err: unknown) {
      if (err instanceof HttpErrorResponse && err.status === 429) {
        this.rateLimitError.set(true);
      } else {
        // Neutral confirmation even on unexpected errors — never leak account existence.
        this.submitted.set(true);
      }
    } finally {
      this.loading.set(false);
    }
  }
}
