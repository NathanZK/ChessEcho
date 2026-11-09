import type { Page } from '@playwright/test';
import type { StateModel, AbstractTransitionRule } from './stateModel';
import { exploreAbstractStates } from './bfsExplorer';
import { InvariantErrorObserver, type ActionLogEntry } from './monkeyOracles';

export type { ActionLogEntry };

export interface ModelPathExecutionResult<TState, TContext> {
  success: boolean;
  finalState: TState;
  finalContext: TContext;
  actionLog: ActionLogEntry<TState>[];
  visitedStates: Set<TState>;
  executedTransitions: Set<string>;
  error?: Error;
  screenshotPath?: string;
}

export interface ModelPathExecutionOptions<TState, TContext> {
  initialState?: TState;
  initialContext?: TContext;
  observer?: InvariantErrorObserver;
  stepOffset?: number;
  captureArtifactsOnFailure?: boolean;
}

/**
 * Finds the shortest action path from initialState to targetState using BFS traversal.
 * Returns empty array if targetState equals initialState, or null if unreachable.
 */
export function findBfsPath<
  TState extends string = string,
  TAction extends string = string,
  TContext = unknown
>(
  model: StateModel<TState, TAction, TContext>,
  targetState: TState,
  initialState: TState = model.initialState,
  initialContext: TContext = model.createInitialContext(),
  maxDepth = 6
): TAction[] | null {
  if (targetState === initialState) {
    return [];
  }

  const exploration = exploreAbstractStates(
    initialState,
    model.transitions as AbstractTransitionRule<TState, TAction, TContext>[],
    maxDepth,
    initialContext,
    model.contextSignature
      ? (state, ctx) => `${state}:${model.contextSignature!(ctx)}`
      : undefined
  );

  const candidate = exploration.paths.find((p) => p.targetState === targetState);
  return candidate ? [...candidate.actions] : null;
}

/**
 * Executes a sequence of model actions against Playwright:
 * - Sequentially executes concrete DOM actions via model.executeAction.
 * - Asserts model state oracles and invariant observers at every step.
 * - Atomically commits visited states and transitions only upon successful verification.
 * - Captures diagnostic logs, screenshots on failure, and detaches observers in a finally block.
 */
export async function executeModelPath<
  TState extends string = string,
  TAction extends string = string,
  TContext = unknown
>(
  page: Page,
  model: StateModel<TState, TAction, TContext>,
  actions: TAction[],
  options?: ModelPathExecutionOptions<TState, TContext>
): Promise<ModelPathExecutionResult<TState, TContext>> {
  const {
    initialState = model.initialState,
    initialContext = model.createInitialContext(),
    observer,
    stepOffset = 0,
    captureArtifactsOnFailure = true,
  } = options ?? {};

  let currentState = initialState;
  let currentContext = { ...initialContext };
  const actionLog: ActionLogEntry<TState>[] = [];
  const visitedStates = new Set<TState>();
  const executedTransitions = new Set<string>();

  const activeObserver = observer ?? new InvariantErrorObserver();
  if (page && typeof page.on === 'function') {
    activeObserver.attach(page);
  }

  try {
    if (model.ensureInitialState && initialState === model.initialState && stepOffset === 0) {
      await model.ensureInitialState(page);
    }

    for (let i = 0; i < actions.length; i++) {
      const action = actions[i];
      const globalStep = stepOffset + i + 1;

      const rule = model.transitions.find(
        (r) =>
          r.from === currentState &&
          r.action === action &&
          (!r.guard || r.guard(currentContext))
      );

      if (!rule) {
        throw new Error(
          `[Model Path Error] Forbidden or unguarded action "${String(action)}" from state "${String(currentState)}" at step ${globalStep}.`
        );
      }

      const nextState = rule.to;
      const nextContext = rule.effect ? rule.effect(currentContext) : { ...currentContext };
      const stepStartTime = Date.now();

      actionLog.push({
        step: globalStep,
        fromState: currentState,
        action: String(action),
        toState: nextState,
        timestamp: stepStartTime,
      });

      // Concrete action execution
      await model.executeAction(page, action, currentContext);

      // State assertion oracle (supports both functions and { assert } objects)
      const oracle = model.oracles[nextState];
      if (typeof oracle === 'function') {
        await oracle(page, nextContext);
      } else if (oracle && 'assert' in oracle && typeof oracle.assert === 'function') {
        await oracle.assert(page, nextContext);
      }

      // Check invariant observer
      if (typeof activeObserver.assertNoErrors === 'function') {
        activeObserver.assertNoErrors(globalStep, String(nextState));
      }

      // Success: advance abstract state and record coverage
      currentState = nextState;
      currentContext = nextContext;
      visitedStates.add(nextState);
      executedTransitions.add(`${String(rule.from)}->${String(action)}->${String(nextState)}`);
    }

    return {
      success: true,
      finalState: currentState,
      finalContext: currentContext,
      actionLog,
      visitedStates,
      executedTransitions,
    };
  } catch (err) {
    let screenshotPath: string | undefined;
    if (captureArtifactsOnFailure && page && typeof page.screenshot === 'function') {
      try {
        const timestamp = Date.now();
        screenshotPath = `test-results/model-failure-${model.domain}-${timestamp}.png`;
        await page.screenshot({ path: screenshotPath, fullPage: true });
      } catch {
        // Safe fallback in test/mock environments where screenshot API throws
      }
    }

    return {
      success: false,
      finalState: currentState,
      finalContext: currentContext,
      actionLog,
      visitedStates,
      executedTransitions,
      error: err as Error,
      screenshotPath,
    };
  } finally {
    if (activeObserver && typeof activeObserver.detach === 'function') {
      activeObserver.detach();
    }
  }
}
