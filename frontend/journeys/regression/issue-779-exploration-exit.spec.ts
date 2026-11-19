import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DYNAMIC_EVAL_MOCK_PUZZLE,
} from '../fixtures/mockApi';

/**
 * Issue #779 regression: exploration-exit desynchronization.
 *
 * The monkey campaign surfaced this sequence at steps 90/124 but no deterministic
 * test protected it. After solving a puzzle, entering line exploration and exiting,
 * the board restored the entry FEN while its internal move history still pointed at
 * the solved move. The next attempt was therefore classified as a line continuation
 * (`isInitialDecision === false`), so it was never evaluated: no rejection, no
 * rollback, and no attempt-count update. The session silently absorbed the move.
 *
 * What this regression proves:
 *   1. The first attempt on a dynamic-evaluation puzzle (`targetMove` empty) issues
 *      exactly one network `/api/puzzles/evaluate-move` request for the played move.
 *   2. After entering and exiting exploration, the restored board is a fresh initial
 *      decision rather than a line continuation: the next move is evaluated against
 *      the puzzle, is rejected, increments the failure counter, and the session stays
 *      usable afterwards.
 *   3. No stale evaluation request is replayed after exit.
 *
 * Known deviation from #831 AC2 (awaiting maintainer approval):
 *   AC2 asked for a request-level assertion that the post-exit attempt triggers a new
 *   network evaluation. The application does not work that way for target-move
 *   puzzles: `ChessBoardArea` evaluates those moves client-side against the puzzle's
 *   target/acceptable moves, so a correct fresh evaluation legitimately issues no
 *   request. The regression therefore proves the AC2 intent (the post-exit attempt is
 *   freshly evaluated, not swallowed) through observable evaluation outcomes, while
 *   still pinning the only network evaluation to the pre-exit dynamic attempt. This is
 *   a test-design deviation, not a coverage loss, and it must be disclosed in the PR
 *   and signed off by a maintainer before publication.
 */
test.describe('Issue #779: exploration exit preserves fresh evaluation of the next move', () => {
  test('solve -> enter exploration -> exit exploration -> post-exit mistake is newly rejected', async ({
    page,
  }) => {
    const evaluationRequests: Array<{ fen: string | null; move: string | null }> = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/puzzles/evaluate-move')) {
        const parsed = new URL(req.url());
        evaluationRequests.push({
          fen: parsed.searchParams.get('fen'),
          move: parsed.searchParams.get('move'),
        });
      }
    });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DYNAMIC_EVAL_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();
    await expect(boardArea).toHaveAttribute('data-board-fen', DYNAMIC_EVAL_MOCK_PUZZLE.fen);

    const playMove = async (from: string, to: string) => {
      await page.locator(`[data-square="${from}"]`).click();
      await page.waitForTimeout(60);
      await page.locator(`[data-square="${to}"]`).click();
    };

    // 1. Solve the puzzle. The dynamic puzzle has no targetMove, so this is the
    //    first network-evaluated attempt.
    await playMove('d2', 'd4');
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();
    expect(evaluationRequests).toHaveLength(1);
    expect(evaluationRequests[0]).toMatchObject({
      fen: DYNAMIC_EVAL_MOCK_PUZZLE.fen,
      move: 'd4',
    });

    // 2. Enter line exploration.
    await continueButton.click();
    const exitButton = page.getByTitle(/Exit Exploration/i).first();
    await expect(exitButton).toBeVisible();

    // 3. Exit exploration back to practice mode.
    await exitButton.click();
    await expect(exitButton).not.toBeVisible();
    await expect(boardArea).toHaveAttribute('data-board-fen', DYNAMIC_EVAL_MOCK_PUZZLE.fen);
    await expect(boardArea).toHaveAttribute('data-feedback-status', 'CORRECT');

    // 4. Submit a mistake from the restored baseline position.
    await playMove('a2', 'a3');

    // 5. The attempt must be evaluated and rejected. Before the fix the move was
    //    treated as a continuation: no banner, no rollback, no counter update.
    await expect(page.getByRole('heading', { name: /Not the Recommended Move/i })).toBeVisible();
    await expect(page.getByText(/Failed:\s*1\b/i)).toBeVisible();
    await expect(boardArea).toHaveAttribute('data-feedback-status', 'INCORRECT');

    // 6. The session remains usable: after resetting, the correct move is still
    //    evaluated and solves.
    await page.locator('button[title="Reset Position"]').first().click();
    await expect(boardArea).toHaveAttribute('data-board-fen', DYNAMIC_EVAL_MOCK_PUZZLE.fen);
    await playMove('d2', 'd4');
    await expect(page.getByRole('button', { name: /Continue Exploration →/i })).toBeVisible();
    await expect(boardArea).toHaveAttribute('data-feedback-status', 'CORRECT');

    // 7. No stale evaluation was replayed. The single network evaluation belongs to
    //    the original dynamic attempt; the two post-exit attempts are target-move
    //    evaluations performed locally by ChessBoardArea (see the AC2 note above).
    expect(evaluationRequests).toHaveLength(1);
    expect(evaluationRequests[0].move).toBe('d4');
  });
});
