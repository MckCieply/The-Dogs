import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./components/home/home.component').then((m) => m.HomeComponent),
    canActivate: [authGuard],
    title: 'Home — The Dogs',
  },
  {
    path: 'login',
    loadComponent: () =>
      import('./components/auth/login.component').then(
        (m) => m.LoginComponent,
      ),
    title: 'Sign in — The Dogs',
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./components/auth/register.component').then(
        (m) => m.RegisterComponent,
      ),
    title: 'Create account — The Dogs',
  },
  {
    path: 'confirm-email',
    loadComponent: () =>
      import('./components/auth/confirm-email.component').then(
        (m) => m.ConfirmEmailComponent,
      ),
    title: 'Confirm your email — The Dogs',
  },
  {
    path: 'forgot-password',
    loadComponent: () =>
      import('./components/auth/forgot-password.component').then(
        (m) => m.ForgotPasswordComponent,
      ),
    title: 'Reset password — The Dogs',
  },
  {
    path: 'reset-password',
    loadComponent: () =>
      import('./components/auth/reset-password.component').then(
        (m) => m.ResetPasswordComponent,
      ),
    title: 'Choose a new password — The Dogs',
  },
  {
    path: '**',
    redirectTo: '',
  },
];
