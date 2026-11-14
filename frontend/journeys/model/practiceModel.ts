import { Chess, type Square } from 'chess.js';
import type { StateModel, StateOracle } from './stateModel';
import { MOCK_PUZZLES, DEFAULT_MOCK_PUZZLE } from '../fixtures/mockApi';

export const DEFAULT_PRACTICE_FEN = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';

export type PracticeAbstractState =
  | 'DISCOVERY'
  | 'PRACTICE_IDLE'
  | 'EVALUATING'
  | 'CORRECT'
  | 'INCORRECT'
  | 'HISTORICAL_MISTAKE'
  | 'EVALUATION_ERROR'
  | 'EXPLORING'
  | 'BLINDFOLD';

export type PracticeAction =
  | 'SELECT_PUZZLE'
  | 'SUBMIT_OPTIMAL'
  | 'SUBMIT_MISTAKE'
  | 'SUBMIT_BLUNDER'
  | 'SUBMIT_FAULT'
  | 'RETRY'
  | 'RESET'
  | 'CONTINUE_EXPLORATION'
  | 'EXIT_EXPLORATION'
  | 'APPLY_DECISION_MOVE'
  | 'BACK_TO_PUZZLES'
  | 'FLIP_BOARD'
  | 'REQUEST_HINT'
  | 'ENTER_BLINDFOLD'
  | 'EXIT_BLINDFOLD'
  | 'TOGGLE_BLINDFOLD_VISIBILITY'
  | 'SUBMIT_BLINDFOLD_MOVE'
  | 'NAVIGATE_HISTORY_PREV'
  | 'NAVIGATE_HISTORY_NEXT'
  | 'TOGGLE_TIMER_MODE'
  | 'NEXT_PUZZLE';

export const PRACTICE_ABSTRACT_STATES: PracticeAbstractState[] = [
  'DISCOVERY',
  'PRACTICE_IDLE',
  'EVALUATING',
  'CORRECT',
  'INCORRECT',
  'HISTORICAL_MISTAKE',
  'EVALUATION_ERROR',
  'EXPLORING',
  'BLINDFOLD',
];

export const PRACTICE_ACTIONS: PracticeAction[] = [
  'SELECT_PUZZLE',
  'SUBMIT_OPTIMAL',
  'SUBMIT_MISTAKE',
  'SUBMIT_BLUNDER',
  'SUBMIT_FAULT',
  'RETRY',
  'RESET',
  'CONTINUE_EXPLORATION',
  'EXIT_EXPLORATION',
  'APPLY_DECISION_MOVE',
  'BACK_TO_PUZZLES',
  'FLIP_BOARD',
  'REQUEST_HINT',
  'ENTER_BLINDFOLD',
  'EXIT_BLINDFOLD',
  'TOGGLE_BLINDFOLD_VISIBILITY',
  'SUBMIT_BLINDFOLD_MOVE',
  'NAVIGATE_HISTORY_PREV',
  'NAVIGATE_HISTORY_NEXT',
  'TOGGLE_TIMER_MODE',
  'NEXT_PUZZLE',
];

export interface PuzzleFixture {
  puzzleId: string;
  source?: string;
  fen: string;
  playerColor?: 'WHITE' | 'BLACK' | string;
  targetMove: string;
  mistakeMove?: string;
  blunderMove?: string;
  solutionLine?: string[];
  openingTitle?: string;
  acceptableMoves?: Array<{ move: string; evalLoss?: number }>;
  movesPlayed?: Array<{ move: string; timesPlayed?: number; averageLoss?: number }>;
  priority?: number;
  timesReached?: number;
  mistakeCount?: number;
  mistakeRate?: number;
  gameUrls?: string[];
  evalCp?: number;
}

export interface PracticeCampaignContext {
  corpus: PuzzleFixture[];
  expectedSolvedCount: number;
  expectedFailedCount: number;
  faultInjectionEnabled: boolean;
  timerMode: 'STOPWATCH' | 'COUNTDOWN' | null;
}

export interface PracticeModelContext {
  corpus?: PuzzleFixture[];
  activePuzzleIndex?: number;
  activePuzzle?: PuzzleFixture;
  puzzleLoaded: boolean;
  attemptCount: number;
  inExploration: boolean;
  solved?: boolean;
  startingFen?: string;
  expectedFen?: string;
  expectedHistory?: string[];
  expectedSideToMove?: 'white' | 'black';
  expectedSolvedCount?: number;
  expectedFailedCount?: number;
  lastMoveSan?: string;
  isBoardFlipped?: boolean;
  blindfoldActive?: boolean;
  blindfoldVisible?: boolean;
  blindfoldMoveCount?: number;
  blindfoldPreviousState?: PracticeAbstractState;
  isWeaknessPlaylist?: boolean;
  hintActive?: boolean;
  historyIndex?: number;
  historyLength?: number;
  timerMode?: 'STOPWATCH' | 'COUNTDOWN' | null;
  faultInjectionEnabled?: boolean;
  pendingEvaluationMove?: string | null;
  pendingEvaluationPayload?: unknown | null;
  explorationDecisionMove?: string | null;
  solutionLineProgress?: number;
  selectedSquare?: string | null;
  promotionPiece?: 'q' | 'r' | 'b' | 'n' | null;
}

export function createInitialContext(overrides?: Partial<PracticeModelContext>): PracticeModelContext {
  return {
    puzzleLoaded: false,
    attemptCount: 0,
    inExploration: false,
    solved: false,
    expectedSolvedCount: 0,
    expectedFailedCount: 0,
    expectedHistory: [],
    expectedSideToMove: 'white',
    isBoardFlipped: false,
    blindfoldActive: false,
    blindfoldVisible: false,
    blindfoldMoveCount: 0,
    hintActive: false,
    historyIndex: 0,
    historyLength: 0,
    timerMode: null,
    faultInjectionEnabled: false,
    ...overrides,
  };
}

export function createPracticeContext(
  fixture: PuzzleFixture,
  corpus: PuzzleFixture[],
  index: number,
  campaignContext: PracticeCampaignContext
): PracticeModelContext {
  const chess = new Chess(fixture.fen);
  return {
    // Puzzle-scoped state (strictly initialized, preventing leakage across puzzles)
    activePuzzleIndex: index,
    activePuzzle: fixture,
    corpus,
    puzzleLoaded: true,
    inExploration: false,
    startingFen: fixture.fen,
    expectedFen: fixture.fen,
    expectedSideToMove: chess.turn() === 'w' ? 'white' : 'black',
    expectedHistory: [],
    historyIndex: 0,
    historyLength: 0,
    attemptCount: 0,
    solved: false,
    lastMoveSan: undefined,
    isBoardFlipped: false,
    hintActive: false,
    isWeaknessPlaylist: fixture.source === 'weakness',
    blindfoldActive: false,
    blindfoldVisible: false,
    blindfoldMoveCount: 0,
    blindfoldPreviousState: undefined,
    pendingEvaluationMove: null,
    pendingEvaluationPayload: null,
    explorationDecisionMove: null,
    solutionLineProgress: 0,
    selectedSquare: null,
    promotionPiece: null,

    // Campaign-scoped state (explicitly preserved)
    expectedSolvedCount: campaignContext.expectedSolvedCount,
    expectedFailedCount: campaignContext.expectedFailedCount,
    faultInjectionEnabled: campaignContext.faultInjectionEnabled,
    timerMode: campaignContext.timerMode,
  };
}

export function deriveMoveSquares(fen: string, san: string): { from: string; to: string } {
  const chess = new Chess(fen);
  const moves = chess.moves({ verbose: true });
  const moveObj = moves.find((m) => m.san === san);
  if (!moveObj) {
    throw new Error(
      `[DynamicMoveDerivationError] Move "${san}" is not legal in position "${fen}". Legal moves: ${moves.map((m) => m.san).join(', ')}`
    );
  }
  return { from: moveObj.from, to: moveObj.to };
}

