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
  baseline: {
    occurredAt: '2025-12-01T00:00:00Z',
    mistakeRate: 50,
    winRate: 25,
    sourceEncounterCount: 4,
  },
  points: [
    {
      checkpointId: '00000000-0000-0000-0000-000000000001',
      occurredAt: '2026-01-01T00:00:00Z',
      mistakeRate: 50,
      winRate: 25,
      attempts: 4,
      open: false,
    },
    {
      checkpointId: '00000000-0000-0000-0000-000000000002',
      occurredAt: '2026-02-01T00:00:00Z',
      mistakeRate: 25,
      winRate: 50,
      attempts: 8,
      open: true,
    },
  ],
  currentIntervalState: 'MEASURED_OPEN',
  excludedUndatedEncounters: 0,
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
    expect(screen.getByText('Historical baseline')).toBeInTheDocument();
    expect(screen.getByText('4 dated encounters')).toBeInTheDocument();
    expect(screen.getByText('Current interval is open.')).toBeInTheDocument();
    expect(screen.getByTestId('progress-interval-status-00000000-0000-0000-0000-000000000001'))
      .toHaveTextContent(/closed interval/i);
    expect(screen.getByTestId('progress-interval-status-00000000-0000-0000-0000-000000000002'))
      .toHaveTextContent(/open interval/i);
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

  it('shows baseline-only history without inventing a training interval', async () => {
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce({
      ...response,
      baseline: response.baseline,
      points: [],
      currentIntervalState: 'NO_CHECKPOINT',
      mistakeRateChange: null,
      winRateChange: null,
      assessment: 'More played encounters are needed to assess progress.',
    });

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(/no solved puzzle checkpoint yet/i)).toBeInTheDocument();
    expect(screen.getByText('4 dated encounters')).toBeInTheDocument();
    expect(screen.getByText('50%')).toBeInTheDocument();
    expect(screen.getByText('25%')).toBeInTheDocument();
    expect(screen.getByText(`Dated through ${new Date(response.baseline!.occurredAt).toLocaleDateString()}`))
      .toBeInTheDocument();
    expect(screen.getByText('No measured interval observations yet.')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /position progress over time/i })).not.toBeInTheDocument();
    expect(screen.queryAllByTestId('progress-point-mistake-rate')).toHaveLength(0);
    expect(screen.getByText('More played encounters are needed to assess progress.')).toBeInTheDocument();
  });

  it('shows a single measured interval and its supplied baseline comparison', async () => {
    const singlePoint = { ...response, points: [response.points[0]] };
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce(singlePoint);

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(response.assessment)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /position progress over time/i })).toBeInTheDocument();
    expect(screen.getAllByTestId('progress-point-mistake-rate')).toHaveLength(1);
    expect(screen.getAllByTestId('progress-point-win-rate')).toHaveLength(1);
    expect(screen.getByText('-50%')).toBeInTheDocument();
    expect(screen.getByText('+100%')).toBeInTheDocument();
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

  it('distinguishes a measured closed interval from an empty current interval', async () => {
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce({
      ...response,
      points: [response.points[0]],
      currentIntervalState: 'OPEN_AWAITING_EVIDENCE',
    });

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(/waiting for a dated game after the latest solve/i)).toBeInTheDocument();
    expect(screen.getByTestId('progress-interval-status-00000000-0000-0000-0000-000000000001'))
      .toHaveTextContent(/closed interval/i);
    expect(screen.queryByText('Current interval is open.')).not.toBeInTheDocument();
  });

  it('reports undated encounters excluded from rates and observations', async () => {
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce({
      ...response,
      excludedUndatedEncounters: 2,
    });

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(/2 undated encounters excluded because game date is unavailable/i)).toBeInTheDocument();
  });
});
