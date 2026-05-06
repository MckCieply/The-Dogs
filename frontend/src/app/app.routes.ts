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
    path: '**',
    redirectTo: '',
  },
];
