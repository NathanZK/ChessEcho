import {
  PracticeAbstractState,
  PracticeAction,
  PracticeModelContext,
  TransitionRule,
  createInitialContext,
} from './practiceModel';

export interface GeneratedPath {
  targetState: PracticeAbstractState;
  actions: PracticeAction[];
  states: PracticeAbstractState[];
}

export interface BfsExplorationResult {
  reachableStates: Set<PracticeAbstractState>;
  paths: GeneratedPath[];
  transitionsCovered: Set<string>;
}

interface BfsQueueNode {
  state: PracticeAbstractState;
  context: PracticeModelContext;
  actions: PracticeAction[];
  states: PracticeAbstractState[];
  depth: number;
}

export function explorePracticeStates(
  initialState: PracticeAbstractState = 'DISCOVERY',
  transitions: TransitionRule[],
  maxDepth = 4,
  initialContext: PracticeModelContext = createInitialContext()
): BfsExplorationResult {
  const reachableStates = new Set<PracticeAbstractState>([initialState]);
  const paths: GeneratedPath[] = [];
  const transitionsCovered = new Set<string>();

  const queue: BfsQueueNode[] = [
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
      const transitionSignature = `${current.state}->${rule.action}->${nextState}`;
      transitionsCovered.add(transitionSignature);

      // Cycle prevention: avoid repeating identical state+context in the same branch
      const branchKey = `${nextState}:${nextContext.puzzleLoaded}:${nextContext.inExploration}`;
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
      }
    }
  }

  return {
    reachableStates,
    paths,
    transitionsCovered,
  };
}