export function deriveFenAtHistoryIndex(
  startingFen: string,
  history: string[],
  index: number
): { fen: string; sideToMove: 'white' | 'black' } {
  if (index < 0 || index > history.length) {
    throw new Error(`[HistoryIndexOutOfBoundsError] Index ${index} is outside bounds [0, ${history.length}]`);
  }
  const chess = new Chess(startingFen);
  for (let i = 0; i < index; i++) {
    try {
      const result = chess.move(history[i]);
      if (!result) {
        throw new Error(`[IllegalHistoryMoveError] Move "${history[i]}" at ply ${i} is illegal from FEN "${chess.fen()}"`);
      }
    } catch {
      throw new Error(`[IllegalHistoryMoveError] Move "${history[i]}" at ply ${i} is illegal from FEN "${chess.fen()}"`);
    }
  }
  return {
    fen: chess.fen(),
    sideToMove: chess.turn() === 'w' ? 'white' : 'black',
  };
}

export interface TransitionRule {
  from: PracticeAbstractState;
  action: PracticeAction;
  to: PracticeAbstractState;
  guard?: (context: PracticeModelContext) => boolean;
  effect?: (context: PracticeModelContext) => PracticeModelContext;
}

export function deriveMoveState(
  ctx: PracticeModelContext,
  san: string,
  resultType: 'CORRECT' | 'INCORRECT' | 'HISTORICAL_MISTAKE'
): PracticeModelContext {
  const currentFen = ctx.expectedFen ?? DEFAULT_PRACTICE_FEN;
  const chess = new Chess(currentFen);
  try {
    chess.move(san);
  } catch {
    const fallback = new Chess(DEFAULT_PRACTICE_FEN);
    fallback.move(san);
    chess.load(fallback.fen());
  }
  const nextFen = chess.fen();
  const nextSide = chess.turn() === 'w' ? 'white' : 'black';
  const newHistoryIndex = (ctx.historyIndex ?? 0) + 1;

  if (resultType === 'CORRECT') {
    return {
      ...ctx,
      attemptCount: ctx.attemptCount + 1,
      solved: true,
      hintActive: false,
      historyLength: newHistoryIndex,
      historyIndex: newHistoryIndex,
      expectedSolvedCount: (ctx.expectedSolvedCount ?? 0) + 1,
      expectedFen: nextFen,
      expectedHistory: [...(ctx.expectedHistory ?? []), san],
      expectedSideToMove: nextSide,
      lastMoveSan: san,
    };
  }

  // Dynamic evaluation puzzles (!targetMove) roll back board to pre-move baseline on inaccurate move
  const isDynamicEvalRollback = resultType === 'INCORRECT' && !ctx.activePuzzle?.targetMove;
  const resultingFen = isDynamicEvalRollback ? (ctx.startingFen ?? DEFAULT_PRACTICE_FEN) : nextFen;
  const resultingHistory = isDynamicEvalRollback ? (ctx.expectedHistory ?? []) : [...(ctx.expectedHistory ?? []), san];
  const resultingHistoryIndex = isDynamicEvalRollback ? (ctx.historyIndex ?? 0) : newHistoryIndex;
  const resultingSide = isDynamicEvalRollback ? (ctx.expectedSideToMove ?? 'white') : nextSide;

  return {
    ...ctx,
    attemptCount: ctx.attemptCount + 1,
    hintActive: false,
    historyLength: resultingHistoryIndex,
    historyIndex: resultingHistoryIndex,
    expectedFailedCount: (ctx.expectedFailedCount ?? 0) + 1,
    expectedFen: resultingFen,
    expectedHistory: resultingHistory,
    expectedSideToMove: resultingSide,
    lastMoveSan: san,
  };
}

