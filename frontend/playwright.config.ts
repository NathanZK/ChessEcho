import { defineConfig, devices } from '@playwright/test';

const isNightly = !!process.env.PLAYWRIGHT_NIGHTLY;
const isCI = !!process.env.CI;

export default defineConfig({
  testDir: './journeys',
  testMatch: /.*\.spec\.ts$/,
  testIgnore: process.env.PLAYWRIGHT_INCLUDE_MONKEY ? [] : ['**/*-monkey.spec.ts'],
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isNightly ? 2 : 0,
  workers: isCI ? 1 : undefined,
  reporter: 'list',
  outputDir: './test-results',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: isCI ? 'npx next start -p 3000' : 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !isCI,
    timeout: 120_000,
  },
});
