import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_ACCOUNT, DEFAULT_MOCK_PUZZLE } from './fixtures/mockApi';
import { runLiveBfsModel } from './model/liveBfsRunner';
import { PlaywrightBrowserPracticeSutAdapter } from './model/browserSutAdapter';

test.describe('Live Model-Driven BFS Execution (Issue #790)', () => {
  test.beforeEach(async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Puzzle 1 of 1')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Ruy Lopez / Italian Game' })).toBeVisible();
    await expect(page.getByText('Solved: 0')).toBeVisible();
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();
    await expect(page.locator('[data-square="d2"]')).toBeVisible();
  });

  test('executes dynamically computed BFS path to SOLVED against running application', async ({ page }) => {
    const sut = new PlaywrightBrowserPracticeSutAdapter(page, {
      puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
      entryFen: DEFAULT_MOCK_PUZZLE.fen,
      boardFen: DEFAULT_MOCK_PUZZLE.fen,
    });

    const result = await runLiveBfsModel(page, 'SOLVED', {
      sut,
      initialContext: {
        puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
        entryFen: DEFAULT_MOCK_PUZZLE.fen,
      },
    });

    expect(result.targetState).toBe('SOLVED');
    expect(result.path.length).toBeGreaterThan(0);
    expect(result.steps.length).toBe(result.path.length);

    // Verify last observed state has solved feedback
    expect(result.finalObservedState.currentView).toBe('PRACTICE');
    expect(result.finalObservedState.activePuzzle?.id).toBe(DEFAULT_MOCK_PUZZLE.puzzleId);
    expect(['SOLVED', 'CORRECT']).toContain(result.finalObservedState.feedbackPanel.status);
  });

  test('executes model-generated BFS path from INITIAL to EXIT asserting live exploration invariants', async ({
    page,
  }) => {
    const sut = new PlaywrightBrowserPracticeSutAdapter(page, {
      puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
      entryFen: DEFAULT_MOCK_PUZZLE.fen,
      boardFen: DEFAULT_MOCK_PUZZLE.fen,
    });

    const result = await runLiveBfsModel(page, 'EXIT', {
      sut,
      initialContext: {
        puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
        entryFen: DEFAULT_MOCK_PUZZLE.fen,
      },
    });

    expect(result.targetState).toBe('EXIT');
    expect(result.path).toEqual([
      'MAKE_MOVE_CORRECT',
      'ENTER_LINE_EXPLORATION',
      'EXIT_EXPLORATION',
    ]);

    // Live InvariantEvaluator.assertExplorationExitInvariant verified the live DOM:
    expect(result.finalObservedState.currentView).toBe('PRACTICE');
    expect(result.finalObservedState.activePuzzle?.id).toBe(DEFAULT_MOCK_PUZZLE.puzzleId);
    expect(result.finalObservedState.boardFen).toBe(DEFAULT_MOCK_PUZZLE.fen);
    expect(result.finalObservedState.feedbackPanel.isInteractive).toBe(true);
  });
});
