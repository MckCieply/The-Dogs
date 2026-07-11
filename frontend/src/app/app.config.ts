import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  isDevMode,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import {
  provideHttpClient,
  withFetch,
  withInterceptors,
} from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideServiceWorker } from '@angular/service-worker';
import { providePrimeNG } from 'primeng/config';
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';

// Aura's default primary palette maps 500 to emerald.500 (#10b981), which measures only
// 2.53:1 against white — an axe-core serious color-contrast violation on the login page's
// "Sign in" button and .text-primary links (both render/derive from --p-primary-500). Shift
// the palette's mid-to-dark shades down two steps so the resting color is emerald.700
// (#047857, ~5.5:1 against white) while hover/active stay progressively darker.
const AccessiblePrimaryPreset = definePreset(Aura, {
  semantic: {
    primary: {
      500: '{emerald.700}',
      600: '{emerald.800}',
      700: '{emerald.900}',
    },
  },
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    provideAnimationsAsync(),
    providePrimeNG({
      theme: {
        preset: AccessiblePrimaryPreset,
        options: {
          prefix: 'p',
          darkModeSelector: '.app-dark',
          cssLayer: false,
        },
      },
    }),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