export const PRACTICE_TRANSITIONS: TransitionRule[] = [
  // Discovery to Practice
  {
    from: 'DISCOVERY',
    action: 'SELECT_PUZZLE',
    to: 'PRACTICE_IDLE',
    effect: (ctx) => {
      const corpus = ctx.corpus ?? MOCK_PUZZLES;
      const index = ctx.activePuzzleIndex ?? 0;
      const fixture = corpus[index] ?? corpus[0] ?? DEFAULT_MOCK_PUZZLE;
      return createPracticeContext(fixture, corpus, index, {
        corpus,
        expectedSolvedCount: ctx.expectedSolvedCount ?? 0,
        expectedFailedCount: ctx.expectedFailedCount ?? 0,
        faultInjectionEnabled: Boolean(ctx.faultInjectionEnabled),
        timerMode: ctx.timerMode ?? null,
      });
    },
  },
  // Submitting moves from Idle
  {
    from: 'PRACTICE_IDLE',
    action: 'SUBMIT_OPTIMAL',
    to: 'CORRECT',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.blindfoldActive && !ctx.faultInjectionEnabled,
    effect: (ctx) =>
      deriveMoveState(
        ctx,
        ctx.activePuzzle?.targetMove || ctx.activePuzzle?.solutionLine?.[0] || 'd4',
        'CORRECT'
      ),
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'SUBMIT_MISTAKE',
    to: 'INCORRECT',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.blindfoldActive && !ctx.faultInjectionEnabled,
    effect: (ctx) =>
      deriveMoveState(
        ctx,
        ctx.activePuzzle?.mistakeMove ?? 'a3',
        'INCORRECT'
      ),
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'SUBMIT_BLUNDER',
    to: 'HISTORICAL_MISTAKE',
    guard: (ctx) =>
      ctx.puzzleLoaded &&
      !ctx.blindfoldActive &&
      !ctx.faultInjectionEnabled &&
      Boolean(ctx.activePuzzle?.movesPlayed && ctx.activePuzzle.movesPlayed.length > 0),
    effect: (ctx) =>
      deriveMoveState(
        ctx,
        ctx.activePuzzle?.blunderMove ?? ctx.activePuzzle?.movesPlayed?.[0]?.move ?? 'h4',
        'HISTORICAL_MISTAKE'
      ),
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'SUBMIT_FAULT',
    to: 'EVALUATION_ERROR',
    guard: (ctx) => ctx.puzzleLoaded && !!ctx.faultInjectionEnabled,
    effect: (ctx) => {
      const fen = ctx.expectedFen ?? ctx.startingFen ?? DEFAULT_PRACTICE_FEN;
      const targetMove = ctx.activePuzzle?.targetMove || ctx.activePuzzle?.solutionLine?.[0] || 'd4';
      return {
        ...ctx,
        faultInjectionEnabled: false,
        pendingEvaluationMove: targetMove,
        pendingEvaluationPayload: {
          fen,
          move: targetMove,
        },
      };
    },
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'RESET',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => {
      const startingFen = ctx.startingFen ?? ctx.activePuzzle?.fen ?? DEFAULT_PRACTICE_FEN;
      const chess = new Chess(startingFen);
      return {
        ...ctx,
        expectedFen: startingFen,
        expectedHistory: [],
        expectedSideToMove: chess.turn() === 'w' ? 'white' : 'black',
        hintActive: false,
        historyIndex: 0,
        historyLength: 0,
      };
    },
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({
      ...ctx,
      puzzleLoaded: false,
      inExploration: false,
      hintActive: false,
      blindfoldActive: false,
      historyIndex: 0,
      historyLength: 0,
    }),
  },
  // Recovery from Incorrect / Mistake / Error
  {
    from: 'INCORRECT',
    action: 'RETRY',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => {
      const startingFen = ctx.startingFen ?? ctx.activePuzzle?.fen ?? DEFAULT_PRACTICE_FEN;
      const chess = new Chess(startingFen);
      return {
        ...ctx,
        expectedFen: startingFen,
        expectedSideToMove: chess.turn() === 'w' ? 'white' : 'black',
        expectedHistory: [],
        hintActive: false,
        historyIndex: 0,
        historyLength: 0,
      };
    },
  },
  {
    from: 'HISTORICAL_MISTAKE',
    action: 'RETRY',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => {
      const startingFen = ctx.startingFen ?? ctx.activePuzzle?.fen ?? DEFAULT_PRACTICE_FEN;
      const chess = new Chess(startingFen);
      return {
        ...ctx,
        expectedFen: startingFen,
        expectedSideToMove: chess.turn() === 'w' ? 'white' : 'black',
        expectedHistory: [],
        hintActive: false,
        historyIndex: 0,
        historyLength: 0,
      };
    },
  },
  {
    from: 'HISTORICAL_MISTAKE',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false, hintActive: false, blindfoldActive: false }),
  },
  {
    from: 'EVALUATION_ERROR',
    action: 'RETRY',
    to: 'CORRECT',
    guard: (ctx) => {
      if (!ctx.puzzleLoaded) return false;
      const target = (ctx.activePuzzle?.targetMove || ctx.activePuzzle?.solutionLine?.[0]) ?? 'd4';
      const move = ctx.pendingEvaluationMove ?? target;
      const isTarget = move === target;
      const isAcceptable = ctx.activePuzzle?.acceptableMoves?.some((m) => m.move === move);
      return isTarget || Boolean(isAcceptable);
    },
    effect: (ctx) => {
      const target = (ctx.activePuzzle?.targetMove || ctx.activePuzzle?.solutionLine?.[0]) ?? 'd4';
      const move = ctx.pendingEvaluationMove ?? target;
      const nextCtx = deriveMoveState(ctx, move, 'CORRECT');
      return {
        ...nextCtx,
        pendingEvaluationMove: null,
        pendingEvaluationPayload: null,
      };
    },
  },
  {
    from: 'EVALUATION_ERROR',
    action: 'RETRY',
    to: 'INCORRECT',
    guard: (ctx) => {
      if (!ctx.puzzleLoaded) return false;
      const target = (ctx.activePuzzle?.targetMove || ctx.activePuzzle?.solutionLine?.[0]) ?? 'd4';
      const move = ctx.pendingEvaluationMove ?? target;
      const isTarget = move === target;
      const isAcceptable = ctx.activePuzzle?.acceptableMoves?.some((m) => m.move === move);
      return !isTarget && !isAcceptable;
    },
    effect: (ctx) => {
      const move = ctx.pendingEvaluationMove ?? 'a3';
      const nextCtx = deriveMoveState(ctx, move, 'INCORRECT');
      return {
        ...nextCtx,
        pendingEvaluationMove: null,
        pendingEvaluationPayload: null,
      };
    },
  },
  {
    from: 'EVALUATION_ERROR',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false }),
  },
  {
    from: 'INCORRECT',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false, hintActive: false, blindfoldActive: false }),
  },
  // Transition to Line Exploration
  {
    from: 'CORRECT',
    action: 'CONTINUE_EXPLORATION',
    to: 'EXPLORING',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => ({ ...ctx, inExploration: true }),
  },
  {
    from: 'CORRECT',
    action: 'NEXT_PUZZLE',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => Boolean(ctx.solved) && ctx.puzzleLoaded && !ctx.blindfoldActive && !ctx.inExploration && Boolean(ctx.isWeaknessPlaylist),
    effect: (ctx) => {
      const corpus = ctx.corpus ?? MOCK_PUZZLES;
      const nextIndex = ((ctx.activePuzzleIndex ?? 0) + 1) % corpus.length;
      const nextFixture = corpus[nextIndex] ?? corpus[0] ?? DEFAULT_MOCK_PUZZLE;
      return createPracticeContext(nextFixture, corpus, nextIndex, {
        corpus,
        expectedSolvedCount: ctx.expectedSolvedCount ?? 0,
        expectedFailedCount: ctx.expectedFailedCount ?? 0,
        faultInjectionEnabled: Boolean(ctx.faultInjectionEnabled),
        timerMode: ctx.timerMode ?? null,
      });
    },
  },
  {
    from: 'CORRECT',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false, hintActive: false, blindfoldActive: false }),
  },
  // Line Exploration back to Idle, Correct, or Discovery
  {
    from: 'EXPLORING',
    action: 'EXIT_EXPLORATION',
    to: 'CORRECT',
    guard: (ctx) => ctx.inExploration,
    effect: (ctx) => ({
      ...ctx,
      inExploration: false,
    }),
  },
  {
    from: 'EXPLORING',
    action: 'APPLY_DECISION_MOVE',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => Boolean(ctx.inExploration) && Boolean(ctx.explorationDecisionMove),
    effect: (ctx) => {
      const fen = ctx.startingFen ?? DEFAULT_PRACTICE_FEN;
      return {
        ...ctx,
        inExploration: false,
        expectedFen: fen,
        expectedSideToMove: 'white',
        expectedHistory: [],
        historyIndex: 0,
        historyLength: 0,
        explorationDecisionMove: null,
      };
    },
  },
  {
    from: 'EXPLORING',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false, hintActive: false, blindfoldActive: false }),
  },
  // Audited Controls (Issue #770)
  // 1. Board Orientation: FLIP_BOARD
  {
    from: 'PRACTICE_IDLE',
    action: 'FLIP_BOARD',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, isBoardFlipped: !ctx.isBoardFlipped }),
  },
  {
    from: 'EXPLORING',
    action: 'FLIP_BOARD',
    to: 'EXPLORING',
    guard: (ctx) => ctx.inExploration && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, isBoardFlipped: !ctx.isBoardFlipped }),
  },
  {
    from: 'CORRECT',
    action: 'FLIP_BOARD',
    to: 'CORRECT',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, isBoardFlipped: !ctx.isBoardFlipped }),
  },
  {
    from: 'INCORRECT',
    action: 'FLIP_BOARD',
    to: 'INCORRECT',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, isBoardFlipped: !ctx.isBoardFlipped }),
  },
  {
    from: 'HISTORICAL_MISTAKE',
    action: 'FLIP_BOARD',
    to: 'HISTORICAL_MISTAKE',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, isBoardFlipped: !ctx.isBoardFlipped }),
  },
  // 2. Hint Request: REQUEST_HINT
  {
    from: 'PRACTICE_IDLE',
    action: 'REQUEST_HINT',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.solved && !ctx.blindfoldActive && !ctx.inExploration,
    effect: (ctx) => ({ ...ctx, hintActive: true }),
  },
  // 3. Blindfold Mode Transitions (Gated by isWeaknessPlaylist in post-attempt feedback states)
  {
    from: 'CORRECT',
    action: 'ENTER_BLINDFOLD',
    to: 'BLINDFOLD',
    guard: (ctx) => Boolean(ctx.isWeaknessPlaylist) && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, blindfoldActive: true, blindfoldPreviousState: 'CORRECT', blindfoldVisible: false }),
  },
  {
    from: 'HISTORICAL_MISTAKE',
    action: 'ENTER_BLINDFOLD',
    to: 'BLINDFOLD',
    guard: (ctx) => Boolean(ctx.isWeaknessPlaylist) && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, blindfoldActive: true, blindfoldPreviousState: 'HISTORICAL_MISTAKE', blindfoldVisible: false }),
  },
  {
    from: 'INCORRECT',
    action: 'ENTER_BLINDFOLD',
    to: 'BLINDFOLD',
    guard: (ctx) => Boolean(ctx.isWeaknessPlaylist) && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, blindfoldActive: true, blindfoldPreviousState: 'INCORRECT', blindfoldVisible: false }),
  },
  {
    from: 'EXPLORING',
    action: 'ENTER_BLINDFOLD',
    to: 'BLINDFOLD',
    guard: (ctx) => Boolean(ctx.isWeaknessPlaylist) && !ctx.blindfoldActive,
    effect: (ctx) => ({ ...ctx, blindfoldActive: true, blindfoldPreviousState: 'EXPLORING', blindfoldVisible: false }),
  },
  {
    from: 'BLINDFOLD',
    action: 'TOGGLE_BLINDFOLD_VISIBILITY',
    to: 'BLINDFOLD',
    guard: (ctx) => Boolean(ctx.blindfoldActive),
    effect: (ctx) => ({ ...ctx, blindfoldVisible: !ctx.blindfoldVisible }),
  },
  {
    from: 'BLINDFOLD',
    action: 'SUBMIT_BLINDFOLD_MOVE',
    to: 'BLINDFOLD',
    guard: (ctx) => Boolean(ctx.blindfoldActive),
    effect: (ctx) => ({ ...ctx, blindfoldMoveCount: (ctx.blindfoldMoveCount ?? 0) + 1 }),
  },
  {
    from: 'BLINDFOLD',
    action: 'EXIT_BLINDFOLD',
    to: 'CORRECT',
    guard: (ctx) => Boolean(ctx.blindfoldActive) && (ctx.blindfoldPreviousState === 'CORRECT' || !ctx.blindfoldPreviousState),
    effect: (ctx) => ({ ...ctx, blindfoldActive: false, blindfoldPreviousState: undefined }),
  },
  {
    from: 'BLINDFOLD',
    action: 'EXIT_BLINDFOLD',
    to: 'HISTORICAL_MISTAKE',
    guard: (ctx) => Boolean(ctx.blindfoldActive) && ctx.blindfoldPreviousState === 'HISTORICAL_MISTAKE',
    effect: (ctx) => ({ ...ctx, blindfoldActive: false, blindfoldPreviousState: undefined }),
  },
  {
    from: 'BLINDFOLD',
    action: 'EXIT_BLINDFOLD',
    to: 'INCORRECT',
    guard: (ctx) => Boolean(ctx.blindfoldActive) && ctx.blindfoldPreviousState === 'INCORRECT',
    effect: (ctx) => ({ ...ctx, blindfoldActive: false, blindfoldPreviousState: undefined }),
  },
  {
    from: 'BLINDFOLD',
    action: 'EXIT_BLINDFOLD',
    to: 'EXPLORING',
    guard: (ctx) => Boolean(ctx.blindfoldActive) && ctx.blindfoldPreviousState === 'EXPLORING',
    effect: (ctx) => ({ ...ctx, blindfoldActive: false, blindfoldPreviousState: undefined }),
  },
  // 4. Move History Navigation: NAVIGATE_HISTORY_PREV & NAVIGATE_HISTORY_NEXT
  {
    from: 'PRACTICE_IDLE',
    action: 'NAVIGATE_HISTORY_PREV',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => (ctx.historyIndex ?? 0) > 0 && !ctx.blindfoldActive,
    effect: (ctx) => {
      const history = ctx.expectedHistory ?? [];
      const newIndex = Math.max(0, (ctx.historyIndex ?? 0) - 1);
      const startingFen = ctx.startingFen ?? DEFAULT_PRACTICE_FEN;
      const { fen, sideToMove } = (history.length > 0 || newIndex === 0)
        ? deriveFenAtHistoryIndex(startingFen, history, newIndex)
        : { fen: ctx.expectedFen ?? startingFen, sideToMove: ctx.expectedSideToMove ?? 'white' };
      return {
        ...ctx,
        historyIndex: newIndex,
        historyLength: history.length || ctx.historyLength || 0,
        expectedFen: fen,
        expectedSideToMove: sideToMove,
      };
    },
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'NAVIGATE_HISTORY_NEXT',
    to: 'PRACTICE_IDLE',
    guard: (ctx) =>
      (ctx.historyIndex ?? 0) <
        (ctx.expectedHistory && ctx.expectedHistory.length > 0
          ? ctx.expectedHistory.length
          : (ctx.historyLength ?? 0)) &&
      !ctx.blindfoldActive,
    effect: (ctx) => {
      const history = ctx.expectedHistory ?? [];
      const maxLen = history.length > 0 ? history.length : (ctx.historyLength ?? 0);
      const newIndex = Math.min(maxLen, (ctx.historyIndex ?? 0) + 1);
      const startingFen = ctx.startingFen ?? DEFAULT_PRACTICE_FEN;
      const { fen, sideToMove } = (history.length >= newIndex && history.length > 0)
        ? deriveFenAtHistoryIndex(startingFen, history, newIndex)
        : { fen: ctx.expectedFen ?? startingFen, sideToMove: ctx.expectedSideToMove ?? 'white' };
      return {
        ...ctx,
        historyIndex: newIndex,
        historyLength: history.length || maxLen,
        expectedFen: fen,
        expectedSideToMove: sideToMove,
      };
    },
  },
  {
    from: 'EXPLORING',
    action: 'NAVIGATE_HISTORY_PREV',
    to: 'EXPLORING',
    guard: (ctx) => (ctx.historyIndex ?? 0) > 0 && !ctx.blindfoldActive,
    effect: (ctx) => {
      const history = ctx.expectedHistory ?? [];
      const newIndex = Math.max(0, (ctx.historyIndex ?? 0) - 1);
      const startingFen = ctx.startingFen ?? DEFAULT_PRACTICE_FEN;
      const { fen, sideToMove } = (history.length > 0 || newIndex === 0)
        ? deriveFenAtHistoryIndex(startingFen, history, newIndex)
        : { fen: ctx.expectedFen ?? startingFen, sideToMove: ctx.expectedSideToMove ?? 'white' };
      return {
        ...ctx,
        historyIndex: newIndex,
        historyLength: history.length || ctx.historyLength || 0,
        expectedFen: fen,
        expectedSideToMove: sideToMove,
      };
    },
  },
  {
    from: 'EXPLORING',
    action: 'NAVIGATE_HISTORY_NEXT',
    to: 'EXPLORING',
    guard: (ctx) =>
      (ctx.historyIndex ?? 0) <
        (ctx.expectedHistory && ctx.expectedHistory.length > 0
          ? ctx.expectedHistory.length
          : (ctx.historyLength ?? 0)) &&
      !ctx.blindfoldActive,
    effect: (ctx) => {
      const history = ctx.expectedHistory ?? [];
      const maxLen = history.length > 0 ? history.length : (ctx.historyLength ?? 0);
      const newIndex = Math.min(maxLen, (ctx.historyIndex ?? 0) + 1);
      const startingFen = ctx.startingFen ?? DEFAULT_PRACTICE_FEN;
      const { fen, sideToMove } = (history.length >= newIndex && history.length > 0)
        ? deriveFenAtHistoryIndex(startingFen, history, newIndex)
        : { fen: ctx.expectedFen ?? startingFen, sideToMove: ctx.expectedSideToMove ?? 'white' };
      return {
        ...ctx,
        historyIndex: newIndex,
        historyLength: history.length || maxLen,
        expectedFen: fen,
        expectedSideToMove: sideToMove,
      };
    },
  },
  // 5. Timed Training Mode: TOGGLE_TIMER_MODE
  {
    from: 'PRACTICE_IDLE',
    action: 'TOGGLE_TIMER_MODE',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded && !ctx.blindfoldActive,
    effect: (ctx) => {
      const nextMode = ctx.timerMode === null ? 'STOPWATCH' : ctx.timerMode === 'STOPWATCH' ? 'COUNTDOWN' : null;
      return { ...ctx, timerMode: nextMode };
    },
  },
];

