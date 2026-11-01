import type { LongDecisionTimeControl, DecisionSeverity } from '../services/api';

export type PuzzleSource = 'puzzle' | 'weakness' | 'long-decision' | 'similar' | 'supplied';

export interface LongDecisionContext {
  timeControl: LongDecisionTimeControl;
  decisionTimeMs: number;
  movePlayed: string;
  severity: DecisionSeverity;
  gameUrl?: string | null;
  platformGameId?: string;
  plyNumber?: number;
}

export interface SimilarPositionContext {
  score?: number;
  rank?: number;
  canonicalFen?: string;
}

export interface AcceptableMove {
  move: string;
  evalLoss: number;
}

export interface MoveBreakdown {
  move: string;
  timesPlayed: number;
  averageLoss: number;
}

export interface Puzzle {
  puzzleId: string;
  source?: PuzzleSource;
  fen: string;
  playerColor: 'WHITE' | 'BLACK';
  targetMove: string;
  openingTitle: string;
  acceptableMoves: AcceptableMove[];
  movesPlayed: MoveBreakdown[];
  priority: number;
  timesReached: number;
  mistakeCount: number;
  mistakeRate: number;
  gameUrls?: string[];
  evalCp?: number; // Stockfish evaluation in centipawns (e.g. +50 = +0.5 pawns)
  longDecisionContext?: LongDecisionContext;
  similarContext?: SimilarPositionContext;
}

/**
 * Affirmative predicate that verifies whether a puzzle is backed by genuine
 * historical account evidence (e.g. times reached > 0 or mistake count > 0).
 *
 * A source tag alone (such as 'weakness') does NOT prove that valid historical recurrence data exists.
 * Non-weakness sources (long-decision, similar, supplied) and unbacked test fixtures
 * return false, preventing fabricated 0.0% / 0 games recurrence statistics.
 */
export function isWeaknessBacked(puzzle: Puzzle | null | undefined): boolean {
  if (!puzzle) return false;
  if (
    puzzle.source === 'long-decision' ||
    puzzle.source === 'similar' ||
    puzzle.source === 'supplied'
  ) {
    return false;
  }
  return (puzzle.timesReached ?? 0) > 0 || (puzzle.mistakeCount ?? 0) > 0;
}
