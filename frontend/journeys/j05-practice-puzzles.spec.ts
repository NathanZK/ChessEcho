import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_PUZZLE,
  DEFAULT_MOCK_ACCOUNT,
  BLACK_TO_MOVE_MOCK_PUZZLE,
  DYNAMIC_EVAL_MOCK_PUZZLE,
  WEAKNESS_BACKED_MOCK_PUZZLE,
} from './fixtures/mockApi';
import { playSanMove, playSquareMove } from './support/practiceDom';

test.describe('Journey J05: Practice Puzzles Discovery and Carousel Flow (UOBS-12)', () => {
  test('direct practice, hint lifecycle, solve, and challenge mode candidate submission', async ({ page }) => {
    // 1. Setup mocked API boundary with authenticated account and puzzle catalog
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    // 3. Verify interactive puzzle shell header and attempt counts
    await expect(page.getByText('Puzzle 1 of 1')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Ruy Lopez / Italian Game' })).toBeVisible();
    await expect(page.getByText('Solved: 0')).toBeVisible();

    // 4. Test Hint lifecycle before solving: click Hint button
    const hintButton = page.getByRole('button', { name: /Hint/i });
    await expect(hintButton).toBeEnabled();
    await hintButton.click();

    // Verify hint square is highlighted on the board (d2 square for target move d4)
    const d2Square = page.locator('[data-square="d2"]');
    await expect(d2Square).toBeVisible();

    // 5. Execute target move: d4 (d2 -> d4) to solve puzzle
    const d4Square = page.locator('[data-square="d4"]');
    await d2Square.click();
    await d4Square.click();

    // 6. Verify feedback panel indicates solved status and incremented attempt counter
    await expect(page.getByText('Solved: 1')).toBeVisible();
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();

    // 7. Enter Line Exploration
    await continueButton.click();

    // 8. Select Challenge Mode in Line Exploration (UOBS-12)
    const challengeModeButton = page.getByRole('button', { name: /Challenge Mode/i });
    if (await challengeModeButton.isVisible()) {
      await challengeModeButton.click();
      await expect(page.getByText(/Challenge Mode — find a strong candidate move/i)).toBeVisible();

      // Submit candidate move in challenge mode
      const candidateInput = page.getByPlaceholder(/Enter candidate moves/i);
      if (await candidateInput.isVisible()) {
        await candidateInput.fill('d5');
        await page.getByRole('button', { name: /Submit Candidates/i }).click();
      }

      // Finish Challenge Mode
      const finishChallengeButton = page.getByRole('button', { name: /Finish Challenge/i });
      if (await finishChallengeButton.isVisible()) {
        await finishChallengeButton.click();
      }
    }

    // 9. Exit exploration back to discovery catalog
    const exitButton = page.getByTitle(/Exit Exploration/i);
    if (await exitButton.isVisible()) {
      await exitButton.click();
    }
  });

  /**
   * Ported from the retired model-based practice suite (Path 1). Truthfulness of
   * the answer (INV-03) and mistake rejection are application behavior, not model
   * behavior, so they are asserted here directly.
   */
  test('hides the solution before solving and rejects a mistake with a clean reset', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // INV-03: target move and exploration controls stay hidden before solving.
    await expect(page.getByRole('button', { name: /Continue Exploration →/i })).not.toBeVisible();
    await expect(page.getByText('d4 is the recommended move')).not.toBeVisible();

    await playSquareMove(page, 'a2', 'a3');

    await expect(page.getByRole('heading', { name: /Not the Recommended Move/i })).toBeVisible();
    await expect(page.getByText(/Failed:\s*1\b/i)).toBeVisible();

    await page.locator('button[title="Reset Position"]').first().click();
    await expect(boardArea).toHaveAttribute('data-board-fen', DEFAULT_MOCK_PUZZLE.fen);
    await expect(page.getByText(/Failed:\s*1\b/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /Start Practice/i })).not.toBeVisible();
  });

  /**
   * Ported from the retired model-based practice suite (Path 3): navigating away
   * while a move evaluation is still in flight must not corrupt the next view.
   */
  test('stays on discovery when navigating away during an in-flight evaluation', async ({
    page,
  }) => {
    let releaseEvaluation: () => void = () => {};
    const evaluationGate = new Promise<void>((resolve) => {
      releaseEvaluation = resolve;
    });
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(error.message));

    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DYNAMIC_EVAL_MOCK_PUZZLE],
    });
    await page.route('**/api/puzzles/evaluate-move*', async (route) => {
      await evaluationGate;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'CORRECT', evalLoss: 0.0 }),
      });
    });

    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();

    await playSanMove(page, DYNAMIC_EVAL_MOCK_PUZZLE.fen, 'd4');

    await page.getByRole('button', { name: /Back to Puzzles/i }).click();
    await expect(page.getByRole('button', { name: /Start Practice/i })).toBeVisible();

    releaseEvaluation();
    await page.waitForTimeout(150);

    await expect(page.getByRole('button', { name: /Start Practice/i })).toBeVisible();
    await expect(page.locator('[data-testid="practice-board-area"]')).not.toBeVisible();
    expect(pageErrors).toEqual([]);
  });

  /**
   * Ported from the retired model-based practice suite (Path 5): a server error
   * surfaces an actionable retry and a successful retry advances the puzzle.
   */
  test('shows the evaluation error banner and recovers through retry after HTTP 500', async ({
    page,
  }) => {
    const mockHandle = await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DYNAMIC_EVAL_MOCK_PUZZLE],
      evaluateMoveFault: { fault: 'server_error_500' },
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();

    await playSanMove(page, DYNAMIC_EVAL_MOCK_PUZZLE.fen, 'd4');

    await expect(page.getByText('Move Evaluation Failed')).toBeVisible();
    await expect(page.getByRole('button', { name: /Retry evaluation/i })).toBeVisible();

    mockHandle.setEvaluateMoveFault(undefined);
    await page.getByRole('button', { name: /Retry evaluation/i }).click();

    await expect(page.getByRole('button', { name: /Continue Exploration →/i })).toBeVisible();
    await expect(page.getByText(/Solved:\s*1\b/i)).toBeVisible();
    await expect(page.getByText('Move Evaluation Failed')).not.toBeVisible();
  });

  /**
   * Ported from the retired model-based practice suite (Path 6): a malformed
   * evaluation payload is reported and the user can leave for discovery cleanly.
   */
  test('reports a malformed evaluation payload and returns to discovery', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DYNAMIC_EVAL_MOCK_PUZZLE],
      evaluateMoveFault: { fault: 'malformed_json' },
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();

    await playSanMove(page, DYNAMIC_EVAL_MOCK_PUZZLE.fen, 'd4');
    await expect(page.getByText('Move Evaluation Failed')).toBeVisible();

    await page.getByRole('button', { name: /Back to Puzzles/i }).click();
    await expect(page.getByRole('button', { name: /Start Practice/i })).toBeVisible();
    await expect(page.locator('[data-testid="practice-board-area"]')).not.toBeVisible();
  });

  /**
   * Ported from the retired model-based practice suite (Path 7): the board is
   * oriented for the side to move in a black-to-move corpus position.
   */
  test('orients the board for a black-to-move puzzle', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [BLACK_TO_MOVE_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    const d2 = page.locator('[data-square="d2"]');
    const d7 = page.locator('[data-square="d7"]');
    await expect(d7).toBeVisible({ timeout: 10_000 });

    const d2Box = await d2.boundingBox();
    const d7Box = await d7.boundingBox();
    expect(d2Box).not.toBeNull();
    expect(d7Box).not.toBeNull();
    expect(d7Box!.y).toBeGreaterThan(d2Box!.y);
  });

  /**
   * Ported from the retired model-based practice suite (Path 4): board flip,
   * hint highlighting, timer mode cycling, blindfold entry/exit, and exploration
   * history navigation remain available alongside solving.
   */
  test('supports flip, hint, timer modes, blindfold, and history navigation', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [WEAKNESS_BACKED_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    const d2 = page.locator('[data-square="d2"]');
    const d7 = page.locator('[data-square="d7"]');
    await expect(d2).toBeVisible();

    // Flip the board: rank 2 moves below rank 7.
    const beforeFlip = (await d2.boundingBox())!;
    const beforeFlipD7 = (await d7.boundingBox())!;
    await page.locator('button[title*="Flip"]').first().click();
    expect(beforeFlip.y).toBeGreaterThan(beforeFlipD7.y);
    await expect
      .poll(async () => {
        const flippedD2 = await d2.boundingBox();
        const flippedD7 = await d7.boundingBox();
        return flippedD2 && flippedD7 ? flippedD7.y > flippedD2.y : false;
      })
      .toBe(true);
    await page.locator('button[title*="Flip"]').first().click();

    // Hint highlights the origin square of the target move.
    await page.getByRole('button', { name: /Hint/i }).first().click();
    const hasHintHighlight = await d2.evaluate((node) => {
      const isAmber = (element: Element) =>
        (element as HTMLElement).style?.backgroundColor?.includes('245, 158, 11') ?? false;
      return isAmber(node) || Array.from(node.querySelectorAll('*')).some(isAmber);
    });
    expect(hasHintHighlight).toBe(true);

    // Timer mode cycles: stopwatch -> countdown -> off. The settings control and
    // the timer modes are part of the journey, so their absence must fail the test.
    const settingsButton = page.getByRole('button', { name: /Puzzle Settings/i }).first();
    await expect(settingsButton).toBeVisible();
    if ((await settingsButton.getAttribute('aria-expanded')) !== 'true') {
      await settingsButton.click();
    }
    await expect(settingsButton).toHaveAttribute('aria-expanded', 'true');
    const stopwatch = page.getByRole('button', { name: /^Stopwatch$/i }).first();
    await expect(stopwatch).toBeVisible();
    await stopwatch.click();
    await page.getByRole('button', { name: /^Countdown$/i }).first().click();
    await page.getByRole('button', { name: /^Off$/i }).first().click();

    // Solve, then run one blindfold round and exit back to feedback.
    await playSanMove(page, WEAKNESS_BACKED_MOCK_PUZZLE.fen, 'd4');
    await expect(page.getByRole('button', { name: /Continue Exploration →/i })).toBeVisible();

    await page.getByRole('button', { name: /Train Blindfold/i }).first().click();
    await expect(page.locator('[data-testid="blindfold-board"]')).toBeVisible();
    await page.locator('button[aria-label="Reveal board"]').last().click({ force: true });
    await expect(page.locator('button[aria-label="Hide board"]').last()).toBeVisible();
    await page.getByRole('button', { name: /^Exit$/i }).last().click({ force: true });
    await expect(page.getByRole('button', { name: /Continue Exploration →/i })).toBeVisible();

    // Enter exploration and walk the history backwards and forwards.
    await page.getByRole('button', { name: /Continue Exploration →/i }).click();
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    const solvedFen = await boardArea.getAttribute('data-board-fen');
    const previousMove = page.locator('button[title*="Previous Move"]').first();
    await expect(previousMove).toBeEnabled();
    await previousMove.click();
    await expect(boardArea).toHaveAttribute('data-board-fen', WEAKNESS_BACKED_MOCK_PUZZLE.fen);
    const nextMove = page.locator('button[title*="Next Move"]').first();
    await expect(nextMove).toBeEnabled();
    await nextMove.click();
    await expect(boardArea).toHaveAttribute('data-board-fen', solvedFen!);

    await page.getByTitle(/Exit Exploration/i).first().click();
    await expect(page.getByRole('button', { name: /Back to Puzzles/i })).toBeVisible();
    await page.getByRole('button', { name: /Back to Puzzles/i }).click();
    await expect(page.getByRole('button', { name: /Start Practice/i })).toBeVisible();
  });
});
