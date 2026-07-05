import { Component, inject, signal } from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { PasswordModule } from 'primeng/password';
import { MessageModule } from 'primeng/message';
import { LucideAngularModule, Dog, Eye, EyeOff, Lock, Mail } from 'lucide-angular';
import { AuthStore } from '../../services/auth.store';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    ButtonModule,
    InputTextModule,
    PasswordModule,
    MessageModule,
    LucideAngularModule,
  ],
  template: `
    <div class="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div class="w-full max-w-md">
        <!-- Logo / Heading -->
        <div class="flex flex-col items-center mb-8">
          <lucide-icon [img]="DogIcon" class="w-12 h-12 text-primary mb-3" />
          <h1 class="text-3xl font-bold text-gray-900">The Dogs</h1>
          <p class="text-gray-500 mt-1">Sign in to your trainer account</p>
        </div>

        <!-- Card -->
        <div class="bg-white rounded-2xl shadow-md p-8">
          <form [formGroup]="loginForm" (ngSubmit)="onSubmit()" novalidate>
            <!-- Email -->
            <div class="flex flex-col gap-1 mb-5">
              <label for="email" class="text-sm font-medium text-gray-700">
                Email address
              </label>
              <div class="relative">
                <span class="absolute inset-y-0 left-3 flex items-center pointer-events-none">
                  <lucide-icon [img]="MailIcon" class="w-4 h-4 text-gray-400" />
                </span>
                <input
                  pInputText
                  id="email"
                  type="email"
                  formControlName="email"
                  autocomplete="email"
                  placeholder="trainer@example.com"
                  [class.ng-invalid]="emailInvalid()"
                  [class.ng-dirty]="emailInvalid()"
                  class="w-full pl-10"
                  aria-required="true"
                  [attr.aria-describedby]="emailInvalid() ? 'email-error' : null"
                />
              </div>
              @if (emailInvalid()) {
                <small id="email-error" class="text-red-700 text-xs" role="alert">
                  Please enter a valid email address.
                </small>
              }
            </div>

            <!-- Password -->
            <div class="flex flex-col gap-1 mb-6">
              <label for="password" class="text-sm font-medium text-gray-700">
                Password
              </label>
              <div class="relative">
                <span class="absolute inset-y-0 left-3 flex items-center pointer-events-none z-10">
                  <lucide-icon [img]="LockIcon" class="w-4 h-4 text-gray-400" />
                </span>
                <p-password
                  inputId="password"
                  formControlName="password"
                  [feedback]="false"
                  [toggleMask]="true"
                  autocomplete="current-password"
                  placeholder="Your password"
                  styleClass="w-full"
                  inputStyleClass="w-full pl-10"
                  aria-required="true"
                  [attr.aria-describedby]="passwordInvalid() ? 'password-error' : null"
                />
              </div>
              @if (passwordInvalid()) {
                <small id="password-error" class="text-red-700 text-xs" role="alert">
                  Password is required.
                </small>
              }
            </div>

            <!-- Server error -->
            @if (authStore.authError()) {
              <p-message
                severity="error"
                [text]="authStore.authError()!"
                styleClass="w-full mb-4"
                role="alert"
              />
            }

            <!-- Submit -->
            <p-button
              type="submit"
              label="Sign in"
              styleClass="w-full"
              [loading]="authStore.isLoading()"
              [disabled]="authStore.isLoading()"
            />
          </form>

          <div class="text-sm text-gray-500 text-center mt-6 flex flex-col gap-1">
            <span>
              No account yet?
              <a routerLink="/register" class="text-primary underline">Create one</a>
            </span>
            <a routerLink="/forgot-password" class="text-primary underline">
              Forgot your password?
            </a>
          </div>
        </div>
      </div>
    </div>
  `,
})
export class LoginComponent {
  protected readonly authStore = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  protected readonly DogIcon = Dog;
  protected readonly MailIcon = Mail;
  protected readonly LockIcon = Lock;
  protected readonly EyeIcon = Eye;
  protected readonly EyeOffIcon = EyeOff;

  loginForm: FormGroup = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(1)]],
  });

  protected emailInvalid = signal(false);
  protected passwordInvalid = signal(false);

  async onSubmit(): Promise<void> {
    this.loginForm.markAllAsTouched();

    const emailCtrl = this.loginForm.get('email');
    const passwordCtrl = this.loginForm.get('password');

    this.emailInvalid.set(emailCtrl != null ? !emailCtrl.valid : true);
    this.passwordInvalid.set(passwordCtrl != null ? !passwordCtrl.valid : true);

    if (this.loginForm.invalid) return;

    try {
      await this.authStore.login(
        emailCtrl!.value as string,
        passwordCtrl!.value as string,
      );
      await this.router.navigate(['/']);
    } catch {
      // Error is already set in the store; template reads authStore.authError()
    }
  }
}
