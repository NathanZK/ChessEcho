import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ClusterEvidenceModal } from '../components/ClusterEvidenceModal';
import * as api from '../services/api';

vi.mock('react-chessboard', () => ({
  Chessboard: (props: { options?: Record<string, unknown> }) => (
    <div data-testid="mock-chessboard" data-options={JSON.stringify(props?.options || {})}>
      Mock Chessboard
    </div>
  ),
}));

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual,
    getClusterPositions: vi.fn(),
  };
});

describe('ClusterEvidenceModal Component (#758)', () => {
  const onClose = vi.fn();
  const mockCluster: api.RecurringWeaknessResponse = {
    clusterId: 'cluster-uuid-1',
    clusterType: 'STRUCTURAL',
    recommendationPriority: 0.3,
    representativeFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    memberPositionCount: 4,
    totalOccurrences: 20,
    distinctGamesCount: 15,
    mistakeCount: 12,
    mistakeFrequency: 0.6,
    blunderRate: 0.25,
    evaluatedOccurrences: 18,
    unevaluatedOccurrences: 2,
    evalLossDistribution: {
      MINOR: 6,
      MODERATE: 4,
      SIGNIFICANT: 3,
      SEVERE: 5,
      UNEVALUATED: 2,
    },
    evalLossPercentages: {
      MINOR: 30,
      MODERATE: 20,
      SIGNIFICANT: 15,
      SEVERE: 25,
      UNEVALUATED: 10,
    },
    timingProfile: { FAST: 2, NORMAL: 10, SLOW: 8 },
    formatBreakdown: { RAPID: 12, BLITZ: 6, BULLET: 2 },
  };

  const mockEvaluatedPosition: api.ClusterMemberPosition = {
    positionId: 'pos-1',
    fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    playerColor: 'WHITE',
    movePlayed: 'Bc4',
    targetMove: 'Bb5',
    acceptableMoves: [{ move: 'Bb5', evalLoss: 0.0 }, { move: 'd4', evalLoss: 0.15 }],
    evalLoss: 0.85,
    mistakeSeverity: 'MINOR',
    decisionTimeMs: 3200,
    decisionSeverity: 'NO_CONCERN',
    timeControl: 'RAPID',
    gameId: 'game-1',
    platformGameId: '123456789',
    plyNumber: 5,
    moveNumber: 3,
  };

  const mockUnevaluatedPosition: api.ClusterMemberPosition = {
    positionId: 'pos-2',
    fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
    playerColor: 'BLACK',
    movePlayed: 'exd4',
    targetMove: null,
    acceptableMoves: null,
    evalLoss: null,
    mistakeSeverity: null,
    decisionTimeMs: null,
    decisionSeverity: 'UNKNOWN',
    timeControl: 'BLITZ',
    gameId: 'game-2',
    platformGameId: '987654321',
    plyNumber: 6,
    moveNumber: 3,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders modal dialog with accessibility attributes and header metrics', async () => {
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      positions: [mockEvaluatedPosition],
    });

    render(
      <ClusterEvidenceModal
        cluster={mockCluster}
        accountId="test-account"
        onClose={onClose}
      />
    );

    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeInTheDocument();
    expect(dialog).toHaveAttribute('aria-labelledby');
    expect(screen.getByRole('heading', { name: /Cluster Evidence Inspection/i })).toBeInTheDocument();

    // Aggregate stats in header
    expect(screen.getByText(/4 variations/i)).toBeInTheDocument();
    expect(screen.getByText(/20 occurrences/i)).toBeInTheDocument();
  });

  it('closes dialog on ESC key or close button', async () => {
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      positions: [mockEvaluatedPosition],
    });

    render(
      <ClusterEvidenceModal
        cluster={mockCluster}
        accountId="test-account"
        onClose={onClose}
      />
    );

    const closeBtn = screen.getByRole('button', { name: /close dialog/i });
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: 'Escape', code: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('displays aggregate MistakeSeverity distribution and TimeControl breakdown', async () => {
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      positions: [mockEvaluatedPosition],
    });

    render(
      <ClusterEvidenceModal
        cluster={mockCluster}
        accountId="test-account"
        onClose={onClose}
      />
    );

    // MistakeSeverity distribution
    await waitFor(() => {
      expect(screen.getByText(/Minor: 6 \(30%\)/i)).toBeInTheDocument();
      expect(screen.getByText(/Moderate: 4 \(20%\)/i)).toBeInTheDocument();
      expect(screen.getByText(/Significant: 3 \(15%\)/i)).toBeInTheDocument();
      expect(screen.getByText(/Severe: 5 \(25%\)/i)).toBeInTheDocument();
      expect(screen.getByText(/Unevaluated: 2 \(10%\)/i)).toBeInTheDocument();
    });

    // TimeControl breakdown
    expect(screen.getByText(/Rapid: 12/i)).toBeInTheDocument();
    expect(screen.getByText(/Blitz: 6/i)).toBeInTheDocument();
    expect(screen.getByText(/Bullet: 2/i)).toBeInTheDocument();
  });

  it('renders all 8 position attributes for evaluated and unevaluated positions', async () => {
    vi.mocked(api.getClusterPositions).mockResolvedValueOnce({
      page: 0,
      size: 10,
      totalElements: 2,
      totalPages: 1,
      hasNext: false,
      positions: [mockEvaluatedPosition, mockUnevaluatedPosition],
    });

    render(
      <ClusterEvidenceModal
        cluster={mockCluster}
        accountId="test-account"
        onClose={onClose}
      />
    );

    // Evaluated position card
    await waitFor(() => {
      expect(screen.getByText('Bc4')).toBeInTheDocument();
      expect(screen.getByText('Bb5')).toBeInTheDocument();
      expect(screen.getByText(/0.85 pawns/i)).toBeInTheDocument();
      expect(screen.getByText('3.2s')).toBeInTheDocument();
      expect(screen.getByText('NO_CONCERN')).toBeInTheDocument();
      expect(screen.getByText('Move 3 (ply 5)')).toBeInTheDocument();
    });

    // Acceptable alternatives
    expect(screen.getByText(/d4 \[0\.15\]/i)).toBeInTheDocument();

    // Unevaluated position card
    expect(screen.getByText('exd4')).toBeInTheDocument();
    expect(screen.getByText('Pending analysis')).toBeInTheDocument();
    expect(screen.getAllByText('Unevaluated').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    expect(screen.getByText('Move 3 (ply 6)')).toBeInTheDocument();
  });

  it('handles pagination navigation between pages', async () => {
    vi.mocked(api.getClusterPositions)
      .mockResolvedValueOnce({
        page: 0,
        size: 1,
        totalElements: 2,
        totalPages: 2,
        hasNext: true,
        positions: [mockEvaluatedPosition],
      })
      .mockResolvedValueOnce({
        page: 1,
        size: 1,
        totalElements: 2,
        totalPages: 2,
        hasNext: false,
        positions: [mockUnevaluatedPosition],
      });

    render(
      <ClusterEvidenceModal
        cluster={mockCluster}
        accountId="test-account"
        onClose={onClose}
      />
    );

    await waitFor(() => {
      expect(screen.getByText(/Page 1 of 2/i)).toBeInTheDocument();
    });

    const nextBtn = screen.getByRole('button', { name: /next page/i });
    expect(nextBtn).not.toBeDisabled();
    fireEvent.click(nextBtn);

    await waitFor(() => {
      expect(api.getClusterPositions).toHaveBeenCalledWith(
        'cluster-uuid-1',
        expect.objectContaining({ page: 1, accountId: 'test-account' })
      );
      expect(screen.getByText(/Page 2 of 2/i)).toBeInTheDocument();
    });
  });

  it('displays retryable error banner when fetching positions fails', async () => {
    vi.mocked(api.getClusterPositions)
      .mockRejectedValueOnce(new Error('Failed to load evidence positions'))
      .mockResolvedValueOnce({
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
        positions: [mockEvaluatedPosition],
      });

    render(
      <ClusterEvidenceModal
        cluster={mockCluster}
        accountId="test-account"
        onClose={onClose}
      />
    );

    await waitFor(() => {
      expect(screen.getByText(/Failed to load evidence positions/i)).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: /retry/i });
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(api.getClusterPositions).toHaveBeenCalledTimes(2);
      expect(screen.getByText('Bc4')).toBeInTheDocument();
    });
  });
});
