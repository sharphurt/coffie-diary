import { defineConfig, devices } from '@playwright/test';
if (!process.env.E2E_BASE_URL || !process.env.OWNER_PASSWORD) {
  throw new Error('Use tools/e2e.ps1 or set E2E_BASE_URL and OWNER_PASSWORD for a dedicated test deployment.');
}
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
  ],
});
