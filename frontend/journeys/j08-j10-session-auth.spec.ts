import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks } from './fixtures/mockApi';

test.describe('Journeys J08-J10: Authentication and Session Lifecycle', () => {
  test('J08: guest bootstrap displays sign in and register navigation without errors', async ({ page }) => {
    // 1. Setup unauthenticated guest mocks
    await setupJourneyApiMocks(page, { authenticated: false });

    // 2. Visit root
    await page.goto('/');

    // 3. Verify unauthenticated header links are present
    const signInLink = page.getByRole('link', { name: /Sign In/i });
    const registerLink = page.getByRole('link', { name: /Register/i });
    await expect(signInLink).toBeVisible();
    await expect(registerLink).toBeVisible();

    // 4. Verify no error banners exist
    await expect(page.locator('main [role="alert"]')).toHaveCount(0);
  });

  test('J09 & J10: registration flow with password visibility toggle redirects to authenticated session', async ({ page }) => {
    // 1. Setup unauthenticated mocks
    await setupJourneyApiMocks(page, { authenticated: false });

    // 2. Navigate to registration
    await page.goto('/register');

    // 3. Test password visibility toggle
    const passwordInput = page.getByLabel(/^Password/i);
    const toggleButton = page.getByRole('button', { name: 'Show password' });
    await expect(passwordInput).toHaveAttribute('type', 'password');
    await expect(toggleButton).toBeVisible();

    await toggleButton.click();
    await expect(passwordInput).toHaveAttribute('type', 'text');
    await expect(page.getByRole('button', { name: 'Hide password' })).toBeVisible();

    await page.getByRole('button', { name: 'Hide password' }).click();
    await expect(passwordInput).toHaveAttribute('type', 'password');

    // 4. Fill credentials and submit
    await page.getByLabel(/Email/i).fill('newuser@example.com');
    await passwordInput.fill('securepass123');

    await page.getByRole('button', { name: /Create account/i }).click();

    // 5. Verify redirection to home and authenticated status
    await page.waitForURL('/');
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Sign out/i })).toBeVisible();
  });

  test('J10: login error handling, successful sign in, and logout lifecycle', async ({ page }) => {
    // 1. Setup unauthenticated mocks
    await setupJourneyApiMocks(page, { authenticated: false });

    // 2. Navigate to login
    await page.goto('/login');

    // 3. Submit invalid credentials and verify error alert
    await page.getByLabel(/Email/i).fill('invalid@example.com');
    await page.getByLabel(/^Password/i).fill('wrongpass');
    await page.getByRole('button', { name: /Sign in/i }).click();

    await expect(page.locator('p[role="alert"]')).toContainText('Invalid email or password');

    // 4. Submit valid credentials and verify authenticated redirect
    await page.getByLabel(/Email/i).fill('player@example.com');
    await page.getByLabel(/^Password/i).fill('password123');
    await page.getByRole('button', { name: /Sign in/i }).click();

    await page.waitForURL('/');
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible();
    const signOutButton = page.getByRole('button', { name: /Sign out/i });
    await expect(signOutButton).toBeVisible();

    // 5. Perform logout
    await signOutButton.click();

    // 6. Verify session cleared and guest controls restored
    await expect(page.getByRole('link', { name: /Sign In/i })).toBeVisible();
    await expect(page.getByRole('link', { name: /Register/i })).toBeVisible();
    await expect(page.getByText('Signed in to ChessProbe')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Sign out/i })).toHaveCount(0);
  });

  test('J08/J10: simulated session expiration transitions cleanly to guest state', async ({ page }) => {
    // 1. Start with authenticated session
    const mockHandle = await setupJourneyApiMocks(page, { authenticated: true });

    await page.goto('/');
    await expect(page.getByText('Signed in to ChessProbe').first()).toBeVisible();

    // 2. Simulate 401 session expiration
    mockHandle.setAuthenticated(false);

    // 3. Reload page to trigger fresh session bootstrap
    await page.reload();

    // 4. Verify clean transition to unauthenticated guest mode
    await expect(page.getByRole('link', { name: /Sign In/i })).toBeVisible();
    await expect(page.getByRole('link', { name: /Register/i })).toBeVisible();
    await expect(page.getByText('Signed in to ChessProbe')).toHaveCount(0);
  });
});
