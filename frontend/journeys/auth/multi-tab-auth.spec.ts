import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_ACCOUNT } from '../fixtures/mockApi';

test.describe('Multi-Tab Authentication Synchronization (ADD-15 / ADR-04)', () => {
  test('logging out in Tab A terminates authenticated session and purges cached data in Tab B without page reload', async ({
    context,
  }) => {
    // 1. Create two pages in the same browser context sharing origin and session state
    const pageA = await context.newPage();
    const pageB = await context.newPage();

    // 2. Setup mocks for both pages with authenticated session and connected account
    const mockHandleA = await setupJourneyApiMocks(pageA, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
    });
    const mockHandleB = await setupJourneyApiMocks(pageB, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
    });

    // 3. Load both tabs on the application home
    await pageA.goto('/', { waitUntil: 'domcontentloaded' });
    await pageB.goto('/', { waitUntil: 'domcontentloaded' });

    // 4. Verify both pages mount in authenticated state showing connected account
    await expect(pageA.getByRole('button', { name: /Sign out/i })).toBeVisible();
    await expect(pageB.getByRole('button', { name: /Sign out/i })).toBeVisible();
    await expect(pageA.getByText('Signed in to ChessProbe').first()).toBeVisible();
    await expect(pageB.getByText('Signed in to ChessProbe').first()).toBeVisible();

    // Ensure mock backend now treats subsequent requests as unauthenticated once logout occurs
    mockHandleA.setAuthenticated(false);
    mockHandleB.setAuthenticated(false);

    // 5. Trigger logout in Tab A
    await pageA.getByRole('button', { name: /Sign out/i }).click();

    // 6. Verify Tab A immediately transitions to unauthenticated guest status
    await expect(pageA.getByRole('link', { name: /Sign In/i })).toBeVisible();
    await expect(pageA.getByRole('link', { name: /Register/i })).toBeVisible();
    await expect(pageA.getByRole('button', { name: /Sign out/i })).toHaveCount(0);

    // 7. Assert Tab B receives BroadcastChannel('chessprobe_auth') LOGOUT message and transitions
    // to unauthenticated status immediately WITHOUT manual page reload
    await expect(pageB.getByRole('link', { name: /Sign In/i })).toBeVisible();
    await expect(pageB.getByRole('link', { name: /Register/i })).toBeVisible();
    await expect(pageB.getByRole('button', { name: /Sign out/i })).toHaveCount(0);
    await expect(pageB.getByText('Signed in to ChessProbe')).toHaveCount(0);

    // Cleanup pages
    await pageA.close();
    await pageB.close();
  });
});
