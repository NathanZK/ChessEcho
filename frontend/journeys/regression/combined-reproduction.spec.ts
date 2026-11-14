import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from '../fixtures/mockApi';
import { STATE_ORACLES, ensureDiscoveryState, createInitialContext, DEFAULT_PRACTICE_FEN, deriveMoveState } from '../model/practiceModel';
import { runCombinedBfsMonkey } from '../model/combinedRunner';

/**
 * Phase 5 Regression Specification (Issue #716)
 *
 * This test suite demonstrates the standardized conversion and permanent regression
 * reproduction of a combined model-based BFS navigation + constrained monkey execution.
 *
 * Sequence Replayed:
 * - BFS Preamble: DISCOVERY -> SELECT_PUZZLE -> PRACTICE_IDLE -> SUBMIT_INACCURATE -> INCORRECT
 * - Deep Monkey Actions: RETRY -> PRACTICE_IDLE -> SUBMIT_BLUNDER -> HISTORICAL_MISTAKE -> BACK_TO_PUZZLES -> DISCOVERY
 *
 * Validations:
 * 1. Monotonic action step ordering across BFS and monkey phases.
 * 2. Board rollback hygiene between consecutive attempts without residual square selection.
 * 3. Deterministic attempt counter semantics (Failed: 0 -> Failed: 1 -> Failed: 2).
 * 4. Continuous invariant oracle assertion across every transition.
 */
test.describe('Phase 5 Regression: Combined BFS-Monkey Deterministic Reproduction', () => {
  test('replays combined sequence via runCombinedBfsMonkey with invariant maintenance and attempt progression', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles');

    const result = await runCombinedBfsMonkey({
      page,
      targetState: 'INCORRECT',
      monkeySeed: 1337,
      monkeyActionBudget: 4,
    });

    expect(result.success).toBe(true);
    expect(result.executedBfsSteps).toBe(2);
    expect(result.executedMonkeySteps).toBeGreaterThanOrEqual(1);
    expect(result.actionLog.length).toBeGreaterThanOrEqual(3);

    // Monotonic step ordering verification
    for (let i = 0; i < result.actionLog.length; i++) {
      expect(result.actionLog[i].step).toBe(i + 1);
    }

    // Verify BFS preamble actions
    expect(result.actionLog[0].action).toBe('SELECT_PUZZLE');
    expect(result.actionLog[1].action).toBe('SUBMIT_MISTAKE');
    expect(result.actionLog[1].toState).toBe('INCORRECT');
  });

  test('replays explicit combined sequence: BFS preamble to INCORRECT followed by retry, blunder, and exit', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles');

    let context = createInitialContext();

    // [BFS Preamble Step 0] Ensure Discovery state
    await ensureDiscoveryState(page);
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);

    // [BFS Preamble Step 1] SELECT_PUZZLE: DISCOVERY -> PRACTICE_IDLE
    const startPracticeButton = page.getByRole('button', { name: /Start Practice/i });
    await startPracticeButton.click();
    context = {
      ...context,
      puzzleLoaded: true,
      activePuzzle: DEFAULT_MOCK_PUZZLE,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);
    await expect(page.getByText(/Failed: 0/i)).toBeVisible();

    // [BFS Preamble Step 2] SUBMIT_INACCURATE: PRACTICE_IDLE -> INCORRECT
    const a2 = page.locator('[data-square="a2"]');
    const a3 = page.locator('[data-square="a3"]');
    await expect(a2).toBeVisible();
    await a2.click();
    await page.waitForTimeout(60);
    await a3.click();

    context = deriveMoveState(context, 'a3', 'INCORRECT');
    await STATE_ORACLES.INCORRECT.assert(page, context, expect);
    await expect(page.getByText(/Failed: 1/i)).toBeVisible();

    // [Monkey Exploration Step 3] RETRY: INCORRECT -> PRACTICE_IDLE
    // Reset board cleanly, ensuring no residual selection
    const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
    if (await resetButton.isVisible().catch(() => false)) {
      await resetButton.click();
      await page.waitForTimeout(100);
    }
    context = {
      ...context,
      activePuzzle: DEFAULT_MOCK_PUZZLE,
      expectedFen: DEFAULT_PRACTICE_FEN,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // Verify baseline piece presence
    await expect(page.locator('[data-square="d2"]')).toBeVisible();
    await expect(page.locator('[data-square="h2"]')).toBeVisible();

    // [Monkey Exploration Step 4] SUBMIT_BLUNDER: PRACTICE_IDLE -> HISTORICAL_MISTAKE
    const h2 = page.locator('[data-square="h2"]');
    const h4 = page.locator('[data-square="h4"]');
    await expect(h2).toBeVisible();
    await h2.click();
    await page.waitForTimeout(60);
    await h4.click();

    context = deriveMoveState(context, 'h4', 'HISTORICAL_MISTAKE');
    await STATE_ORACLES.HISTORICAL_MISTAKE.assert(page, context, expect);
    await expect(page.getByText(/Failed: 2/i)).toBeVisible();

    // [Monkey Exploration Step 5] BACK_TO_PUZZLES: HISTORICAL_MISTAKE -> DISCOVERY
    const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backBtn).toBeVisible();
    await backBtn.click();
    context = {
      ...context,
      puzzleLoaded: false,
      activePuzzle: undefined,
    };
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
  });
});
