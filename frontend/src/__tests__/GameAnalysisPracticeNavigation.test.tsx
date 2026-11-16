import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { GameAnalysisView } from '../components/GameAnalysisView';
import * as api from '../services/api';
import type { GameAnalysisResponse, SimilarPositionsResponse } from '../services/api';

vi.mock('../services/api', () => ({
  fetchGames: vi.fn(),
  analyzeGame: vi.fn(),
  fetchSimilarPositions: vi.fn(),
}));

vi.mock('../components/ChessBoardView', () => ({
  ChessBoardView: ({ fen }: { fen: string }) => (
    <div data-testid="mock-chessboard-view" data-fen={fen}>
      Board preview
    </div>
  ),
}));

describe('GameAnalysisPracticeNavigation', () => {
  const mockAnalysis: GameAnalysisResponse = {
    gameId: 'game-practice-123',
    whiteUsername: 'HeroPlayer',
    blackUsername: 'RivalPlayer',
    timeControl: 'blitz',
    playedAt: '2026-11-09T18:00:00Z',
    result: 'win',
    analysisStatus: 'COMPLETE',
    plies: [
      {
        plyNumber: 14,
        movePlayed: 'Qd6??',
        playerColor: 'WHITE',
        preMoveFen: 'r1bqk2r/ppppbppp/2n5/4p3/2B1n3/3P1N2/PPP2PPP/RNBQ1RK1 w kq - 0 6',
        decisionTimeMs: 18000,
        decisionSeverity: 'SIGNIFICANT',
        bestMove: 'dxe4',
        bestEvalCp: 180,
        evalCp: -140,
        evalLoss: 3.2,
        weaknessSeverity: 'CRITICAL',
      },
    ],
  };

  const mockSimilarResponse: SimilarPositionsResponse = {
    queryFen: mockAnalysis.plies[0].preMoveFen,
    candidatesEvaluated: 10,
    shortlistedCandidatesCount: 1,
    engineCompletedCount: 1,
    dynamicSimilarityApplied: false,
    fallbackReason: null,
    queryStatus: 'SUCCESS',
    queryAchievedDepth: 16,
    queryPrincipalVariations: [],
    results: [
      {
        rank: 1,
        score: 0.95,
        staticScore: 0.95,
        dynamicScore: 0.0,
        positionId: 'sim-pos-456',
        fen: 'r1bqk2r/ppppbppp/2n5/4p3/2B1n3/3P1N2/PPP2PPP/RNBQ1RK1 w kq - 0 6',
        canonicalFen: 'r1bqk2r/ppppbppp/2n5/4p3/2B1n3/3P1N2/PPP2PPP/RNBQ1RK1 w kq -',
        searchStatus: 'COMPLETED',
        achievedDepth: 16,
        principalVariations: [{ rank: 1, moves: ['dxe4'], achievedDepth: 16 }],
        occurrences: [
          {
            id: 'occ-999',
            platformGameId: 'other-game-777',
            playedMove: 'dxe4',
            plyNumber: 12,
            playerColor: 'WHITE',
            opponentUsername: 'OpponentMaster',
            gameResult: 'win',
            playedAt: '2026-10-12T10:00:00Z',
            timeControl: 'blitz',
            chessComUrl: null,
          },
        ],
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.analyzeGame).mockResolvedValue(mockAnalysis);
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockSimilarResponse);
  });

  it('passes onPracticeSimilarPosition callback when Practice CTA is clicked in similar positions', async () => {
    const onPracticeSimilarPosition = vi.fn();
    const onExpandedSectionsChange = vi.fn();

    render(
      <GameAnalysisView
        initialGameId="game-practice-123"
        onPracticeSimilarPosition={onPracticeSimilarPosition}
        onExpandedSectionsChange={onExpandedSectionsChange}
      />
    );

    await screen.findByText(/Qualifying Mistake Moves/i);

    // Expand structural similarities section
    const structuralBtn = screen.getByRole('button', { name: /Structural Similarities/i });
    fireEvent.click(structuralBtn);

    expect(onExpandedSectionsChange).toHaveBeenCalledWith(
      expect.objectContaining({ structural: true })
    );

    // Wait for similar position card
    expect(await screen.findByText(/OpponentMaster/i)).toBeInTheDocument();

    // Click Practice button on similar position
    const practiceButtons = screen.getAllByRole('button', { name: /^Practice$/i });
    expect(practiceButtons.length).toBeGreaterThanOrEqual(1);
    fireEvent.click(practiceButtons[0]);

    expect(onPracticeSimilarPosition).toHaveBeenCalledTimes(1);
    expect(onPracticeSimilarPosition).toHaveBeenCalledWith(
      expect.objectContaining({ positionId: 'sim-pos-456' }),
      expect.objectContaining({ gameId: 'game-practice-123' }),
      expect.objectContaining({ plyNumber: 14 })
    );
  });

  it('restores expanded sections state when initialExpandedSections is provided', async () => {
    render(
      <GameAnalysisView
        initialGameId="game-practice-123"
        initialExpandedSections={{ structural: true }}
      />
    );

    await screen.findByText(/Qualifying Mistake Moves/i);

    // Structural was already expanded on mount, so it should fetch without user clicking
    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          mode: 'STRUCTURAL',
          excludeGameId: 'game-practice-123',
        })
      );
    });

    expect(await screen.findByText(/OpponentMaster/i)).toBeInTheDocument();
  });

  it('prioritizes engine principal variation best move over historical played move for puzzle target move', () => {
    const matchWithBothMoves = {
      ...mockSimilarResponse.results[0],
      principalVariations: [{ rank: 1, moves: ['Bc4'], achievedDepth: 16 }],
      occurrences: [
        {
          ...mockSimilarResponse.results[0].occurrences[0],
          playedMove: 'Nxe4', // historical move in other game was suboptimal
        },
      ],
    };

    const bestMove =
      matchWithBothMoves.principalVariations[0]?.moves[0] ||
      matchWithBothMoves.occurrences[0]?.playedMove ||
      '';

    expect(bestMove).toBe('Bc4');
    expect(bestMove).not.toBe('Nxe4');
  });
});
