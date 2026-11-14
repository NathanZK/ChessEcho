import {
  PracticeAbstractState,
  PracticeAction,
  PracticeModelContext,
  TransitionRule,
  createInitialContext,
} from './practiceModel';
import type { SutAdapter } from './sutAdapter';

export interface AbstractGeneratedPath<TState, TAction> {
  targetState: TState;
  actions: TAction[];
  states: TState[];
}

export interface AbstractBfsResult<TState, TAction> {
  reachableStates: Set<TState>;
  paths: AbstractGeneratedPath<TState, TAction>[];
  transitionsCovered: Set<string>;
}

export interface AbstractTransitionRule<TState, TAction, TContext> {
  from: TState;
  action: TAction;
  to: TState;
  guard?: (context: TContext) => boolean;
  effect?: (context: TContext) => TContext;
}

interface BfsQueueNode<TState, TAction, TContext> {
  state: TState;
  context: TContext;
  actions: TAction[];
  states: TState[];
  depth: number;
}

/**
 * Deterministic canonical state hash that normalizes object key ordering.
 */
export function canonicalStateHash<TState, TContext>(state: TState, context: TContext): string {
  function sortKeys(value: unknown): unknown {
    if (value === null || typeof value !== 'object') {
      return value;
    }
    if (Array.isArray(value)) {
      return value.map(sortKeys);
    }
    const obj = value as Record<string, unknown>;
    const sortedObj: Record<string, unknown> = {};
    const keys = Object.keys(obj).sort();
    for (const key of keys) {
      sortedObj[key] = sortKeys(obj[key]);
    }
    return sortedObj;
  }

  return `${String(state)}:${JSON.stringify(sortKeys(context))}`;
}

export function exploreAbstractStates<TState, TAction, TContext>(
  initialState: TState,
  transitions: AbstractTransitionRule<TState, TAction, TContext>[],
  maxDepth = 6,
  initialContext: TContext,
  contextSignatureFn?: (state: TState, context: TContext) => string,
  budget = 100
): AbstractBfsResult<TState, TAction> {
  const reachableStates = new Set<TState>([initialState]);
  const paths: AbstractGeneratedPath<TState, TAction>[] = [];
  const transitionsCovered = new Set<string>();

  const queue: BfsQueueNode<TState, TAction, TContext>[] = [
    {
      state: initialState,
      context: initialContext,
      actions: [],
      states: [initialState],
      depth: 0,
    },
  ];

  const visitedSignatures = new Map<string, number>();

  while (queue.length > 0) {
    if (transitionsCovered.size >= budget) {
      break;
    }

    const current = queue.shift();
    if (!current) {
      break;
    }

    reachableStates.add(current.state);

    if (current.depth > 0) {
      paths.push({
        targetState: current.state,
        actions: current.actions,
        states: current.states,
      });
    }

    if (current.depth >= maxDepth) {
      continue;
    }

    for (const rule of transitions) {
      if (transitionsCovered.size >= budget) {
        break;
      }

      if (rule.from !== current.state) {
        continue;
      }

      if (rule.guard && !rule.guard(current.context)) {
        continue;
      }

      const nextContext = rule.effect ? rule.effect(current.context) : { ...current.context };
      const nextState = rule.to;
      const transitionSignature = `${String(current.state)}->${String(rule.action)}->${String(nextState)}`;
      transitionsCovered.add(transitionSignature);

      // Canonical state hashing for cycle prevention
      const branchKey = contextSignatureFn
        ? contextSignatureFn(nextState, nextContext)
        : canonicalStateHash(nextState, nextContext);
      const previousDepth = visitedSignatures.get(branchKey);

      // Only enqueue if not visited or reached at a shallower/equal depth
      if (previousDepth === undefined || current.depth + 1 <= previousDepth) {
        visitedSignatures.set(branchKey, current.depth + 1);
        queue.push({
          state: nextState,
          context: nextContext,
          actions: [...current.actions, rule.action],
          states: [...current.states, nextState],
          depth: current.depth + 1,
        });
      } else {
        paths.push({
          targetState: nextState,
          actions: [...current.actions, rule.action],
          states: [...current.states, nextState],
        });
      }
    }
  }

  return {
    reachableStates,
    paths,
    transitionsCovered,
  };
}

export type GeneratedPath = AbstractGeneratedPath<PracticeAbstractState, PracticeAction>;
export type BfsExplorationResult = AbstractBfsResult<PracticeAbstractState, PracticeAction>;

export function explorePracticeStates(
  initialState: PracticeAbstractState = 'DISCOVERY',
  transitions: TransitionRule[],
  maxDepth = 6,
  initialContext: PracticeModelContext = createInitialContext()
): BfsExplorationResult {
  return exploreAbstractStates(
    initialState,
    transitions,
    maxDepth,
    initialContext,
    (state, ctx) =>
      `${state}:${ctx.puzzleLoaded}:${ctx.inExploration}:${ctx.attemptCount}:${ctx.solved}:${ctx.isBoardFlipped}:${ctx.blindfoldActive}:${ctx.blindfoldVisible}:${ctx.hintActive}:${Math.min(ctx.historyIndex ?? 0, 2)}:${ctx.timerMode}`
  );
}

export interface TraceMinimizationResult<TAction> {
  originalLength: number;
  minimizedActions: TAction[];
  replaySeed: number;
  replayCommand: string;
}

/**
 * Failure-preserving trace minimization (1-delta greedy reduction).
 * Strips intermediate neutral actions while preserving the reproducing invariant failure.
 */
export async function minimizeTrace<TState, TAction, TObservedState, TContext>(
  initialState: TState,
  initialContext: TContext,
  failingActions: TAction[],
  adapter: SutAdapter<TState, TAction, TObservedState>,
  evaluator: (expectedState: TState, observed: TObservedState, action: TAction) => void,
  transitionFn: (
    state: TState,
    action: TAction,
    ctx: TContext
  ) => { nextState: TState; nextContext: TContext } | null,
  seed = 42
): Promise<TraceMinimizationResult<TAction>> {
  async function reproducesFailure(actions: TAction[]): Promise<boolean> {
    await adapter.reset(initialContext);
    let curState = initialState;
    let curContext = initialContext;

    for (const action of actions) {
      const transition = transitionFn(curState, action, curContext);
      if (!transition) {
        return false;
      }
      curState = transition.nextState;
      curContext = transition.nextContext;

      try {
        await adapter.executeAction(action);
        const observed = await adapter.observeState();
        evaluator(curState, observed, action);
      } catch {
        return true;
      }
    }
    return false;
  }

  const initialFailed = await reproducesFailure(failingActions);
  if (!initialFailed) {
    throw new Error('Initial trace did not fail invariant evaluation');
  }

  let currentActions = [...failingActions];
  let reduced = true;

  while (reduced) {
    reduced = false;
    for (let i = 0; i < currentActions.length; i++) {
      const candidate = currentActions.filter((_, idx) => idx !== i);
      if (candidate.length === 0) {
        continue;
      }
      const fails = await reproducesFailure(candidate);
      if (fails) {
        currentActions = candidate;
        reduced = true;
        break;
      }
    }
  }

  return {
    originalLength: failingActions.length,
    minimizedActions: currentActions,
    replaySeed: seed,
    replayCommand: `npm --prefix frontend run test -- frontend/journeys/model/bfsExplorer.test.ts --seed=${seed}`,
  };
}
