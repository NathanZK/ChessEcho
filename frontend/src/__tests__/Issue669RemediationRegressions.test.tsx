import React from 'react';
import { render, screen, renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { toCanonicalMove, derivePositionPlayerColor } from '../utils/chessMove';
import { GameAnalysisPliesList } from '../components/GameAnalysisPliesList';
import { GameAnalysisMoveDetail } from '../components/GameAnalysisMoveDetail';
import { useMistakeSimilarity } from '../hooks/useMistakeSimilarity';
import { gameAnalysisReturnStore, type GameAnalysisReturnSnapshot } from '../utils/browserStores';
import * as api from '../services/api';
import type { AnalyzedPly, GameAnalysisResponse, SimilarPositionResult } from '../services/api';

vi.mock('../services/api', () => ({
  fetchSimilarPositions: vi.fn(),
}));

vi.mock('../components/ChessBoardView', () => ({
  ChessBoardView: ({ fen, boardOrientation }: { fen: string; boardOrientation?: string }) => (
    <div data-testid="mock-chessboard-view" data-fen={fen} data-orientation={boardOrientation}>
      Board preview
    </div>
  ),
}));

describe('Issue #669 Remediation Regressions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
  });

  describe('1. Puzzle move encoding: Stockfish PV UCI to canonical SAN and side-to-move derivation', () => {
    it('converts UCI moves into canonical SAN for the exact position FEN', () => {
      const startFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
      const knightFen = 'r1bqkbnr/pppppppp/2n5/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 1 2';

      const pawnMove = toCanonicalMove(startFen, 'e2e4');
      expect(pawnMove).not.toBeNull();
      expect(pawnMove?.san).toBe('e4');

      const knightMove = toCanonicalMove(knightFen, 'g1f3');
      expect(knightMove).not.toBeNull();
      expect(knightMove?.san).toBe('Nf3');

      // Illegal move returns null
      expect(toCanonicalMove(startFen, 'e2e5')).toBeNull();
    });

    it('derives player color from position side-to-move when occurrence metadata is absent', () => {
      const whiteTurnFen = 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 4 4';
      const blackTurnFen = 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 4 4';

      // White to move without occurrence metadata
      expect(derivePositionPlayerColor(whiteTurnFen, undefined)).toBe('WHITE');
      expect(derivePositionPlayerColor(whiteTurnFen, null)).toBe('WHITE');

      // Black to move without occurrence metadata
      expect(derivePositionPlayerColor(blackTurnFen, undefined)).toBe('BLACK');
      expect(derivePositionPlayerColor(blackTurnFen, null)).toBe('BLACK');

      // Occurrence metadata takes precedence over FEN side to move
      expect(derivePositionPlayerColor(whiteTurnFen, 'BLACK')).toBe('BLACK');
      expect(derivePositionPlayerColor(blackTurnFen, 'WHITE')).toBe('WHITE');
      expect(derivePositionPlayerColor(whiteTurnFen, 'WHITE')).toBe('WHITE');
    });
  });

  describe('2. Practice return state: threshold and timing-filter restoration', () => {
    it('preserves and restores exact evaluation-loss threshold and timing filters across practice round-trip', () => {
      const snapshot: GameAnalysisReturnSnapshot = {
        gameId: 'game-practice-test-456',
        plyNumber: 18,
        evalLossThreshold: 1.25,
        timingFilters: ['Critical', 'Significant'],
        expandedSections: { structural: true, dynamic: false },
      };

      gameAnalysisReturnStore.set(snapshot);
      const retrieved = gameAnalysisReturnStore.getSnapshot();

      expect(retrieved).not.toBeNull();
      expect(retrieved?.gameId).toBe('game-practice-test-456');
      expect(retrieved?.plyNumber).toBe(18);
      expect(retrieved?.evalLossThreshold).toBe(1.25);
      expect(retrieved?.timingFilters).toEqual(['Critical', 'Significant']);
      expect(retrieved?.expandedSections).toEqual({ structural: true, dynamic: false });
    });
  });

  describe('3. Configured threshold consistency: dynamic mistake classification and badges', () => {
    const mockPly: AnalyzedPly = {
      plyNumber: 14,
      movePlayed: 'Bc4',
      playerColor: 'WHITE',
      preMoveFen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
      decisionTimeMs: 12000,
      decisionSeverity: 'CRITICAL',
      bestMove: 'Nf3',
      evalCp: 30,
      evalLoss: 0.50, // 0.50 is between 0.30 and 0.80
      weaknessSeverity: 'SIGNIFICANT',
    };

    const mockAnalysis: GameAnalysisResponse = {
      gameId: 'game-threshold-test',
      whiteUsername: 'HeroPlayer',
      blackUsername: 'RivalPlayer',
      timeControl: 'blitz',
      playedAt: '2026-11-09T18:00:00Z',
      result: 'win',
      analysisStatus: 'COMPLETE',
      plies: [mockPly],
    };

    it('classifies a 0.50 eval loss as a timing observation when threshold is 0.80', () => {
      const { rerender } = render(
        <GameAnalysisPliesList
          totalPliesCount={1}
          qualifyingPlies={[mockPly]}
          selectedPlyNumber={14}
          onSelectPly={vi.fn()}
          evalLossThreshold={0.80}
        />
      );

      // Under threshold 0.80, 0.50 is not an error mistake: rendered with neutral text and real eval loss
      const evalLossBadge = screen.getByText('-0.50');
      expect(evalLossBadge.className).toContain('text-on-surface-variant');

      // Now rerender with threshold 0.40: it must dynamically become a mistake!
      rerender(
        <GameAnalysisPliesList
          totalPliesCount={1}
          qualifyingPlies={[mockPly]}
          selectedPlyNumber={14}
          onSelectPly={vi.fn()}
          evalLossThreshold={0.40}
        />
      );

      const mistakeBadge = screen.getByText('-0.50');
      expect(mistakeBadge.className).toContain('text-error');
    });

    it('updates MoveDetail header and practice button label when threshold changes', () => {
      const onPracticeMistake = vi.fn();

      const { rerender } = render(
        <GameAnalysisMoveDetail
          activePly={mockPly}
          analysis={mockAnalysis}
          evalLossThreshold={0.80}
          onPracticeMistake={onPracticeMistake}
        />
      );

      // At 0.80 threshold, 0.50 evalLoss is not a mistake
      expect(screen.getByText('Move Note:')).toBeDefined();
      expect(screen.getByText('Practice this position')).toBeDefined();

      // Change threshold to 0.40: dynamically becomes a mistake
      rerender(
        <GameAnalysisMoveDetail
          activePly={mockPly}
          analysis={mockAnalysis}
          evalLossThreshold={0.40}
          onPracticeMistake={onPracticeMistake}
        />
      );

      expect(screen.getByText('Eval Loss:')).toBeDefined();
      expect(screen.getByText('Practice this mistake')).toBeDefined();
    });
  });

  describe('4. Similarity state isolation: keying by game, account, and position FEN', () => {
    const startFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

    const mockAnalysis1: GameAnalysisResponse = {
      gameId: 'game-alpha',
      whiteUsername: 'HeroPlayer',
      blackUsername: 'RivalPlayer',
      timeControl: 'blitz',
      playedAt: '2026-11-09T18:00:00Z',
      result: 'win',
      analysisStatus: 'COMPLETE',
      plies: [],
    };

    const mockAnalysis2: GameAnalysisResponse = {
      ...mockAnalysis1,
      gameId: 'game-beta',
    };

    const mockPly: AnalyzedPly = {
      plyNumber: 1,
      movePlayed: 'e4',
      playerColor: 'WHITE',
      preMoveFen: startFen,
      evalLoss: 0,
      weaknessSeverity: 'NO_CONCERN',
      decisionSeverity: 'NO_CONCERN',
    };

    it('resets similarity results when switching games with the identical starting FEN', async () => {
      const mockResult: SimilarPositionResult = {
        rank: 1,
        score: 0.95,
        staticScore: 0.95,
        dynamicScore: 0.95,
        searchStatus: 'EXACT',
        achievedDepth: 16,
        positionId: 'sim-pos-1',
        fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
        canonicalFen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
        occurrences: [],
        principalVariations: [],
      };

      vi.mocked(api.fetchSimilarPositions).mockResolvedValueOnce({
        queryFen: startFen,
        candidatesEvaluated: 1,
        shortlistedCandidatesCount: 1,
        engineCompletedCount: 1,
        dynamicSimilarityApplied: false,
        fallbackReason: null,
        queryStatus: 'SUCCESS',
        queryAchievedDepth: 16,
        queryPrincipalVariations: [],
        results: [mockResult],
      });

      const { result, rerender } = renderHook(
        ({ analysis, accountId }) =>
          useMistakeSimilarity(mockPly, analysis, undefined, undefined, accountId),
        {
          initialProps: { analysis: mockAnalysis1, accountId: 'acc-1' },
        }
      );

      // Expand structural section and wait for results
      await act(async () => {
        result.current.handleToggleStructural();
      });

      expect(result.current.structuralResults).toHaveLength(1);

      // Now switch to Game Beta with the exact same FEN and account
      rerender({ analysis: mockAnalysis2, accountId: 'acc-1' });

      // Cached results from Game Alpha must be immediately reset to null!
      expect(result.current.structuralResults).toBeNull();
      expect(result.current.isStructuralExpanded).toBe(false);
    });

    it('resets similarity results when switching accounts on the same game and FEN', async () => {
      const mockResult: SimilarPositionResult = {
        rank: 1,
        score: 0.95,
        staticScore: 0.95,
        dynamicScore: 0.95,
        searchStatus: 'EXACT',
        achievedDepth: 16,
        positionId: 'sim-pos-2',
        fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
        canonicalFen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
        occurrences: [],
        principalVariations: [],
      };

      vi.mocked(api.fetchSimilarPositions).mockResolvedValueOnce({
        queryFen: startFen,
        candidatesEvaluated: 1,
        shortlistedCandidatesCount: 1,
        engineCompletedCount: 1,
        dynamicSimilarityApplied: false,
        fallbackReason: null,
        queryStatus: 'SUCCESS',
        queryAchievedDepth: 16,
        queryPrincipalVariations: [],
        results: [mockResult],
      });

      const { result, rerender } = renderHook(
        ({ analysis, accountId }) =>
          useMistakeSimilarity(mockPly, analysis, undefined, undefined, accountId),
        {
          initialProps: { analysis: mockAnalysis1, accountId: 'acc-1' },
        }
      );

      await act(async () => {
        result.current.handleToggleStructural();
      });

      expect(result.current.structuralResults).toHaveLength(1);

      // Switch to account 2
      rerender({ analysis: mockAnalysis1, accountId: 'acc-2' });

      // State is reset
      expect(result.current.structuralResults).toBeNull();
    });

    it('discards stale responses when game changes while request is in-flight', async () => {
      let resolveFirstCall: (value: api.SimilarPositionsResponse) => void = () => {};
      const pendingPromise = new Promise<api.SimilarPositionsResponse>((resolve) => {
        resolveFirstCall = resolve;
      });

      vi.mocked(api.fetchSimilarPositions).mockImplementationOnce(() => pendingPromise);

      const { result, rerender } = renderHook(
        ({ analysis }) =>
          useMistakeSimilarity(mockPly, analysis, undefined, undefined, 'acc-1'),
        {
          initialProps: { analysis: mockAnalysis1 },
        }
      );

      // Trigger search for Game Alpha
      act(() => {
        result.current.handleToggleStructural();
      });

      expect(result.current.isSearchingStructural).toBe(true);

      // User switches to Game Beta before Game Alpha resolves
      rerender({ analysis: mockAnalysis2 });

      // Game Alpha promise finishes
      await act(async () => {
        const staleResult: SimilarPositionResult = {
          rank: 1,
          score: 0.9,
          staticScore: 0.9,
          dynamicScore: 0.9,
          searchStatus: 'EXACT',
          achievedDepth: 16,
          positionId: 'leak-pos',
          fen: startFen,
          canonicalFen: startFen,
          occurrences: [],
          principalVariations: [],
        };
        resolveFirstCall({
          queryFen: startFen,
          candidatesEvaluated: 1,
          shortlistedCandidatesCount: 1,
          engineCompletedCount: 1,
          dynamicSimilarityApplied: false,
          fallbackReason: null,
          queryStatus: 'SUCCESS',
          queryAchievedDepth: 16,
          queryPrincipalVariations: [],
          results: [staleResult],
        });
      });

      // The stale response must NOT populate Game Beta's state!
      expect(result.current.structuralResults).toBeNull();
    });
  });
});
