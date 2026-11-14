/**
 * Utility formatting functions for decimal values, pawn evaluation loss,
 * score rates, and timers across the UI.
 */

/**
 * Format a number to a fixed number of decimals with a dot separator.
 * Non-finite numbers (null, undefined, NaN, Infinity) safely fall back to 0.
 */
export function formatDecimal(
  val: number | null | undefined,
  decimals: number = 2
): string {
  const safeVal = typeof val === 'number' && Number.isFinite(val) ? val : 0;
  return safeVal.toFixed(decimals).replace(',', '.');
}

/**
 * Format evaluation pawn loss to fixed decimal places (defaults to 2).
 */
export function formatPawnLoss(
  val: number | null | undefined,
  decimals: number = 2
): string {
  return formatDecimal(val, decimals);
}

/**
 * Format a score rate ratio (e.g. 0.625) as a percentage with 1 decimal place (e.g. "62.5%").
 * Returns the fallback string (defaulting to "0.0%") if rate is null, undefined, or not finite.
 */
export function formatScoreRate(
  rate: number | null | undefined,
  fallback: string = '0.0%'
): string {
  if (typeof rate === 'number' && Number.isFinite(rate)) {
    return `${(rate * 100).toFixed(1)}%`;
  }
  return fallback;
}

/**
 * Format milliseconds into "m:ss" with zero-padded seconds.
 * Clamps negative milliseconds to 0.
 */
export function formatTimerMs(ms: number): string {
  const safeMs = typeof ms === 'number' && Number.isFinite(ms) ? ms : 0;
  const clamped = Math.max(0, Math.round(safeMs));
  const totalSeconds = Math.floor(clamped / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

/**
 * Calculates percentage height for White's portion of the evaluation bar (50% is equal 0.0).
 * Capped between -10.0 and +10.0 pawns (-1000 to +1000 centipawns):
 * +10 -> 100% white, 0 -> 50% white, -10 -> 0% white.
 * Null, undefined, or non-finite inputs safely evaluate to 50% (0 centipawns).
 */
export function calculateWhitePercent(evalCp?: number | null): number {
  const normalized = typeof evalCp === 'number' && Number.isFinite(evalCp) ? evalCp : 0;
  const pawns = normalized / 100.0;
  const clampedPawns = Math.max(-10, Math.min(10, pawns));
  return 50 + ((clampedPawns / 10.0) * 50);
}
