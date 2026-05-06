import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { LucideAngularModule, LogOut, Dog } from 'lucide-angular';
import { AuthStore } from '../../services/auth.store';

/**
 * Placeholder home page — protected by authGuard.
 * Displays the logged-in trainer's email and a logout button.
 * Real feature modules (dogs, notes, scheduler) will be lazy-loaded here.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [ButtonModule, LucideAngularModule],
  template: `
    <div class="min-h-screen bg-gray-50">
      <!-- Top navbar -->
      <header class="bg-white shadow-sm">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <lucide-icon [img]="DogIcon" class="w-6 h-6 text-primary" />
            <span class="text-lg font-semibold text-gray-900">The Dogs</span>
          </div>
          <p-button
            [text]="true"
            severity="secondary"
            (onClick)="logout()"
            [loading]="authStore.isLoading()"
            aria-label="Log out"
          >
            <lucide-icon [img]="LogOutIcon" class="w-4 h-4 mr-2" />
            Log out
          </p-button>
        </div>
      </header>

      <!-- Main content -->
      <main class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
        <div class="text-center">
          <h2 class="text-2xl font-bold text-gray-900 mb-2">
            Welcome, {{ authStore.currentUser()?.email }}
          </h2>
          <p class="text-gray-500">
            Dog management features are coming soon.
          </p>
        </div>
      </main>
    </div>
  `,
})
export class HomeComponent {
  protected readonly authStore = inject(AuthStore);
  private readonly router = inject(Router);

  protected readonly DogIcon = Dog;
  protected readonly LogOutIcon = LogOut;

  async logout(): Promise<void> {
    await this.authStore.logout();
    await this.router.navigate(['/login']);
  }
}
