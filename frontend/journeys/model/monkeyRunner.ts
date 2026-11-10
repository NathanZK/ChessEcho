import fs from 'node:fs';
import type { Page } from '@playwright/test';
import {
  type PracticeAbstractState,
  type PracticeAction,
  type PracticeModelContext,
  createInitialContext,
  getNextState,
  ensureDiscoveryState,
  executeConcreteAction,
} from './practiceModel';

export { executeConcreteAction };
import { createPrng } from './prng';
import { selectConstrainedAction } from './monkeySelector';
import type { MonkeySelectionStrategy } from './campaignConfig';
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
  stepOffset?: number;
  screenshotOnFailure?: boolean;
  stepHook?: (step: number, state: PracticeAbstractState) => Promise<void>;
  traceMode?: 'failures-only' | 'all' | 'off';
  strategy?: MonkeySelectionStrategy;
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
 * Runs a bounded, state-constrained monkey test sequence using a deterministic seed.
 */
export async function runConstrainedMonkey(options: MonkeyRunOptions): Promise<MonkeyRunResult> {
  const {
    page,
    seed,
    actionBudget,
    initialState = 'DISCOVERY',
    initialContext = createInitialContext(),
    stepOffset = 0,
    screenshotOnFailure = true,
    traceMode = 'failures-only',
  } = options;

  const prng = createPrng(seed);
  const observer = new InvariantErrorObserver();
  observer.attach(page);

  const networkMonitor = new NetworkActivityMonitor();
  networkMonitor.attach(page);

  const context = page.context();
  let tracingActive = false;
  if (traceMode !== 'off') {
    try {
      await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
      tracingActive = true;
    } catch {
      // Tracing already active in outer runner or unsupported
    }
  }

  let currentState = initialState;
  let currentContext = { ...initialContext };
  const actionLog: ActionLogEntry[] = [];
  const strategy = options.strategy ?? 'uniform';
  const transitionCounts = new Map<string, number>();
  const stateCounts = new Map<PracticeAbstractState, number>();
  stateCounts.set(initialState, 1);

  // Align UI with initial state if starting in DISCOVERY but currently on interactive board
  if (initialState === 'DISCOVERY') {
    await ensureDiscoveryState(page);
  }

  try {
    for (let i = 1; i <= actionBudget; i++) {
      const step = stepOffset + i;
      if (options.stepHook) {
        await options.stepHook(step, currentState);
      }

      const action = selectConstrainedAction(currentState, currentContext, prng, strategy, {
        transitionCounts,
        stateCounts,
      });

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
        const transitionKey = `${currentState}->${action}->${nextState}`;
        transitionCounts.set(transitionKey, (transitionCounts.get(transitionKey) ?? 0) + 1);
        stateCounts.set(nextState, (stateCounts.get(nextState) ?? 0) + 1);
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

    let tracePath: string | undefined;
    if (tracingActive && traceMode === 'all') {
      fs.mkdirSync('test-results/monkey-traces', { recursive: true });
      tracePath = `test-results/monkey-traces/trace-seed-${seed}.zip`;
      await context.tracing.stop({ path: tracePath }).catch(() => {});
      tracingActive = false;
    }

    return {
      seed,
      executedSteps: actionLog.length,
      actionLog,
      finalState: currentState,
      finalContext: currentContext,
      success: true,
      tracePath,
    };
  } finally {
    // If execution succeeded, stop tracing without path to release resources cleanly
    if (tracingActive) {
      await context.tracing.stop().catch(() => {});
      tracingActive = false;
    }
  }
}
