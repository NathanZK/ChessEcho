import { Chess } from 'chess.js';

export type BlindfoldTurn = 'PLAYER' | 'CHESSECHO';

export interface BlindfoldMove {
  san: string;
  fen: string;
  timestamp: number;
}

export interface BlindfoldGameState {
  initialFen: string;
  currentFen: string;
  moveHistory: BlindfoldMove[];
  currentTurn: BlindfoldTurn;
  isVisible: boolean;
  notationInput: string;
  notationError: string | null;
  lastPlayerMove: { san: string; evalLoss?: number } | null;
  lastChessEchoMove: { san: string } | null;
  moveCount: number;
  isLoading: boolean;
  isFailed: boolean;
}

/** @deprecated Preserve the feature's original public name for existing callers. */
export type BlinfoldGameState = BlindfoldGameState;

export type BlindfoldMoveResult = BlindfoldGameState | { error: string };

const illegalMove = (error: unknown): { error: string } => ({
  error: `Illegal move: ${error instanceof Error ? error.message : 'invalid notation'}`,
});

function applyMove(
  state: BlindfoldGameState,
  sanMove: string,
  currentFen: string,
  turn: BlindfoldTurn,
): BlindfoldMoveResult {
  const notation = sanMove.trim();
  if (!notation) return illegalMove('notation is empty');

  try {
    const game = new Chess(currentFen);
    const move = game.move(notation);
    const nextMove = { san: move.san, fen: game.fen(), timestamp: Date.now() };
    return {
      ...state,
      currentFen: nextMove.fen,
      moveHistory: [...state.moveHistory, nextMove],
      currentTurn: turn === 'PLAYER' ? 'CHESSECHO' : 'PLAYER',
      notationInput: '',
      notationError: null,
      lastPlayerMove: turn === 'PLAYER' ? { san: move.san } : state.lastPlayerMove,
      lastChessEchoMove: turn === 'CHESSECHO' ? { san: move.san } : state.lastChessEchoMove,
      moveCount: state.moveCount + 1,
      isFailed: false,
    };
  } catch (error) {
    return illegalMove(error);
  }
}

export function createBlinfoldGameState(initialFen: string): BlindfoldGameState {
  new Chess(initialFen);
  return {
    initialFen,
    currentFen: initialFen,
    moveHistory: [],
    currentTurn: 'PLAYER',
    isVisible: false,
    notationInput: '',
    notationError: null,
    lastPlayerMove: null,
    lastChessEchoMove: null,
    moveCount: 0,
    isLoading: false,
    isFailed: false,
  };
}

export function applyPlayerMove(
  state: BlindfoldGameState,
  sanMove: string,
  currentFen: string,
): BlindfoldMoveResult {
  return applyMove(state, sanMove, currentFen, 'PLAYER');
}

export function applyChessEchoMove(
  state: BlindfoldGameState,
  sanMove: string,
  currentFen: string,
): BlindfoldMoveResult {
  return applyMove(state, sanMove, currentFen, 'CHESSECHO');
}

export function resetBlinfoldGame(
  state: BlindfoldGameState,
  initialFen: string,
): BlindfoldGameState {
  return {
    ...createBlinfoldGameState(initialFen),
    isVisible: state.isVisible,
  };
}

export function getAccumulatedPosition(
  state: BlindfoldGameState,
): { fen: string; moveCount: number } {
  return { fen: state.currentFen, moveCount: state.moveCount };
}

export function rebuildPositionFromHistory(
  initialFen: string,
  history: Array<{ san: string }>,
): string {
  const game = new Chess(initialFen);
  for (const move of history) game.move(move.san);
  return game.fen();
}
