import {
  type PracticeAbstractState,
  type PracticeAction,
  type PracticeModelContext,
  PRACTICE_TRANSITIONS,
} from './practiceModel';
import type { PRNG } from './prng';
import type { MonkeySelectionStrategy } from './campaignConfig';

export interface SelectorHistory {
  transitionCounts?: Map<string, number>;
  stateCounts?: Map<PracticeAbstractState, number>;
}

/**
 * Selects an action permitted by the Phase 2 practice abstract state model
 * from the given state and context, using a deterministic PRNG and strategy.
 *
 * Returns null if no valid transitions are available from the current state.
 */
export function selectConstrainedAction(
  state: PracticeAbstractState,
  context: PracticeModelContext,
  prng: PRNG,
  strategy: MonkeySelectionStrategy = 'uniform',
  history?: SelectorHistory,
): PracticeAction | null {
  const permittedRules = PRACTICE_TRANSITIONS.filter(
    (rule) => rule.from === state && (!rule.guard || rule.guard(context)),
  );

  if (permittedRules.length === 0) {
    return null;
  }

  const transitionCounts = history?.transitionCounts;
  if (strategy === 'least-used-transition' && transitionCounts) {
    let minCount = Infinity;
    for (const rule of permittedRules) {
      const key = `${rule.from}->${rule.action}->${rule.to}`;
      const count = transitionCounts.get(key) ?? 0;
      minCount = Math.min(minCount, count);
    }
    const candidates = permittedRules.filter((rule) => {
      const key = `${rule.from}->${rule.action}->${rule.to}`;
      return (transitionCounts.get(key) ?? 0) === minCount;
    });
    const chosenIndex = prng.nextInt(0, candidates.length - 1);
    return candidates[chosenIndex].action;
  }

  const stateCounts = history?.stateCounts;
  if (strategy === 'rare-state-bias' && stateCounts) {
    let minCount = Infinity;
    for (const rule of permittedRules) {
      const count = stateCounts.get(rule.to) ?? 0;
      minCount = Math.min(minCount, count);
    }
    const candidates = permittedRules.filter((rule) => {
      return (stateCounts.get(rule.to) ?? 0) === minCount;
    });
    const chosenIndex = prng.nextInt(0, candidates.length - 1);
    return candidates[chosenIndex].action;
  }

  const chosenIndex = prng.nextInt(0, permittedRules.length - 1);
  return permittedRules[chosenIndex].action;
}
