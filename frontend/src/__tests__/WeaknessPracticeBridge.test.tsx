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

  it('triggers onSelectPractice when "Practice This Weakness" is clicked on a cluster card', async () => {
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValueOnce([mockCluster]);

    render(<WeaknessesList {...defaultProps} />);

    const practiceBtn = await screen.findByRole('button', { name: /Practice This Weakness/i });
    expect(practiceBtn).toBeInTheDocument();

    fireEvent.click(practiceBtn);

    expect(onSelectPractice).toHaveBeenCalledWith(
      expect.objectContaining({
        puzzleId: 'cluster-uuid-1',
        fen: mockCluster.representativeFen,
        timesReached: 18,
        mistakeCount: 10,
      })
    );
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
});
