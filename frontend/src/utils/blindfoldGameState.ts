import { Chess } from 'chess.js';

export type BlindfoldTurn = 'PLAYER' | 'CHESSECHO';

export type BlindfoldPlayerColor = 'WHITE' | 'BLACK';

export type BlindfoldTerminalReason = 'CHECKMATE' | 'STALEMATE' | 'DRAW';

export type BlindfoldWinner = 'PLAYER' | 'CHESSECHO' | 'DRAW';

export interface BlindfoldMove {
  san: string;
  fen: string;
  timestamp: number;
}

export interface BlindfoldPlayerMoveFeedback {
  san: string;
  evalLoss?: number;
  acceptable?: boolean;
  feedbackText?: string;
}

export interface BlindfoldGameState {
  initialFen: string;
  currentFen: string;
  moveHistory: BlindfoldMove[];
  currentTurn: BlindfoldTurn;
  isVisible: boolean;
  notationInput: string;
  notationError: string | null;
  lastPlayerMove: BlindfoldPlayerMoveFeedback | null;
  lastChessEchoMove: { san: string } | null;
  moveCount: number;
  isLoading: boolean;
  isFailed: boolean;
  isTerminal?: boolean;
  terminalReason?: BlindfoldTerminalReason;
  winner?: BlindfoldWinner;
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
  if (state.isTerminal) return illegalMove('game is complete');

  const notation = sanMove.trim();
  if (!notation) return illegalMove('notation is empty');

  try {
    const game = new Chess(currentFen);
    const move = game.move(notation);
    const nextMove = { san: move.san, fen: game.fen(), timestamp: Date.now() };

    let isTerminal = false;
    let terminalReason: BlindfoldTerminalReason | undefined;
    let winner: BlindfoldWinner | undefined;

    if (game.isGameOver()) {
      isTerminal = true;
      if (game.isCheckmate()) {
        terminalReason = 'CHECKMATE';
        winner = turn === 'PLAYER' ? 'PLAYER' : 'CHESSECHO';
      } else if (game.isStalemate()) {
        terminalReason = 'STALEMATE';
        winner = 'DRAW';
      } else {
        terminalReason = 'DRAW';
        winner = 'DRAW';
      }
    }

    return {
      ...state,
      currentFen: nextMove.fen,
      moveHistory: [...state.moveHistory, nextMove],
      currentTurn: isTerminal ? state.currentTurn : (turn === 'PLAYER' ? 'CHESSECHO' : 'PLAYER'),
      notationInput: '',
      notationError: null,
      lastPlayerMove: turn === 'PLAYER' ? { san: move.san } : state.lastPlayerMove,
      lastChessEchoMove: turn === 'CHESSECHO' ? { san: move.san } : state.lastChessEchoMove,
      moveCount: state.moveCount + 1,
      isFailed: false,
      isTerminal,
      terminalReason,
      winner,
    };
  } catch (error) {
    return illegalMove(error);
  }
}

function openingTurn(game: Chess, playerColor?: BlindfoldPlayerColor): BlindfoldTurn {
  if (!playerColor) return 'PLAYER';
  const playerSide = playerColor === 'WHITE' ? 'w' : 'b';
  return game.turn() === playerSide ? 'PLAYER' : 'CHESSECHO';
}

export function createBlinfoldGameState(
  initialFen: string,
  playerColor?: BlindfoldPlayerColor,
): BlindfoldGameState {
  const game = new Chess(initialFen);
  return {
    initialFen,
    currentFen: initialFen,
    moveHistory: [],
    currentTurn: openingTurn(game, playerColor),
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
  playerColor?: BlindfoldPlayerColor,
): BlindfoldGameState {
  return {
    ...createBlinfoldGameState(initialFen, playerColor),
    isVisible: state.isVisible,
  };
}

export function getAccumulatedPosition(
  state: BlindfoldGameState,
): { fen: string; moveCount: number } {
  return { fen: state.currentFen, moveCount: state.moveCount };
}

export function toggleBlinfoldVisibility(state: BlindfoldGameState): BlindfoldGameState {
  return { ...state, isVisible: !state.isVisible };
}

export function setBlinfoldVisibility(state: BlindfoldGameState, isVisible: boolean): BlindfoldGameState {
  return { ...state, isVisible };
}

export function setPlayerMoveEvaluation(
  state: BlindfoldGameState,
  evalLoss?: number,
  acceptable?: boolean,
  feedbackText?: string,
): BlindfoldGameState {
  if (!state.lastPlayerMove) return state;
  return {
    ...state,
    lastPlayerMove: {
      ...state.lastPlayerMove,
      ...(evalLoss !== undefined ? { evalLoss } : {}),
      ...(acceptable !== undefined ? { acceptable } : {}),
      ...(feedbackText !== undefined ? { feedbackText } : {}),
    },
  };
}

export function rebuildPositionFromHistory(
  initialFen: string,
  history: Array<{ san: string }>,
): string {
  const game = new Chess(initialFen);
  for (const move of history) game.move(move.san);
  return game.fen();
}
