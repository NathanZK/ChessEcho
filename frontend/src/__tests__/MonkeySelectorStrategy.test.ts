import { describe, expect, it } from 'vitest';
import { createPrng } from '../../journeys/model/prng';
import {
  selectConstrainedAction,
  type SelectorHistory,
} from '../../journeys/model/monkeySelector';
import {
  createInitialContext,
  PRACTICE_TRANSITIONS,
  type PracticeAbstractState,
} from '../../journeys/model/practiceModel';

describe('Feedback-Guided Selection Strategies (monkeySelector.ts)', () => {
  it('defaults to uniform selection when no strategy or history is provided', () => {
    const prng1 = createPrng(12345);
    const prng2 = createPrng(12345);
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };

    const actionUniform = selectConstrainedAction('PRACTICE_IDLE', context, prng1, 'uniform');
    const actionDefault = selectConstrainedAction('PRACTICE_IDLE', context, prng2);

    expect(actionUniform).not.toBeNull();
    expect(actionUniform).toBe(actionDefault);
  });

  it('safely falls back to uniform selection when strategy is guided but history or maps are undefined', () => {
    const prng1 = createPrng(12345);
    const prng2 = createPrng(12345);
    const prng3 = createPrng(12345);
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };

    const action1 = selectConstrainedAction('PRACTICE_IDLE', context, prng1, 'uniform');
    const action2 = selectConstrainedAction('PRACTICE_IDLE', context, prng2, 'least-used-transition', undefined);
    const action3 = selectConstrainedAction('PRACTICE_IDLE', context, prng3, 'rare-state-bias', {});

    expect(action2).toBe(action1);
    expect(action3).toBe(action1);
  });

  it('does not execute guided branches when strategy is uniform even if history is populated', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const validRules = PRACTICE_TRANSITIONS.filter(
      (r) => r.from === 'PRACTICE_IDLE' && (!r.guard || r.guard(context)),
    );

    const history: SelectorHistory = {
      transitionCounts: new Map([[
        `${validRules[0].from}->${validRules[0].action}->${validRules[0].to}`,
        1000,
      ]]),
      stateCounts: new Map([[validRules[0].to, 1000]]),
    };

    // Under uniform, seed that picks index 0 will still pick validRules[0].action
    // whereas under least-used it would be blocked
    let pickedIndex0 = false;
    for (let seed = 1; seed <= 100; seed++) {
      const prng = createPrng(seed);
      const action = selectConstrainedAction('PRACTICE_IDLE', context, prng, 'uniform', history);
      if (action === validRules[0].action) {
        pickedIndex0 = true;
        break;
      }
    }
    expect(pickedIndex0).toBe(true);
  });

  it('least-used-transition selects the transition with the lowest execution count', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const validRules = PRACTICE_TRANSITIONS.filter(
      (r) => r.from === 'PRACTICE_IDLE' && (!r.guard || r.guard(context)),
    );
    expect(validRules.length).toBeGreaterThanOrEqual(2);

    const history: SelectorHistory = {
      transitionCounts: new Map<string, number>(),
    };

    // Mark the first rule as heavily used
    const heavilyUsedKey = `${validRules[0].from}->${validRules[0].action}->${validRules[0].to}`;
    history.transitionCounts?.set(heavilyUsedKey, 10);

    // Across 100 seeds, least-used-transition must NEVER select validRules[0].action
    // while there are other candidate transitions with count 0.
    for (let seed = 1; seed <= 100; seed++) {
      const prng = createPrng(seed);
      const chosenAction = selectConstrainedAction(
        'PRACTICE_IDLE',
        context,
        prng,
        'least-used-transition',
        history,
      );
      expect(chosenAction).not.toBe(validRules[0].action);
    }
  });

  it('least-used-transition correctly identifies minimum count when all transitions have non-zero counts', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const validRules = PRACTICE_TRANSITIONS.filter(
      (r) => r.from === 'PRACTICE_IDLE' && (!r.guard || r.guard(context)),
    );
    expect(validRules.length).toBeGreaterThanOrEqual(2);

    const history: SelectorHistory = {
      transitionCounts: new Map<string, number>(),
    };

    // Populate non-zero counts for every valid rule: rule 0 has 5, rule 1 has 2 (min), rest have 10
    validRules.forEach((rule, idx) => {
      const key = `${rule.from}->${rule.action}->${rule.to}`;
      history.transitionCounts?.set(key, idx === 1 ? 2 : 10);
    });

    const prng = createPrng(42);
    const chosen = selectConstrainedAction(
      'PRACTICE_IDLE',
      context,
      prng,
      'least-used-transition',
      history,
    );
    expect(chosen).toBe(validRules[1].action);
  });

  it('least-used-transition deterministically samples among tied minimal transitions using PRNG', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const history: SelectorHistory = {
      transitionCounts: new Map<string, number>(),
    };

    // Run 50 selections with different seeds to ensure it does not always pick index 0
    const chosenActions = new Set<string>();
    for (let seed = 1; seed <= 50; seed++) {
      const prng = createPrng(seed);
      const action = selectConstrainedAction(
        'PRACTICE_IDLE',
        context,
        prng,
        'least-used-transition',
        history,
      );
      if (action) {
        chosenActions.add(action);
      }
    }

    expect(chosenActions.size).toBeGreaterThan(1);
  });

  it('rare-state-bias prioritizes transitions leading to historically under-visited target states', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const validRules = PRACTICE_TRANSITIONS.filter(
      (r) => r.from === 'PRACTICE_IDLE' && (!r.guard || r.guard(context)),
    );
    const targetStates = Array.from(new Set(validRules.map((r) => r.to)));
    expect(targetStates.length).toBeGreaterThanOrEqual(2);

    const history: SelectorHistory = {
      stateCounts: new Map<PracticeAbstractState, number>(),
    };

    // Set high visit count on targetStates[0] and 0 on targetStates[1]
    history.stateCounts?.set(targetStates[0], 100);
    history.stateCounts?.set(targetStates[1], 0);

    // Across 100 seeds, rare-state-bias must NEVER select a transition leading to targetStates[0]
    for (let seed = 1; seed <= 100; seed++) {
      const prng = createPrng(seed);
      const chosenAction = selectConstrainedAction(
        'PRACTICE_IDLE',
        context,
        prng,
        'rare-state-bias',
        history,
      );

      const chosenRule = validRules.find((r) => r.action === chosenAction);
      expect(chosenRule).toBeDefined();
      expect(chosenRule?.to).not.toBe(targetStates[0]);
    }
  });

  it('rare-state-bias correctly identifies minimum target state count when all target states have non-zero counts', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const validRules = PRACTICE_TRANSITIONS.filter(
      (r) => r.from === 'PRACTICE_IDLE' && (!r.guard || r.guard(context)),
    );
    const targetStates = Array.from(new Set(validRules.map((r) => r.to)));
    expect(targetStates.length).toBeGreaterThanOrEqual(2);

    const history: SelectorHistory = {
      stateCounts: new Map<PracticeAbstractState, number>(),
    };

    targetStates.forEach((state, idx) => {
      history.stateCounts?.set(state, idx === 0 ? 5 : 20);
    });

    const prng = createPrng(42);
    const chosen = selectConstrainedAction(
      'PRACTICE_IDLE',
      context,
      prng,
      'rare-state-bias',
      history,
    );
    const chosenRule = validRules.find((r) => r.action === chosen);
    expect(chosenRule?.to).toBe(targetStates[0]);
  });

  it('rare-state-bias breaks ties among equally rare states using PRNG', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const history: SelectorHistory = {
      stateCounts: new Map<PracticeAbstractState, number>(),
    };

    const chosenActions = new Set<string>();
    for (let seed = 1; seed <= 50; seed++) {
      const prng = createPrng(seed);
      const action = selectConstrainedAction(
        'PRACTICE_IDLE',
        context,
        prng,
        'rare-state-bias',
        history,
      );
      if (action) {
        chosenActions.add(action);
      }
    }

    expect(chosenActions.size).toBeGreaterThan(1);
  });

  it('preserves strict determinism for identical seed and history state', () => {
    const context = { puzzleLoaded: true, attemptCount: 1, inExploration: false };
    const history: SelectorHistory = {
      transitionCounts: new Map([['PRACTICE_IDLE->SUBMIT_MISTAKE->INCORRECT', 2]]),
      stateCounts: new Map([['CORRECT', 1], ['INCORRECT', 2]]),
    };

    const prng1 = createPrng(777);
    const prng2 = createPrng(777);

    const action1 = selectConstrainedAction('PRACTICE_IDLE', context, prng1, 'least-used-transition', history);
    const action2 = selectConstrainedAction('PRACTICE_IDLE', context, prng2, 'least-used-transition', history);

    expect(action1).toBe(action2);
  });

  it('returns null when state has no permitted transitions regardless of strategy', () => {
    const prng = createPrng(1);
    const context = createInitialContext();

    expect(selectConstrainedAction('EVALUATING', context, prng, 'uniform')).toBeNull();
    expect(selectConstrainedAction('EVALUATING', context, prng, 'least-used-transition')).toBeNull();
    expect(selectConstrainedAction('EVALUATING', context, prng, 'rare-state-bias')).toBeNull();
  });
});
