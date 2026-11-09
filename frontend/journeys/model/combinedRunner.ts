import fs from 'node:fs';
import type { Page } from '@playwright/test';
import {
  type PracticeAbstractState,
  type PracticeAction,
  type PracticeModelContext,
  createInitialContext,
  PracticeModel,
} from './practiceModel';
import { findBfsPath, executeModelPath } from './modelRunner';
import {
  type ActionLogEntry,
  InvariantErrorObserver,
  NetworkActivityMonitor,
} from './monkeyOracles';
import {
  runConstrainedMonkey,
} from './monkeyRunner';

export type HighRiskScenario =
  | 'retry-after-wrong-move'
  | 'continuation-loading'
  | 'evaluation-pending'
  | 'navigation-during-evaluation';

export interface HighRiskScenarioConfig {
  scenario: HighRiskScenario;
  targetState: PracticeAbstractState;
  bfsPath: PracticeAction[];
  description: string;
}

export const HIGH_RISK_SCENARIOS: Record<HighRiskScenario, HighRiskScenarioConfig> = {
  'retry-after-wrong-move': {
    scenario: 'retry-after-wrong-move',
    targetState: 'INCORRECT',
    bfsPath: ['SELECT_PUZZLE', 'SUBMIT_MISTAKE'],
    description: 'Enters practice, plays inaccurate move (a2->a3), verifies board rollback and prepares for retry exploration.',
  },
  'continuation-loading': {
    scenario: 'continuation-loading',
    targetState: 'EXPLORING',
    bfsPath: ['SELECT_PUZZLE', 'SUBMIT_OPTIMAL', 'CONTINUE_EXPLORATION'],
    description: 'Solves puzzle optimally, transitions across mode boundary to Line Exploration mode, and prepares for line interactions.',
  },
  'evaluation-pending': {
    scenario: 'evaluation-pending',
    targetState: 'PRACTICE_IDLE',
    bfsPath: ['SELECT_PUZZLE'],
    description: 'Reaches interactive board ready for move submission under an intercepted deferred evaluation route gate.',
  },
  'navigation-during-evaluation': {
    scenario: 'navigation-during-evaluation',
    targetState: 'PRACTICE_IDLE',
    bfsPath: ['SELECT_PUZZLE'],
    description: 'Reaches interactive board ready to test concurrent navigation during held move evaluation.',
  },
};

export interface CombinedRunOptions {
  page: Page;
  targetState: PracticeAbstractState;
  monkeySeed: number;
  monkeyActionBudget: number;
  customBfsPath?: PracticeAction[];
  stepOffset?: number;
  screenshotOnFailure?: boolean;
  stepHook?: (step: number, state: PracticeAbstractState) => Promise<void>;
}

export interface BfsExecutionResult {
  success: boolean;
  finalState: PracticeAbstractState;
  finalContext: PracticeModelContext;
  actionLog: ActionLogEntry[];
  error?: Error;
}

export interface CombinedRunResult {
  targetState: PracticeAbstractState;
  bfsPath: PracticeAction[];
  monkeySeed: number;
  executedBfsSteps: number;
  executedMonkeySteps: number;
  actionLog: ActionLogEntry[];
  finalState: PracticeAbstractState;
  finalContext: PracticeModelContext;
  success: boolean;
  error?: Error;
  screenshotPath?: string;
  tracePath?: string;
}

/**
 * Computes the shortest action sequence from initialState to targetState
 * using bounded BFS traversal on the Practice State Model.
 * Returns null if the target state cannot be reached.
 */
export function findBfsPathToState(
  targetState: PracticeAbstractState,
  initialState: PracticeAbstractState = 'DISCOVERY',
  initialContext?: PracticeModelContext
): PracticeAction[] | null {
  const context: PracticeModelContext = initialContext ?? {
    puzzleLoaded: initialState !== 'DISCOVERY',
    attemptCount: 0,
    inExploration: initialState === 'EXPLORING',
  };

  return findBfsPath(PracticeModel, targetState, initialState, context, 6);
}

/**
 * Sequentially executes a BFS action path, updating state and context,
 * and asserting invariant oracles at every intermediate step.
 */
export async function executeBfsPath(
  page: Page,
  actions: PracticeAction[],
  options?: {
    initialState?: PracticeAbstractState;
    initialContext?: PracticeModelContext;
    observer?: InvariantErrorObserver;
    stepOffset?: number;
  }
): Promise<BfsExecutionResult> {
  const result = await executeModelPath(page, PracticeModel, actions, options);
  return {
    success: result.success,
    finalState: result.finalState,
    finalContext: result.finalContext,
    actionLog: result.actionLog,
    error: result.error,
  };
}

/**
 * Orchestrates combined BFS path seeding and constrained monkey testing:
 * 1. Resolves and executes shortest path to target state with step-by-step invariant verification.
 * 2. Hands off the verified page and cumulative context to runConstrainedMonkey.
 * 3. In the event of failure, dumps stitched traces, screenshots, and Playwright trace archives.
 */
