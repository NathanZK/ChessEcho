import type { Puzzle } from '@/types/puzzle';
import type { AnalyzedPly, GameAnalysisResponse, SimilarPositionResult } from '@/services/api';
import { derivePositionPlayerColor, resolveCanonicalPuzzleMove } from './chessMove';

export function buildMistakePracticePuzzle(
  ply: AnalyzedPly,
  analysis: GameAnalysisResponse
): Puzzle {
  const targetMove = resolveCanonicalPuzzleMove(ply.preMoveFen, ply.bestMove);
  const playerColor = derivePositionPlayerColor(ply.preMoveFen, ply.playerColor);
  return {
    puzzleId: `game-analysis-${analysis.gameId}-ply-${ply.plyNumber}`,
    source: 'weakness',
    fen: ply.preMoveFen,
    playerColor,
    targetMove,
    openingTitle: `Game Mistake (Move ${Math.ceil(ply.plyNumber / 2)})`,
    acceptableMoves: targetMove ? [{ move: targetMove, evalLoss: 0 }] : [],
    movesPlayed: [],
    priority: 1,
    timesReached: 1,
    mistakeCount: 1,
    mistakeRate: 1.0,
    evalCp: ply.evalCp ?? undefined,
  };
}

export function buildSimilarPositionPracticePuzzle(
  match: SimilarPositionResult,
  analysis: GameAnalysisResponse
): Puzzle {
  const occ = match.occurrences[0];
  const rawMove = match.principalVariations[0]?.moves[0] || occ?.playedMove || '';
  let targetMove = resolveCanonicalPuzzleMove(match.fen, rawMove);
  if (!targetMove && occ?.playedMove) {
    targetMove = resolveCanonicalPuzzleMove(match.fen, occ.playedMove);
  }
  const playerColor = derivePositionPlayerColor(match.fen, occ?.playerColor);

  return {
    puzzleId: `game-analysis-similar-${analysis.gameId}-${match.positionId}`,
    source: 'similar',
    fen: match.fen,
    playerColor,
    targetMove,
    openingTitle: `Similar Position (Move ${occ ? Math.ceil(occ.plyNumber / 2) : '?'})`,
    acceptableMoves: targetMove ? [{ move: targetMove, evalLoss: 0 }] : [],
    movesPlayed: [],
    priority: 1,
    timesReached: 1,
    mistakeCount: 0,
    mistakeRate: 0,
    similarContext: {
      score: match.score,
      rank: match.rank,
      canonicalFen: match.canonicalFen,
    },
  };
}
