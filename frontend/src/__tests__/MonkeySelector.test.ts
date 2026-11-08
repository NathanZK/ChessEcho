import { describe, expect, it } from 'vitest';
import { createPrng } from '../../journeys/model/prng';
import { selectConstrainedAction } from '../../journeys/model/monkeySelector';
import { diagnoseTransitionFailure } from '../../journeys/model/monkeyRunner';
import { createInitialContext, isValidTransition, type PracticeAbstractState } from '../../journeys/model/practiceModel';

describe('Mulberry32 Seeded PRNG (prng.ts)', () => {
  it('produces deterministic identical sequences for identical seeds', () => {
    const prng1 = createPrng(42);
    const prng2 = createPrng(42);

    const seq1 = Array.from({ length: 10 }, () => prng1.next());
    const seq2 = Array.from({ length: 10 }, () => prng2.next());

    expect(seq1).toEqual(seq2);
    for (const val of seq1) {
      expect(val).toBeGreaterThanOrEqual(0);
      expect(val).toBeLessThan(1);
    }
  });

  it('produces distinct sequences for distinct seeds', () => {
    const prng1 = createPrng(42);
    const prng2 = createPrng(1337);

    const seq1 = Array.from({ length: 5 }, () => prng1.next());
    const seq2 = Array.from({ length: 5 }, () => prng2.next());

    expect(seq1).not.toEqual(seq2);
  });

  it('preserves seed 0 as a distinct, valid deterministic sequence without zero-collapse', () => {
    const prng0 = createPrng(0);
    const prng0Replay = createPrng(0);
    const prng1 = createPrng(1);

    expect(prng0.seed).toBe(0);
    expect(prng1.seed).toBe(1);

    const seq0 = Array.from({ length: 10 }, () => prng0.next());
    const seq0Replay = Array.from({ length: 10 }, () => prng0Replay.next());
    const seq1 = Array.from({ length: 10 }, () => prng1.next());

    // Deterministic replay of seed 0
    expect(seq0).toEqual(seq0Replay);

    // Distinguishability: seed 0 must NOT collapse to seed 1
    expect(seq0).not.toEqual(seq1);

    for (const val of seq0) {
      expect(val).toBeGreaterThanOrEqual(0);
      expect(val).toBeLessThan(1);
    }
  });

  it('nextInt bounds integers inclusive of min and max', () => {
    const prng = createPrng(12345);
    const results = new Set<number>();

    for (let i = 0; i < 100; i++) {
      const val = prng.nextInt(3, 7);
      expect(Number.isInteger(val)).toBe(true);
      expect(val).toBeGreaterThanOrEqual(3);
      expect(val).toBeLessThanOrEqual(7);
      results.add(val);
    }

    // Over 100 iterations, all values in [3, 7] should be sampled
    expect(results.size).toBe(5);
  });
});

describe('State-Constrained Action Selector (monkeySelector.ts)', () => {
  it('only selects actions that are valid transitions from the current state', () => {
    const states: PracticeAbstractState[] = [
      'DISCOVERY',
      'PRACTICE_IDLE',
      'CORRECT',
      'INCORRECT',
      'HISTORICAL_MISTAKE',
      'EXPLORING',
    ];

    const prng = createPrng(999);

    for (const state of states) {
      const context = {
        puzzleLoaded: state !== 'DISCOVERY',
        attemptCount: 1,
        inExploration: state === 'EXPLORING',
      };

      for (let i = 0; i < 20; i++) {
        const action = selectConstrainedAction(state, context, prng);
        expect(action).not.toBeNull();
        expect(isValidTransition(state, action!, context)).toBe(true);
      }
    }
  });

  it('deterministically selects the same action sequence for identical seeds and state sequences', () => {
    const runSelection = (seed: number) => {
      const prng = createPrng(seed);
      const context = createInitialContext();
      context.puzzleLoaded = true;

      return [
        selectConstrainedAction('PRACTICE_IDLE', context, prng),
        selectConstrainedAction('PRACTICE_IDLE', context, prng),
        selectConstrainedAction('CORRECT', context, prng),
        selectConstrainedAction('EXPLORING', { ...context, inExploration: true }, prng),
      ];
    };

    const run1 = runSelection(55555);
    const run2 = runSelection(55555);
    const run3 = runSelection(99999);

    expect(run1).toEqual(run2);
    expect(run1).not.toEqual(run3);
  });

  it('returns null if no transitions exist from state with context', () => {
    const prng = createPrng(1);
    // DISCOVERY with puzzleLoaded: true has no transitions requiring puzzleLoaded
    // But DISCOVERY always has SELECT_PUZZLE
    // If state has zero valid transitions, returns null
    const action = selectConstrainedAction('EVALUATING', createInitialContext(), prng);
    // EVALUATING currently has no defined outgoing user transitions in practiceModel
    expect(action).toBeNull();
  });
});

describe('Transition Failure Diagnostics (monkeyRunner.ts)', () => {
  it('identifies failed HTTP responses with status code and error details', () => {
    const error = new Error('locator.waitFor: Timeout 5000ms exceeded');
    const requests = [
      {
        url: 'http://localhost:3000/api/puzzles/evaluate-move?move=d4',
        method: 'GET',
        status: 500,
        failed: true,
        failureText: 'Internal Server Error',
        timestamp: Date.now(),
      },
    ];

    const diagnosis = diagnoseTransitionFailure('SUBMIT_OPTIMAL', 'CORRECT', requests, error);
    expect(diagnosis).toContain('[Failed HTTP Response]');
    expect(diagnosis).toContain('500');
    expect(diagnosis).toContain('/api/puzzles/evaluate-move');
  });

  it('identifies missing network request when evaluation action dispatched no HTTP call', () => {
    const error = new Error('locator.waitFor: Timeout 5000ms exceeded');
    // Requests list without evaluate-move
    const requests = [
      {
        url: 'http://localhost:3000/api/other-endpoint',
        method: 'GET',
        status: 200,
        failed: false,
        timestamp: Date.now(),
      },
    ];

    const diagnosis = diagnoseTransitionFailure('SUBMIT_OPTIMAL', 'CORRECT', requests, error);
    expect(diagnosis).toContain('[Missing Request]');
    expect(diagnosis).toContain('did not dispatch an evaluation request');
  });

  it('identifies state transition timeout when network succeeded but DOM did not settle', () => {
    const error = new Error('locator.waitFor: Timeout 5000ms exceeded');
    const requests = [
      {
        url: 'http://localhost:3000/api/puzzles/evaluate-move?move=d4',
        method: 'GET',
        status: 200,
        failed: false,
        timestamp: Date.now(),
      },
    ];

    const diagnosis = diagnoseTransitionFailure('SUBMIT_OPTIMAL', 'CORRECT', requests, error);
    expect(diagnosis).toContain('[State Transition Timeout]');
    expect(diagnosis).toContain('Timeout 5000ms exceeded');
  });
});
