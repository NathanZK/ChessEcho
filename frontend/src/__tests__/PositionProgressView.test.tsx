import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PositionProgressView } from '../components/PositionProgressView';
import * as api from '../services/api';
import type { PositionProgressResponse } from '../services/api';

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return { ...actual, fetchPositionProgress: vi.fn() };
});

const response: PositionProgressResponse = {
  positionId: 'position-1',
  playerColor: 'BLACK',
  points: [
    { occurredAt: '2026-01-01T00:00:00Z', mistakeRate: 50, winRate: 25, attempts: 4 },
    { occurredAt: '2026-02-01T00:00:00Z', mistakeRate: 25, winRate: 50, attempts: 8 },
  ],
  mistakeRateChange: -50,
  winRateChange: 100,
  assessment: 'You are making fewer mistakes at this position.',
};

const defaultProps = {
  positionId: 'position-1',
  playerColor: 'BLACK' as const,
  sessionStatus: 'authenticated' as const,
  onBack: vi.fn(),
};

describe('PositionProgressView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.fetchPositionProgress).mockResolvedValue(response);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads and renders both history series, the supplied changes, and assessment verbatim', async () => {
    render(<PositionProgressView {...defaultProps} />);

    await waitFor(() => {
      expect(api.fetchPositionProgress).toHaveBeenCalledWith('position-1', 'BLACK');
      expect(screen.getByText('You are making fewer mistakes at this position.')).toBeInTheDocument();
    });

    const chart = screen.getByRole('img', { name: /position progress over time/i });
    expect(chart).toHaveAttribute('aria-describedby', 'progress-chart-description');
    expect(screen.getByText(/mistake rate is green and win rate is blue/i)).toBeInTheDocument();
    expect(screen.getByTestId('progress-series-mistake-rate')).toHaveAttribute(
      'points',
      '42,130 558,175',
    );
    expect(screen.getByTestId('progress-series-win-rate')).toHaveAttribute(
      'points',
      '42,175 558,130',
    );
    expect(screen.getByText('-50%')).toBeInTheDocument();
    expect(screen.getByText('+100%')).toBeInTheDocument();
  });

  it('shows sign-in CTA without requesting progress for unauthenticated users', () => {
    render(<PositionProgressView {...defaultProps} sessionStatus="unauthenticated" />);

    expect(screen.getByRole('link', { name: /sign in/i })).toHaveAttribute('href', '/login');
    expect(api.fetchPositionProgress).not.toHaveBeenCalled();
  });

  it('distinguishes unresolved and failed session checks without requesting progress', () => {
    const { rerender } = render(<PositionProgressView {...defaultProps} sessionStatus="loading" />);

    expect(screen.getByText('Checking sign-in…')).toBeInTheDocument();
    expect(api.fetchPositionProgress).not.toHaveBeenCalled();

    rerender(<PositionProgressView {...defaultProps} sessionStatus="error" />);

    expect(
      screen.getByText('Unable to verify sign-in. Refresh the page and try again.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /sign in/i })).not.toBeInTheDocument();
    expect(api.fetchPositionProgress).not.toHaveBeenCalled();
  });

  it('shows a loading state while the authenticated request is pending', async () => {
    let resolve!: (value: PositionProgressResponse) => void;
    vi.mocked(api.fetchPositionProgress).mockReturnValue(
      new Promise((res) => {
        resolve = res;
      }),
    );

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText('Loading position progress…')).toBeInTheDocument();
    resolve(response);
    await screen.findByText(response.assessment);
  });

  it('shows a retryable failure state when the progress request fails', async () => {
    vi.mocked(api.fetchPositionProgress)
      .mockRejectedValueOnce(new Error('Unavailable'))
      .mockResolvedValueOnce(response);

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(/couldn't load position progress/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));

    await screen.findByText(response.assessment);
    expect(api.fetchPositionProgress).toHaveBeenCalledTimes(2);
  });

  it('shows a no-history state for a valid empty history', async () => {
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce({ ...response, points: [] });

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(/no progress history yet/i)).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /position progress over time/i })).not.toBeInTheDocument();
    expect(screen.getByText(response.assessment)).toBeInTheDocument();
  });

  it('shows the single point but does not present it as a trend', async () => {
    const singlePoint = { ...response, points: [response.points[0]] };
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce(singlePoint);

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findAllByText('Not enough history yet')).toHaveLength(2);
    expect(screen.getByText(/one encounter is not enough history to show a trend/i)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /position progress over time/i })).toBeInTheDocument();
    expect(screen.getAllByTestId('progress-point-mistake-rate')).toHaveLength(1);
    expect(screen.getAllByTestId('progress-point-win-rate')).toHaveLength(1);
    expect(screen.queryByText('-50%')).not.toBeInTheDocument();
    expect(screen.queryByText('+100%')).not.toBeInTheDocument();
  });

  it('shows unavailable text for null changes when history is sufficient', async () => {
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce({
      ...response,
      mistakeRateChange: null,
      winRateChange: null,
    });

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findAllByText('Not available yet')).toHaveLength(2);
  });
});
