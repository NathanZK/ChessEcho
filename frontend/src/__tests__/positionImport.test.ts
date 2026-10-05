import { describe, expect, it } from 'vitest';
import { parseFenInput, parsePgnMainLine } from '../utils/positionImport';

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

describe('parseFenInput', () => {
  it('trims and accepts a valid FEN', () => {
    expect(parseFenInput(`  ${START_FEN}  `)).toEqual({ ok: true, fen: START_FEN });
  });

  it.each(['', 'not a fen', '8/8/8/8/8/8/8/8 w - -'])(
    'rejects invalid FEN input %j',
    (input) => {
      expect(parseFenInput(input).ok).toBe(false);
    },
  );
});

describe('parsePgnMainLine', () => {
  it('returns the start position and each main-line ply with exact FEN and SAN history', () => {
    const result = parsePgnMainLine('1. e4 e5 2. Nf3');

    expect(result).toEqual({
      ok: true,
      startFen: START_FEN,
      plies: [
        {
          ply: 0,
          moveNumber: 1,
          color: 'w',
          san: '',
          sanHistory: [],
          fen: START_FEN,
          label: 'Start',
        },
        {
          ply: 1,
          moveNumber: 1,
          color: 'w',
          san: 'e4',
          sanHistory: ['e4'],
          fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
          label: '1. e4',
        },
        {
          ply: 2,
          moveNumber: 1,
          color: 'b',
          san: 'e5',
          sanHistory: ['e4', 'e5'],
          fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
          label: '1... e5',
        },
        {
          ply: 3,
          moveNumber: 2,
          color: 'w',
          san: 'Nf3',
          sanHistory: ['e4', 'e5', 'Nf3'],
          fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
          label: '2. Nf3',
        },
      ],
    });
  });

  it('accepts an empty game and still offers its initial position', () => {
    expect(parsePgnMainLine('[Event "No moves"]')).toEqual({
      ok: true,
      startFen: START_FEN,
      plies: [
        {
          ply: 0,
          moveNumber: 1,
          color: 'w',
          san: '',
          sanHistory: [],
          fen: START_FEN,
          label: 'Start',
        },
      ],
    });
  });

  it('preserves a PGN setup FEN as the start of its selectable history', () => {
    const startFen = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2';
    const result = parsePgnMainLine(
      `[Event "Custom setup"]\n[SetUp "1"]\n[FEN "${startFen}"]\n\n2. Nf3 Nc6`,
    );

    expect(result).toMatchObject({
      ok: true,
      startFen,
      plies: [
        { ply: 0, fen: startFen, sanHistory: [], label: 'Start' },
        { ply: 1, fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2', sanHistory: ['Nf3'] },
        { ply: 2, sanHistory: ['Nf3', 'Nc6'], label: '2... Nc6' },
      ],
    });
  });

  it.each(['', 'not valid PGN'])('rejects malformed PGN input %j', (input) => {
    expect(parsePgnMainLine(input).ok).toBe(false);
  });

  it('rejects a second game with a specific error', () => {
    expect(parsePgnMainLine('1. e4 e5 1-0\n\n[Event "Second game"]\n\n1. d4 d5 0-1')).toEqual({
      ok: false,
      error: "Only one game is supported — paste a single game's PGN.",
    });
  });

  it('rejects variations without treating parenthesized comments as variations', () => {
    expect(parsePgnMainLine('1. e4 (1. d4) e5')).toEqual({
      ok: false,
      error: "PGN variations aren't supported — paste just the main line.",
    });
    expect(parsePgnMainLine('1. e4 {a note (not a variation)} e5').ok).toBe(true);
  });
});
