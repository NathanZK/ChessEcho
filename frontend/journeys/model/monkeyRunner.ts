import fs from 'node:fs';
import type { Page } from '@playwright/test';
import {
  type PracticeAbstractState,
  type PracticeAction,
  type PracticeModelContext,
  createInitialContext,
  getNextState,
} from './practiceModel';
import { createPrng } from './prng';
import { selectConstrainedAction } from './monkeySelector';
import {
  type ActionLogEntry,
  type NetworkRequestRecord,
  InvariantErrorObserver,
  NetworkActivityMonitor,
  assertInvariantOracles,
} from './monkeyOracles';

export interface MonkeyRunOptions {
  page: Page;
  seed: number;
  actionBudget: number;
  initialState?: PracticeAbstractState;
  initialContext?: PracticeModelContext;
  screenshotOnFailure?: boolean;
  stepHook?: (step: number, state: PracticeAbstractState) => Promise<void>;
}

export interface MonkeyRunResult {
  seed: number;
  executedSteps: number;
  actionLog: ActionLogEntry[];
  finalState: PracticeAbstractState;
  finalContext: PracticeModelContext;
  success: boolean;
  error?: Error;
  screenshotPath?: string;
  tracePath?: string;
}

/**
 * Diagnoses whether a transition failure was caused by a failed HTTP response,
 * a missing API request, or a DOM state-transition timeout.
 */
export function diagnoseTransitionFailure(
  action: PracticeAction,
  nextState: PracticeAbstractState,
  stepRequests: NetworkRequestRecord[],
  originalError: Error
): string {
  // 1. Check for explicit HTTP response failures or aborted requests
  const failedResponses = stepRequests.filter((r) => r.failed || (r.status !== undefined && r.status >= 400));
  if (failedResponses.length > 0) {
    const details = failedResponses
      .map((r) => `${r.method} ${r.url} -> ${r.status ?? 'FAILED'} (${r.failureText ?? 'HTTP error'})`)
      .join('; ');
    return `[Failed HTTP Response] Requests failed during action ${action}: ${details}`;
  }

  // 2. Check for missing network requests on actions that require remote evaluation or event recording
  const isEvaluationAction = action === 'SUBMIT_OPTIMAL' || action === 'SUBMIT_MISTAKE' || action === 'SUBMIT_BLUNDER';
  if (isEvaluationAction) {
    const hasPuzzleReq = stepRequests.some((r) => r.url.includes('/api/puzzles/'));
    if (!hasPuzzleReq) {
      return `[Missing Request] Action ${action} did not dispatch an evaluation request to /api/puzzles/.`;
    }
  }

  // 3. Fallback: State transition timeout or DOM assertion mismatch
  return `[State Transition Timeout] Transition to ${nextState} timed out after action ${action}: ${originalError.message}`;
}

/**
 * Translates a high-level model action into concrete Playwright DOM interactions.
 */
export async function executeConcreteAction(page: Page, action: PracticeAction): Promise<void> {
  switch (action) {
    case 'SELECT_PUZZLE': {
      const button = page.getByRole('button', { name: /Start Practice/i });
      await button.waitFor({ state: 'visible', timeout: 5000 });
      await button.click();
      break;
    }

    case 'SUBMIT_OPTIMAL': {
      // White optimal first move: d2 -> d4
      const from = page.locator('[data-square="d2"]');
      const to = page.locator('[data-square="d4"]');
      await from.waitFor({ state: 'visible', timeout: 5000 });
      await from.click();
      await to.click();
      break;
    }

    case 'SUBMIT_MISTAKE': {
      // Inaccurate move: a2 -> a3
      const from = page.locator('[data-square="a2"]');
      const to = page.locator('[data-square="a3"]');
      await from.waitFor({ state: 'visible', timeout: 5000 });
      await from.click();
      await to.click();
      break;
    }

    case 'SUBMIT_BLUNDER': {
      // Severe mistake: h2 -> h4
      const from = page.locator('[data-square="h2"]');
      const to = page.locator('[data-square="h4"]');
      await from.waitFor({ state: 'visible', timeout: 5000 });
      await from.click();
      await to.click();
      break;
    }

    case 'RETRY': {
      // Board is automatically rolled back to baseline FEN upon incorrect move.
      // If Reset button is available, click it to clear selection without touching board pieces.
      const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
      if (await resetButton.isVisible().catch(() => false)) {
        await resetButton.click();
      }
      break;
    }

    case 'RESET': {
      // Resets board to baseline FEN
      const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
      if (await resetButton.isVisible().catch(() => false)) {
        await resetButton.click();
      }
      break;
    }

    case 'CONTINUE_EXPLORATION': {
      const continueBtn = page.getByRole('button', { name: /Continue Exploration →/i });
      await continueBtn.waitFor({ state: 'visible', timeout: 5000 });
      await continueBtn.click();
      break;
    }

    case 'EXIT_EXPLORATION': {
      const exitBtn = page.getByRole('button', { name: /^Exit$|Exit Exploration/i });
      await exitBtn.waitFor({ state: 'visible', timeout: 5000 });
      await exitBtn.click();
      break;
    }

    case 'BACK_TO_PUZZLES': {
      const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });
      await backBtn.waitFor({ state: 'visible', timeout: 5000 });
      await backBtn.click();
      break;
    }

    default:
      throw new Error(`Unsupported monkey action: ${action}`);
  }
}

