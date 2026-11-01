import { describe, it, expect } from 'vitest';
import { toCanonicalMove, areMovesEqual } from '../utils/chessMove';

describe('chessMove utility', () => {
  const startFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  const promoFen = '8/4P3/8/8/8/8/8/4K2k w - - 0 1';

  it('normalizes SAN and UCI moves to the same canonical identity', () => {
    const fromSan = toCanonicalMove(startFen, 'e4');
    const fromUci = toCanonicalMove(startFen, 'e2e4');
    const fromObj = toCanonicalMove(startFen, { from: 'e2', to: 'e4' });

    expect(fromSan).not.toBeNull();
    expect(fromUci).not.toBeNull();
    expect(fromObj).not.toBeNull();

    expect(fromSan).toEqual({
      from: 'e2',
      to: 'e4',
      promotion: null,
      san: 'e4',
    });

    expect(areMovesEqual(fromSan, fromUci)).toBe(true);
    expect(areMovesEqual(fromSan, fromObj)).toBe(true);
  });

  it('normalizes promotion moves with and without equals signs and coordinate formats', () => {
    const m1 = toCanonicalMove(promoFen, 'e8=Q');
    const m2 = toCanonicalMove(promoFen, 'e8Q');
    const m3 = toCanonicalMove(promoFen, 'e7e8q');
    const m4 = toCanonicalMove(promoFen, { from: 'e7', to: 'e8', promotion: 'q' });

    expect(m1).not.toBeNull();
    expect(m2).not.toBeNull();
    expect(m3).not.toBeNull();
    expect(m4).not.toBeNull();

    expect(areMovesEqual(m1, m2)).toBe(true);
    expect(areMovesEqual(m1, m3)).toBe(true);
    expect(areMovesEqual(m1, m4)).toBe(true);
    expect(m1?.promotion).toBe('q');
  });

  it('distinguishes different promotions or destinations', () => {
    const queenPromo = toCanonicalMove(promoFen, 'e8=Q');
    const knightPromo = toCanonicalMove(promoFen, 'e8=N');
    const d4 = toCanonicalMove(startFen, 'd4');
    const e4 = toCanonicalMove(startFen, 'e4');

    expect(areMovesEqual(queenPromo, knightPromo)).toBe(false);
    expect(areMovesEqual(d4, e4)).toBe(false);
  });

  it('returns null safely for illegal or unparseable moves without throwing', () => {
    expect(toCanonicalMove(startFen, 'e5')).toBeNull(); // Illegal for white on move 1
    expect(toCanonicalMove(startFen, 'garbage')).toBeNull();
    expect(toCanonicalMove(startFen, '')).toBeNull();
    expect(toCanonicalMove('invalid-fen', 'e4')).toBeNull();
    expect(toCanonicalMove(startFen, null)).toBeNull();
    expect(toCanonicalMove(startFen, undefined)).toBeNull();
  });
});
