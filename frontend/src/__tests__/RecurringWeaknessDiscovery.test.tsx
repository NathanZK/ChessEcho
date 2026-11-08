import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WeaknessesList } from '../components/WeaknessesList';
import * as api from '../services/api';

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual,
    fetchWeaknesses: vi.fn().mockResolvedValue([]),
    fetchRecurringWeaknesses: vi.fn().mockResolvedValue([]),
    triggerRecurringDiscovery: vi.fn(),
    getRecurringDiscoveryJobStatus: vi.fn(),
    cancelRecurringDiscoveryJob: vi.fn(),
  };
});

describe('Recurring Weakness Discovery & Polling (#735)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const defaultProps = {
    accountId: 'test-account-uuid',
    username: 'magnus',
    onSelectPractice: vi.fn(),
  };

  it('renders "Find Recurring Weaknesses" action button in the toolbar', async () => {
    render(<WeaknessesList {...defaultProps} />);

    const findBtn = screen.getByRole('button', { name: /Find Recurring Weaknesses/i });
    expect(findBtn).toBeInTheDocument();
  });

  it('displays connection prompt when attempting discovery without a connected account', async () => {
    render(<WeaknessesList {...defaultProps} accountId={undefined} username={undefined} />);

    const findBtn = screen.getByRole('button', { name: /Find Recurring Weaknesses/i });
    fireEvent.click(findBtn);

    expect(screen.getByRole('heading', { name: 'Connect Account' })).toBeInTheDocument();
  });

  it('opens discovery modal, submits parameters, and renders progress banner', async () => {
    const mockJob: api.RecurringDiscoveryJobResponse = {
      jobId: 'job-123',
      accountId: 'test-account-uuid',
      status: 'PROCESSING',
      progressPercent: 35,
      stage: 'CLUSTERING',
      createdAt: '2026-11-09T00:00:00Z',
      updatedAt: '2026-11-09T00:01:00Z',
    };

    vi.mocked(api.triggerRecurringDiscovery).mockResolvedValueOnce(mockJob);
    vi.mocked(api.getRecurringDiscoveryJobStatus).mockResolvedValue(mockJob);

    render(<WeaknessesList {...defaultProps} />);

    const findBtn = screen.getByRole('button', { name: /Find Recurring Weaknesses/i });
    fireEvent.click(findBtn);

    // Modal is open
    expect(screen.getByRole('dialog', { name: /Discover Recurring Weaknesses/i })).toBeInTheDocument();

    // Toggle Blitz pill off and on
    const blitzPill = screen.getByRole('button', { name: /^Blitz$/i });
    fireEvent.click(blitzPill); // unchecked
    fireEvent.click(blitzPill); // checked again

    // Submit discovery
    const startBtn = screen.getByRole('button', { name: /Start Discovery/i });
    fireEvent.click(startBtn);

    await waitFor(() => {
      expect(api.triggerRecurringDiscovery).toHaveBeenCalledWith(
        expect.objectContaining({
          accountId: 'test-account-uuid',
          timeControls: expect.arrayContaining(['RAPID', 'BLITZ', 'BULLET']),
          minEvalLoss: 0.8,
        })
      );
    });

    // Modal closes and progress banner appears
    await waitFor(() => {
      expect(screen.getByText(/Stage: CLUSTERING/i)).toBeInTheDocument();
      expect(screen.getByText(/35%/i)).toBeInTheDocument();
    });
  });

  it('cancels discovery job when Cancel button is clicked in the progress banner', async () => {
    const mockJob: api.RecurringDiscoveryJobResponse = {
      jobId: 'job-123',
      accountId: 'test-account-uuid',
      status: 'PROCESSING',
      progressPercent: 50,
      stage: 'EVALUATION',
      createdAt: '2026-11-09T00:00:00Z',
      updatedAt: '2026-11-09T00:01:00Z',
    };

    vi.mocked(api.triggerRecurringDiscovery).mockResolvedValueOnce(mockJob);
    vi.mocked(api.getRecurringDiscoveryJobStatus).mockResolvedValue(mockJob);
    vi.mocked(api.cancelRecurringDiscoveryJob).mockResolvedValueOnce({
      ...mockJob,
      status: 'CANCELLED',
    });

    render(<WeaknessesList {...defaultProps} />);

    fireEvent.click(screen.getByRole('button', { name: /Find Recurring Weaknesses/i }));
    fireEvent.click(screen.getByRole('button', { name: /Start Discovery/i }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Cancel Discovery|Cancel/i })).toBeInTheDocument();
    });

    const cancelBtn = screen.getByRole('button', { name: /Cancel Discovery|Cancel/i });
    fireEvent.click(cancelBtn);

    await waitFor(() => {
      expect(api.cancelRecurringDiscoveryJob).toHaveBeenCalledWith('job-123', 'test-account-uuid', 'magnus');
    });
  });

  it('cleans up polling timers when component unmounts', async () => {
    vi.useFakeTimers();

    const mockJob: api.RecurringDiscoveryJobResponse = {
      jobId: 'job-123',
      accountId: 'test-account-uuid',
      status: 'PROCESSING',
      progressPercent: 10,
      stage: 'INGESTION',
      createdAt: '2026-11-09T00:00:00Z',
      updatedAt: '2026-11-09T00:01:00Z',
    };

    vi.mocked(api.triggerRecurringDiscovery).mockResolvedValueOnce(mockJob);
    vi.mocked(api.getRecurringDiscoveryJobStatus).mockResolvedValue(mockJob);

    const { unmount } = render(<WeaknessesList {...defaultProps} />);

    fireEvent.click(screen.getByRole('button', { name: /Find Recurring Weaknesses/i }));

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Start Discovery/i }));
    });

    unmount();

    // Advance timers - should not trigger any further status calls
    const initialCallCount = vi.mocked(api.getRecurringDiscoveryJobStatus).mock.calls.length;
    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(vi.mocked(api.getRecurringDiscoveryJobStatus).mock.calls.length).toBe(initialCallCount);

    vi.useRealTimers();
  });
});
