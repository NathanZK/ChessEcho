import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

describe('Playwright Configuration', () => {
  const originalEnv = { ...process.env };

  beforeEach(() => {
    vi.resetModules();
    process.env = { ...originalEnv };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  it('configures retries: 0 in standard CI runs and confines retries: 2 to nightly diagnostic runs', async () => {
    process.env.CI = 'true';
    delete process.env.PLAYWRIGHT_NIGHTLY;

    const { default: configCI } = await import('../../playwright.config');
    expect(configCI.retries).toBe(0);

    vi.resetModules();
    process.env.CI = 'true';
    process.env.PLAYWRIGHT_NIGHTLY = '1';

    const { default: configNightly } = await import('../../playwright.config');
    expect(configNightly.retries).toBe(2);
  });

  it('configures failure artifact capture with trace: retain-on-failure', async () => {
    const { default: config } = await import('../../playwright.config');
    expect(config.use?.trace).toBe('retain-on-failure');
  });

  it('configures webServer to run production server in CI and dev server locally', async () => {
    process.env.CI = 'true';
    const { default: configCI } = await import('../../playwright.config');
    const webServerCI = Array.isArray(configCI.webServer) ? configCI.webServer[0] : configCI.webServer;
    expect(webServerCI?.command).toBe('npx next start -p 3000');
    expect(webServerCI?.reuseExistingServer).toBe(false);

    vi.resetModules();
    delete process.env.CI;
    const { default: configLocal } = await import('../../playwright.config');
    const webServerLocal = Array.isArray(configLocal.webServer) ? configLocal.webServer[0] : configLocal.webServer;
    expect(webServerLocal?.command).toBe('npm run dev');
    expect(webServerLocal?.reuseExistingServer).toBe(true);
  });
});
