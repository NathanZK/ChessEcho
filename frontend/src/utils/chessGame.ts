import { Chess, DEFAULT_POSITION } from 'chess.js';

export function createChessGameAtPosition(
  selectedFen: string,
  sanHistory: string[],
  historyStartFen?: string,
): Chess {
  if (sanHistory.length === 0) {
    return new Chess(selectedFen);
  }

  try {
    const game = new Chess(historyStartFen ?? DEFAULT_POSITION);
    for (const san of sanHistory) {
      game.move(san);
    }
    return game.fen() === selectedFen ? game : new Chess(selectedFen);
  } catch {
    return new Chess(selectedFen);
  }
}

export function cloneChessGameWithHistory(game: Chess): Chess {
  try {
    const clone = new Chess();
    clone.loadPgn(game.pgn());
    return clone.fen() === game.fen() ? clone : new Chess(game.fen());
  } catch {
    return new Chess(game.fen());
  }
}

export function applyChessMoveOrUseFen(game: Chess, move: string, resultingFen: string): Chess {
  try {
    const nextGame = cloneChessGameWithHistory(game);
    nextGame.move(move);
    return nextGame.fen() === resultingFen ? nextGame : new Chess(resultingFen);
  } catch {
    return new Chess(resultingFen);
  }
}
