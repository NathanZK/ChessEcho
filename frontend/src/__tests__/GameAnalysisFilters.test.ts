import { describe, it, expect } from 'vitest';
import {
  matchesDecisionTimingFilter,
  matchesEvalLossThreshold,
  filterQualifyingPlies,
  toggleTimingCategory,
  formatEvaluation,
  AnalyzedPly,
} from '../utils/gameAnalysisFilters';

describe('gameAnalysisFilters', () => {
  const samplePlies: AnalyzedPly[] = [
    {
      plyNumber: 1,
      movePlayed: 'e4',
      playerColor: 'WHITE',
      preMoveFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      decisionTimeMs: 15000,
      decisionSeverity: 'MINOR',
      bestMove: 'e4',
      bestEvalCp: 25,
      evalCp: 25,
      evalLoss: 0.0,
      weaknessSeverity: 'NO_CONCERN',
    },
    {
      plyNumber: 12,
      movePlayed: 'Nf6',
      playerColor: 'BLACK',
      preMoveFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
      decisionTimeMs: 35000,
      decisionSeverity: 'SIGNIFICANT',
      bestMove: 'Bc5',
      bestEvalCp: 35,
      evalCp: -120,
      evalLoss: 1.55,
      weaknessSeverity: 'SIGNIFICANT',
    },
    {
      plyNumber: 24,
      movePlayed: 'Qxd4??',
      playerColor: 'WHITE',
      preMoveFen: 'r1b1k2r/pppp1ppp/8/8/3QP3/8/PPP2PPP/RNB1K2R w KQkq - 0 8',
      decisionTimeMs: 4000,
      decisionSeverity: 'NO_CONCERN',
      bestMove: 'O-O',
      bestEvalCp: 150,
      evalCp: -350,
      evalLoss: 5.0,
      weaknessSeverity: 'CRITICAL',
    },
  ];

  it('matchesEvalLossThreshold correctly evaluates against slider', () => {
    expect(matchesEvalLossThreshold(0.0, 0.8)).toBe(false);
    expect(matchesEvalLossThreshold(0.8, 0.8)).toBe(true);
    expect(matchesEvalLossThreshold(1.55, 0.8)).toBe(true);
    expect(matchesEvalLossThreshold(null, 0.8)).toBe(false);
    expect(matchesEvalLossThreshold(undefined, 0.8)).toBe(false);
  });

  it('matchesDecisionTimingFilter filters by multi-select categories', () => {
    expect(matchesDecisionTimingFilter('MINOR', ['All'])).toBe(true);
    expect(matchesDecisionTimingFilter('MINOR', ['Minor'])).toBe(true);
    expect(matchesDecisionTimingFilter('MINOR', ['Significant', 'Critical'])).toBe(false);
    expect(matchesDecisionTimingFilter('NO_CONCERN', ['None'])).toBe(true);
    expect(matchesDecisionTimingFilter(null, ['None'])).toBe(true);
  });

  it('filterQualifyingPlies intersects threshold and timing orthogonally', () => {
    // Threshold 0.8: plies 12 and 24 qualify by eval loss.
    // Timing 'Significant': only ply 12 qualifies.
    const result = filterQualifyingPlies(samplePlies, 0.8, ['Significant']);
    expect(result).toHaveLength(1);
    expect(result[0].plyNumber).toBe(12);

    // Timing 'None': only ply 24 qualifies.
    const noneResult = filterQualifyingPlies(samplePlies, 0.8, ['None']);
    expect(noneResult).toHaveLength(1);
    expect(noneResult[0].plyNumber).toBe(24);

    // Timing 'All': both plies 12 and 24 qualify.
    const allResult = filterQualifyingPlies(samplePlies, 0.8, ['All']);
    expect(allResult).toHaveLength(2);
  });

  it('toggleTimingCategory manages selection state cleanly without mutation', () => {
    let selected = toggleTimingCategory(['All'], 'Minor');
    expect(selected).toEqual(['Minor']);

    selected = toggleTimingCategory(selected, 'Critical');
    expect(selected).toEqual(['Minor', 'Critical']);

    selected = toggleTimingCategory(selected, 'Minor');
    expect(selected).toEqual(['Critical']);

    selected = toggleTimingCategory(selected, 'Critical');
    expect(selected).toEqual(['All']); // Defaults back to All when empty

    selected = toggleTimingCategory(['Minor', 'Significant'], 'All');
    expect(selected).toEqual(['All']);
  });

  it('formatEvaluation formats scores with sign and fixed decimal', () => {
    expect(formatEvaluation(35)).toBe('+0.35');
    expect(formatEvaluation(-120)).toBe('-1.20');
    expect(formatEvaluation(0)).toBe('0.00');
    expect(formatEvaluation(null)).toBe('-');
  });
});
