import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_ACCOUNT } from './fixtures/mockApi';
import {
  SESSION_STATE_ORACLES,
  SessionModel,
} from './model/sessionModel';
import { findBfsPath, executeModelPath } from './model/modelRunner';

test.describe('Model-Based Testing: Session Authentication & Lifecycle', () => {
  test('BFS exploration finds shortest paths across authentication lifecycle', () => {
    const errorPath = findBfsPath(SessionModel, 'AUTH_ERROR');
    expect(errorPath).toBeDefined();
    expect(errorPath).toEqual(['SUBMIT_INVALID_CREDENTIALS']);

    const authPath = findBfsPath(SessionModel, 'AUTHENTICATED');
    expect(authPath).toBeDefined();
    expect(authPath).toEqual(['SUBMIT_VALID_CREDENTIALS']);

    const guestPath = findBfsPath(SessionModel, 'GUEST', 'AUTHENTICATED', { authenticated: true, hasError: false });
    expect(guestPath).toBeDefined();
    expect(guestPath).toEqual(['LOGOUT']);
  });

  test('Path 1: Credential authentication failure and recovery to authenticated session', async ({ page }) => {
    await setupJourneyApiMocks(page, { authenticated: false });

    // Step 1: Execute navigation and invalid credentials submission to reach AUTH_ERROR
    const failureResult = await executeModelPath(page, SessionModel, [
      'NAVIGATE_LOGIN',
      'SUBMIT_INVALID_CREDENTIALS',
    ]);

    expect(failureResult.success).toBe(true);
    expect(failureResult.finalState).toBe('AUTH_ERROR');
    expect(failureResult.visitedStates.has('AUTH_ERROR')).toBe(true);
    await expect(page.locator('p[role="alert"]')).toContainText('Invalid email or password');

    // Step 2: Recover session by submitting valid credentials from AUTH_ERROR state
    const recoveryResult = await executeModelPath(page, SessionModel, ['SUBMIT_VALID_CREDENTIALS'], {
      initialState: failureResult.finalState,
      initialContext: failureResult.finalContext,
      stepOffset: failureResult.actionLog.length,
    });

    expect(recoveryResult.success).toBe(true);
    expect(recoveryResult.finalState).toBe('AUTHENTICATED');
    expect(recoveryResult.visitedStates.has('AUTHENTICATED')).toBe(true);
    await SESSION_STATE_ORACLES.AUTHENTICATED.assert(page, expect);
  });

  test('Path 2: Authenticated session lifecycle and clean logout to guest state', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
    });

    await page.goto('/');
    await SESSION_STATE_ORACLES.AUTHENTICATED.assert(page, expect);

    const logoutPath = findBfsPath(SessionModel, 'GUEST', 'AUTHENTICATED', {
      authenticated: true,
      hasError: false,
    })!;

    const result = await executeModelPath(page, SessionModel, logoutPath, {
      initialState: 'AUTHENTICATED',
      initialContext: { authenticated: true, hasError: false },
    });

    expect(result.success).toBe(true);
    expect(result.finalState).toBe('GUEST');
    expect(result.visitedStates.has('GUEST')).toBe(true);
    await SESSION_STATE_ORACLES.GUEST.assert(page, expect);
  });
});
