import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { WeaknessesList } from '../components/WeaknessesList';
import * as api from '../services/api';

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual,
    fetchWeaknesses: vi.fn().mockResolvedValue([]),
    fetchRecurringWeaknesses: vi.fn(),
    getClusterPositions: vi.fn(),
    triggerRecurringDiscovery: vi.fn(),
    getRecurringDiscoveryJobStatus: vi.fn(),
    cancelRecurringDiscoveryJob: vi.fn(),
  };
});

describe('Thematic Weakness Clusters & Practice Bridge (#735)', () => {
  const onSelectPractice = vi.fn();
  const defaultProps = {
    accountId: 'test-account-uuid',
    username: 'magnus',
    onSelectPractice,
  };

  const mockCluster: api.RecurringWeaknessResponse = {
    clusterId: 'cluster-uuid-1',
    clusterType: 'STRUCTURAL',
    recommendationPriority: 0.3,
    representativeFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    memberPositionCount: 4,
    totalOccurrences: 18,
    distinctGamesCount: 14,
    mistakeCount: 10,
    mistakeFrequency: 0.55,
    blunderRate: 0.3,
    evaluatedOccurrences: 18,
    unevaluatedOccurrences: 0,
    evalLossDistribution: { '0.8-1.5': 6, '1.5-3.0': 4 },
    evalLossPercentages: { '0.8-1.5': 60, '1.5-3.0': 40 },
    timingProfile: { FAST: 2, NORMAL: 9, SLOW: 7 },
    formatBreakdown: { RAPID: 10, BLITZ: 8 },
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders thematic cluster cards with community stats and badges', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);

    render(<WeaknessesList {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByText(/Thematic Clusters/i)).toBeInTheDocument();
      expect(screen.getByText(/Mistake Freq: 55%/i)).toBeInTheDocument();
      expect(screen.getByText(/Blunder Rate: 30%/i)).toBeInTheDocument();
      expect(screen.getByText(/4 variations/i)).toBeInTheDocument();
    });
  });

  it('triggers onSelectPractice with playable playlist when "Practice This Weakness" is clicked on a cluster card', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      positions: [
        {
          positionId: 'pos-123',
          fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
          playerColor: 'WHITE',
          movePlayed: 'Bc4',
          targetMove: 'Bb5',
          acceptableMoves: [{ move: 'Bb5', evalLoss: 0.0 }],
          evalLoss: 0.85,
          mistakeSeverity: 'MINOR',
          decisionTimeMs: 3200,
          decisionSeverity: 'NO_CONCERN',
          timeControl: 'RAPID',
          gameId: 'game-1',
          platformGameId: 'chesscom_1',
          plyNumber: 5,
          moveNumber: 3,
        },
      ],
    });

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    expect(practiceBtn).toBeInTheDocument();

    fireEvent.click(practiceBtn);

    await waitFor(() => {
      expect(api.getClusterPositions).toHaveBeenCalledWith(
        'cluster-uuid-1',
        expect.objectContaining({ playableOnly: true, size: 100, accountId: 'test-account-uuid' })
      );
      expect(onSelectPractice).toHaveBeenCalledWith(
        expect.objectContaining({
          puzzleId: 'pos-123',
          targetMove: 'Bb5',
          openingTitle: 'Cluster Weakness: Move 3',
          movesPlayed: [expect.objectContaining({ move: 'Bc4' })],
        }),
        expect.arrayContaining([
          expect.objectContaining({
            puzzleId: 'pos-123',
            targetMove: 'Bb5',
          }),
        ])
      );
    });
  });

  it('pre-fetches all pages when cluster contains more than 100 positions and passes all puzzles to onSelectPractice', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);

    const page0Positions = Array.from({ length: 100 }, (_, i) => ({
      positionId: `pos-p0-${i}`,
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
      playerColor: 'WHITE' as const,
      movePlayed: 'Bc4',
      targetMove: 'Bb5',
      acceptableMoves: [{ move: 'Bb5', evalLoss: 0.0 }],
      evalLoss: 0.85,
      mistakeSeverity: 'MINOR' as const,
      decisionTimeMs: 3200,
      decisionSeverity: 'NO_CONCERN' as const,
      timeControl: 'RAPID',
      gameId: `game-p0-${i}`,
      platformGameId: `chesscom_p0_${i}`,
      plyNumber: 5,
      moveNumber: 3,
    }));

    const page1Positions = Array.from({ length: 20 }, (_, i) => ({
      positionId: `pos-p1-${i}`,
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
      playerColor: 'WHITE' as const,
      movePlayed: 'Bc4',
      targetMove: 'Bb5',
      acceptableMoves: [{ move: 'Bb5', evalLoss: 0.0 }],
      evalLoss: 0.90,
      mistakeSeverity: 'MINOR' as const,
      decisionTimeMs: 2800,
      decisionSeverity: 'NO_CONCERN' as const,
      timeControl: 'RAPID',
      gameId: `game-p1-${i}`,
      platformGameId: `chesscom_p1_${i}`,
      plyNumber: 5,
      moveNumber: 3,
    }));

    vi.mocked(api.getClusterPositions)
      .mockResolvedValueOnce({
        page: 0,
        size: 100,
        totalElements: 120,
        totalPages: 2,
        hasNext: true,
        positions: page0Positions,
      })
      .mockResolvedValueOnce({
        page: 1,
        size: 100,
        totalElements: 120,
        totalPages: 2,
        hasNext: false,
        positions: page1Positions,
      });

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    fireEvent.click(practiceBtn);

    await waitFor(() => {
      expect(api.getClusterPositions).toHaveBeenCalledTimes(2);
      expect(api.getClusterPositions).toHaveBeenNthCalledWith(
        1,
        'cluster-uuid-1',
        expect.objectContaining({ page: 0, size: 100, playableOnly: true })
      );
      expect(api.getClusterPositions).toHaveBeenNthCalledWith(
        2,
        'cluster-uuid-1',
        expect.objectContaining({ page: 1, size: 100, playableOnly: true })
      );

      expect(onSelectPractice).toHaveBeenCalledWith(
        expect.objectContaining({ puzzleId: 'pos-p0-0' }),
        expect.any(Array)
      );
    });

    expect(onSelectPractice.mock.calls[0][1]).toHaveLength(120);
  });

  it('renders inline alert when cluster contains zero playable positions', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 100,
      totalElements: 0,
      totalPages: 0,
      hasNext: false,
      positions: [],
    });

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    fireEvent.click(practiceBtn);

    await waitFor(() => {
      expect(screen.getByText(/No verified engine solutions available to practice for this cluster/i)).toBeInTheDocument();
      expect(onSelectPractice).not.toHaveBeenCalled();
    });
  });

  it('renders inline alert when practice playlist retrieval fails', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);
    vi.mocked(api.getClusterPositions).mockRejectedValueOnce(new Error('Network error on practice'));

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    fireEvent.click(practiceBtn);

    await waitFor(() => {
      expect(screen.getByText(/Network error on practice/i)).toBeInTheDocument();
      expect(onSelectPractice).not.toHaveBeenCalled();
    });
  });

  it('allows toggling between Thematic Clusters and All Positions views', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);

    render(<WeaknessesList {...defaultProps} />);

    await screen.findByText(/Thematic Clusters/i);

    const positionsToggle = screen.getByRole('button', { name: /^All Positions$/i });
    fireEvent.click(positionsToggle);

    // Positions mode active
    expect(positionsToggle.className).toMatch(/bg-surface-container|text-primary/);

    const clustersToggle = screen.getByRole('button', { name: /^Thematic Clusters$/i });
    fireEvent.click(clustersToggle);

    // Clusters mode restored
    expect(clustersToggle.className).toMatch(/bg-surface-container|text-primary/);
  });

  it('renders retryable StatusBanner when fetching clusters fails', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockRejectedValueOnce(new Error('Network disconnected'));

    render(<WeaknessesList {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByText(/Network disconnected/i)).toBeInTheDocument();
    });
  });

  it('does not launch practice and displays error when page returns empty positions with hasNext=true', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 100,
      totalElements: 10,
      totalPages: 1,
      hasNext: true,
      positions: [],
    });

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    fireEvent.click(practiceBtn);

    await waitFor(() => {
      expect(screen.getByText(/Inconsistent pagination metadata: empty page received with hasNext=true/i)).toBeInTheDocument();
      expect(onSelectPractice).not.toHaveBeenCalled();
    });
  });

  it('does not launch practice and displays error when later page fails in multi-page playlist', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);
    vi.mocked(api.getClusterPositions)
      .mockResolvedValueOnce({
        page: 0,
        size: 100,
        totalElements: 120,
        totalPages: 2,
        hasNext: true,
        positions: [
          {
            positionId: 'pos-1',
            fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
            playerColor: 'WHITE',
            movePlayed: 'Bc4',
            targetMove: 'Bb5',
            acceptableMoves: [{ move: 'Bb5', evalLoss: 0.0 }],
            evalLoss: 0.85,
            mistakeSeverity: 'MINOR',
            decisionTimeMs: 3200,
            decisionSeverity: 'NO_CONCERN',
            timeControl: 'RAPID',
            gameId: 'game-1',
            platformGameId: 'chesscom_1',
            plyNumber: 5,
            moveNumber: 3,
          },
        ],
      })
      .mockRejectedValueOnce(new Error('Network failure on page 1'));

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    fireEvent.click(practiceBtn);

    await waitFor(() => {
      expect(screen.getByText(/Network failure on page 1/i)).toBeInTheDocument();
      expect(onSelectPractice).not.toHaveBeenCalled();
    });
  });

  it('does not launch practice and displays error when pagination metadata is inconsistent with totalElements', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 100,
      totalElements: 50,
      totalPages: 1,
      hasNext: false,
      positions: [
        {
          positionId: 'pos-1',
          fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
          playerColor: 'WHITE',
          movePlayed: 'Bc4',
          targetMove: 'Bb5',
          acceptableMoves: [{ move: 'Bb5', evalLoss: 0.0 }],
          evalLoss: 0.85,
          mistakeSeverity: 'MINOR',
          decisionTimeMs: 3200,
          decisionSeverity: 'NO_CONCERN',
          timeControl: 'RAPID',
          gameId: 'game-1',
          platformGameId: 'chesscom_1',
          plyNumber: 5,
          moveNumber: 3,
        },
      ],
    });

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    fireEvent.click(practiceBtn);

    await waitFor(() => {
      expect(screen.getByText(/Incomplete playlist: retrieved 1 of 50 positions/i)).toBeInTheDocument();
      expect(onSelectPractice).not.toHaveBeenCalled();
    });
  });
});