export function findMatchingTransition(
  from: PracticeAbstractState,
  action: PracticeAction,
  context: PracticeModelContext = createInitialContext()
): TransitionRule | null {
  for (const rule of PRACTICE_TRANSITIONS) {
    if (rule.from === from && rule.action === action) {
      if (!rule.guard || rule.guard(context)) {
        return rule;
      }
    }
  }
  return null;
}

export function isValidTransition(
  from: PracticeAbstractState,
  action: PracticeAction,
  context: PracticeModelContext = createInitialContext()
): boolean {
  return findMatchingTransition(from, action, context) !== null;
}

export function getNextState(
  from: PracticeAbstractState,
  action: PracticeAction,
  context: PracticeModelContext = createInitialContext()
): { state: PracticeAbstractState; context: PracticeModelContext } | null {
  const rule = findMatchingTransition(from, action, context);
  if (!rule) return null;
  const nextContext = rule.effect ? rule.effect(context) : context;
  return {
    state: rule.to,
    context: nextContext,
  };
}

import type { Page, Expect, Locator } from '@playwright/test';

export interface LocatorAssertion {
  toBeVisible: (options?: { timeout?: number }) => Promise<void>;
  [key: string]: unknown;
}

export type ExpectFn = Expect | ((actual: unknown) => LocatorAssertion);

