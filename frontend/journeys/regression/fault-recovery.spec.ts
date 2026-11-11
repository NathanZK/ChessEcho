import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DYNAMIC_EVAL_MOCK_PUZZLE,
} from '../fixtures/mockApi';
import {
  STATE_ORACLES,
  ensureDiscoveryState,
  createInitialContext,
  executeConcreteAction,
  DEFAULT_PRACTICE_FEN,
  type PracticeModelContext,
} from '../model/practiceModel';

/**
 * Fault Recovery Regression Test (Issue #779 / Milestone #771 Reconciliation)
 *
 * Verifies exact evaluation retry semantics:
 * 1. An evaluation HTTP 500 error produces EVALUATION_ERROR.
 * 2. Clicking Retry evaluation re-submits the exact identical request.
 * 3. Successful evaluation restores the application deterministically to CORRECT.
 * 4. Failed attempt and retry do NOT double-increment attempt/solved counters.
 */
test.describe('Deterministic Fault Injection & Retry Recovery (Issue #779)', () => {
  test('recovers from evaluation HTTP 500 error on retry, preserving exact request payload and counter integrity', async ({ page }) => {
    // 1. Intercept evaluation requests to record requests across attempts
    const evaluationRequests: Array<{ url: string; fen: string | null; move: string | null }> = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/puzzles/evaluate-move')) {
        const parsed = new URL(req.url());
        evaluationRequests.push({
          url: req.url(),
          fen: parsed.searchParams.get('fen'),
          move: parsed.searchParams.get('move'),
        });
      }
    });

    // 2. Mount practice view with route fault failureCount: 1 on /api/puzzles/evaluate-move
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DYNAMIC_EVAL_MOCK_PUZZLE],
      evaluateMoveFault: {
        fault: 'server_error_500',
        failureCount: 1,
      },
    });

    // 3. Load application on Practice tab
    await page.goto('/#puzzles');
    let context: PracticeModelContext = {
      ...createInitialContext(),
      faultInjectionEnabled: true,
    };

    // Transition DISCOVERY -> PRACTICE_IDLE
    await ensureDiscoveryState(page);
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
    await executeConcreteAction(page, 'SELECT_PUZZLE', context);
    context = {
      ...context,
      puzzleLoaded: true,
      startingFen: DEFAULT_PRACTICE_FEN,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // Initial counter verification: 0 failed, 0 solved
    await expect(page.getByText(/Failed:\s*0\b/i)).toBeVisible();
    await expect(page.getByText(/Solved:\s*0\b/i)).toBeVisible();

    // 4. Execute SUBMIT_FAULT with target move d4. First request returns HTTP 500.
    await executeConcreteAction(page, 'SUBMIT_FAULT', context);

    // Verify UI displays "Move Evaluation Failed" banner and <button>Retry evaluation</button>
    context = {
      ...context,
      pendingEvaluationMove: 'd4',
      pendingEvaluationPayload: {
        fen: DEFAULT_PRACTICE_FEN,
        move: 'd4',
      },
    };
    await STATE_ORACLES.EVALUATION_ERROR.assert(page, context, expect);
    const retryButton = page.getByRole('button', { name: /Retry evaluation/i });
    await expect(retryButton).toBeVisible();

    // 5. Click "Retry evaluation" button (strictly distinguishing retry from reset)
    await retryButton.click();
    await page.waitForTimeout(100);

    // 6. Second request returns HTTP 200. Error banner clears, board advances to post-move position, and state transitions deterministically to CORRECT
    context = {
      ...context,
      attemptCount: 1,
      expectedSolvedCount: 1,
      expectedFailedCount: 0,
      solved: true,
      lastMoveSan: 'd4',
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedHistory: ['d4'],
      expectedSideToMove: 'black',
      pendingEvaluationMove: null,
      pendingEvaluationPayload: null,
    };
    await STATE_ORACLES.CORRECT.assert(page, context, expect);

    // 7. Assert exact request identity between initial (500) and retry (200) requests
    expect(evaluationRequests.length).toBeGreaterThanOrEqual(2);
    expect(evaluationRequests[0].url).toEqual(evaluationRequests[1].url);
    expect(evaluationRequests[0].fen).toEqual(evaluationRequests[1].fen);
    expect(evaluationRequests[0].move).toEqual(evaluationRequests[1].move);

    // 8. Assert counter integrity: verifies that failed attempt + retry do NOT double-increment attempt/solved counters
    await expect(page.getByText(/Failed:\s*0\b/i)).toBeVisible();
    await expect(page.getByText(/Solved:\s*1\b/i)).toBeVisible();
  });
});
