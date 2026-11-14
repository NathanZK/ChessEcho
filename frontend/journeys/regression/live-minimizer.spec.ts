import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from '../fixtures/mockApi';
import { PlaywrightBrowserPracticeSutAdapter } from '../model/browserSutAdapter';
import { minimizeLiveBrowserTrace } from '../model/liveTraceMinimizer';
import type { PracticeState, PracticeAction } from '../model/modelFsm';
import type { ObservedPracticeState } from '../model/invariantEvaluator';

test.describe('Live Browser Trace Minimization and Seeded Replay (Issue #790)', () => {
  test.beforeEach(async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();
    await expect(page.locator('[data-square="d2"]')).toBeVisible();
  });

  test('minimizes action trace with neutral cycle to 1-minimal reproducing sub-trace against live browser @seed-12345', async ({
    page,
  }) => {
    test.setTimeout(60000);
    const sut = new PlaywrightBrowserPracticeSutAdapter(page, {
      puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
      entryFen: DEFAULT_MOCK_PUZZLE.fen,
      boardFen: DEFAULT_MOCK_PUZZLE.fen,
    });

    // 5-action trace containing neutral solve & reset cycle before entering/exiting exploration
    const failingActions: PracticeAction[] = [
      'MAKE_MOVE_CORRECT',
      'RESET',
      'MAKE_MOVE_CORRECT',
      'ENTER_LINE_EXPLORATION',
      'EXIT_EXPLORATION',
    ];

    // Evaluator that fails specifically when EXIT_EXPLORATION is executed while activePuzzle is loaded
    const simulatedDefectEvaluator = (
      _expectedState: PracticeState,
      observed: ObservedPracticeState,
      action: PracticeAction
    ) => {
      if (action === 'EXIT_EXPLORATION' && observed.activePuzzle !== null) {
        throw new Error('Simulated regression failure upon EXIT_EXPLORATION with active puzzle');
      }
    };

    const minimizationResult = await minimizeLiveBrowserTrace(
      sut,
      failingActions,
      simulatedDefectEvaluator,
      {
        seed: 12345,
        initialContext: {
          puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
          entryFen: DEFAULT_MOCK_PUZZLE.fen,
        },
      }
    );

    // Verify 1-delta reduction pruned the intermediate MAKE_MOVE_CORRECT and RESET actions
    expect(minimizationResult.originalLength).toBe(5);
    expect(minimizationResult.minimizedActions).toEqual([
      'MAKE_MOVE_CORRECT',
      'ENTER_LINE_EXPLORATION',
      'EXIT_EXPLORATION',
    ]);
    expect(minimizationResult.replaySeed).toBe(12345);
    expect(minimizationResult.replayCommand).toContain('@seed-12345');
  });
});
