import { defineConfig, devices } from '@playwright/test';

const isNightly = !!process.env.PLAYWRIGHT_NIGHTLY;
const isCI = !!process.env.CI;
const isExploratory =
  process.env.PLAYWRIGHT_SUITE === 'exploratory' || !!process.env.PLAYWRIGHT_INCLUDE_MONKEY;
const isE2E = !!process.env.PLAYWRIGHT_RUN_E2E;

const defaultIgnored = [
  '**/*-monkey.spec.ts',
  '**/general-defect-detection.spec.ts',
  '**/performance/heapTelemetry.spec.ts',
];

export default defineConfig({
  testDir: './journeys',
  testMatch: /.*\.spec\.ts$/,
  testIgnore: isE2E
    ? (isExploratory ? [] : defaultIgnored)
    : (isExploratory ? ['**/e2e-*.spec.ts'] : [...defaultIgnored, '**/e2e-*.spec.ts']),
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
    command: isCI || isE2E ? 'npx next start -p 3000' : 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !isCI && !isE2E,
    timeout: 120_000,
  },
});
