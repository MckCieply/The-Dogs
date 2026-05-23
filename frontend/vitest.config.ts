import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['src/test-setup.ts'],
    exclude: [
      // Playwright e2e tests — run separately via `npm run e2e`
      'e2e/**',
      'node_modules/**',
    ],
  },
});
