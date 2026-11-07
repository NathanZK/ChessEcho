import { describe, it, expect } from 'vitest';
import {
  PRACTICE_ABSTRACT_STATES,
  PRACTICE_ACTIONS,
  PRACTICE_TRANSITIONS,
  getNextState,
  isValidTransition,
  createInitialContext,
  STATE_ORACLES,
} from '../../journeys/model/practiceModel';
import { explorePracticeStates } from '../../journeys/model/bfsExplorer';
import { selectHighRiskPaths } from '../../journeys/model/pathSelector';

describe('Practice State Model & Invariants', () => {
  it('defines all 8 Core Practice Lifecycle abstract states and 9 actions', () => {
    expect(PRACTICE_TRANSITIONS.length).toBeGreaterThan(0);
    expect(PRACTICE_ABSTRACT_STATES).toEqual([
      'DISCOVERY',
      'PRACTICE_IDLE',
      'EVALUATING',
      'CORRECT',
      'INCORRECT',
      'HISTORICAL_MISTAKE',
      'EVALUATION_ERROR',
      'EXPLORING',
    ]);

    expect(PRACTICE_ACTIONS).toContain('SELECT_PUZZLE');
    expect(PRACTICE_ACTIONS).toContain('SUBMIT_OPTIMAL');
    expect(PRACTICE_ACTIONS).toContain('SUBMIT_MISTAKE');
    expect(PRACTICE_ACTIONS).toContain('SUBMIT_BLUNDER');
    expect(PRACTICE_ACTIONS).toContain('RETRY');
    expect(PRACTICE_ACTIONS).toContain('RESET');
    expect(PRACTICE_ACTIONS).toContain('CONTINUE_EXPLORATION');
    expect(PRACTICE_ACTIONS).toContain('EXIT_EXPLORATION');
    expect(PRACTICE_ACTIONS).toContain('BACK_TO_PUZZLES');
  });

  it('validates allowed transitions across the practice lifecycle', () => {
    const initialCtx = createInitialContext();

    // Discovery to Practice
    expect(isValidTransition('DISCOVERY', 'SELECT_PUZZLE', initialCtx)).toBe(true);
    const selected = getNextState('DISCOVERY', 'SELECT_PUZZLE', initialCtx);
    expect(selected?.state).toBe('PRACTICE_IDLE');
    const practiceCtx = selected!.context;
    expect(practiceCtx.puzzleLoaded).toBe(true);

    // Practice to Solving states
    expect(isValidTransition('PRACTICE_IDLE', 'SUBMIT_OPTIMAL', practiceCtx)).toBe(true);
    const solved = getNextState('PRACTICE_IDLE', 'SUBMIT_OPTIMAL', practiceCtx);
    expect(solved?.state).toBe('CORRECT');
    expect(solved?.context.attemptCount).toBe(1);

    expect(isValidTransition('PRACTICE_IDLE', 'SUBMIT_MISTAKE', practiceCtx)).toBe(true);
    const mistake = getNextState('PRACTICE_IDLE', 'SUBMIT_MISTAKE', practiceCtx);
    expect(mistake?.state).toBe('INCORRECT');

    expect(isValidTransition('PRACTICE_IDLE', 'SUBMIT_BLUNDER', practiceCtx)).toBe(true);
    const blunder = getNextState('PRACTICE_IDLE', 'SUBMIT_BLUNDER', practiceCtx);
    expect(blunder?.state).toBe('HISTORICAL_MISTAKE');

    // Incorrect to Retry
    expect(isValidTransition('INCORRECT', 'RETRY', mistake!.context)).toBe(true);
    expect(getNextState('INCORRECT', 'RETRY', mistake!.context)?.state).toBe('PRACTICE_IDLE');

    // Correct to Line Exploration
    expect(isValidTransition('CORRECT', 'CONTINUE_EXPLORATION', solved!.context)).toBe(true);
    const exploring = getNextState('CORRECT', 'CONTINUE_EXPLORATION', solved!.context);
    expect(exploring?.state).toBe('EXPLORING');
    expect(exploring?.context.inExploration).toBe(true);

    // Exploring back to Practice Idle
    expect(isValidTransition('EXPLORING', 'EXIT_EXPLORATION', exploring!.context)).toBe(true);
    expect(getNextState('EXPLORING', 'EXIT_EXPLORATION', exploring!.context)?.state).toBe('PRACTICE_IDLE');

    // Return to Discovery
    expect(isValidTransition('PRACTICE_IDLE', 'BACK_TO_PUZZLES', practiceCtx)).toBe(true);
    expect(getNextState('PRACTICE_IDLE', 'BACK_TO_PUZZLES', practiceCtx)?.state).toBe('DISCOVERY');
  });

  it('strictly rejects forbidden state transitions', () => {
    const ctx = createInitialContext();

    // Cannot retry from Discovery
    expect(isValidTransition('DISCOVERY', 'RETRY', ctx)).toBe(false);
    expect(getNextState('DISCOVERY', 'RETRY', ctx)).toBeNull();

    // Cannot explore before solving
    expect(isValidTransition('PRACTICE_IDLE', 'CONTINUE_EXPLORATION', ctx)).toBe(false);
    expect(getNextState('PRACTICE_IDLE', 'CONTINUE_EXPLORATION', ctx)).toBeNull();

    // Cannot select puzzle while in exploration
    expect(isValidTransition('EXPLORING', 'SELECT_PUZZLE', ctx)).toBe(false);
    expect(getNextState('EXPLORING', 'SELECT_PUZZLE', ctx)).toBeNull();
  });

  it('defines an invariant oracle definition for every abstract state', () => {
    for (const state of PRACTICE_ABSTRACT_STATES) {
      expect(STATE_ORACLES[state]).toBeDefined();
      expect(typeof STATE_ORACLES[state].assert).toBe('function');
      expect(typeof STATE_ORACLES[state].description).toBe('string');
    }
  });

  describe('Bounded BFS Explorer & Path Selection', () => {
    it('enumerates reachable states bounded by depth without infinite loops', () => {
      const result = explorePracticeStates('DISCOVERY', PRACTICE_TRANSITIONS, 4);

      expect(result.reachableStates.has('DISCOVERY')).toBe(true);
      expect(result.reachableStates.has('PRACTICE_IDLE')).toBe(true);
      expect(result.reachableStates.has('CORRECT')).toBe(true);
      expect(result.reachableStates.has('INCORRECT')).toBe(true);
      expect(result.reachableStates.has('HISTORICAL_MISTAKE')).toBe(true);
      expect(result.reachableStates.has('EXPLORING')).toBe(true);

      expect(result.paths.length).toBeGreaterThan(5);
      expect(result.transitionsCovered.size).toBeGreaterThan(5);
    });

    it('selects at least 3 high-risk paths covering required lifecycle scenarios', () => {
      const exploration = explorePracticeStates('DISCOVERY', PRACTICE_TRANSITIONS, 4);
      const selected = selectHighRiskPaths(exploration, PRACTICE_TRANSITIONS);

      expect(selected.length).toBeGreaterThanOrEqual(3);

      const retryPath = selected.find((p) => p.riskCategory === 'evaluation-retry');
      expect(retryPath).toBeDefined();
      expect(retryPath?.actions).toContain('SUBMIT_MISTAKE');
      expect(retryPath?.actions).toContain('RETRY');

      const navPendingPath = selected.find((p) => p.riskCategory === 'pending-navigation');
      expect(navPendingPath).toBeDefined();

      const exploreReturnPath = selected.find((p) => p.riskCategory === 'mode-boundary-exploration');
      expect(exploreReturnPath).toBeDefined();
      expect(exploreReturnPath?.actions).toContain('CONTINUE_EXPLORATION');
    });
  });
});