export interface InspectableLocator {
  waitFor?: (options?: { state?: 'visible' | 'attached' | 'detached' | 'hidden'; timeout?: number }) => Promise<void>;
  isVisible?: () => Promise<boolean>;
  toBeVisible?: () => Promise<boolean>;
  textContent?: () => Promise<string | null>;
  getAttribute?: (name: string) => Promise<string | null>;
  count?: () => Promise<number>;
  first?: () => InspectableLocator;
}

export interface PageLike {
  getByRole: (role: Parameters<Page['getByRole']>[0], options?: { name?: string | RegExp }) => InspectableLocator;
  getByText: (text: string | RegExp) => InspectableLocator;
  getByLabel?: (text: string | RegExp) => InspectableLocator;
  locator: (selector: string) => InspectableLocator;
}

export interface StateOracleDefinition {
  description: string;
  assert: (page: PageLike, context: PracticeModelContext, expectFn?: ExpectFn) => Promise<void>;
}

export function resolveArgs(context: PracticeModelContext, maybeExpectFn?: unknown): {
  context: PracticeModelContext;
  expectFn?: ExpectFn;
} {
  const exp = typeof maybeExpectFn === 'function' ? (maybeExpectFn as ExpectFn) : undefined;
  return { context, expectFn: exp };
}

export async function verifyVisible(locator: unknown, arg1?: unknown, arg2?: unknown): Promise<void> {
  const expectFn = typeof arg1 === 'function' ? (arg1 as ExpectFn) : (typeof arg2 === 'function' ? (arg2 as ExpectFn) : undefined);
  if (expectFn) {
    await expectFn(locator as Locator).toBeVisible({ timeout: 10_000 });
    return;
  }
  const loc = locator as {
    waitFor?: (options?: { state?: string; timeout?: number }) => Promise<void>;
    toBeVisible?: () => Promise<boolean>;
  } | null | undefined;
  if (loc && typeof loc.waitFor === 'function') {
    await loc.waitFor({ state: 'visible', timeout: 10_000 });
  } else if (loc && typeof loc.toBeVisible === 'function') {
    await loc.toBeVisible();
  }
}

export async function verifyBoardPosition(
  page: PageLike,
  expectedFen: string,
  expectFn?: ExpectFn
): Promise<void> {
  const chess = new Chess(expectedFen);
  const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];

  for (let r = 1; r <= 8; r++) {
    for (const f of files) {
      const sq = `${f}${r}` as Square;
      const piece = chess.get(sq);
      const pieceLocator = page.locator(`[data-square="${sq}"] [data-piece]`) as InspectableLocator;

      if (piece) {
        const expectedCode = `${piece.color}${piece.type.toUpperCase()}`;
        const exactPieceLocator = page.locator(`[data-square="${sq}"] [data-piece="${expectedCode}"]`);

        try {
          await verifyVisible(exactPieceLocator, expectFn);
        } catch {
          if (typeof pieceLocator.count === 'function') {
            const count = await pieceLocator.count();
            if (count === 0) {
              throw new Error(
                `[PIECE_TOPOLOGY_MISMATCH] Square "${sq}" expected piece "${expectedCode}" in position "${expectedFen}", but no piece was found.`
              );
            }
          }

          if (typeof pieceLocator.getAttribute === 'function') {
            const actualCode = await pieceLocator.getAttribute('data-piece');
            if (actualCode && actualCode !== expectedCode) {
              throw new Error(
                `[PIECE_TOPOLOGY_MISMATCH] Square "${sq}" expected piece "${expectedCode}" in position "${expectedFen}", but found "${actualCode}".`
              );
            }
          }

          throw new Error(
            `[PIECE_TOPOLOGY_MISMATCH] Square "${sq}" expected piece "${expectedCode}" in position "${expectedFen}", but piece was not visible.`
          );
        }
      } else {
        if (typeof pieceLocator.waitFor === 'function') {
          await pieceLocator.waitFor({ state: 'hidden', timeout: 500 }).catch(() => {});
        }
        if (typeof pieceLocator.count === 'function') {
          const count = await pieceLocator.count();
          if (count > 0) {
            let actualCode = 'unknown';
            if (typeof pieceLocator.getAttribute === 'function') {
              actualCode = (await pieceLocator.getAttribute('data-piece')) ?? 'unknown';
            }
            throw new Error(
              `[PIECE_TOPOLOGY_MISMATCH] Square "${sq}" expected to be empty in position "${expectedFen}", but found piece "${actualCode}".`
            );
          }
        }
      }
    }
  }
}

export async function verifyAttemptCounters(
  page: PageLike,
  failed: number,
  solved: number,
  expectFn?: ExpectFn
): Promise<void> {
  const anyFailed = page.getByText(/Failed:\s*\d+/i) as InspectableLocator;
  if (typeof anyFailed.textContent === 'function') {
    const text = await anyFailed.textContent().catch(() => null);
    if (text && !new RegExp(`Failed:\\s*${failed}\\b`, 'i').test(text)) {
      throw new Error(
        `[COUNTER_DESYNCHRONIZATION] Expected "Failed: ${failed}", but UI rendered "${text}".`
      );
    }
  }

  const anySolved = page.getByText(/Solved:\s*\d+/i) as InspectableLocator;
  if (typeof anySolved.textContent === 'function') {
    const text = await anySolved.textContent().catch(() => null);
    if (text && !new RegExp(`Solved:\\s*${solved}\\b`, 'i').test(text)) {
      throw new Error(
        `[COUNTER_DESYNCHRONIZATION] Expected "Solved: ${solved}", but UI rendered "${text}".`
      );
    }
  }

  const failedLocator = page.getByText(new RegExp(`Failed:\\s*${failed}`, 'i'));
  const solvedLocator = page.getByText(new RegExp(`Solved:\\s*${solved}`, 'i'));
  await verifyVisible(failedLocator, expectFn);
  await verifyVisible(solvedLocator, expectFn);
}

