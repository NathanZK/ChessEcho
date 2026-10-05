import { describe, expect, it } from 'vitest';
import { cloneChessGameWithHistory, createChessGameAtPosition } from '../utils/chessGame';

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const AFTER_E4_E5_NF3_FEN = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2';

describe('Chess history for imported positions', () => {
  it('replays main-line SAN history from the standard starting position', () => {
    const game = createChessGameAtPosition(AFTER_E4_E5_NF3_FEN, ['e4', 'e5', 'Nf3']);

    expect(game.fen()).toBe(AFTER_E4_E5_NF3_FEN);
    expect(game.history()).toEqual(['e4', 'e5', 'Nf3']);
  });

  it('replays history from a PGN setup FEN', () => {
    const startFen = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2';
    const finalFen = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
    const game = createChessGameAtPosition(finalFen, ['Nf3', 'Nc6'], startFen);

    expect(game.fen()).toBe(finalFen);
    expect(game.history()).toEqual(['Nf3', 'Nc6']);
  });

  it('preserves seeded history when cloning and extending an exploration line', () => {
    const initialGame = createChessGameAtPosition(AFTER_E4_E5_NF3_FEN, ['e4', 'e5', 'Nf3']);
    const continuedGame = cloneChessGameWithHistory(initialGame);
    continuedGame.move('Nf6');

    expect(continuedGame.history()).toEqual(['e4', 'e5', 'Nf3', 'Nf6']);
  });

  it('falls back to the exact selected FEN if PGN history cannot be replayed', () => {
    const game = createChessGameAtPosition(AFTER_E4_E5_NF3_FEN, ['not a move']);

    expect(game.fen()).toBe(AFTER_E4_E5_NF3_FEN);
    expect(game.history()).toEqual([]);
  });

  it('uses the selected FEN directly when there is no move history', () => {
    const game = createChessGameAtPosition(START_FEN, []);

    expect(game.fen()).toBe(START_FEN);
    expect(game.history()).toEqual([]);
  });
});
