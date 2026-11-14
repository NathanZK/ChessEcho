import { describe, it, expect, beforeEach } from 'vitest';
import {
  PRACTICE_FSM_STATES,
  PRACTICE_FSM_ACTIONS,
  DISCOVERY_FSM_STATES,
  DISCOVERY_FSM_ACTIONS,
  createInitialPracticeContext,
  createInitialDiscoveryContext,
  getPracticeFsmNextState,
  getDiscoveryFsmNextState,
  isPracticeFsmTransitionValid,
  isDiscoveryFsmTransitionValid,
  type PracticeContext,
  type PracticeState,
  type PracticeAction,
} from './modelFsm';
import { InMemoryPracticeAdapter } from './inMemoryAdapter';
import { InvariantEvaluator, type ObservedPracticeState } from './invariantEvaluator';
import {
  canonicalStateHash,
  exploreAbstractStates,
  minimizeTrace,
  type AbstractTransitionRule,
} from './bfsExplorer';

describe('Tripartite Architecture Model & Adapters (Issue #789)', () => {
  describe('Abstract Model FSM (modelFsm.ts)', () => {
    it('defines all required Practice Puzzle Lifecycle states and actions', () => {
      expect(PRACTICE_FSM_STATES).toEqual([
        'INITIAL',
        'ATTEMPTED_WRONG',
        'ATTEMPTED_RIGHT',
        'LINE_EXPLORATION',
        'CHALLENGE_MODE',
        'BLINDFOLD',
        'SOLVED',
        'EXIT',
      ]);

      expect(PRACTICE_FSM_ACTIONS).toEqual([
        'SELECT_PUZZLE',
        'MAKE_MOVE_CORRECT',
        'MAKE_MOVE_INCORRECT',
        'ENTER_LINE_EXPLORATION',
        'ENTER_CHALLENGE_MODE',
        'ENTER_BLINDFOLD',
        'SOLVE',
        'RETRY',
        'EXIT_EXPLORATION',
        'EXIT_CHALLENGE',
        'EXIT_BLINDFOLD',
        'RESET',
      ]);
    });

    it('defines all required Discovery Job Lifecycle states and actions', () => {
      expect(DISCOVERY_FSM_STATES).toEqual([
        'QUEUED',
        'PROCESSING',
        'COMPLETED',
        'FAILED',
        'CANCELLED',
      ]);

      expect(DISCOVERY_FSM_ACTIONS).toEqual([
        'START_PROCESSING',
        'COMPLETE',
        'FAIL',
        'CANCEL',
      ]);
    });

    it('enforces legal transitions and rejects invalid transitions for Practice FSM', () => {
      const initialCtx = createInitialPracticeContext({
        puzzleId: 'puzzle-1',
        boardFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        entryFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      });

      // INITIAL -> MAKE_MOVE_INCORRECT -> ATTEMPTED_WRONG
      expect(isPracticeFsmTransitionValid('INITIAL', 'MAKE_MOVE_INCORRECT', initialCtx)).toBe(true);
      const wrong = getPracticeFsmNextState('INITIAL', 'MAKE_MOVE_INCORRECT', initialCtx);
      expect(wrong?.nextState).toBe('ATTEMPTED_WRONG');

      // ATTEMPTED_WRONG -> ENTER_LINE_EXPLORATION -> LINE_EXPLORATION
      expect(isPracticeFsmTransitionValid('ATTEMPTED_WRONG', 'ENTER_LINE_EXPLORATION', wrong!.nextContext)).toBe(true);
      const exploring = getPracticeFsmNextState('ATTEMPTED_WRONG', 'ENTER_LINE_EXPLORATION', wrong!.nextContext);
      expect(exploring?.nextState).toBe('LINE_EXPLORATION');

      // LINE_EXPLORATION -> EXIT_EXPLORATION -> EXIT
      expect(isPracticeFsmTransitionValid('LINE_EXPLORATION', 'EXIT_EXPLORATION', exploring!.nextContext)).toBe(true);
      const exited = getPracticeFsmNextState('LINE_EXPLORATION', 'EXIT_EXPLORATION', exploring!.nextContext);
      expect(exited?.nextState).toBe('EXIT');

      // EXIT is terminal - cannot transition
      expect(isPracticeFsmTransitionValid('EXIT', 'RESET', exited!.nextContext)).toBe(false);
      expect(getPracticeFsmNextState('EXIT', 'RESET', exited!.nextContext)).toBeNull();
    });

    it('enforces legal transitions and terminal immutability for Discovery FSM', () => {
      const ctx = createInitialDiscoveryContext('job-1', 'account-1');

      // QUEUED -> START_PROCESSING -> PROCESSING
      expect(isDiscoveryFsmTransitionValid('QUEUED', 'START_PROCESSING', ctx)).toBe(true);
      const proc = getDiscoveryFsmNextState('QUEUED', 'START_PROCESSING', ctx);
      expect(proc?.nextState).toBe('PROCESSING');

      // PROCESSING -> CANCEL -> CANCELLED
      expect(isDiscoveryFsmTransitionValid('PROCESSING', 'CANCEL', proc!.nextContext)).toBe(true);
      const cancelled = getDiscoveryFsmNextState('PROCESSING', 'CANCEL', proc!.nextContext);
      expect(cancelled?.nextState).toBe('CANCELLED');

      // Terminal states permit no further transitions
      expect(isDiscoveryFsmTransitionValid('CANCELLED', 'COMPLETE', cancelled!.nextContext)).toBe(false);
      expect(getDiscoveryFsmNextState('CANCELLED', 'COMPLETE', cancelled!.nextContext)).toBeNull();
    });
  });

  describe('SUT Adapter & InMemoryAdapter', () => {
    let adapter: InMemoryPracticeAdapter;

    beforeEach(() => {
      adapter = new InMemoryPracticeAdapter();
    });

    it('executes actions and observes resulting in-memory state', async () => {
      await adapter.executeAction('SELECT_PUZZLE', { puzzleId: 'p-1', fen: 'start_fen' });
      let state = await adapter.observeState();
      expect(state.activePuzzle?.id).toBe('p-1');
      expect(state.boardFen).toBe('start_fen');

      await adapter.executeAction('MAKE_MOVE_INCORRECT');
      state = await adapter.observeState();
      expect(state.feedbackPanel.status).toBe('INCORRECT');

      await adapter.reset();
      state = await adapter.observeState();
      expect(state.activePuzzle).toBeNull();
      expect(state.boardFen).toBe('');
    });

    it('rejects illegal actions with an explicit Error', async () => {
      // In-process model tests do NOT claim DOM inspection
      expect(adapter.isDomLevel()).toBe(false);

      await expect(adapter.executeAction('EXIT_EXPLORATION')).rejects.toThrow(
        /Cannot execute EXIT_EXPLORATION/
      );
    });
  });

  describe('Invariant Evaluator (invariantEvaluator.ts & UOBS-13)', () => {
    let evaluator: InvariantEvaluator;

    beforeEach(() => {
      evaluator = new InvariantEvaluator();
    });

    it('evaluates UOBS-13 exploration exit invariant successfully when all 4 conditions hold', () => {
      const expected: PracticeContext = {
        puzzleId: 'puzzle-42',
        boardFen: 'fen_ply_3',
        entryFen: 'fen_ply_2',
        attemptCount: 1,
        currentView: 'PRACTICE',
        inExploration: false,
        inChallengeMode: false,
        inBlindfold: false,
        solved: false,
      };

      const observed: ObservedPracticeState = {
        activePuzzle: { id: 'puzzle-42', source: 'supplied' },
        boardFen: 'fen_ply_2',
        currentView: 'PRACTICE',
        feedbackPanel: {
          status: 'IDLE',
          isInteractive: true,
        },
      };

      expect(() => evaluator.assertExplorationExitInvariant(expected, observed)).not.toThrow();
    });

    it('UOBS-13 violation: throws error when activePuzzle is cleared to null', () => {
      const expected: PracticeContext = {
        puzzleId: 'puzzle-42',
        boardFen: 'fen_ply_3',
        entryFen: 'fen_ply_2',
        attemptCount: 1,
        currentView: 'PRACTICE',
        inExploration: false,
        inChallengeMode: false,
        inBlindfold: false,
        solved: false,
      };

      const observed: ObservedPracticeState = {
        activePuzzle: null, // UOBS-13 regression
        boardFen: 'fen_ply_2',
        currentView: 'PRACTICE',
        feedbackPanel: { status: 'IDLE', isInteractive: true },
      };

      expect(() => evaluator.assertExplorationExitInvariant(expected, observed)).toThrow(
        /UOBS-13 violation: active puzzle context was lost/
      );
    });

    it('UOBS-13 violation: throws error when board position does not restore entry ply', () => {
      const expected: PracticeContext = {
        puzzleId: 'puzzle-42',
        boardFen: 'fen_ply_3',
        entryFen: 'fen_ply_2',
        attemptCount: 1,
        currentView: 'PRACTICE',
        inExploration: false,
        inChallengeMode: false,
        inBlindfold: false,
        solved: false,
      };

      const observed: ObservedPracticeState = {
        activePuzzle: { id: 'puzzle-42' },
        boardFen: 'unrestored_fen', // Not restored
        currentView: 'PRACTICE',
        feedbackPanel: { status: 'IDLE', isInteractive: true },
      };

      expect(() => evaluator.assertExplorationExitInvariant(expected, observed)).toThrow(
        /UOBS-13 violation: board position did not restore entry ply/
      );
    });

    it('UOBS-13 violation: throws error when routed unexpectedly away from PRACTICE', () => {
      const expected: PracticeContext = {
        puzzleId: 'puzzle-42',
        boardFen: 'fen_ply_3',
        entryFen: 'fen_ply_2',
        attemptCount: 1,
        currentView: 'PRACTICE',
        inExploration: false,
        inChallengeMode: false,
        inBlindfold: false,
        solved: false,
      };

      const observed: ObservedPracticeState = {
        activePuzzle: { id: 'puzzle-42' },
        boardFen: 'fen_ply_2',
        currentView: 'WEAKNESSES', // Routed away to #weaknesses
        feedbackPanel: { status: 'IDLE', isInteractive: true },
      };

      expect(() => evaluator.assertExplorationExitInvariant(expected, observed)).toThrow(
        /UOBS-13 violation: view navigated away from PRACTICE/
      );
    });

    it('UOBS-13 violation: throws error when feedback panel is not interactive', () => {
      const expected: PracticeContext = {
        puzzleId: 'puzzle-42',
        boardFen: 'fen_ply_3',
        entryFen: 'fen_ply_2',
        attemptCount: 1,
        currentView: 'PRACTICE',
        inExploration: false,
        inChallengeMode: false,
        inBlindfold: false,
        solved: false,
      };

      const observed: ObservedPracticeState = {
        activePuzzle: { id: 'puzzle-42' },
        boardFen: 'fen_ply_2',
        currentView: 'PRACTICE',
        feedbackPanel: { status: 'RESET', isInteractive: false },
      };

      expect(() => evaluator.assertExplorationExitInvariant(expected, observed)).toThrow(
        /UOBS-13 violation: feedback panel is not interactive/
      );
    });
  });

  describe('Bounded BFS Explorer & Trace Minimization (bfsExplorer.ts)', () => {
    it('canonicalStateHash produces deterministic key regardless of context property order', () => {
      const hash1 = canonicalStateHash('LINE_EXPLORATION', { b: 2, a: 1, c: { y: 20, x: 10 } });
      const hash2 = canonicalStateHash('LINE_EXPLORATION', { a: 1, c: { x: 10, y: 20 }, b: 2 });
      expect(hash1).toBe(hash2);
    });

    it('exploreAbstractStates enforces provisional depth limit d <= 6 and budget N <= 100', () => {
      type SimpleState = 'S0' | 'S1' | 'S2' | 'S3' | 'S4' | 'S5' | 'S6' | 'S7';
      const transitions: AbstractTransitionRule<SimpleState, string, { step: number }>[] = [
        { from: 'S0', action: 'next', to: 'S1', effect: ctx => ({ step: ctx.step + 1 }) },
        { from: 'S1', action: 'next', to: 'S2', effect: ctx => ({ step: ctx.step + 1 }) },
        { from: 'S2', action: 'next', to: 'S3', effect: ctx => ({ step: ctx.step + 1 }) },
        { from: 'S3', action: 'next', to: 'S4', effect: ctx => ({ step: ctx.step + 1 }) },
        { from: 'S4', action: 'next', to: 'S5', effect: ctx => ({ step: ctx.step + 1 }) },
        { from: 'S5', action: 'next', to: 'S6', effect: ctx => ({ step: ctx.step + 1 }) },
        { from: 'S6', action: 'next', to: 'S7', effect: ctx => ({ step: ctx.step + 1 }) },
      ];

      // Depth bound d = 6 should reach S6, but NOT S7
      const resultDepth6 = exploreAbstractStates('S0', transitions, 6, { step: 0 });
      expect(resultDepth6.reachableStates.has('S6')).toBe(true);
      expect(resultDepth6.reachableStates.has('S7')).toBe(false);

      // Transition budget bound N = 3
      const resultBudget3 = exploreAbstractStates('S0', transitions, 6, { step: 0 }, undefined, 3);
      expect(resultBudget3.transitionsCovered.size).toBeLessThanOrEqual(3);
    });

    it('minimizeTrace strips neutral intermediate actions to produce a minimal reproducing sub-trace', async () => {
      const adapter = new InMemoryPracticeAdapter();
      const initialCtx = createInitialPracticeContext({
        puzzleId: null,
        boardFen: 'start_fen',
        entryFen: 'start_fen',
      });

      // Actions containing redundant loops:
      // SELECT_PUZZLE -> MAKE_MOVE_INCORRECT -> RETRY -> ENTER_LINE_EXPLORATION -> EXIT_EXPLORATION
      const failingActions = [
        'SELECT_PUZZLE',
        'MAKE_MOVE_INCORRECT',
        'RETRY',
        'ENTER_LINE_EXPLORATION',
        'EXIT_EXPLORATION',
      ] as const;

      // Invariant evaluator that fails ONLY when exiting exploration with an active puzzle
      const mockEvaluator = (
        _expectedState: PracticeState,
        observed: ObservedPracticeState,
        action: PracticeAction
      ) => {
        if (action === 'EXIT_EXPLORATION' && observed.activePuzzle !== null) {
          throw new Error('Simulated regression failure upon EXIT_EXPLORATION');
        }
      };

      const result = await minimizeTrace(
        'INITIAL',
        initialCtx,
        [...failingActions],
        adapter,
        mockEvaluator,
        getPracticeFsmNextState,
        12345
      );

      expect(result.originalLength).toBe(5);
      // The intermediate failure-neutral actions MAKE_MOVE_INCORRECT and RETRY should be pruned!
      expect(result.minimizedActions).toEqual([
        'SELECT_PUZZLE',
        'ENTER_LINE_EXPLORATION',
        'EXIT_EXPLORATION',
      ]);
      expect(result.replaySeed).toBe(12345);
      expect(result.replayCommand).toContain('--seed=12345');
    });
  });
});
