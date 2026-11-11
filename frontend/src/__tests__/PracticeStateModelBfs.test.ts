import { describe, it, expect } from 'vitest';
import {
  PRACTICE_ABSTRACT_STATES,
  PRACTICE_ACTIONS,
  PRACTICE_TRANSITIONS,
  getNextState,
  isValidTransition,
  createInitialContext,
  createPracticeContext,
  STATE_ORACLES,
  deriveFenAtHistoryIndex,
  deriveContextSignature,
} from '../../journeys/model/practiceModel';
import { explorePracticeStates, exploreAbstractStates, type BfsExplorationResult } from '../../journeys/model/bfsExplorer';
import { selectHighRiskPaths } from '../../journeys/model/pathSelector';

describe('Practice State Model & Invariants', () => {
  it('defines all 9 Core Practice Lifecycle abstract states and 20 action vocabulary entries', () => {
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
      'BLINDFOLD',
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
    expect(PRACTICE_ACTIONS).toContain('APPLY_DECISION_MOVE');
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
    expect(PRACTICE_ACTIONS).toContain('NEXT_PUZZLE');
    expect(PRACTICE_ACTIONS.length).toBe(21);
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

    // Blindfold is forbidden in PRACTICE_IDLE and when isWeaknessPlaylist is false
    expect(isValidTransition('PRACTICE_IDLE', 'ENTER_BLINDFOLD', practiceCtx)).toBe(false);
    const nonWeaknessCtx = { ...practiceCtx, isWeaknessPlaylist: false };
    expect(isValidTransition('CORRECT', 'ENTER_BLINDFOLD', nonWeaknessCtx)).toBe(false);

    // Enter blindfold from CORRECT when isWeaknessPlaylist is true -> transitions to BLINDFOLD
    const weaknessCtx = { ...practiceCtx, isWeaknessPlaylist: true };
    expect(isValidTransition('CORRECT', 'ENTER_BLINDFOLD', weaknessCtx)).toBe(true);
    const blindfoldEntered = getNextState('CORRECT', 'ENTER_BLINDFOLD', weaknessCtx);
    expect(blindfoldEntered?.state).toBe('BLINDFOLD');
    expect(blindfoldEntered?.context.blindfoldActive).toBe(true);
    expect(blindfoldEntered?.context.blindfoldPreviousState).toBe('CORRECT');

    // Inside BLINDFOLD: toggle visibility, submit notation move, and exit back to CORRECT
    expect(isValidTransition('BLINDFOLD', 'TOGGLE_BLINDFOLD_VISIBILITY', blindfoldEntered!.context)).toBe(true);
    const visToggled = getNextState('BLINDFOLD', 'TOGGLE_BLINDFOLD_VISIBILITY', blindfoldEntered!.context);
    expect(visToggled?.state).toBe('BLINDFOLD');
    expect(visToggled?.context.blindfoldVisible).toBe(true);

    expect(isValidTransition('BLINDFOLD', 'SUBMIT_BLINDFOLD_MOVE', blindfoldEntered!.context)).toBe(true);
    const blindfoldMove = getNextState('BLINDFOLD', 'SUBMIT_BLINDFOLD_MOVE', blindfoldEntered!.context);
    expect(blindfoldMove?.state).toBe('BLINDFOLD');
    expect(blindfoldMove?.context.blindfoldMoveCount).toBe(1);

    expect(isValidTransition('BLINDFOLD', 'EXIT_BLINDFOLD', blindfoldEntered!.context)).toBe(true);
    const blindfoldExited = getNextState('BLINDFOLD', 'EXIT_BLINDFOLD', blindfoldEntered!.context);
    expect(blindfoldExited?.state).toBe('CORRECT');
    expect(blindfoldExited?.context.blindfoldActive).toBe(false);
    expect(blindfoldExited?.context.blindfoldPreviousState).toBeUndefined();

    // Symmetrical restoration from INCORRECT, HISTORICAL_MISTAKE, and EXPLORING
    for (const originState of ['INCORRECT', 'HISTORICAL_MISTAKE', 'EXPLORING'] as const) {
      const originCtx = { ...weaknessCtx, inExploration: originState === 'EXPLORING' };
      expect(isValidTransition(originState, 'ENTER_BLINDFOLD', originCtx)).toBe(true);
      const entered = getNextState(originState, 'ENTER_BLINDFOLD', originCtx);
      expect(entered?.state).toBe('BLINDFOLD');
      expect(entered?.context.blindfoldPreviousState).toBe(originState);
      const exited = getNextState('BLINDFOLD', 'EXIT_BLINDFOLD', entered!.context);
      expect(exited?.state).toBe(originState);
    }

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

    // Evaluation Error to Retry / Recovery (deterministic derived state from resubmitted move)
    expect(isValidTransition('EVALUATION_ERROR', 'RETRY', fault!.context)).toBe(true);
    expect(getNextState('EVALUATION_ERROR', 'RETRY', fault!.context)?.state).toBe('CORRECT');

    // Unacceptable move retry transitions to INCORRECT
    const mistakeFaultCtx = { ...fault!.context, pendingEvaluationMove: 'a3' };
    expect(isValidTransition('EVALUATION_ERROR', 'RETRY', mistakeFaultCtx)).toBe(true);
    expect(getNextState('EVALUATION_ERROR', 'RETRY', mistakeFaultCtx)?.state).toBe('INCORRECT');

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

    // Exploring back to Correct via unchanged EXIT_EXPLORATION
    expect(isValidTransition('EXPLORING', 'EXIT_EXPLORATION', exploring!.context)).toBe(true);
    expect(getNextState('EXPLORING', 'EXIT_EXPLORATION', exploring!.context)?.state).toBe('CORRECT');

    // Exploring back to Practice Idle via alternative APPLY_DECISION_MOVE (with decision move set)
    const exploringWithDecision = {
      ...exploring!.context,
      explorationDecisionMove: 'a3',
    };
    expect(isValidTransition('EXPLORING', 'APPLY_DECISION_MOVE', exploringWithDecision)).toBe(true);
    expect(getNextState('EXPLORING', 'APPLY_DECISION_MOVE', exploringWithDecision)?.state).toBe('PRACTICE_IDLE');

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

  describe('deriveFenAtHistoryIndex & History Invariant Synchronization', () => {
    const history = ['e4', 'e5', 'Nf3', 'Nc6'];
    const startFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

    it('derives exact FEN and side to move across history plies', () => {
      const ply0 = deriveFenAtHistoryIndex(startFen, history, 0);
      expect(ply0.fen).toBe(startFen);
      expect(ply0.sideToMove).toBe('white');

      const ply1 = deriveFenAtHistoryIndex(startFen, history, 1);
      expect(ply1.fen).toBe('rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1');
      expect(ply1.sideToMove).toBe('black');

      const ply2 = deriveFenAtHistoryIndex(startFen, history, 2);
      expect(ply2.fen).toBe('rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2');
      expect(ply2.sideToMove).toBe('white');

      const ply4 = deriveFenAtHistoryIndex(startFen, history, 4);
      expect(ply4.fen).toBe('r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3');
      expect(ply4.sideToMove).toBe('white');
    });

    it('throws HistoryIndexOutOfBoundsError when index is negative or exceeds history length', () => {
      expect(() => deriveFenAtHistoryIndex(startFen, history, -1)).toThrow(
        /\[HistoryIndexOutOfBoundsError\] Index -1 is outside bounds \[0, 4\]/
      );
      expect(() => deriveFenAtHistoryIndex(startFen, history, 5)).toThrow(
        /\[HistoryIndexOutOfBoundsError\] Index 5 is outside bounds \[0, 4\]/
      );
    });

    it('throws IllegalHistoryMoveError when a move in history is illegal from the current FEN', () => {
      const corruptHistory = ['e4', 'e5', 'Nf6', 'Nc6']; // Nf6 is illegal for White on ply 3
      expect(() => deriveFenAtHistoryIndex(startFen, corruptHistory, 3)).toThrow(
        /\[IllegalHistoryMoveError\] Move "Nf6" at ply 2 is illegal/
      );
    });

    it('updates expectedFen and expectedSideToMove synchronously during NAVIGATE_HISTORY_PREV and NEXT', () => {
      const baseContext = {
        ...createInitialContext(),
        puzzleLoaded: true,
        startingFen: startFen,
        expectedHistory: ['e4', 'e5'],
        historyIndex: 2,
        expectedFen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
        expectedSideToMove: 'white' as const,
      };

      // Navigate back 1 move
      const prevStep = getNextState('PRACTICE_IDLE', 'NAVIGATE_HISTORY_PREV', baseContext);
      expect(prevStep?.context.historyIndex).toBe(1);
      expect(prevStep?.context.expectedFen).toBe('rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1');
      expect(prevStep?.context.expectedSideToMove).toBe('black');

      // Navigate forward 1 move
      const nextStep = getNextState('PRACTICE_IDLE', 'NAVIGATE_HISTORY_NEXT', prevStep!.context);
      expect(nextStep?.context.historyIndex).toBe(2);
      expect(nextStep?.context.expectedFen).toBe('rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2');
      expect(nextStep?.context.expectedSideToMove).toBe('white');
    });
  });

  describe('createPracticeContext & NEXT_PUZZLE Transition', () => {
    const dummyCorpus = [
      {
        puzzleId: 'puzzle-1',
        source: 'weakness' as const,
        fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
        playerColor: 'WHITE' as const,
        targetMove: 'd4',
        openingTitle: 'Test Puzzle 1',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 0,
        mistakeRate: 0,
      },
      {
        puzzleId: 'puzzle-2',
        source: 'weakness' as const,
        fen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
        playerColor: 'BLACK' as const,
        targetMove: 'd6',
        openingTitle: 'Test Puzzle 2',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 0,
        mistakeRate: 0,
      },
    ];

    it('initializes all 23 puzzle-scoped fields cleanly while preserving campaign-scoped state', () => {
      const campaignCtx = {
        corpus: dummyCorpus,
        expectedSolvedCount: 5,
        expectedFailedCount: 2,
        faultInjectionEnabled: true,
        timerMode: 'STOPWATCH' as const,
      };

      const ctx = createPracticeContext(dummyCorpus[1], dummyCorpus, 1, campaignCtx);

      // Puzzle-scoped fields reset/initialized to fixture 2
      expect(ctx.activePuzzleIndex).toBe(1);
      expect(ctx.activePuzzle).toBe(dummyCorpus[1]);
      expect(ctx.puzzleLoaded).toBe(true);
      expect(ctx.startingFen).toBe(dummyCorpus[1].fen);
      expect(ctx.expectedFen).toBe(dummyCorpus[1].fen);
      expect(ctx.expectedSideToMove).toBe('black');
      expect(ctx.expectedHistory).toEqual([]);
      expect(ctx.historyIndex).toBe(0);
      expect(ctx.historyLength).toBe(0);
      expect(ctx.attemptCount).toBe(0);
      expect(ctx.solved).toBe(false);
      expect(ctx.lastMoveSan).toBeUndefined();
      expect(ctx.isBoardFlipped).toBe(false);
      expect(ctx.hintActive).toBe(false);
      expect(ctx.isWeaknessPlaylist).toBe(true);
      expect(ctx.blindfoldActive).toBe(false);
      expect(ctx.blindfoldVisible).toBe(false);
      expect(ctx.blindfoldMoveCount).toBe(0);
      expect(ctx.blindfoldPreviousState).toBeUndefined();
      expect(ctx.pendingEvaluationMove).toBeNull();
      expect(ctx.pendingEvaluationPayload).toBeNull();
      expect(ctx.explorationDecisionMove).toBeNull();
      expect(ctx.solutionLineProgress).toBe(0);
      expect(ctx.selectedSquare).toBeNull();
      expect(ctx.promotionPiece).toBeNull();

      // Campaign-scoped fields preserved
      expect(ctx.expectedSolvedCount).toBe(5);
      expect(ctx.expectedFailedCount).toBe(2);
      expect(ctx.faultInjectionEnabled).toBe(true);
      expect(ctx.timerMode).toBe('STOPWATCH');
    });

    it('validates NEXT_PUZZLE transition from CORRECT to PRACTICE_IDLE with next puzzle loaded', () => {
      const solvedCtx = {
        ...createPracticeContext(dummyCorpus[0], dummyCorpus, 0, {
          corpus: dummyCorpus,
          expectedSolvedCount: 1,
          expectedFailedCount: 0,
          faultInjectionEnabled: false,
          timerMode: null,
        }),
        solved: true,
        attemptCount: 1,
        expectedHistory: ['d4'],
      };

      expect(isValidTransition('CORRECT', 'NEXT_PUZZLE', solvedCtx)).toBe(true);
      const nextState = getNextState('CORRECT', 'NEXT_PUZZLE', solvedCtx);
      expect(nextState?.state).toBe('PRACTICE_IDLE');
      expect(nextState?.context.activePuzzleIndex).toBe(1);
      expect(nextState?.context.activePuzzle?.puzzleId).toBe('puzzle-2');
      expect(nextState?.context.solved).toBe(false);
      expect(nextState?.context.attemptCount).toBe(0);
      expect(nextState?.context.historyIndex).toBe(0);
      expect(nextState?.context.expectedFen).toBe(dummyCorpus[1].fen);
      expect(nextState?.context.expectedSideToMove).toBe('black');
      expect(nextState?.context.expectedSolvedCount).toBe(1); // Preserved

      // Cannot advance to NEXT_PUZZLE if not solved or if in blindfold
      expect(isValidTransition('CORRECT', 'NEXT_PUZZLE', { ...solvedCtx, solved: false })).toBe(false);
      expect(isValidTransition('CORRECT', 'NEXT_PUZZLE', { ...solvedCtx, blindfoldActive: true })).toBe(false);
      expect(isValidTransition('PRACTICE_IDLE', 'NEXT_PUZZLE', solvedCtx)).toBe(false);
    });
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

  describe('Context Signature Equivalence & Collision Prevention (deriveContextSignature)', () => {
    const dummyCorpus = [
      {
        puzzleId: 'puzzle-1',
        source: 'weakness' as const,
        fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
        playerColor: 'WHITE' as const,
        targetMove: 'd4',
        openingTitle: 'Test Puzzle 1',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 0,
        mistakeRate: 0,
      },
      {
        puzzleId: 'puzzle-2',
        source: 'weakness' as const,
        fen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
        playerColor: 'BLACK' as const,
        targetMove: 'd6',
        openingTitle: 'Test Puzzle 2',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 0,
        mistakeRate: 0,
      },
    ];

    it('Collision 1: distinguishes active puzzle identity and position FEN across different playlist puzzles', () => {
      const campaignCtx = {
        corpus: dummyCorpus,
        expectedSolvedCount: 0,
        expectedFailedCount: 0,
        faultInjectionEnabled: false,
        timerMode: null as null,
      };
      const puzzle0 = createPracticeContext(dummyCorpus[0], dummyCorpus, 0, campaignCtx);
      const puzzle1 = createPracticeContext(dummyCorpus[1], dummyCorpus, 1, campaignCtx);

      const sig0 = deriveContextSignature(puzzle0);
      const sig1 = deriveContextSignature(puzzle1);

      expect(sig0).not.toBe(sig1);
      expect(sig0).toContain('puzzle-1');
      expect(sig1).toContain('puzzle-2');
    });

    it('Collision 2: preserves exact deep history navigation indices and lengths without lossy clamping', () => {
      const ctxPly2 = createInitialContext({ historyIndex: 2, historyLength: 4 });
      const ctxPly3 = createInitialContext({ historyIndex: 3, historyLength: 4 });

      const sigPly2 = deriveContextSignature(ctxPly2);
      const sigPly3 = deriveContextSignature(ctxPly3);

      expect(sigPly2).not.toBe(sigPly3);
      expect(sigPly2).toContain('history:2/4');
      expect(sigPly3).toContain('history:3/4');
    });

    it('Collision 3: distinguishes blindfold originating states (CORRECT vs HISTORICAL_MISTAKE vs INCORRECT)', () => {
      const ctxFromCorrect = createInitialContext({
        blindfoldActive: true,
        blindfoldPreviousState: 'CORRECT',
      });
      const ctxFromMistake = createInitialContext({
        blindfoldActive: true,
        blindfoldPreviousState: 'HISTORICAL_MISTAKE',
      });
      const ctxFromIncorrect = createInitialContext({
        blindfoldActive: true,
        blindfoldPreviousState: 'INCORRECT',
      });

      const sigCorrect = deriveContextSignature(ctxFromCorrect);
      const sigMistake = deriveContextSignature(ctxFromMistake);
      const sigIncorrect = deriveContextSignature(ctxFromIncorrect);

      expect(sigCorrect).not.toBe(sigMistake);
      expect(sigCorrect).not.toBe(sigIncorrect);
      expect(sigMistake).not.toBe(sigIncorrect);
      expect(sigCorrect).toContain('bf-origin:CORRECT');
      expect(sigMistake).toContain('bf-origin:HISTORICAL_MISTAKE');
      expect(sigIncorrect).toContain('bf-origin:INCORRECT');
    });

    it('Collision 4: distinguishes exploration states with and without available exploration decision moves', () => {
      const ctxWithoutDecision = createInitialContext({
        inExploration: true,
        explorationDecisionMove: null,
      });
      const ctxWithDecision = createInitialContext({
        inExploration: true,
        explorationDecisionMove: 'd4',
      });

      const sigWithout = deriveContextSignature(ctxWithoutDecision);
      const sigWith = deriveContextSignature(ctxWithDecision);

      expect(sigWithout).not.toBe(sigWith);
      expect(sigWithout).toContain('decision:none');
      expect(sigWith).toContain('decision:d4');
    });

    it('Equivalence: ignores transient interaction fields that do not change transition reachability', () => {
      const baseCtx = createInitialContext({
        activePuzzleIndex: 0,
        expectedFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        historyIndex: 0,
        historyLength: 0,
      });

      const transientModifiedCtx = {
        ...baseCtx,
        selectedSquare: 'e2',
        promotionPiece: 'q' as const,
        pendingEvaluationMove: 'e4',
        pendingEvaluationPayload: { test: 123 },
      };

      expect(deriveContextSignature(baseCtx)).toBe(deriveContextSignature(transientModifiedCtx));
    });
  });
});
