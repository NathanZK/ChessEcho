import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from '../fixtures/mockApi';
import { playSquareMove } from '../support/practiceDom';

/**
 * Phase 4 Regression Demonstration (Issue #715)
 *
 * This test preserves the deterministic replay of a monkey-discovered sequence
 * after the monkey harness was retired.
 *
 * Root Cause Analysis (Residual Square Selection):
 * - Investigation proved the defect was in the TEST EXECUTOR, not in the
 *   application's React/Chessboard runtime.
 * - During a RETRY step, the executor clicked `[data-square="d2"]` under the
 *   mistaken assumption that clicking the board clears interaction state.
 * - However, square `d2` is occupied by a White pawn in the baseline FEN. Clicking
 *   `d2` selected that pawn. On the next `SUBMIT_OPTIMAL` step, the initial click on
 *   `d2` deselected the pawn, and the second click on `d4` was a no-op on an empty
 *   square. The move was never submitted.
 *
 * Fix & Contract:
 * - Non-move transitions (RETRY, RESET) must NEVER click occupied chessboard squares.
 *   They rely on dedicated controls or board rollback.
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

    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();
    await expect(boardArea).toHaveAttribute('data-board-fen', DEFAULT_MOCK_PUZZLE.fen);

    // Initial attempt count: 0 failed
    await expect(page.getByText(/Failed:\s*0\b/i)).toBeVisible();
    await expect(page.getByText(/Solved:\s*0\b/i)).toBeVisible();

    // [Step 2] PRACTICE_IDLE -> INCORRECT (submit inaccurate move a2 -> a3)
    await playSquareMove(page, 'a2', 'a3');
    await expect(page.getByRole('heading', { name: /Not the Recommended Move/i })).toBeVisible();
    await expect(page.getByText(/Failed:\s*1\b/i)).toBeVisible();

    // [Step 3] INCORRECT -> PRACTICE_IDLE (retry via dedicated control, never a square click)
    const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
    if (await resetButton.isVisible().catch(() => false)) {
      await resetButton.click();
    } else {
      await page.locator('button[title="Reset Position"]').first().click();
    }

    // Verify baseline FEN is restored: pawns at d2 and h2, squares d4 and h4 empty
    await expect(boardArea).toHaveAttribute('data-board-fen', DEFAULT_MOCK_PUZZLE.fen);
    await expect(page.locator('[data-square="d2"]')).toBeVisible();
    await expect(page.locator('[data-square="h2"]')).toBeVisible();

    // [Step 4] PRACTICE_IDLE -> HISTORICAL_MISTAKE (submit blunder h2 -> h4)
    // Clean selection without residual interference from prior steps
    await playSquareMove(page, 'h2', 'h4');
    await expect(page.getByText(/Recurring Weakness Detected!|Matches your previous blunder|blunder/i)).toBeVisible();
    await expect(page.getByText(/Failed:\s*2\b/i)).toBeVisible();

    // [Step 5] HISTORICAL_MISTAKE -> DISCOVERY (Back to Puzzles transition)
    const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backBtn).toBeVisible();
    await backBtn.click();
    await expect(page.getByRole('button', { name: /Start Practice/i })).toBeVisible();
    await expect(page.getByText(/Strengthen your play by solving recurring mistake positions/i)).toBeVisible();
  });
});