/**
 * Runs a bounded, state-constrained monkey test sequence using a deterministic seed.
 */
export async function runConstrainedMonkey(options: MonkeyRunOptions): Promise<MonkeyRunResult> {
  const {
    page,
    seed,
    actionBudget,
    initialState = 'DISCOVERY',
    initialContext = createInitialContext(),
    screenshotOnFailure = true,
  } = options;

  const prng = createPrng(seed);
  const observer = new InvariantErrorObserver();
  observer.attach(page);

  const networkMonitor = new NetworkActivityMonitor();
  networkMonitor.attach(page);

  const context = page.context();
  let tracingActive = false;
  try {
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    tracingActive = true;
  } catch {
    // Tracing already active in outer runner or unsupported
  }

  let currentState = initialState;
  let currentContext = { ...initialContext };
  const actionLog: ActionLogEntry[] = [];

  // Align UI with initial state if starting in DISCOVERY but currently on interactive board
  if (initialState === 'DISCOVERY') {
    const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });
    if (await backBtn.isVisible().catch(() => false)) {
      await backBtn.click();
    }
  }

  try {
    for (let step = 1; step <= actionBudget; step++) {
      if (options.stepHook) {
        await options.stepHook(step, currentState);
      }

      const action = selectConstrainedAction(currentState, currentContext, prng);

      if (!action) {
        // Reached an absorb state or state with no permitted outgoing transitions
        break;
      }

      const transitionResult = getNextState(currentState, action, currentContext);
      if (!transitionResult) {
        break;
      }

      const { state: nextState, context: nextContext } = transitionResult;
      const stepStartTime = Date.now();

      actionLog.push({
        step,
        fromState: currentState,
        action,
        toState: nextState,
        timestamp: stepStartTime,
      });

      try {
        await executeConcreteAction(page, action);
        // Await expected state-specific oracle to guarantee React has settled
        await assertInvariantOracles(page, nextState, observer, step);
      } catch (error) {
        const stepRequests = networkMonitor.getRequestsSince(stepStartTime);
        const diagnosis = diagnoseTransitionFailure(action, nextState, stepRequests, error as Error);

        fs.mkdirSync('test-results/monkey-failures', { recursive: true });

        let screenshotPath: string | undefined;
        if (screenshotOnFailure) {
          screenshotPath = `test-results/monkey-failures/failure-seed-${seed}-step-${step}.png`;
          await page
            .screenshot({
              path: screenshotPath,
              fullPage: true,
            })
            .catch(() => {});
        }

        let tracePath: string | undefined;
        if (tracingActive) {
          tracePath = `test-results/monkey-failures/trace-seed-${seed}-step-${step}.zip`;
          await context.tracing.stop({ path: tracePath }).catch(() => {});
          tracingActive = false;
        }

        const formattedLog = actionLog
          .map((entry) => `[Step ${entry.step}] ${entry.fromState} --(${entry.action})--> ${entry.toState}`)
          .join('\n');

        const failureMessage =
          `Constrained Monkey Failure (Seed: ${seed}, Step: ${step}):\n` +
          `Action Sequence:\n${formattedLog}\n` +
          `Diagnosis: ${diagnosis}\n` +
          `Error: ${(error as Error).message}`;

        const wrappedError = new Error(failureMessage);
        wrappedError.stack = (error as Error).stack;

        return {
          seed,
          executedSteps: step,
          actionLog,
          finalState: currentState,
          finalContext: currentContext,
          success: false,
          error: wrappedError,
          screenshotPath,
          tracePath,
        };
      }

      currentState = nextState;
      currentContext = nextContext;
    }

    return {
      seed,
      executedSteps: actionLog.length,
      actionLog,
      finalState: currentState,
      finalContext: currentContext,
      success: true,
    };
  } finally {
    // If execution succeeded, stop tracing without path to release resources cleanly
    if (tracingActive) {
      await context.tracing.stop().catch(() => {});
      tracingActive = false;
    }
  }
}
