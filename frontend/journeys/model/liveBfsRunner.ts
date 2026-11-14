import type { Page } from '@playwright/test';
import {
  type PracticeState,
  type PracticeAction,
  type PracticeContext,
  PRACTICE_FSM_STATES,
  PRACTICE_FSM_ACTIONS,
  getPracticeFsmNextState,
  createInitialPracticeContext,
} from './modelFsm';
import {
  exploreAbstractStates,
  type AbstractTransitionRule,
  type AbstractGeneratedPath,
} from './bfsExplorer';
import { PlaywrightBrowserPracticeSutAdapter } from './browserSutAdapter';
import { InvariantEvaluator, type ObservedPracticeState } from './invariantEvaluator';

export interface LiveBfsStep {
  stepIndex: number;
  action: PracticeAction;
  expectedState: PracticeState;
  observedState: ObservedPracticeState;
}

export interface LiveBfsResult {
  targetState: PracticeState;
  path: PracticeAction[];
  states: PracticeState[];
  finalObservedState: ObservedPracticeState;
  steps: LiveBfsStep[];
}

export interface LiveBfsOptions {
  maxDepth?: number;
  initialContext?: Partial<PracticeContext>;
  sut?: PlaywrightBrowserPracticeSutAdapter;
}

/**
 * Derives the complete transition rule set for the Practice FSM.
 */
export function getPracticeFsmTransitionRules(): AbstractTransitionRule<
  PracticeState,
  PracticeAction,
  PracticeContext
>[] {
  const rules: AbstractTransitionRule<PracticeState, PracticeAction, PracticeContext>[] = [];

  for (const state of PRACTICE_FSM_STATES) {
    for (const action of PRACTICE_FSM_ACTIONS) {
      // In the live browser UI, exploration can only be entered after attempting a move or solving
      if (action === 'ENTER_LINE_EXPLORATION' && state === 'INITIAL') {
        continue;
      }
      const sampleCtx = createInitialPracticeContext({
        puzzleId: 'mock-puzzle-1',
        entryFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
        boardFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
      });
      const result = getPracticeFsmNextState(state, action, sampleCtx);
      if (result !== null) {
        rules.push({
          from: state,
          action,
          to: result.nextState,
          guard: (ctx: PracticeContext) => getPracticeFsmNextState(state, action, ctx) !== null,
          effect: (ctx: PracticeContext) => {
            const res = getPracticeFsmNextState(state, action, ctx);
            return res ? res.nextContext : ctx;
          },
        });
      }
    }
  }

  return rules;
}

/**
 * Computes a model-generated BFS action path from initialState to targetState.
 */
export function findBfsPathToState(
  initialState: PracticeState,
  targetState: PracticeState,
  initialContext: PracticeContext,
  maxDepth = 6
): AbstractGeneratedPath<PracticeState, PracticeAction> | null {
  const transitions = getPracticeFsmTransitionRules();
  const exploration = exploreAbstractStates(
    initialState,
    transitions,
    maxDepth,
    initialContext
  );

  const matchingPath = exploration.paths.find((p) => p.targetState === targetState);
  return matchingPath ?? null;
}

/**
 * Executes a dynamically computed BFS path against the running Next.js application,
 * observing real application state after every single action and asserting invariants.
 */
export async function runLiveBfsModel(
  page: Page,
  targetState: PracticeState,
  options: LiveBfsOptions = {}
): Promise<LiveBfsResult> {
  const initialContext = createInitialPracticeContext(options.initialContext);
  const sut = options.sut ?? new PlaywrightBrowserPracticeSutAdapter(page, initialContext);
  const invariantEvaluator = new InvariantEvaluator();

  const initialState = sut.getCurrentState();
  const path = findBfsPathToState(
    initialState,
    targetState,
    initialContext,
    options.maxDepth ?? 6
  );

  if (!path) {
    throw new Error(
      `LiveBfsRunner: No BFS path found from ${initialState} to ${targetState} within maxDepth ${options.maxDepth ?? 6}`
    );
  }

  let currentState = initialState;
  let currentContext = { ...initialContext };
  const steps: LiveBfsStep[] = [];
  let lastObservedState: ObservedPracticeState | null = null;

  for (let i = 0; i < path.actions.length; i++) {
    const action = path.actions[i];
    const transition = getPracticeFsmNextState(currentState, action, currentContext);
    if (!transition) {
      throw new Error(
        `LiveBfsRunner: Invalid model transition for action ${action} from state ${currentState}`
      );
    }

    // 1. Execute action via Playwright SUT adapter
    await sut.executeAction(action, currentContext);

    // 2. Observe live DOM application state immediately
    const observed = await sut.observeState();
    lastObservedState = observed;

    // 3. Feed observed state into InvariantEvaluator assertions
    invariantEvaluator.assertInvariants(transition.nextState, observed, action);

    // 4. If exiting exploration, assert historical invariant UOBS-13
    if (action === 'EXIT_EXPLORATION' || (currentState === 'LINE_EXPLORATION' && transition.nextState === 'EXIT')) {
      invariantEvaluator.assertExplorationExitInvariant(transition.nextContext, observed);
    }

    // 5. Check expected view alignment
    if (observed.currentView !== transition.nextContext.currentView) {
      throw new Error(
        `LiveBfsRunner invariant violation at step ${i} (${action}): expected currentView ${transition.nextContext.currentView}, got ${observed.currentView}`
      );
    }

    steps.push({
      stepIndex: i,
      action,
      expectedState: transition.nextState,
      observedState: observed,
    });

    currentState = transition.nextState;
    currentContext = {
      ...transition.nextContext,
      boardFen: observed.boardFen || transition.nextContext.boardFen,
    };
  }

  return {
    targetState,
    path: path.actions,
    states: path.states,
    finalObservedState: lastObservedState!,
    steps,
  };
}