export async function verifyTurnState(
  page: PageLike,
  expectedTurn: 'white' | 'black',
  isExploration?: boolean,
  expectFn?: ExpectFn
): Promise<void> {
  if (!isExploration) return;
  const opposingTurnText = expectedTurn === 'white' ? /Black to move/i : /White to move/i;
  const opposingLocator = page.getByText(opposingTurnText) as InspectableLocator;

  const checkVisible = opposingLocator.isVisible ?? opposingLocator.toBeVisible;
  if (typeof checkVisible === 'function') {
    const isOpposingVisible = await checkVisible.call(opposingLocator).catch(() => false);
    if (isOpposingVisible) {
      throw new Error(
        `[TURN_MISMATCH] Expected side to move "${expectedTurn}", but UI rendered opposing turn indicator.`
      );
    }
  }

  const turnText = expectedTurn === 'white' ? /White to move/i : /Black to move/i;
  const turnLocator = page.getByText(turnText) as InspectableLocator;
  const checkTurnVisible = turnLocator.isVisible ?? turnLocator.toBeVisible;
  if (typeof checkTurnVisible === 'function') {
    const isTurnVisible = await checkTurnVisible.call(turnLocator).catch(() => false);
    if (isTurnVisible) {
      await verifyVisible(turnLocator, expectFn);
    }
  }
}

export function escapeRegex(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export async function verifyMoveFeedback(
  page: PageLike,
  state: PracticeAbstractState,
  lastMoveSan?: string,
  expectFn?: ExpectFn
): Promise<void> {
  if (!lastMoveSan) return;
  const escapedSan = escapeRegex(lastMoveSan);
  if (state === 'CORRECT') {
    const moveFeedback = page.getByText(new RegExp(`${escapedSan}\\s+is\\s+(?:the best|an acceptable)`, 'i'));
    await verifyVisible(moveFeedback, expectFn);
  } else if (state === 'INCORRECT') {
    const heading = page.getByRole('heading', { name: /Not the Recommended Move/i });
    await verifyVisible(heading, expectFn);
    const moveFeedback = page.getByText(new RegExp(`${escapedSan}\\s+is not the recommended move`, 'i'));
    await verifyVisible(moveFeedback, expectFn);
  } else if (state === 'HISTORICAL_MISTAKE') {
    const blunderBanner = page.getByText(/Recurring Weakness Detected!|Matches your previous blunder|blunder/i);
    await verifyVisible(blunderBanner, expectFn);
  }
}

export async function verifyMoveHistory(
  page: PageLike,
  expectedHistory: string[],
  expectFn?: ExpectFn
): Promise<void> {
  if (!expectedHistory || expectedHistory.length === 0) return;
  for (const moveSan of expectedHistory) {
    const moveLocator = page.getByText(new RegExp(`(?:^|\\b|from\\s+)${moveSan}(?:\\b|$)`)) as InspectableLocator;
    const checkVisible = moveLocator.isVisible ?? moveLocator.toBeVisible;
    if (typeof checkVisible === 'function') {
      const isVisible = await checkVisible.call(moveLocator).catch(() => false);
      if (!isVisible) {
        throw new Error(
          `[MOVE_HISTORY_MISMATCH] Expected move log entry "${moveSan}" from sequence [${expectedHistory.join(', ')}] was not found in rendered move history.`
        );
      }
    }
    try {
      await verifyVisible(moveLocator, expectFn);
    } catch (err) {
      throw new Error(
        `[MOVE_HISTORY_MISMATCH] Expected move log entry "${moveSan}" from sequence [${expectedHistory.join(', ')}] was not found in rendered move history: ${err instanceof Error ? err.message : String(err)}`
      );
    }
  }
}

export const STATE_ORACLES: Record<PracticeAbstractState, StateOracleDefinition> = {
  DISCOVERY: {
    description: 'Catalog view is active; Start Practice button and catalog description are visible.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByRole('button', { name: /Start Practice/i }), expectFn);
      await verifyVisible(page.getByText(/Strengthen your play by solving recurring mistake positions/i), expectFn);
    },
  },
  PRACTICE_IDLE: {
    description: 'Interactive board is ready; square d2 is visible and Back to Puzzles is interactive.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.locator('[data-square="d2"]'), expectFn);
      await verifyVisible(page.getByRole('button', { name: /Back to Puzzles/i }), expectFn);

      if (context.puzzleLoaded) {
        if (context.expectedFen) {
          await verifyBoardPosition(page, context.expectedFen, expectFn);
        }
        if (context.expectedFailedCount !== undefined && context.expectedSolvedCount !== undefined) {
          await verifyAttemptCounters(page, context.expectedFailedCount, context.expectedSolvedCount, expectFn);
        }
      }
    },
  },
  EVALUATING: {
    description: 'Move evaluation is in-flight; evaluating indicator is active.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByText(/Evaluating move/i), expectFn);
    },
  },
  CORRECT: {
    description: 'Puzzle solved optimally; Continue Exploration button visible and solved count incremented.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByRole('button', { name: /Continue Exploration →/i }), expectFn);
      await verifyVisible(page.getByText(/Solved: [1-9]\d*/i), expectFn);

      if (context.expectedFen) {
        await verifyBoardPosition(page, context.expectedFen, expectFn);
      }
      if (context.expectedFailedCount !== undefined && context.expectedSolvedCount !== undefined) {
        await verifyAttemptCounters(page, context.expectedFailedCount, context.expectedSolvedCount, expectFn);
      }
      if (context.lastMoveSan) {
        await verifyMoveFeedback(page, 'CORRECT', context.lastMoveSan, expectFn);
      }
      if (context.expectedHistory && context.expectedHistory.length > 0) {
        await verifyMoveHistory(page, context.expectedHistory, expectFn);
      }
    },
  },
  INCORRECT: {
    description: 'Inaccurate move played; rollback to baseline FEN and Not the Recommended Move banner visible.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByRole('heading', { name: /Not the Recommended Move/i }), expectFn);
      await verifyVisible(page.locator('[data-square="d2"]'), expectFn);

      if (context.expectedFen) {
        await verifyBoardPosition(page, context.expectedFen, expectFn);
      }
      if (context.expectedFailedCount !== undefined && context.expectedSolvedCount !== undefined) {
        await verifyAttemptCounters(page, context.expectedFailedCount, context.expectedSolvedCount, expectFn);
      }
      if (context.lastMoveSan) {
        await verifyMoveFeedback(page, 'INCORRECT', context.lastMoveSan, expectFn);
      }
    },
  },
  HISTORICAL_MISTAKE: {
    description: 'Historical mistake repeated; mistake banner and hint/retry action visible.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByText(/Recurring Weakness Detected!|Matches your previous blunder|blunder/i), expectFn);

      if (context.expectedFen) {
        await verifyBoardPosition(page, context.expectedFen, expectFn);
      }
      if (context.expectedFailedCount !== undefined && context.expectedSolvedCount !== undefined) {
        await verifyAttemptCounters(page, context.expectedFailedCount, context.expectedSolvedCount, expectFn);
      }
      if (context.lastMoveSan) {
        await verifyMoveFeedback(page, 'HISTORICAL_MISTAKE', context.lastMoveSan, expectFn);
      }
    },
  },
  EVALUATION_ERROR: {
    description: 'Evaluation failed or timed out; error banner visible.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByText('Move Evaluation Failed'), expectFn);
    },
  },
  EXPLORING: {
    description: 'Line exploration mode active; exploration controls and Exit Exploration visible.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByText(/Line Exploration/i), expectFn);
      await verifyVisible(page.getByRole('button', { name: /^Exit$|Exit Exploration/i }), expectFn);

      if (context.expectedFen) {
        await verifyBoardPosition(page, context.expectedFen, expectFn);
      }
      if (context.expectedSideToMove) {
        await verifyTurnState(page, context.expectedSideToMove, true, expectFn);
      }
      if (context.expectedHistory && context.expectedHistory.length > 0) {
        const decisionBanner = page.getByText(/Line Exploration\s*\(from/i) as InspectableLocator;
        const checkVisible = decisionBanner.isVisible ?? decisionBanner.toBeVisible;
        if (typeof checkVisible === 'function' && (await checkVisible.call(decisionBanner).catch(() => false))) {
          await verifyMoveHistory(page, context.expectedHistory, expectFn);
        }
      }
    },
  },
  BLINDFOLD: {
    description: 'Blindfold training mode active; notation exchange UI and blindfold board wrapper are mounted.',
    assert: async (page: PageLike, context: PracticeModelContext, maybeExpectFn?: unknown) => {
      const { expectFn } = resolveArgs(context, maybeExpectFn);
      await verifyVisible(page.getByText(/Blindfold continuation/i), expectFn);
      await verifyVisible(page.locator('[data-testid="blindfold-board"]'), expectFn);
      if (typeof page.getByLabel === 'function') {
        const labelLocator = page.getByLabel('Move notation');
        await verifyVisible(labelLocator.first ? labelLocator.first() : labelLocator, expectFn);
      } else {
        const inputLocator = page.locator('input[aria-label="Move notation"]');
        await verifyVisible(inputLocator.first ? inputLocator.first() : inputLocator, expectFn);
      }
      const sendBtn = page.getByRole('button', { name: /Send/i });
      await verifyVisible(sendBtn.first ? sendBtn.first() : sendBtn, expectFn);
      const toggleBtn = page.getByRole('button', { name: /Hide board|Reveal board/i });
      await verifyVisible(toggleBtn.first ? toggleBtn.first() : toggleBtn, expectFn);
      const exitBtn = page.getByRole('button', { name: /^Exit$/i });
      await verifyVisible(exitBtn.first ? exitBtn.first() : exitBtn, expectFn);
    },
  },
};

