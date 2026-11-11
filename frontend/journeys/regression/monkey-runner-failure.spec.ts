import fs from 'node:fs';
import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  MOCK_PUZZLES,
} from '../fixtures/mockApi';
import {
  ensureDiscoveryState,
  createPracticeContext,
  executeConcreteAction,
} from '../model/practiceModel';
import { runConstrainedMonkey } from '../model/monkeyRunner';

/**
 * Unassisted Monkey-Runner Failure-Propagation Test (Section 3.2)
 *
 * Verifies that when rendered DOM diverges during live unassisted monkey execution:
 * 1. runConstrainedMonkey halts and catches the oracle failure without manual assertion.
 * 2. Structured error diagnostics report PIECE_TOPOLOGY_MISMATCH, affected square d4,
 *    expected piece wP, and observed piece bP.
 * 3. Diagnostic artifacts (screenshot and trace zip) are captured to disk.
 */
test.describe('Unassisted Monkey-Runner Failure Propagation (Issue #779 / Section 3.2)', () => {
  test('unassisted monkey runner halts on DOM corruption, catching oracle error with artifacts', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: MOCK_PUZZLES,
    });

    await page.goto('/#puzzles');
    await ensureDiscoveryState(page);

    const fixture = MOCK_PUZZLES[0];
    const initialContext = createPracticeContext(fixture, MOCK_PUZZLES, 0, {
      corpus: MOCK_PUZZLES,
      expectedSolvedCount: 0,
      expectedFailedCount: 0,
      faultInjectionEnabled: false,
      timerMode: null,
    });

    // Mount practice view on interactive board in PRACTICE_IDLE
    await executeConcreteAction(page, 'SELECT_PUZZLE', initialContext);

    let corruptionInjected = false;
    let corruptedStep = 0;
    const testSeed = 1;

    const result = await runConstrainedMonkey({
      page,
      seed: testSeed,
      actionBudget: 15,
      initialState: 'PRACTICE_IDLE',
      initialContext,
      traceMode: 'failures-only',
      screenshotOnFailure: true,
      stepHook: async (step, action) => {
        // Condition: inject DOM mutation when SUBMIT_OPTIMAL is dispatched
        if (action === 'SUBMIT_OPTIMAL' && !corruptionInjected) {
          corruptionInjected = true;
          corruptedStep = step;
          await page
            .locator('[data-square="d4"] [data-piece]')
            .evaluate((el) => el.setAttribute('data-piece', 'bP'));
        }
      },
    });

    // 1. Invariant: corruption was encountered and executed
    expect(corruptionInjected).toBe(true);
    expect(corruptedStep).toBeGreaterThanOrEqual(1);

    // 2. Unassisted runner halts and marks run as unsuccessful
    expect(result.success).toBe(false);
    expect(result.executedSteps).toBe(corruptedStep);
    expect(result.error).toBeDefined();

    // 3. Error diagnostics include structured error code and expected vs actual piece & square
    const errorMessage = result.error?.message ?? '';
    expect(errorMessage).toContain('PIECE_TOPOLOGY_MISMATCH');
    expect(errorMessage).toContain('Square "d4"');
    expect(errorMessage).toContain('expected piece "wP"');
    expect(errorMessage).toContain('found "bP"');
    expect(errorMessage).toContain(`Seed: ${testSeed}`);
    expect(errorMessage).toContain(`Step: ${corruptedStep}`);

    // 4. Artifact verification: screenshot and trace archive exist on disk
    expect(result.screenshotPath).toBeDefined();
    expect(fs.existsSync(result.screenshotPath!)).toBe(true);

    expect(result.tracePath).toBeDefined();
    expect(fs.existsSync(result.tracePath!)).toBe(true);
  });
});
