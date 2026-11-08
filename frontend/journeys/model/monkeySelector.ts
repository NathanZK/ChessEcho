import {
  type PracticeAbstractState,
  type PracticeAction,
  type PracticeModelContext,
  PRACTICE_TRANSITIONS,
} from './practiceModel';
import type { PRNG } from './prng';

/**
 * Selects an action permitted by the Phase 2 practice abstract state model
 * from the given state and context, using a deterministic PRNG.
 *
 * Returns null if no valid transitions are available from the current state.
 */
export function selectConstrainedAction(
  state: PracticeAbstractState,
  context: PracticeModelContext,
  prng: PRNG,
): PracticeAction | null {
  const permittedRules = PRACTICE_TRANSITIONS.filter(
    (rule) => rule.from === state && (!rule.guard || rule.guard(context)),
  );

  if (permittedRules.length === 0) {
    return null;
  }

  const chosenIndex = prng.nextInt(0, permittedRules.length - 1);
  return permittedRules[chosenIndex].action;
}