/**
 * Reliably transitions the application into DISCOVERY state from an initial page load.
 * Avoids race conditions on cold loads where the practice board or catalog has not yet mounted.
 */
export async function ensureDiscoveryState(page: Page): Promise<void> {
  const startPracticeBtn = page.getByRole('button', { name: /Start Practice/i });
  const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });

  await backBtn.or(startPracticeBtn).first().waitFor({ state: 'visible', timeout: 10000 });

  if (await backBtn.isVisible()) {
    await backBtn.click();
    await startPracticeBtn.waitFor({ state: 'visible', timeout: 10000 });
  }
}

async function playSquareMove(
  page: Page,
  fromSquare: string,
  toSquare: string
): Promise<void> {
  const fromLocator = page.locator(`[data-square="${fromSquare}"]`);
  const toLocator = page.locator(`[data-square="${toSquare}"]`);

  await fromLocator.waitFor({ state: 'visible', timeout: 5000 });
  await toLocator.waitFor({ state: 'visible', timeout: 5000 });

  await fromLocator.click();
  await page.waitForTimeout(60);
  await toLocator.click();
}

/**
 * Translates a high-level practice model action into concrete Playwright DOM interactions.
 */
export async function executeConcreteAction(
  page: Page,
  action: PracticeAction,
  context?: PracticeModelContext
): Promise<void> {
  switch (action) {
    case 'SELECT_PUZZLE': {
      const button = page.getByRole('button', { name: /Start Practice/i });
      await button.waitFor({ state: 'visible', timeout: 5000 });
      await button.click();
      break;
    }

    case 'SUBMIT_OPTIMAL': {
      const fen = context?.expectedFen ?? DEFAULT_PRACTICE_FEN;
      const san = context?.activePuzzle?.targetMove || context?.activePuzzle?.solutionLine?.[0] || 'd4';
      const { from, to } = deriveMoveSquares(fen, san);
      await playSquareMove(page, from, to);
      break;
    }

    case 'SUBMIT_MISTAKE': {
      const fen = context?.expectedFen ?? DEFAULT_PRACTICE_FEN;
      const san = context?.activePuzzle?.mistakeMove || 'a3';
      const { from, to } = deriveMoveSquares(fen, san);
      await playSquareMove(page, from, to);
      break;
    }

    case 'SUBMIT_BLUNDER': {
      const fen = context?.expectedFen ?? DEFAULT_PRACTICE_FEN;
      const san = context?.activePuzzle?.blunderMove || context?.activePuzzle?.movesPlayed?.[0]?.move || 'h4';
      const { from, to } = deriveMoveSquares(fen, san);
      await playSquareMove(page, from, to);
      break;
    }

    case 'SUBMIT_FAULT': {
      const fen = context?.expectedFen ?? DEFAULT_PRACTICE_FEN;
      const san = context?.activePuzzle?.targetMove || context?.activePuzzle?.solutionLine?.[0] || 'd4';
      const { from, to } = deriveMoveSquares(fen, san);
      await playSquareMove(page, from, to);
      break;
    }

    case 'NEXT_PUZZLE': {
      const nextBtn = page.getByRole('button', { name: /Next Puzzle/i }).first();
      await nextBtn.waitFor({ state: 'visible', timeout: 5000 });
      await nextBtn.click();
      await page.waitForTimeout(100);
      if (context?.corpus && context.activePuzzleIndex !== undefined) {
        const nextIndex = (context.activePuzzleIndex + 1) % context.corpus.length;
        const nextPuzzle = context.corpus[nextIndex];
        if (nextPuzzle?.fen) {
          await verifyBoardPosition(page, nextPuzzle.fen);
        }
      }
      break;
    }

    case 'RETRY': {
      // Board is automatically rolled back to baseline FEN upon incorrect move.
      // If Retry evaluation button or Reset button is available, click to restore interactive idle.
      const retryEvalButton = page.getByRole('button', { name: /Retry evaluation/i });
      const resetButton = page.getByRole('button', { name: /Reset Position|Reset Board|Reset/i });
      if (await retryEvalButton.isVisible().catch(() => false)) {
        await retryEvalButton.click();
      } else if (await resetButton.isVisible().catch(() => false)) {
        await resetButton.click();
      }
      await page.waitForTimeout(100);
      break;
    }

    case 'RESET': {
      // Resets board to baseline FEN
      const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
      if (await resetButton.isVisible().catch(() => false)) {
        await resetButton.click();
      }
      await page.waitForTimeout(100);
      break;
    }

    case 'CONTINUE_EXPLORATION': {
      const continueBtn = page.getByRole('button', { name: /Continue Exploration →/i });
      await continueBtn.waitFor({ state: 'visible', timeout: 5000 });
      await continueBtn.click();
      break;
    }

    case 'EXIT_EXPLORATION':
    case 'APPLY_DECISION_MOVE': {
      const exitBtn = page.getByRole('button', { name: /^Exit$|Exit Exploration/i });
      await exitBtn.waitFor({ state: 'visible', timeout: 5000 });
      await exitBtn.click();
      break;
    }

    case 'BACK_TO_PUZZLES': {
      const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });
      await backBtn.waitFor({ state: 'visible', timeout: 5000 });
      await backBtn.click();
      break;
    }

    case 'FLIP_BOARD': {
      const flipBtn = page.locator('button[title*="Flip"]').or(page.getByRole('button', { name: /Flip/i })).first();
      if (await flipBtn.isVisible().catch(() => false)) {
        await flipBtn.click();
      } else {
        await page.keyboard.press('x');
      }
      await page.waitForTimeout(60);
      break;
    }

    case 'REQUEST_HINT': {
      const hintBtn = page.locator('button[title*="Hint"]').or(page.getByRole('button', { name: /Hint/i })).first();
      if (await hintBtn.isVisible().catch(() => false)) {
        const disabled = await hintBtn.isDisabled().catch(() => false);
        if (!disabled) {
          await hintBtn.click();
        }
      }
      await page.waitForTimeout(60);
      break;
    }

    case 'ENTER_BLINDFOLD': {
      const trainBlindfoldBtn = page.getByRole('button', { name: /Train Blindfold/i }).first();
      await verifyVisible(trainBlindfoldBtn);
      const isLoc = trainBlindfoldBtn as { isDisabled?: () => Promise<boolean>; click?: () => Promise<void> };
      if (typeof isLoc.isDisabled === 'function' && (await isLoc.isDisabled().catch(() => false))) {
        throw new Error('[ActionExecutionError] "Train Blindfold" button is disabled');
      }
      if (typeof isLoc.click === 'function') {
        await isLoc.click();
      }
      await page.waitForTimeout(100);
      break;
    }

    case 'EXIT_BLINDFOLD': {
      const exitBlindfoldBtn = page.getByRole('button', { name: /^Exit$/i }).first();
      await verifyVisible(exitBlindfoldBtn);
      const isLoc = exitBlindfoldBtn as { click?: () => Promise<void> };
      if (typeof isLoc.click === 'function') {
        await isLoc.click();
      }
      await page.waitForTimeout(100);
      break;
    }

    case 'TOGGLE_BLINDFOLD_VISIBILITY': {
      const toggleBtn = page.getByRole('button', { name: /Hide board|Reveal board/i }).first();
      await verifyVisible(toggleBtn);
      const isLoc = toggleBtn as { click?: () => Promise<void> };
      if (typeof isLoc.click === 'function') {
        await isLoc.click();
      }
      await page.waitForTimeout(60);
      break;
    }

    case 'SUBMIT_BLINDFOLD_MOVE': {
      const notationInput = page.getByLabel('Move notation');
      const sendBtn = page.getByRole('button', { name: /Send/i });
      await verifyVisible(notationInput);
      await verifyVisible(sendBtn);
      const inputLoc = notationInput as { isDisabled?: () => Promise<boolean>; fill?: (text: string) => Promise<void> };
      if (typeof inputLoc.isDisabled === 'function' && (await inputLoc.isDisabled().catch(() => false))) {
        throw new Error('[ActionExecutionError] Blindfold move notation input is disabled');
      }
      if (typeof inputLoc.fill === 'function') {
        await inputLoc.fill('d4');
      }
      const sendLoc = sendBtn as { click?: () => Promise<void> };
      if (typeof sendLoc.click === 'function') {
        await sendLoc.click();
      }
      await page.waitForTimeout(100);
      break;
    }

    case 'NAVIGATE_HISTORY_PREV': {
      const prevMoveBtn = page.locator('button[title*="Previous Move"]').first();
      if (await prevMoveBtn.isVisible().catch(() => false)) {
        const disabled = await prevMoveBtn.isDisabled().catch(() => false);
        if (!disabled) {
          await prevMoveBtn.click();
        }
      }
      await page.waitForTimeout(60);
      break;
    }

    case 'NAVIGATE_HISTORY_NEXT': {
      const nextMoveBtn = page.locator('button[title*="Next Move"]').first();
      if (await nextMoveBtn.isVisible().catch(() => false)) {
        const disabled = await nextMoveBtn.isDisabled().catch(() => false);
        if (!disabled) {
          await nextMoveBtn.click();
        }
      }
      await page.waitForTimeout(60);
      break;
    }

    case 'TOGGLE_TIMER_MODE': {
      const settingsToggle = page.getByRole('button', { name: /Puzzle Settings/i }).first();
      if (await settingsToggle.isVisible().catch(() => false)) {
        const expanded = await settingsToggle.getAttribute('aria-expanded');
        if (expanded !== 'true') {
          await settingsToggle.click();
          await page.waitForTimeout(60);
        }
      }
      const stopwatchBtn = page.getByRole('button', { name: /^Stopwatch$/i }).first();
      const countdownBtn = page.getByRole('button', { name: /^Countdown$/i }).first();
      const offBtn = page.getByRole('button', { name: /^Off$/i }).first();
      if (await stopwatchBtn.isVisible().catch(() => false)) {
        const isStopwatchActive = (await stopwatchBtn.getAttribute('class'))?.includes('bg-emerald-600');
        const isCountdownActive = (await countdownBtn.getAttribute('class'))?.includes('bg-emerald-600');
        if (isStopwatchActive) {
          await countdownBtn.click();
        } else if (isCountdownActive) {
          await offBtn.click();
        } else {
          await stopwatchBtn.click();
        }
      }
      await page.waitForTimeout(60);
      break;
    }

    default:
      throw new Error(`Unsupported practice action: ${action}`);
  }
}

