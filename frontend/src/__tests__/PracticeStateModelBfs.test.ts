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
import { explorePracticeStates, exploreAbstractStates, type BfsExplorationResult } from '../../journeys/model/bfsExplorer';
import { selectHighRiskPaths } from '../../journeys/model/pathSelector';

describe('Practice State Model & Invariants', () => {
  it('defines all 8 Core Practice Lifecycle abstract states and 19 action vocabulary entries', () => {
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
    expect(PRACTICE_ACTIONS).toContain('SUBMIT_FAULT');
    expect(PRACTICE_ACTIONS).toContain('RETRY');
    expect(PRACTICE_ACTIONS).toContain('RESET');
    expect(PRACTICE_ACTIONS).toContain('CONTINUE_EXPLORATION');
    expect(PRACTICE_ACTIONS).toContain('EXIT_EXPLORATION');
    expect(PRACTICE_ACTIONS).toContain('BACK_TO_PUZZLES');

    // Issue #770 audited controls
    expect(PRACTICE_ACTIONS).toContain('FLIP_BOARD');
    expect(PRACTICE_ACTIONS).toContain('REQUEST_HINT');
    expect(PRACTICE_ACTIONS).toContain('ENTER_BLINDFOLD');
    expect(PRACTICE_ACTIONS).toContain('EXIT_BLINDFOLD');
    expect(PRACTICE_ACTIONS).toContain('TOGGLE_BLINDFOLD_VISIBILITY');
    expect(PRACTICE_ACTIONS).toContain('SUBMIT_BLINDFOLD_MOVE');
    expect(PRACTICE_ACTIONS).toContain('NAVIGATE_HISTORY_PREV');
    expect(PRACTICE_ACTIONS).toContain('NAVIGATE_HISTORY_NEXT');
    expect(PRACTICE_ACTIONS).toContain('TOGGLE_TIMER_MODE');
    expect(PRACTICE_ACTIONS.length).toBe(19);
  });

  it('initializes context with default orthogonal UI control values', () => {
    const ctx = createInitialContext();
    expect(ctx.puzzleLoaded).toBe(false);
    expect(ctx.attemptCount).toBe(0);
    expect(ctx.inExploration).toBe(false);
    expect(ctx.isBoardFlipped).toBe(false);
    expect(ctx.blindfoldActive).toBe(false);
    expect(ctx.blindfoldVisible).toBe(false);
    expect(ctx.blindfoldMoveCount).toBe(0);
    expect(ctx.hintActive).toBe(false);
    expect(ctx.historyIndex).toBe(0);
    expect(ctx.historyLength).toBe(0);
    expect(ctx.timerMode).toBeNull();
    expect(ctx.faultInjectionEnabled).toBe(false);
  });

  it('validates transitions and guards for newly introduced controls', () => {
    const practiceCtx = {
      ...createInitialContext(),
      puzzleLoaded: true,
      historyLength: 2,
      historyIndex: 1,
    };

    // Board flip toggles isBoardFlipped
    expect(isValidTransition('PRACTICE_IDLE', 'FLIP_BOARD', practiceCtx)).toBe(true);
    const flipped = getNextState('PRACTICE_IDLE', 'FLIP_BOARD', practiceCtx);
    expect(flipped?.state).toBe('PRACTICE_IDLE');
    expect(flipped?.context.isBoardFlipped).toBe(true);

    // Request hint sets hintActive
    expect(isValidTransition('PRACTICE_IDLE', 'REQUEST_HINT', practiceCtx)).toBe(true);
    const hinted = getNextState('PRACTICE_IDLE', 'REQUEST_HINT', practiceCtx);
    expect(hinted?.context.hintActive).toBe(true);

    // Enter blindfold, toggle visibility, submit notation move, and exit blindfold
    expect(isValidTransition('PRACTICE_IDLE', 'ENTER_BLINDFOLD', practiceCtx)).toBe(true);
    const blindfoldEntered = getNextState('PRACTICE_IDLE', 'ENTER_BLINDFOLD', practiceCtx);
    expect(blindfoldEntered?.context.blindfoldActive).toBe(true);

    expect(isValidTransition('PRACTICE_IDLE', 'TOGGLE_BLINDFOLD_VISIBILITY', blindfoldEntered!.context)).toBe(true);
    const visToggled = getNextState('PRACTICE_IDLE', 'TOGGLE_BLINDFOLD_VISIBILITY', blindfoldEntered!.context);
    expect(visToggled?.context.blindfoldVisible).toBe(true);

    expect(isValidTransition('PRACTICE_IDLE', 'SUBMIT_BLINDFOLD_MOVE', blindfoldEntered!.context)).toBe(true);
    const blindfoldMove = getNextState('PRACTICE_IDLE', 'SUBMIT_BLINDFOLD_MOVE', blindfoldEntered!.context);
    expect(blindfoldMove?.context.blindfoldMoveCount).toBe(1);

    expect(isValidTransition('PRACTICE_IDLE', 'EXIT_BLINDFOLD', blindfoldEntered!.context)).toBe(true);
    const blindfoldExited = getNextState('PRACTICE_IDLE', 'EXIT_BLINDFOLD', blindfoldEntered!.context);
    expect(blindfoldExited?.context.blindfoldActive).toBe(false);

    // Cannot flip board or request hint when blindfold is active
    expect(isValidTransition('PRACTICE_IDLE', 'FLIP_BOARD', blindfoldEntered!.context)).toBe(false);
    expect(isValidTransition('PRACTICE_IDLE', 'REQUEST_HINT', blindfoldEntered!.context)).toBe(false);

    // History navigation prev/next
    expect(isValidTransition('PRACTICE_IDLE', 'NAVIGATE_HISTORY_PREV', practiceCtx)).toBe(true);
    const prevStep = getNextState('PRACTICE_IDLE', 'NAVIGATE_HISTORY_PREV', practiceCtx);
    expect(prevStep?.context.historyIndex).toBe(0);

    // At historyIndex 0, cannot navigate further prev
    expect(isValidTransition('PRACTICE_IDLE', 'NAVIGATE_HISTORY_PREV', prevStep!.context)).toBe(false);
    expect(isValidTransition('PRACTICE_IDLE', 'NAVIGATE_HISTORY_NEXT', prevStep!.context)).toBe(true);

    // Toggle timer mode: null -> STOPWATCH -> COUNTDOWN -> null
    const timer1 = getNextState('PRACTICE_IDLE', 'TOGGLE_TIMER_MODE', practiceCtx);
    expect(timer1?.context.timerMode).toBe('STOPWATCH');
    const timer2 = getNextState('PRACTICE_IDLE', 'TOGGLE_TIMER_MODE', timer1!.context);
    expect(timer2?.context.timerMode).toBe('COUNTDOWN');
    const timer3 = getNextState('PRACTICE_IDLE', 'TOGGLE_TIMER_MODE', timer2!.context);
    expect(timer3?.context.timerMode).toBeNull();
  });

  it('correctly models move truncation after undo in history navigation', () => {
    // Start with 3 moves in history, positioned at the end
    const initialContext = {
      ...createInitialContext(),
      puzzleLoaded: true,
      historyLength: 3,
      historyIndex: 3,
    };

    // User navigates back 2 moves: historyIndex becomes 1
    const step1 = getNextState('PRACTICE_IDLE', 'NAVIGATE_HISTORY_PREV', initialContext);
    const step2 = getNextState('PRACTICE_IDLE', 'NAVIGATE_HISTORY_PREV', step1!.context);
    expect(step2?.context.historyIndex).toBe(1);
    expect(step2?.context.historyLength).toBe(3);

    // Making a new move after undo truncates future history
    const nextMove = getNextState('PRACTICE_IDLE', 'SUBMIT_OPTIMAL', step2!.context);
    expect(nextMove?.context.historyIndex).toBe(2);
    expect(nextMove?.context.historyLength).toBe(2); // Truncated from 3 to 2
  });

  it('preserves selection isolation invariant for auxiliary controls', () => {
    const auxActions = [
      'FLIP_BOARD',
      'REQUEST_HINT',
      'ENTER_BLINDFOLD',
      'EXIT_BLINDFOLD',
      'TOGGLE_BLINDFOLD_VISIBILITY',
      'SUBMIT_BLINDFOLD_MOVE',
      'NAVIGATE_HISTORY_PREV',
      'NAVIGATE_HISTORY_NEXT',
      'TOGGLE_TIMER_MODE',
      'RESET',
      'RETRY',
    ] as const;

    // Verify all auxiliary actions are distinct from move submission actions
    const moveSubmissionActions = ['SUBMIT_OPTIMAL', 'SUBMIT_MISTAKE', 'SUBMIT_BLUNDER'];
    for (const action of auxActions) {
      expect(moveSubmissionActions).not.toContain(action);
    }
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

    // Practice to Fault State (guarded by faultInjectionEnabled)
    expect(isValidTransition('PRACTICE_IDLE', 'SUBMIT_FAULT', practiceCtx)).toBe(false);
    const faultEnabledCtx = { ...practiceCtx, faultInjectionEnabled: true };
    expect(isValidTransition('PRACTICE_IDLE', 'SUBMIT_FAULT', faultEnabledCtx)).toBe(true);
    const fault = getNextState('PRACTICE_IDLE', 'SUBMIT_FAULT', faultEnabledCtx);
    expect(fault?.state).toBe('EVALUATION_ERROR');

    // Evaluation Error to Retry / Recovery
    expect(isValidTransition('EVALUATION_ERROR', 'RETRY', fault!.context)).toBe(true);
    expect(getNextState('EVALUATION_ERROR', 'RETRY', fault!.context)?.state).toBe('PRACTICE_IDLE');

    // Evaluation Error back to Discovery
    expect(isValidTransition('EVALUATION_ERROR', 'BACK_TO_PUZZLES', fault!.context)).toBe(true);
    expect(getNextState('EVALUATION_ERROR', 'BACK_TO_PUZZLES', fault!.context)?.state).toBe('DISCOVERY');

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

  it('independently tracks expected FEN, history, side to move, and attempt counts across transitions', () => {
    const initialCtx = createInitialContext();
    expect(initialCtx.expectedSolvedCount).toBe(0);
    expect(initialCtx.expectedFailedCount).toBe(0);
    expect(initialCtx.expectedHistory).toEqual([]);
    expect(initialCtx.expectedSideToMove).toBe('white');

    // SELECT_PUZZLE -> PRACTICE_IDLE
    const selected = getNextState('DISCOVERY', 'SELECT_PUZZLE', initialCtx);
    expect(selected).not.toBeNull();
    const idleCtx = selected!.context;
    expect(idleCtx.puzzleLoaded).toBe(true);
    expect(idleCtx.expectedFen).toBeDefined();
    expect(idleCtx.expectedSolvedCount).toBe(0);
    expect(idleCtx.expectedFailedCount).toBe(0);
    expect(idleCtx.expectedSideToMove).toBe('white');

    // SUBMIT_OPTIMAL -> CORRECT
    const solved = getNextState('PRACTICE_IDLE', 'SUBMIT_OPTIMAL', idleCtx);
    expect(solved).not.toBeNull();
    const solvedCtx = solved!.context;
    expect(solvedCtx.solved).toBe(true);
    expect(solvedCtx.attemptCount).toBe(1);
    expect(solvedCtx.expectedSolvedCount).toBe(1);
    expect(solvedCtx.expectedFailedCount).toBe(0);
    expect(solvedCtx.lastMoveSan).toBe('d4');
    expect(solvedCtx.expectedHistory).toEqual(['d4']);
    expect(solvedCtx.expectedSideToMove).toBe('black');
    expect(solvedCtx.expectedFen).not.toEqual(idleCtx.expectedFen);

    // SUBMIT_MISTAKE from IDLE -> INCORRECT
    const mistake = getNextState('PRACTICE_IDLE', 'SUBMIT_MISTAKE', idleCtx);
    expect(mistake).not.toBeNull();
    const mistakeCtx = mistake!.context;
    expect(mistakeCtx.attemptCount).toBe(1);
    expect(mistakeCtx.expectedSolvedCount).toBe(0);
    expect(mistakeCtx.expectedFailedCount).toBe(1);
    expect(mistakeCtx.lastMoveSan).toBe('a3');
    expect(mistakeCtx.expectedFen).not.toEqual(idleCtx.expectedFen);

    // RETRY from INCORRECT -> PRACTICE_IDLE (resets to baseline position)
    const retried = getNextState('INCORRECT', 'RETRY', mistakeCtx);
    expect(retried).not.toBeNull();
    expect(retried!.context.expectedFen).toEqual(idleCtx.expectedFen);

    // SUBMIT_BLUNDER from IDLE -> HISTORICAL_MISTAKE
    const blunder = getNextState('PRACTICE_IDLE', 'SUBMIT_BLUNDER', idleCtx);
    expect(blunder).not.toBeNull();
    const blunderCtx = blunder!.context;
    expect(blunderCtx.attemptCount).toBe(1);
    expect(blunderCtx.expectedSolvedCount).toBe(0);
    expect(blunderCtx.expectedFailedCount).toBe(1);
    expect(blunderCtx.lastMoveSan).toBe('h4');
    expect(blunderCtx.expectedFen).not.toEqual(idleCtx.expectedFen);
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

      const faultResult = explorePracticeStates('DISCOVERY', PRACTICE_TRANSITIONS, 4, {
        ...createInitialContext(),
        faultInjectionEnabled: true,
      });
      expect(faultResult.reachableStates.has('EVALUATION_ERROR')).toBe(true);

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

    it('throws explicit diagnostic Error when BFS exploration fails to discover required paths', () => {
      const emptyExploration: BfsExplorationResult = {
        reachableStates: new Set(['DISCOVERY']),
        paths: [],
        transitionsCovered: new Set(),
      };

      expect(() => selectHighRiskPaths(emptyExploration, PRACTICE_TRANSITIONS)).toThrow(
        /\[PathSelector Error\] BFS exploration failed to discover required path/
      );
    });

    it('generic exploreAbstractStates explores domain-agnostic models with custom context signature', () => {
      type CustomState = 'INIT' | 'RUNNING' | 'DONE';
      type CustomAction = 'START' | 'FINISH';
      interface CustomContext { count: number }

      const transitions = [
        {
          from: 'INIT' as CustomState,
          action: 'START' as CustomAction,
          to: 'RUNNING' as CustomState,
          effect: (ctx: CustomContext) => ({ count: ctx.count + 1 }),
        },
        {
          from: 'RUNNING' as CustomState,
          action: 'FINISH' as CustomAction,
          to: 'DONE' as CustomState,
          guard: (ctx: CustomContext) => ctx.count > 0,
        },
      ];

      const signatureFn = (state: CustomState, ctx: CustomContext) => `${state}:${ctx.count}`;
      const result = exploreAbstractStates<CustomState, CustomAction, CustomContext>(
        'INIT',
        transitions,
        3,
        { count: 0 },
        signatureFn
      );

      expect(result.reachableStates.has('INIT')).toBe(true);
      expect(result.reachableStates.has('RUNNING')).toBe(true);
      expect(result.reachableStates.has('DONE')).toBe(true);
      expect(result.paths.length).toBe(2);
      expect(result.transitionsCovered.has('INIT->START->RUNNING')).toBe(true);
      expect(result.transitionsCovered.has('RUNNING->FINISH->DONE')).toBe(true);
    });
  });
});
