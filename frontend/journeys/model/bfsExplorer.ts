import {
  PracticeAbstractState,
  PracticeAction,
  PracticeModelContext,
  TransitionRule,
  createInitialContext,
} from './practiceModel';

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

export function exploreAbstractStates<TState, TAction, TContext>(
  initialState: TState,
  transitions: AbstractTransitionRule<TState, TAction, TContext>[],
  maxDepth = 4,
  initialContext: TContext,
  contextSignatureFn?: (state: TState, context: TContext) => string
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
    const current = queue.shift()!;
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

      // Cycle prevention: avoid repeating identical state+context in the same branch
      const branchKey = contextSignatureFn
        ? contextSignatureFn(nextState, nextContext)
        : `${String(nextState)}:${JSON.stringify(nextContext)}`;
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
  maxDepth = 4,
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
