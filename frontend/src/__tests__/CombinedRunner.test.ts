import { describe, it, expect } from 'vitest';
import {
  findBfsPathToState,
  HIGH_RISK_SCENARIOS,
  type HighRiskScenario,
} from '../../journeys/model/combinedRunner';

describe('Combined Model-Based BFS and Monkey Runner (Phase 5)', () => {
  describe('findBfsPathToState', () => {
    it('resolves the shortest path from DISCOVERY to PRACTICE_IDLE', () => {
      const path = findBfsPathToState('PRACTICE_IDLE');
      expect(path).toEqual(['SELECT_PUZZLE']);
    });

    it('resolves the shortest path from DISCOVERY to INCORRECT (retry-after-wrong-move)', () => {
      const path = findBfsPathToState('INCORRECT');
      expect(path).toEqual(['SELECT_PUZZLE', 'SUBMIT_MISTAKE']);
    });

    it('resolves the shortest path from DISCOVERY to CORRECT', () => {
      const path = findBfsPathToState('CORRECT');
      expect(path).toEqual(['SELECT_PUZZLE', 'SUBMIT_OPTIMAL']);
    });

    it('resolves the shortest path from DISCOVERY to HISTORICAL_MISTAKE', () => {
      const path = findBfsPathToState('HISTORICAL_MISTAKE');
      expect(path).toEqual(['SELECT_PUZZLE', 'SUBMIT_BLUNDER']);
    });

    it('resolves the shortest path from DISCOVERY to EXPLORING (continuation-loading)', () => {
      const path = findBfsPathToState('EXPLORING');
      expect(path).toEqual(['SELECT_PUZZLE', 'SUBMIT_OPTIMAL', 'CONTINUE_EXPLORATION']);
    });

    it('resolves paths from custom non-initial states', () => {
      const path = findBfsPathToState('PRACTICE_IDLE', 'INCORRECT');
      expect(path).toEqual(['RETRY']);
    });

    it('resolves the shortest path from DISCOVERY to EVALUATION_ERROR', () => {
      const path = findBfsPathToState('EVALUATION_ERROR');
      expect(path).toEqual(['SELECT_PUZZLE', 'SUBMIT_FAULT']);
    });

    it('returns null for unreachable states or states with no valid incoming transitions', () => {
      // EVALUATING has no inbound transitions in static PRACTICE_TRANSITIONS
      const path = findBfsPathToState('EVALUATING');
      expect(path).toBeNull();
    });

    it('returns empty path when targetState equals initialState', () => {
      const path = findBfsPathToState('DISCOVERY', 'DISCOVERY');
      expect(path).toEqual([]);
    });
  });

  describe('HIGH_RISK_SCENARIOS', () => {
    const requiredScenarios: HighRiskScenario[] = [
      'retry-after-wrong-move',
      'continuation-loading',
      'evaluation-pending',
      'navigation-during-evaluation',
    ];

    it.each(requiredScenarios)('defines valid configuration for scenario: %s', (scenario) => {
      const config = HIGH_RISK_SCENARIOS[scenario];
      expect(config).toBeDefined();
      expect(config.scenario).toBe(scenario);
      expect(config.targetState).toBeDefined();
      expect(Array.isArray(config.bfsPath)).toBe(true);
      expect(config.bfsPath.length).toBeGreaterThan(0);
    });

    it('maps retry-after-wrong-move to INCORRECT with mistake submission path', () => {
      const config = HIGH_RISK_SCENARIOS['retry-after-wrong-move'];
      expect(config.targetState).toBe('INCORRECT');
      expect(config.bfsPath).toEqual(['SELECT_PUZZLE', 'SUBMIT_MISTAKE']);
    });

    it('maps continuation-loading to EXPLORING with optimal move and exploration entry', () => {
      const config = HIGH_RISK_SCENARIOS['continuation-loading'];
      expect(config.targetState).toBe('EXPLORING');
      expect(config.bfsPath).toEqual(['SELECT_PUZZLE', 'SUBMIT_OPTIMAL', 'CONTINUE_EXPLORATION']);
    });

    it('maps evaluation-pending and navigation-during-evaluation to PRACTICE_IDLE preamble for gate dispatch', () => {
      const evalPending = HIGH_RISK_SCENARIOS['evaluation-pending'];
      const navEval = HIGH_RISK_SCENARIOS['navigation-during-evaluation'];

      expect(evalPending.targetState).toBe('PRACTICE_IDLE');
      expect(evalPending.bfsPath).toEqual(['SELECT_PUZZLE']);

      expect(navEval.targetState).toBe('PRACTICE_IDLE');
      expect(navEval.bfsPath).toEqual(['SELECT_PUZZLE']);
    });
  });
});
