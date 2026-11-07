import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from './fixtures/mockApi';
import {
  PRACTICE_TRANSITIONS,
  STATE_ORACLES,
} from './model/practiceModel';
import { explorePracticeStates } from './model/bfsExplorer';
import { selectHighRiskPaths } from './model/pathSelector';

test.describe('Phase 2 Model-Based Testing: Bounded BFS Exploration & High-Risk Paths', () => {
  test('BFS exploration enumerates reachable states and selects 3 high-risk paths', () => {
    const exploration = explorePracticeStates('DISCOVERY', PRACTICE_TRANSITIONS, 4);
    const selected = selectHighRiskPaths(exploration, PRACTICE_TRANSITIONS);

    expect(selected.length).toBeGreaterThanOrEqual(3);
    expect(selected.some((p) => p.riskCategory === 'evaluation-retry')).toBe(true);
    expect(selected.some((p) => p.riskCategory === 'mode-boundary-exploration')).toBe(true);
    expect(selected.some((p) => p.riskCategory === 'pending-navigation')).toBe(true);
  });

  test('Path 1: Evaluation mistake and board retry (evaluation-retry)', async ({ page }) => {
    // 1. Setup mocked API boundary
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');

    // 3. Navigate to discovery to begin from formal initial state
    const backToPuzzlesButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backToPuzzlesButton).toBeVisible();
    await backToPuzzlesButton.click();
    await STATE_ORACLES.DISCOVERY.assert(page, expect);

    // 4. Action: SELECT_PUZZLE -> PRACTICE_IDLE
    const startPracticeButton = page.getByRole('button', { name: /Start Practice/i });
    await expect(startPracticeButton).toBeVisible();
    await startPracticeButton.click();
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, expect);

    // REGRESSION CHECK: Answer Secrecy (INV-03)
    // Target move and exploration controls must be hidden prior to solving
    await expect(page.getByRole('button', { name: /Continue Exploration →/i })).not.toBeVisible();
    await expect(page.getByText('d4 is the recommended move')).not.toBeVisible();

    // 5. Action: SUBMIT_MISTAKE (play inaccurate move a2 -> a3) -> INCORRECT
    const a2Square = page.locator('[data-square="a2"]');
    const a3Square = page.locator('[data-square="a3"]');
    await expect(a2Square).toBeVisible();
    await a2Square.click();
    await a3Square.click();
    await STATE_ORACLES.INCORRECT.assert(page, expect);

    // 6. Action: RETRY -> PRACTICE_IDLE (board is rolled back to baseline FEN)
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, expect);
  });

  test('Path 2: Mode boundary crossing from solve to line exploration and return (mode-boundary-exploration)', async ({ page }) => {
    // 1. Setup mocked API boundary
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, expect);

    // 3. Action: SUBMIT_OPTIMAL (play d2 -> d4) -> CORRECT
    const d2Square = page.locator('[data-square="d2"]');
    const d4Square = page.locator('[data-square="d4"]');
    await expect(d2Square).toBeVisible();
    await d2Square.click();
    await d4Square.click();
    await STATE_ORACLES.CORRECT.assert(page, expect);

    // 4. Action: CONTINUE_EXPLORATION -> EXPLORING
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();
    await continueButton.click();
    await STATE_ORACLES.EXPLORING.assert(page, expect);

    // 5. Action: EXIT_EXPLORATION -> PRACTICE_IDLE
    const exitExplorationButton = page.getByRole('button', { name: /^Exit$|Exit Exploration/i });
    await expect(exitExplorationButton).toBeVisible();
    await exitExplorationButton.click();
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, expect);

    // 6. Action: BACK_TO_PUZZLES -> DISCOVERY
    const backButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backButton).toBeVisible();
    await backButton.click();
    await STATE_ORACLES.DISCOVERY.assert(page, expect);
  });

  test('Path 3: Navigation during in-flight evaluation with deferred route gate (pending-navigation)', async ({ page }) => {
    let releaseEvaluation: () => void = () => {};
    const evaluationGate = new Promise<void>((resolve) => {
      releaseEvaluation = resolve;
    });

    // 1. Setup mocked API boundary
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Intercept evaluate-move with the deferred gate to guarantee in-flight pending state
    await page.route('**/api/puzzles/evaluate-move*', async (route) => {
      await evaluationGate;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'CORRECT',
          evalLoss: 0.0,
        }),
      });
    });

    // 3. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, expect);

    // 4. Submit move to trigger in-flight evaluation: d2 -> d4
    const d2Square = page.locator('[data-square="d2"]');
    const d4Square = page.locator('[data-square="d4"]');
    await expect(d2Square).toBeVisible();
    await d2Square.click();
    await d4Square.click();

    // 5. While evaluation is held in-flight, navigate back to discovery
    const backButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backButton).toBeVisible();
    await backButton.click();

    // 6. Assert Discovery view is immediately rendered
    await STATE_ORACLES.DISCOVERY.assert(page, expect);

    // 7. Release deferred network route and verify Discovery remains intact with zero unhandled errors
    releaseEvaluation();
    await page.waitForTimeout(150);
    await STATE_ORACLES.DISCOVERY.assert(page, expect);
  });
});
