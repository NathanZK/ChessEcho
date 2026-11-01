import { Chess } from 'chess.js';

export interface CanonicalMove {
  from: string;
  to: string;
  promotion: string | null;
  san: string;
}

function parseStringMove(chess: Chess, trimmed: string) {
  try {
    return chess.move(trimmed);
  } catch {
    const uciMatch = /^([a-h][1-8])([a-h][1-8])([qrbnQRBN])?$/.exec(trimmed);
    if (!uciMatch) return null;
    try {
      return chess.move({
        from: uciMatch[1],
        to: uciMatch[2],
        promotion: uciMatch[3]?.toLowerCase(),
      });
    } catch {
      return null;
    }
  }
}

/**
 * Normalizes a chess move representation (SAN, UCI, or coordinate object)
 * into a canonical coordinate identity (from, to, promotion) strictly against
 * the provided immutable reference FEN.
 *
 * Returns null if the FEN is invalid, or if the move is illegal/unparseable.
 */
export function toCanonicalMove(
  fen: string,
  moveInput: string | { from: string; to: string; promotion?: string } | null | undefined
): CanonicalMove | null {
  if (!fen || !moveInput) return null;
  try {
    const chess = new Chess(fen);
    let moveObj = null;

    if (typeof moveInput === 'string') {
      const trimmed = moveInput.trim();
      if (!trimmed) return null;
      moveObj = parseStringMove(chess, trimmed);
    } else {
      // Object input { from, to, promotion }
      moveObj = chess.move({
        from: moveInput.from,
        to: moveInput.to,
        promotion: moveInput.promotion?.toLowerCase(),
      });
    }

    if (!moveObj) return null;

    return {
      from: moveObj.from,
      to: moveObj.to,
      promotion: moveObj.promotion ? moveObj.promotion.toLowerCase() : null,
      san: moveObj.san,
    };
  } catch {
    return null;
  }
}

/**
 * Compares two moves for canonical identity (origin, destination, promotion piece).
 */
export function areMovesEqual(
  m1: CanonicalMove | null | undefined,
  m2: CanonicalMove | null | undefined
): boolean {
  if (!m1 || !m2) return false;
  return m1.from === m2.from && m1.to === m2.to && m1.promotion === m2.promotion;
}