export async function runCombinedBfsMonkey(
  options: CombinedRunOptions
): Promise<CombinedRunResult> {
  const {
    page,
    targetState,
    monkeySeed,
    monkeyActionBudget,
    customBfsPath,
    stepOffset = 0,
    screenshotOnFailure = true,
  } = options;

  const resolvedBfsPath = customBfsPath ?? findBfsPathToState(targetState);

  if (resolvedBfsPath === null) {
    return {
      targetState,
      bfsPath: [],
      monkeySeed,
      executedBfsSteps: 0,
      executedMonkeySteps: 0,
      actionLog: [],
      finalState: 'DISCOVERY',
      finalContext: createInitialContext(),
      success: false,
      error: new Error(`Unreachable target state: ${targetState}`),
    };
  }

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
    // Tracing already active in outer runner
  }

  try {
    // 1. Execute BFS path with invariant assertion at each step
    const bfsResult = await executeBfsPath(page, resolvedBfsPath, {
      initialState: 'DISCOVERY',
      initialContext: createInitialContext(),
      observer,
      stepOffset,
    });

    if (!bfsResult.success) {
      fs.mkdirSync('test-results/combined-failures', { recursive: true });

      const failedStep = stepOffset + bfsResult.actionLog.length;
      let screenshotPath: string | undefined;
      if (screenshotOnFailure) {
        screenshotPath = `test-results/combined-failures/failure-target-${targetState}-seed-${monkeySeed}.png`;
        await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
      }

      let tracePath: string | undefined;
      if (tracingActive) {
        tracePath = `test-results/combined-failures/trace-target-${targetState}-seed-${monkeySeed}.zip`;
        await context.tracing.stop({ path: tracePath }).catch(() => {});
        tracingActive = false;
      }

      const formattedLog = bfsResult.actionLog
        .map((entry) => `[Step ${entry.step}] ${entry.fromState} --(${entry.action})--> ${entry.toState}`)
        .join('\n');

      const failureMessage =
        `Combined BFS-Monkey Failure during BFS Preamble (Target: ${targetState}, Step: ${failedStep}, Seed: ${monkeySeed}):\n` +
        `Action Sequence:\n${formattedLog}\n` +
        `Error: ${bfsResult.error?.message}`;

      const wrappedError = new Error(failureMessage);
      wrappedError.stack = bfsResult.error?.stack;

      return {
        targetState,
        bfsPath: resolvedBfsPath,
        monkeySeed,
        executedBfsSteps: bfsResult.actionLog.length,
        executedMonkeySteps: 0,
        actionLog: bfsResult.actionLog,
        finalState: bfsResult.finalState,
        finalContext: bfsResult.finalContext,
        success: false,
        error: wrappedError,
        screenshotPath,
        tracePath,
      };
    }

    // 2. Handoff to Constrained Monkey at reached state with accumulated context
    const monkeyResult = await runConstrainedMonkey({
      page,
      seed: monkeySeed,
      actionBudget: monkeyActionBudget,
      initialState: bfsResult.finalState,
      initialContext: bfsResult.finalContext,
      stepOffset: stepOffset + bfsResult.actionLog.length,
      screenshotOnFailure,
      stepHook: options.stepHook,
    });

    const combinedActionLog = [...bfsResult.actionLog, ...monkeyResult.actionLog];

    if (!monkeyResult.success) {
      fs.mkdirSync('test-results/combined-failures', { recursive: true });

      let screenshotPath = monkeyResult.screenshotPath;
      if (screenshotOnFailure && !screenshotPath) {
        screenshotPath = `test-results/combined-failures/failure-target-${targetState}-seed-${monkeySeed}.png`;
        await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
      }

      let tracePath = monkeyResult.tracePath;
      if (tracingActive && !tracePath) {
        tracePath = `test-results/combined-failures/trace-target-${targetState}-seed-${monkeySeed}.zip`;
        await context.tracing.stop({ path: tracePath }).catch(() => {});
        tracingActive = false;
      }

      const formattedLog = combinedActionLog
        .map((entry) => `[Step ${entry.step}] ${entry.fromState} --(${entry.action})--> ${entry.toState}`)
        .join('\n');

      const failureMessage =
        `Combined BFS-Monkey Failure during Monkey Phase (Target: ${targetState}, Seed: ${monkeySeed}):\n` +
        `Action Sequence:\n${formattedLog}\n` +
        `Error: ${monkeyResult.error?.message}`;

      const wrappedError = new Error(failureMessage);
      wrappedError.stack = monkeyResult.error?.stack;

      return {
        targetState,
        bfsPath: resolvedBfsPath,
        monkeySeed,
        executedBfsSteps: bfsResult.actionLog.length,
        executedMonkeySteps: monkeyResult.executedSteps,
        actionLog: combinedActionLog,
        finalState: monkeyResult.finalState,
        finalContext: monkeyResult.finalContext,
        success: false,
        error: wrappedError,
        screenshotPath,
        tracePath,
      };
    }

    return {
      targetState,
      bfsPath: resolvedBfsPath,
      monkeySeed,
      executedBfsSteps: bfsResult.actionLog.length,
      executedMonkeySteps: monkeyResult.executedSteps,
      actionLog: combinedActionLog,
      finalState: monkeyResult.finalState,
      finalContext: monkeyResult.finalContext,
      success: true,
    };
  } finally {
    if (tracingActive) {
      await context.tracing.stop().catch(() => {});
      tracingActive = false;
    }
  }
}