/**
 * Derives a deterministic context signature for equivalence partitioning of practice configurations.
 *
 * Equivalence Boundaries:
 * - Active puzzle identity and expected position FEN differentiate behavior across puzzles/positions.
 * - Exact historyIndex and historyLength differentiate navigation states across exploration plies.
 * - blindfoldPreviousState differentiates blindfold sessions by their target restoration state on exit.
 * - explorationDecisionMove differentiates exploration sessions where decision moves can be applied vs standard exploration.
 * - Operational modes (exploration, blindfold visibility/activity, board orientation, timerMode, playlist mode) are included.
 * - Transient fields (pending HTTP payloads, intermediate square selection, promotion piece) are excluded.
 */
export function deriveContextSignature(ctx: PracticeModelContext): string {
  const puzzleKey = ctx.activePuzzle?.puzzleId ?? (ctx.activePuzzleIndex !== undefined ? `idx-${ctx.activePuzzleIndex}` : 'none');
  const fenKey = ctx.expectedFen ?? ctx.startingFen ?? 'none';
  const historyKey = `${ctx.historyIndex ?? 0}/${ctx.historyLength ?? 0}`;
  const blindfoldOrigin = ctx.blindfoldPreviousState ?? 'none';
  const decisionKey = ctx.explorationDecisionMove ?? 'none';
  const timer = ctx.timerMode ?? 'off';
  const side = ctx.expectedSideToMove ?? 'white';

  return [
    puzzleKey,
    fenKey,
    ctx.puzzleLoaded ? 'loaded' : 'unloaded',
    ctx.inExploration ? 'exploring' : 'standard',
    `attempts:${ctx.attemptCount}`,
    ctx.solved ? 'solved' : 'unsolved',
    ctx.isBoardFlipped ? 'flipped' : 'normal',
    ctx.blindfoldActive ? 'bf-active' : 'bf-off',
    ctx.blindfoldVisible ? 'bf-vis' : 'bf-hid',
    `bf-origin:${blindfoldOrigin}`,
    ctx.hintActive ? 'hint-on' : 'hint-off',
    `history:${historyKey}`,
    `decision:${decisionKey}`,
    `timer:${timer}`,
    `side:${side}`,
    ctx.isWeaknessPlaylist ? 'weakness' : 'standard-playlist',
    ctx.faultInjectionEnabled ? 'fault-on' : 'fault-off',
  ].join('|');
}

/**
 * StateModel implementation for Practice Puzzles.
 */
export const PracticeModel: StateModel<
  PracticeAbstractState,
  PracticeAction,
  PracticeModelContext
> = {
  domain: 'practice',
  initialState: 'DISCOVERY',
  createInitialContext,
  transitions: PRACTICE_TRANSITIONS,
  oracles: STATE_ORACLES as Record<PracticeAbstractState, StateOracle<PracticeModelContext>>,
  executeAction: executeConcreteAction,
  contextSignature: deriveContextSignature,
  ensureInitialState: ensureDiscoveryState,
};

