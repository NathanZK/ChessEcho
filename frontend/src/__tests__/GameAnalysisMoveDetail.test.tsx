import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { GameAnalysisMoveDetail } from '../components/GameAnalysisMoveDetail';
import * as api from '../services/api';
import type { AnalyzedPly, GameAnalysisResponse, SimilarPositionsResponse } from '../services/api';

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

describe('GameAnalysisMoveDetail', () => {
  const mockAnalysis: GameAnalysisResponse = {
    gameId: 'game-curr-123',
    whiteUsername: 'HeroPlayer',
    blackUsername: 'RivalPlayer',
    timeControl: 'blitz',
    playedAt: '2026-11-09T18:00:00Z',
    result: 'win',
    analysisStatus: 'COMPLETE',
    plies: [],
  };

  const mockActivePly: AnalyzedPly = {
    plyNumber: 12,
    movePlayed: 'Nf6',
    playerColor: 'BLACK',
    preMoveFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
    decisionTimeMs: 14500,
    decisionSeverity: 'SIGNIFICANT',
    bestMove: 'Bc5',
    bestEvalCp: 40,
    evalCp: -120,
    evalLoss: 1.6,
    weaknessSeverity: 'SIGNIFICANT',
  };

  const mockStructuralResponse: SimilarPositionsResponse = {
    queryFen: mockActivePly.preMoveFen,
    candidatesEvaluated: 15,
    shortlistedCandidatesCount: 2,
    engineCompletedCount: 2,
    dynamicSimilarityApplied: false,
    fallbackReason: null,
    queryStatus: 'SUCCESS',
    queryAchievedDepth: 16,
    queryPrincipalVariations: [],
    results: [
      {
        rank: 1,
        score: 0.92,
        staticScore: 0.92,
        dynamicScore: 0.0,
        positionId: 'pos-struct-1',
        fen: 'r1bqk2r/pppp1ppp/2n2n2/2b1p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 0 5',
        canonicalFen: 'r1bqk2r/pppp1ppp/2n2n2/2b1p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq -',
        searchStatus: 'COMPLETED',
        achievedDepth: 16,
        principalVariations: [],
        occurrences: [
          {
            id: 'occ-1',
            platformGameId: 'other-game-999',
            playedMove: 'Bc5',
            plyNumber: 8,
            playerColor: 'BLACK',
            opponentUsername: 'GrandmasterA',
            gameResult: 'win',
            playedAt: '2026-10-15T12:00:00Z',
            timeControl: 'blitz',
            chessComUrl: null,
          },
        ],
      },
    ],
  };

  const mockDynamicResponse: SimilarPositionsResponse = {
    queryFen: mockActivePly.preMoveFen,
    candidatesEvaluated: 25,
    shortlistedCandidatesCount: 1,
    engineCompletedCount: 1,
    dynamicSimilarityApplied: true,
    fallbackReason: null,
    queryStatus: 'SUCCESS',
    queryAchievedDepth: 16,
    queryPrincipalVariations: [],
    results: [
      {
        rank: 1,
        score: 0.88,
        staticScore: 0.0,
        dynamicScore: 0.88,
        positionId: 'pos-dyn-1',
        fen: 'r2qk2r/ppp2ppp/2n1bn2/3pp3/4P3/2NP1N2/PPP1BPPP/R1BQK2R w KQkq - 1 6',
        canonicalFen: 'r2qk2r/ppp2ppp/2n1bn2/3pp3/4P3/2NP1N2/PPP1BPPP/R1BQK2R w KQkq -',
        searchStatus: 'COMPLETED',
        achievedDepth: 16,
        principalVariations: [],
        occurrences: [
          {
            id: 'occ-2',
            platformGameId: 'other-game-888',
            playedMove: 'd5',
            plyNumber: 10,
            playerColor: 'BLACK',
            opponentUsername: 'TacticianB',
            gameResult: 'loss',
            playedAt: '2026-10-20T15:30:00Z',
            timeControl: 'rapid',
            chessComUrl: null,
          },
        ],
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.fetchSimilarPositions).mockImplementation(async (params) => {
      if (params.mode === 'DYNAMIC') return mockDynamicResponse;
      return mockStructuralResponse;
    });
  });

  it('renders pre-move board preview with orientation matching player to move', () => {
    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
      />
    );

    expect(screen.getByText(/Ply 12:/i)).toBeInTheDocument();
    expect(screen.getByText('Nf6')).toBeInTheDocument();
    expect(screen.getByText('1.60 pawns')).toBeInTheDocument();
    expect(screen.getByText(/14.5s/i)).toBeInTheDocument();

    const boards = screen.getAllByTestId('mock-chessboard-view');
    expect(boards.length).toBeGreaterThanOrEqual(1);
    expect(boards[0]).toHaveAttribute('data-fen', mockActivePly.preMoveFen);
    expect(boards[0]).toHaveAttribute('data-orientation', 'BLACK');
  });

  it('renders independent Structural and Dynamic similarity sections collapsed by default', () => {
    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
      />
    );

    const structuralHeader = screen.getByRole('button', { name: /Structural Similarities/i });
    const dynamicHeader = screen.getByRole('button', { name: /Dynamic Similarities/i });

    expect(structuralHeader).toBeInTheDocument();
    expect(dynamicHeader).toBeInTheDocument();

    // No similarity queries sent while collapsed
    expect(api.fetchSimilarPositions).not.toHaveBeenCalled();
    expect(screen.queryByText('GrandmasterA')).not.toBeInTheDocument();
    expect(screen.queryByText('TacticianB')).not.toBeInTheDocument();
  });

  it('expanding Structural section fetches structural similarity with excludeGameId', async () => {
    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
      />
    );

    const structuralHeader = screen.getByRole('button', { name: /Structural Similarities/i });
    fireEvent.click(structuralHeader);

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          fen: mockActivePly.preMoveFen,
          mode: 'STRUCTURAL',
          limit: 5,
          excludeGameId: 'game-curr-123',
        })
      );
    });

    // Dynamic was not called
    expect(api.fetchSimilarPositions).not.toHaveBeenCalledWith(
      expect.objectContaining({ mode: 'DYNAMIC' })
    );

    expect(await screen.findByText(/GrandmasterA/i)).toBeInTheDocument();
    expect(screen.getByText(/Move 4/i)).toBeInTheDocument();
  });

  it('expanding Dynamic section independently fetches dynamic similarity with excludeGameId', async () => {
    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
      />
    );

    const dynamicHeader = screen.getByRole('button', { name: /Dynamic Similarities/i });
    fireEvent.click(dynamicHeader);

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          fen: mockActivePly.preMoveFen,
          mode: 'DYNAMIC',
          limit: 5,
          excludeGameId: 'game-curr-123',
        })
      );
    });

    expect(await screen.findByText(/TacticianB/i)).toBeInTheDocument();
  });

  it('renders clear empty state when zero similar positions exist in other games', async () => {
    vi.mocked(api.fetchSimilarPositions).mockResolvedValueOnce({
      ...mockStructuralResponse,
      results: [],
    });

    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Structural Similarities/i }));

    expect(
      await screen.findByText(/No structural similar positions found in your other games/i)
    ).toBeInTheDocument();
  });

  it('triggers onPracticeSimilarPosition when Practice button on similar position is clicked', async () => {
    const onPracticeSimilarPosition = vi.fn();

    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
        onPracticeSimilarPosition={onPracticeSimilarPosition}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Structural Similarities/i }));
    expect(await screen.findByText(/GrandmasterA/i)).toBeInTheDocument();

    const practiceSimilarBtn = screen.getAllByRole('button', { name: /^Practice$/i })[0];
    fireEvent.click(practiceSimilarBtn);

    expect(onPracticeSimilarPosition).toHaveBeenCalledTimes(1);
    expect(onPracticeSimilarPosition).toHaveBeenCalledWith(
      expect.objectContaining({ positionId: 'pos-struct-1' }),
      expect.objectContaining({ gameId: 'game-curr-123' }),
      mockActivePly
    );
  });

  it('resets similarity section expansion to collapsed when active ply changes', async () => {
    const { rerender } = render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Structural Similarities/i }));
    expect(await screen.findByText(/GrandmasterA/i)).toBeInTheDocument();

    const mockSecondPly: AnalyzedPly = {
      ...mockActivePly,
      plyNumber: 24,
      movePlayed: 'f3??',
      preMoveFen: 'r1bqkb1r/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    };

    rerender(
      <GameAnalysisMoveDetail
        activePly={mockSecondPly}
        analysis={mockAnalysis}
      />
    );

    expect(screen.queryByText(/GrandmasterA/i)).not.toBeInTheDocument();
    expect(api.fetchSimilarPositions).toHaveBeenCalledTimes(1);
  });

  it('passes accountId and guestUsername to fetchSimilarPositions when provided', async () => {
    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
        accountId="acc-uuid-123"
        guestUsername="GuestUser"
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Structural Similarities/i }));

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          accountId: 'acc-uuid-123',
          username: 'GuestUser',
        })
      );
    });
  });

  it('passes activePly as third argument to onPracticeSimilarPosition callback', async () => {
    const onPracticeSimilarPosition = vi.fn();

    render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
        onPracticeSimilarPosition={onPracticeSimilarPosition}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Structural Similarities/i }));
    expect(await screen.findByText(/GrandmasterA/i)).toBeInTheDocument();

    const practiceSimilarBtn = screen.getAllByRole('button', { name: /^Practice$/i })[0];
    fireEvent.click(practiceSimilarBtn);

    expect(onPracticeSimilarPosition).toHaveBeenCalledWith(
      expect.objectContaining({ positionId: 'pos-struct-1' }),
      expect.objectContaining({ gameId: 'game-curr-123' }),
      mockActivePly
    );
  });

  it('discards stale similarity results when activePly switches before response resolves', async () => {
    let resolveFirstQuery: ((value: SimilarPositionsResponse) => void) | null = null;
    vi.mocked(api.fetchSimilarPositions).mockImplementation((params) => {
      if (params.fen === mockActivePly.preMoveFen) {
        return new Promise((resolve) => {
          resolveFirstQuery = resolve;
        });
      }
      return Promise.resolve(mockStructuralResponse);
    });

    const { rerender } = render(
      <GameAnalysisMoveDetail
        activePly={mockActivePly}
        analysis={mockAnalysis}
      />
    );

    // Expand structural for ply 12 (starts pending request)
    fireEvent.click(screen.getByRole('button', { name: /Structural Similarities/i }));
    expect(screen.getByText(/Searching structural similar positions/i)).toBeInTheDocument();

    // User switches to ply 24 with different FEN
    const mockSecondPly: AnalyzedPly = {
      ...mockActivePly,
      plyNumber: 24,
      movePlayed: 'f3??',
      preMoveFen: 'r1bqkb1r/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    };
    rerender(
      <GameAnalysisMoveDetail
        activePly={mockSecondPly}
        analysis={mockAnalysis}
      />
    );

    // Now resolve the stale first query for ply 12
    if (resolveFirstQuery) {
      (resolveFirstQuery as (val: SimilarPositionsResponse) => void)(mockStructuralResponse);
    }

    await new Promise((r) => setTimeout(r, 50));

    // Stale result for ply 12 should NOT be displayed
    expect(screen.queryByText(/GrandmasterA/i)).not.toBeInTheDocument();
  });

  it('formats objectively good move with timing observation neutrally and provides Practice this position CTA', () => {
    const goodMoveWithTimingConcern: AnalyzedPly = {
      ...mockActivePly,
      evalLoss: 0.05,
      weaknessSeverity: 'NO_CONCERN',
      decisionSeverity: 'CRITICAL',
    };

    render(
      <GameAnalysisMoveDetail
        activePly={goodMoveWithTimingConcern}
        analysis={mockAnalysis}
        onPracticeMistake={vi.fn()}
      />
    );

    // Should not render red eval loss text
    expect(screen.queryByText(/0.05 pawns/i)).not.toBeInTheDocument();
    expect(screen.getByText(/Timing Observation/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Practice this position/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Practice this mistake/i })).not.toBeInTheDocument();
  });
});
