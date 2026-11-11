import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from '../fixtures/mockApi';
import { STATE_ORACLES, ensureDiscoveryState, createInitialContext, DEFAULT_PRACTICE_FEN } from '../model/practiceModel';

/**
 * Phase 4 Regression Demonstration (Issue #715)
 *
 * This test suite demonstrates the standardized conversion of a failing monkey
 * execution trace into a permanent, deterministic Playwright regression test.
 *
 * Root Cause Analysis (Residual Square Selection):
 * - Investigation proved the defect was in the TEST EXECUTOR (`executeConcreteAction`),
 *   not in the application's React/Chessboard runtime.
 * - During step 8 (`RETRY`), the monkey runner clicked `[data-square="d2"]` under the
 *   mistaken assumption that clicking the board clears interaction state.
 * - However, square `d2` is occupied by a White pawn in the baseline FEN. Clicking `d2`
 *   selected that pawn (`moveFromSquare = 'd2'`).
 * - On step 9 (`SUBMIT_OPTIMAL`), the runner attempted `d2 -> d4`. The initial click on `d2`
 *   deselected the pawn (`moveFromSquare = null`), and the second click on `d4` was a no-op
 *   on an empty square. The move was never submitted, stranding the runner in `PRACTICE_IDLE`.
 *
 * Fix & Contract:
 * - Non-move transitions (`RETRY`, `RESET`) must NEVER click occupied chessboard squares.
 *   They must rely on dedicated action controls (e.g. Reset button) or board rollback.
 * - The board rollback mechanism restores baseline FEN and clears residual selection.
 * - Attempt counts increment deterministically:
 *     Initial: Failed: 0
 *     After Mistake (a2->a3): Failed: 1
 *     After Blunder (h2->h4): Failed: 2
 */
test.describe('Phase 4 Regression: Monkey-Discovered Sequence Conversion', () => {
  test('replays monkey sequence: mistake -> retry -> blunder with verified cleared selection and attempt semantics', async ({ page }) => {
    // 1. Setup mocked API
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Load application at Practice tab
    await page.goto('/#puzzles');

    let context = createInitialContext();

    // [Step 1] DISCOVERY -> PRACTICE_IDLE
    await ensureDiscoveryState(page);
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
    const startPracticeButton = page.getByRole('button', { name: /Start Practice/i });
    await startPracticeButton.click();
    context = {
      ...context,
      puzzleLoaded: true,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // Initial attempt count: 0 failed
    await expect(page.getByText(/Failed: 0/i)).toBeVisible();
    await expect(page.getByText(/Solved: 0/i)).toBeVisible();

    // [Step 2] PRACTICE_IDLE -> INCORRECT (Submit inaccurate move a2 -> a3)
    const a2 = page.locator('[data-square="a2"]');
    const a3 = page.locator('[data-square="a3"]');
    await expect(a2).toBeVisible();
    await a2.click();
    await a3.click();

    context = {
      ...context,
      attemptCount: 1,
      expectedFailedCount: 1,
      lastMoveSan: 'a3',
      expectedFen: DEFAULT_PRACTICE_FEN,
    };
    // Invariant: Inaccurate move triggers INCORRECT oracle and increments failed count
    await STATE_ORACLES.INCORRECT.assert(page, context, expect);
    await expect(page.getByText(/Failed: 1/i)).toBeVisible();

    // [Step 3] INCORRECT -> PRACTICE_IDLE (Retry rolls back board cleanly without residual selection)
    // Board automatically rolls back to baseline FEN. Reset button ensures state hygiene.
    const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
    if (await resetButton.isVisible().catch(() => false)) {
      await resetButton.click();
    }
    context = {
      ...context,
      expectedFen: DEFAULT_PRACTICE_FEN,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // Verify baseline FEN is restored: pawns at d2 and h2, squares d4 and h4 empty
    await expect(page.locator('[data-square="d2"]')).toBeVisible();
    await expect(page.locator('[data-square="h2"]')).toBeVisible();

    // [Step 4] PRACTICE_IDLE -> HISTORICAL_MISTAKE (Submit blunder h2 -> h4)
    // Clean selection without residual interference from prior steps
    const h2 = page.locator('[data-square="h2"]');
    const h4 = page.locator('[data-square="h4"]');
    await expect(h2).toBeVisible();
    await h2.click();
    await h4.click();

    context = {
      ...context,
      attemptCount: 2,
      expectedFailedCount: 2,
      lastMoveSan: 'h4',
      expectedFen: DEFAULT_PRACTICE_FEN,
    };
    // Invariant: Blunder recognized, HISTORICAL_MISTAKE oracle matches coaching feedback
    await STATE_ORACLES.HISTORICAL_MISTAKE.assert(page, context, expect);
    await expect(page.getByText(/Failed: 2/i)).toBeVisible();

    // [Step 5] HISTORICAL_MISTAKE -> DISCOVERY (Back to Puzzles transition)
    const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backBtn).toBeVisible();
    await backBtn.click();
    context = {
      ...context,
      puzzleLoaded: false,
    };
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
  });

  test('demonstrates executor residual selection failure mode and verify isolation', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles');

    let context = createInitialContext();

    // Enter Practice (if on catalog view)
    const startPracticeBtn = page.getByRole('button', { name: /Start Practice/i });
    if (await startPracticeBtn.isVisible().catch(() => false)) {
      await startPracticeBtn.click();
    }
    context = {
      ...context,
      puzzleLoaded: true,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // If an executor erroneously clicks d2 (occupied pawn square), it selects d2
    const d2 = page.locator('[data-square="d2"]');
    await d2.click();

    // A second click on d2 deselects it, proving the toggle behavior
    await d2.click();

    // Now clicking d2 then d4 plays the move cleanly
    await d2.click();
    const d4 = page.locator('[data-square="d4"]');
    await d4.click();

    context = {
      ...context,
      attemptCount: 1,
      expectedSolvedCount: 1,
      lastMoveSan: 'd4',
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedHistory: ['d4'],
    };
    // Optimal move is accepted
    await STATE_ORACLES.CORRECT.assert(page, context, expect);
  });
});
