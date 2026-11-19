import type { Page } from '@playwright/test';
import { Chess } from 'chess.js';

/**
 * Small, model-independent DOM helpers shared by deterministic practice journeys.
 * These are the only pieces of the retired practice model that had reuse value.
 */

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

/** Plays a move through the board's square-click interaction. */
export async function playSquareMove(
  page: Page,
  fromSquare: string,
  toSquare: string
): Promise<void> {
  await page.locator(`[data-square="${fromSquare}"]`).click();
  await page.waitForTimeout(60);
  await page.locator(`[data-square="${toSquare}"]`).click();
}

/** Plays a SAN move on the practice board, deriving its squares from the FEN. */
export async function playSanMove(page: Page, fen: string, san: string): Promise<void> {
  const { from, to } = deriveMoveSquares(fen, san);
  await playSquareMove(page, from, to);
}
