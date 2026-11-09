import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import GameAnalysisView from '../components/GameAnalysisView';
import * as api from '../services/api';

vi.mock('../services/api', () => ({
  fetchGames: vi.fn(),
  analyzeGame: vi.fn(),
  fetchSimilarPositions: vi.fn(),
}));

describe('GameAnalysisView', () => {
  const mockGames: api.GamePageResponse = {
    content: [
      {
        id: 'game-123',
        platformGameId: 'chess-com-1',
        timeControl: 'blitz',
        playedAt: '2026-11-09T18:00:00Z',
        result: 'win',
        whiteUsername: 'HeroPlayer',
        blackUsername: 'RivalPlayer',
        pgn: '1. e4 e5',
      },
    ],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 20,
  };

  const mockAnalysis: api.GameAnalysisResponse = {
    gameId: 'game-123',
    whiteUsername: 'HeroPlayer',
    blackUsername: 'RivalPlayer',
    timeControl: 'blitz',
    playedAt: '2026-11-09T18:00:00Z',
    result: 'win',
    analysisStatus: 'COMPLETE',
    plies: [
      {
        plyNumber: 1,
        movePlayed: 'e4',
        playerColor: 'WHITE',
        preMoveFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        decisionTimeMs: 3000,
        decisionSeverity: 'NO_CONCERN',
        bestMove: 'e4',
        bestEvalCp: 20,
        evalCp: 20,
        evalLoss: 0.0,
        weaknessSeverity: 'NO_CONCERN',
      },
      {
        plyNumber: 12,
        movePlayed: 'Nf6',
        playerColor: 'BLACK',
        preMoveFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
        decisionTimeMs: 25000,
        decisionSeverity: 'SIGNIFICANT',
        bestMove: 'Bc5',
        bestEvalCp: 35,
        evalCp: -120,
        evalLoss: 1.55,
        weaknessSeverity: 'SIGNIFICANT',
      },
      {
        plyNumber: 20,
        movePlayed: 'f3??',
        playerColor: 'WHITE',
        preMoveFen: 'r1bqkb1r/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
        decisionTimeMs: 2000,
        decisionSeverity: 'NO_CONCERN',
        bestMove: 'd4',
        bestEvalCp: 40,
        evalCp: -200,
        evalLoss: 2.4,
        weaknessSeverity: 'CRITICAL',
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.fetchGames).mockResolvedValue(mockGames);
    vi.mocked(api.analyzeGame).mockResolvedValue(mockAnalysis);
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue({
      queryFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
      candidatesEvaluated: 10,
      shortlistedCandidatesCount: 2,
      engineCompletedCount: 2,
      dynamicSimilarityApplied: false,
      fallbackReason: null,
      queryStatus: 'SUCCESS',
      queryAchievedDepth: 16,
      queryPrincipalVariations: [],
      results: [],
    });
  });

  it('renders My Games list initially and allows selecting a game to analyze', async () => {
    render(<GameAnalysisView />);

    expect(await screen.findByText('HeroPlayer')).toBeInTheDocument();
    expect(screen.getByText('RivalPlayer')).toBeInTheDocument();

    const analyzeBtn = screen.getByRole('button', { name: /Analyze/i });
    fireEvent.click(analyzeBtn);

    await waitFor(() => {
      expect(api.analyzeGame).toHaveBeenCalledWith('game-123');
    });

    expect(await screen.findByText(/Game Analysis/i)).toBeInTheDocument();
    expect(screen.getAllByText(/Nf6/i).length).toBeGreaterThan(0);
  });

  it('operates eval loss threshold and timing filters independently without mutual reset', async () => {
    render(<GameAnalysisView initialGameId="game-123" />);

    // Plies 12 (loss 1.55, SIGNIFICANT) and 20 (loss 2.40, NO_CONCERN) qualify by default threshold (0.8)
    await screen.findByText(/Qualifying Mistake Moves/i);
    expect(screen.getAllByText(/Nf6/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/f3\?\?/i).length).toBeGreaterThan(0);
    expect(screen.queryByText(/^e4$/i)).not.toBeInTheDocument(); // loss 0.0 does not qualify

    // Select 'Significant' timing filter
    const significantBtn = screen.getByRole('button', { name: /^Significant$/i });
    fireEvent.click(significantBtn);

    // Only ply 12 should be displayed
    expect(screen.getAllByText(/Nf6/i).length).toBeGreaterThan(0);
    expect(screen.queryByText(/f3\?\?/i)).not.toBeInTheDocument();

    // Adjust threshold slider to 2.0 (ply 12 has 1.55, so it should now filter out)
    const slider = screen.getByLabelText(/Eval Loss Threshold/i);
    fireEvent.change(slider, { target: { value: '2.0' } });

    // Both should be filtered out
    expect(screen.queryByText(/Nf6/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/f3\?\?/i)).not.toBeInTheDocument();

    // Reset timing to All, ply 20 (loss 2.4) should show up while threshold 2.0 remains
    const allTimingBtn = screen.getByRole('button', { name: /^All$/i });
    fireEvent.click(allTimingBtn);

    expect(screen.getAllByText(/f3\?\?/i).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Nf6/i)).not.toBeInTheDocument();
  });

  it('queries structural and dynamic historical similarity using pre-move FEN', async () => {
    render(<GameAnalysisView initialGameId="game-123" />);

    await screen.findByText(/Qualifying Mistake Moves/i);
    const ply12Item = screen.getAllByText(/Nf6/i)[0];
    fireEvent.click(ply12Item);

    const structuralBtn = screen.getByRole('button', { name: /Search Structural Similarity/i });
    fireEvent.click(structuralBtn);

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
          mode: 'STRUCTURAL',
        })
      );
    });

    const dynamicBtn = screen.getByRole('button', { name: /Search Dynamic Similarity/i });
    fireEvent.click(dynamicBtn);

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
          mode: 'DYNAMIC',
        })
      );
    });
  });

  it('triggers onPracticeMistake callback when Practice this mistake button is clicked', async () => {
    const onPracticeMistake = vi.fn();
    render(<GameAnalysisView initialGameId="game-123" onPracticeMistake={onPracticeMistake} />);

    await screen.findByText(/Qualifying Mistake Moves/i);
    const ply12Item = screen.getAllByText(/Nf6/i)[0];
    fireEvent.click(ply12Item);

    const practiceBtn = screen.getByRole('button', { name: /Practice this mistake/i });
    fireEvent.click(practiceBtn);

    expect(onPracticeMistake).toHaveBeenCalledTimes(1);
    expect(onPracticeMistake).toHaveBeenCalledWith(
      expect.objectContaining({ plyNumber: 12, movePlayed: 'Nf6' }),
      expect.objectContaining({ gameId: 'game-123' })
    );
  });
});
