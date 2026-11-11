import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
  BLACK_TO_MOVE_MOCK_PUZZLE,
  DYNAMIC_EVAL_MOCK_PUZZLE,
  WEAKNESS_BACKED_MOCK_PUZZLE,
} from './fixtures/mockApi';
import {
  PRACTICE_TRANSITIONS,
  STATE_ORACLES,
  executeConcreteAction,
  createInitialContext,
  DEFAULT_PRACTICE_FEN,
  type PracticeModelContext,
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

    let context: PracticeModelContext = createInitialContext();

    // 3. Navigate to discovery to begin from formal initial state
    const backToPuzzlesButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backToPuzzlesButton).toBeVisible();
    await backToPuzzlesButton.click();
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);

    // 4. Action: SELECT_PUZZLE -> PRACTICE_IDLE
    const startPracticeButton = page.getByRole('button', { name: /Start Practice/i });
    await expect(startPracticeButton).toBeVisible();
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
    context = {
      ...context,
      attemptCount: 1,
      expectedFailedCount: 1,
      lastMoveSan: 'a3',
      expectedFen: DEFAULT_PRACTICE_FEN,
    };
    await STATE_ORACLES.INCORRECT.assert(page, context, expect);

    // 6. Action: RETRY -> PRACTICE_IDLE (board is rolled back to baseline FEN)
    await executeConcreteAction(page, 'RETRY', context);
    await page.waitForTimeout(100);
    context = {
      ...context,
      expectedFen: DEFAULT_PRACTICE_FEN,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);
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
    let context: PracticeModelContext = {
      ...createInitialContext(),
      puzzleLoaded: true,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // 3. Action: SUBMIT_OPTIMAL (play d2 -> d4) -> CORRECT
    const d2Square = page.locator('[data-square="d2"]');
    const d4Square = page.locator('[data-square="d4"]');
    await expect(d2Square).toBeVisible();
    await d2Square.click();
    await d4Square.click();
    context = {
      ...context,
      attemptCount: 1,
      expectedSolvedCount: 1,
      solved: true,
      lastMoveSan: 'd4',
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedHistory: ['d4'],
    };
    await STATE_ORACLES.CORRECT.assert(page, context, expect);

    // 4. Action: CONTINUE_EXPLORATION -> EXPLORING
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();
    await continueButton.click();
    context = {
      ...context,
      inExploration: true,
      expectedSideToMove: 'black',
    };
    await STATE_ORACLES.EXPLORING.assert(page, context, expect);

    // 5. Action: EXIT_EXPLORATION -> CORRECT (unchanged exploration exit restores solve feedback)
    const exitExplorationButton = page.getByRole('button', { name: /^Exit$|Exit Exploration/i });
    await expect(exitExplorationButton).toBeVisible();
    await exitExplorationButton.click();
    context = {
      ...context,
      inExploration: false,
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedSideToMove: 'black',
    };
    await STATE_ORACLES.CORRECT.assert(page, context, expect);

    // 6. Action: BACK_TO_PUZZLES -> DISCOVERY
    const backButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backButton).toBeVisible();
    await backButton.click();
    context = {
      ...context,
      puzzleLoaded: false,
    };
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
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
    let context: PracticeModelContext = {
      ...createInitialContext(),
      puzzleLoaded: true,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

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
    context = {
      ...context,
      puzzleLoaded: false,
    };
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);

    // 7. Release deferred network route and verify Discovery remains intact with zero unhandled errors
    releaseEvaluation();
    await page.waitForTimeout(150);
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
  });

  test('Path 4: Interactive control operations (flip, hint, timer, blindfold, and history navigation)', async ({ page }) => {
    // 1. Setup mocked API boundary with weakness-backed puzzle for blindfold eligibility
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [WEAKNESS_BACKED_MOCK_PUZZLE],
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');
    let context: PracticeModelContext = {
      ...createInitialContext(),
      puzzleLoaded: true,
      isWeaknessPlaylist: true,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // 3. Action: FLIP_BOARD
    await executeConcreteAction(page, 'FLIP_BOARD');
    await page.waitForTimeout(60);

    // 4. Action: REQUEST_HINT (highlights hint square)
    await executeConcreteAction(page, 'REQUEST_HINT');
    await page.waitForTimeout(60);

    // 5. Action: TOGGLE_TIMER_MODE (cycle modes: STOPWATCH -> COUNTDOWN -> untimed)
    await executeConcreteAction(page, 'TOGGLE_TIMER_MODE');
    await page.waitForTimeout(60);
    await executeConcreteAction(page, 'TOGGLE_TIMER_MODE');
    await page.waitForTimeout(60);
    await executeConcreteAction(page, 'TOGGLE_TIMER_MODE');
    await page.waitForTimeout(60);

    // 6. Action: SUBMIT_OPTIMAL -> CORRECT
    await executeConcreteAction(page, 'SUBMIT_OPTIMAL');
    context = {
      ...context,
      attemptCount: 1,
      expectedSolvedCount: 1,
      solved: true,
      lastMoveSan: 'd4',
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedHistory: ['d4'],
    };
    await STATE_ORACLES.CORRECT.assert(page, context, expect);

    // 7. Action: ENTER_BLINDFOLD from feedback state, TOGGLE_BLINDFOLD_VISIBILITY, SUBMIT_BLINDFOLD_MOVE & EXIT_BLINDFOLD back to CORRECT
    await executeConcreteAction(page, 'ENTER_BLINDFOLD');
    await page.waitForTimeout(100);
    context = {
      ...context,
      blindfoldActive: true,
      blindfoldPreviousState: 'CORRECT',
    };
    await STATE_ORACLES.BLINDFOLD.assert(page, context, expect);

    await executeConcreteAction(page, 'TOGGLE_BLINDFOLD_VISIBILITY');
    await page.waitForTimeout(60);
    await executeConcreteAction(page, 'SUBMIT_BLINDFOLD_MOVE');
    await page.waitForTimeout(100);
    await executeConcreteAction(page, 'EXIT_BLINDFOLD');
    await page.waitForTimeout(100);
    context = {
      ...context,
      blindfoldActive: false,
      blindfoldPreviousState: undefined,
    };
    await STATE_ORACLES.CORRECT.assert(page, context, expect);

    // 8. Action: CONTINUE_EXPLORATION -> EXPLORING
    await executeConcreteAction(page, 'CONTINUE_EXPLORATION');
    context = {
      ...context,
      inExploration: true,
      expectedSideToMove: 'black',
    };
    await STATE_ORACLES.EXPLORING.assert(page, context, expect);

    // 9. In exploration, navigate history back and forward
    await executeConcreteAction(page, 'NAVIGATE_HISTORY_PREV');
    await page.waitForTimeout(60);
    await executeConcreteAction(page, 'NAVIGATE_HISTORY_NEXT');
    await page.waitForTimeout(60);

    // 10. Exit exploration back to correct, then back to discovery
    await executeConcreteAction(page, 'EXIT_EXPLORATION');
    context = {
      ...context,
      inExploration: false,
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedSideToMove: 'black',
    };
    await STATE_ORACLES.CORRECT.assert(page, context, expect);
    await executeConcreteAction(page, 'BACK_TO_PUZZLES');
    context = {
      ...context,
      puzzleLoaded: false,
    };
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
  });

  test('Path 5: Deterministic network failure injection (HTTP 500), observable EVALUATION_ERROR banner, and retry recovery', async ({ page }) => {
    // 1. Setup mocked API with initial HTTP 500 failure on evaluate-move
    const mockHandle = await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DYNAMIC_EVAL_MOCK_PUZZLE],
      evaluateMoveFault: { fault: 'server_error_500' },
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');
    let context: PracticeModelContext = {
      ...createInitialContext(),
      puzzleLoaded: true,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // 3. Submit move: d2 -> d4 under failing route
    const d2Square = page.locator('[data-square="d2"]');
    const d4Square = page.locator('[data-square="d4"]');
    await expect(d2Square).toBeVisible();
    await d2Square.click();
    await d4Square.click();

    // 4. Assert EVALUATION_ERROR oracle (error banner is visible and retry button exists)
    context = {
      ...context,
      pendingEvaluationMove: 'd4',
    };
    await STATE_ORACLES.EVALUATION_ERROR.assert(page, context, expect);
    const retryButton = page.getByRole('button', { name: /Retry evaluation/i });
    await expect(retryButton).toBeVisible();

    // 5. Clear the fault dynamically and click Retry evaluation to recover and transition to CORRECT
    mockHandle.setEvaluateMoveFault(undefined);
    await executeConcreteAction(page, 'RETRY', context);
    await page.waitForTimeout(100);

    // 6. Assert restored interactive state is CORRECT (move evaluated successfully)
    context = {
      ...context,
      attemptCount: 1,
      expectedSolvedCount: 1,
      solved: true,
      lastMoveSan: 'd4',
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedHistory: ['d4'],
    };
    await STATE_ORACLES.CORRECT.assert(page, context, expect);
  });

  test('Path 6: Evaluation error recovery via navigation back to Discovery (BACK_TO_PUZZLES)', async ({ page }) => {
    // 1. Setup mocked API with malformed JSON response body
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DYNAMIC_EVAL_MOCK_PUZZLE],
      evaluateMoveFault: { fault: 'malformed_json' },
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');
    let context: PracticeModelContext = {
      ...createInitialContext(),
      puzzleLoaded: true,
      expectedFen: DEFAULT_PRACTICE_FEN,
      expectedSideToMove: 'white',
      expectedFailedCount: 0,
      expectedSolvedCount: 0,
    };
    await STATE_ORACLES.PRACTICE_IDLE.assert(page, context, expect);

    // 3. Submit move: d2 -> d4 under malformed JSON response route
    const d2Square = page.locator('[data-square="d2"]');
    const d4Square = page.locator('[data-square="d4"]');
    await expect(d2Square).toBeVisible();
    await d2Square.click();
    await d4Square.click();

    // 4. Assert EVALUATION_ERROR oracle
    context = {
      ...context,
      pendingEvaluationMove: 'd4',
    };
    await STATE_ORACLES.EVALUATION_ERROR.assert(page, context, expect);

    // 5. Navigate back to Discovery
    const backButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backButton).toBeVisible();
    await backButton.click();

    // 6. Assert Discovery view is stable
    context = {
      ...context,
      puzzleLoaded: false,
    };
    await STATE_ORACLES.DISCOVERY.assert(page, context, expect);
  });

  test('Path 7: Multi-Position Puzzle Corpus - Black to move puzzle loading and board orientation', async ({ page }) => {
    // 1. Setup mocked API with BLACK_TO_MOVE_MOCK_PUZZLE
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [BLACK_TO_MOVE_MOCK_PUZZLE],
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');

    // 3. Assert board is rendered with Black orientation (rank 7 at bottom, rank 2 at top)
    const d2Square = page.locator('[data-square="d2"]');
    const d7Square = page.locator('[data-square="d7"]');
    await expect(d7Square).toBeVisible({ timeout: 10000 });
    const d2Box = await d2Square.boundingBox();
    const d7Box = await d7Square.boundingBox();
    expect(d2Box).not.toBeNull();
    expect(d7Box).not.toBeNull();
    expect(d7Box!.y).toBeGreaterThan(d2Box!.y);

    const backButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backButton).toBeVisible();
  });
});
