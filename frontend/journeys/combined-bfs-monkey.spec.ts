import fs from 'node:fs';
import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from './fixtures/mockApi';
import { STATE_ORACLES, createInitialContext } from './model/practiceModel';
import {
  executeBfsPath,
  runCombinedBfsMonkey,
  HIGH_RISK_SCENARIOS,
} from './model/combinedRunner';

test.describe('Phase 5: Combined Model-Based BFS Exploration with Constrained Monkey Testing', () => {
  test('Scenario 1: BFS seeds retry-after-wrong-move (INCORRECT) followed by constrained monkey exploration', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles');

    const config = HIGH_RISK_SCENARIOS['retry-after-wrong-move'];
    const seed = 500101;
    const result = await runCombinedBfsMonkey({
      page,
      targetState: config.targetState,
      monkeySeed: seed,
      monkeyActionBudget: 6,
    });

    if (!result.success) {
      console.error(result.error?.message);
    }

    expect(result.success).toBe(true);
    expect(result.executedBfsSteps).toBe(2);
    expect(result.bfsPath).toEqual(['SELECT_PUZZLE', 'SUBMIT_MISTAKE']);
    expect(result.executedMonkeySteps).toBeGreaterThanOrEqual(1);

    // Verify monotonic step ordering across BFS and monkey phases
    const steps = result.actionLog.map((e) => e.step);
    for (let i = 0; i < steps.length; i++) {
      expect(steps[i]).toBe(i + 1);
    }

    // Verify BFS preamble entries in actionLog
    expect(result.actionLog[0].action).toBe('SELECT_PUZZLE');
    expect(result.actionLog[0].toState).toBe('PRACTICE_IDLE');
    expect(result.actionLog[1].action).toBe('SUBMIT_MISTAKE');
    expect(result.actionLog[1].toState).toBe('INCORRECT');
  });

  test('Scenario 2: BFS seeds continuation-loading (EXPLORING) followed by constrained line exploration', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles');

    const config = HIGH_RISK_SCENARIOS['continuation-loading'];
    const seed = 500202;
    const result = await runCombinedBfsMonkey({
      page,
      targetState: config.targetState,
      monkeySeed: seed,
      monkeyActionBudget: 6,
    });

    if (!result.success) {
      console.error(result.error?.message);
    }

    expect(result.success).toBe(true);
    expect(result.executedBfsSteps).toBe(3);
    expect(result.bfsPath).toEqual(['SELECT_PUZZLE', 'SUBMIT_OPTIMAL', 'CONTINUE_EXPLORATION']);
    expect(result.executedMonkeySteps).toBeGreaterThanOrEqual(1);

    // Verify preamble landed in EXPLORING mode
    expect(result.actionLog[2].toState).toBe('EXPLORING');
    // Verify cumulative context retained inExploration
    expect(result.finalContext.puzzleLoaded).toBe(true);
  });

  test('Scenario 3 & 4: In-flight evaluation pending and navigation during evaluation with deferred route gate', async ({ page }) => {
    let releaseEvaluation: () => void = () => {};
    const evaluationGate = new Promise<void>((resolve) => {
      releaseEvaluation = resolve;
    });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // Intercept evaluate-move with the deferred gate to hold in-flight pending state
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

    await page.goto('/#puzzles');

    // 1. BFS navigates to PRACTICE_IDLE preamble
    const bfsResult = await executeBfsPath(page, ['SELECT_PUZZLE']);
    expect(bfsResult.success).toBe(true);
    expect(bfsResult.finalState).toBe('PRACTICE_IDLE');

    // 2. Submit move to trigger in-flight evaluation: d2 -> d4
    const d2Square = page.locator('[data-square="d2"]');
    const d4Square = page.locator('[data-square="d4"]');
    await expect(d2Square).toBeVisible();
    await d2Square.click();
    await page.waitForTimeout(60);
    await d4Square.click();

    // 3. While evaluation is held in-flight, navigate back to discovery
    const backButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backButton).toBeVisible();
    await backButton.click();

    // 5. Assert Discovery view is immediately rendered
    await STATE_ORACLES.DISCOVERY.assert(page, createInitialContext(), expect);

    // 6. Release deferred network route and verify Discovery remains intact with zero unhandled errors
    releaseEvaluation();
    await page.waitForTimeout(150);
    await STATE_ORACLES.DISCOVERY.assert(page, createInitialContext(), expect);
  });

  test('Deliberately induced failure in combined execution captures full record, screenshot, and trace archive', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    await page.goto('/#puzzles');

    const failureSeed = 999555;
    const result = await runCombinedBfsMonkey({
      page,
      targetState: 'INCORRECT',
      monkeySeed: failureSeed,
      monkeyActionBudget: 5,
      screenshotOnFailure: true,
      stepHook: async (step) => {
        // Trigger page error during monkey execution (step 3: after BFS preamble of 2 steps)
        if (step === 3) {
          await page.evaluate(() => {
            console.error('Deliberately induced runtime exception during combined monkey execution');
          });
        }
      },
    });

    expect(result.success).toBe(false);
    expect(result.error).toBeDefined();
    expect(result.error?.message).toContain(`Seed: ${failureSeed}`);
    expect(result.error?.message).toContain('Deliberately induced runtime exception');
    expect(result.bfsPath).toEqual(['SELECT_PUZZLE', 'SUBMIT_MISTAKE']);
    expect(result.executedBfsSteps).toBe(2);

    // Verify screenshot and trace exist
    expect(result.screenshotPath).toBeDefined();
    expect(result.tracePath).toBeDefined();
    expect(fs.existsSync(result.screenshotPath!)).toBe(true);
    expect(fs.existsSync(result.tracePath!)).toBe(true);

    // Clean up temporary failure artifacts
    if (result.screenshotPath && fs.existsSync(result.screenshotPath)) {
      fs.unlinkSync(result.screenshotPath);
    }
    if (result.tracePath && fs.existsSync(result.tracePath)) {
      fs.unlinkSync(result.tracePath);
    }
  });
});
