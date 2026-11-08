import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_ACCOUNT } from './fixtures/mockApi';
import {
  SESSION_TRANSITIONS,
  SESSION_STATE_ORACLES,
  createInitialSessionContext,
  SessionAbstractState,
  SessionAction,
  SessionModelContext,
} from './model/sessionModel';
import { exploreAbstractStates } from './model/bfsExplorer';

test.describe('Model-Based Testing: Session Authentication & Lifecycle', () => {
  test('BFS exploration finds shortest paths across authentication lifecycle', () => {
    const exploration = exploreAbstractStates<SessionAbstractState, SessionAction, SessionModelContext>(
      'GUEST',
      SESSION_TRANSITIONS,
      4,
      createInitialSessionContext(),
      (state, ctx) => `${state}:${ctx.authenticated}:${ctx.hasError}`
    );

    expect(exploration.reachableStates.has('AUTH_ERROR')).toBe(true);
    expect(exploration.reachableStates.has('AUTHENTICATED')).toBe(true);
    expect(exploration.reachableStates.has('GUEST')).toBe(true);

    const errorPath = exploration.paths.find((p) => p.targetState === 'AUTH_ERROR');
    expect(errorPath).toBeDefined();
    expect(errorPath?.actions).toEqual(['START_AUTH', 'AUTH_FAILURE']);

    const authPath = exploration.paths.find((p) => p.targetState === 'AUTHENTICATED');
    expect(authPath).toBeDefined();
    expect(authPath?.actions).toEqual(['START_AUTH', 'AUTH_SUCCESS']);
  });

  test('Path 1: Credential authentication failure and recovery to authenticated session', async ({ page }) => {
    await setupJourneyApiMocks(page, { authenticated: false });

    await page.goto('/');
    await SESSION_STATE_ORACLES.GUEST.assert(page, expect);

    await page.goto('/login');
    await SESSION_STATE_ORACLES.AUTHENTICATING.assert(page, expect);

    // Invalid credential submission
    await page.getByLabel(/Email/i).fill('invalid@example.com');
    await page.getByLabel(/^Password/i).fill('wrongpass');
    await page.getByRole('button', { name: /Sign in/i }).click();

    await SESSION_STATE_ORACLES.AUTH_ERROR.assert(page, expect);
    await expect(page.locator('p[role="alert"]')).toContainText('Invalid email or password');

    // Valid credential recovery
    await page.getByLabel(/Email/i).fill('player@example.com');
    await page.getByLabel(/^Password/i).fill('password123');
    await page.getByRole('button', { name: /Sign in/i }).click();

    await page.waitForURL('/');
    await SESSION_STATE_ORACLES.AUTHENTICATED.assert(page, expect);
  });

  test('Path 2: Authenticated session lifecycle and clean logout to guest state', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
    });

    await page.goto('/');
    await SESSION_STATE_ORACLES.AUTHENTICATED.assert(page, expect);

    const signOutButton = page.getByRole('button', { name: /Sign out/i });
    await expect(signOutButton).toBeVisible();
    await signOutButton.click();

    await SESSION_STATE_ORACLES.GUEST.assert(page, expect);
  });
});
