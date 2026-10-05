import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
  assessment: 'You are making fewer mistakes at this position, and your win rate has increased.',
};

const defaultProps = {
  positionId: 'position-1',
  playerColor: 'BLACK' as const,
  minEvalLoss: 0.3,
  accountId: 'account-1',
  sessionStatus: 'authenticated' as const,
  onBack: vi.fn(),
};

describe('PositionProgressView', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.fetchPositionProgress).mockResolvedValue(response);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads and renders both history series, the supplied changes, and assessment verbatim', async () => {
    render(<PositionProgressView {...defaultProps} />);

    await waitFor(() => {
      expect(api.fetchPositionProgress).toHaveBeenCalledWith('position-1', 'BLACK', 'account-1', 0.3);
      expect(screen.getByText(response.assessment)).toBeInTheDocument();
    });

    const chart = screen.getByRole('img', { name: /position progress over time/i });
    expect(chart).toHaveAttribute('aria-describedby', 'progress-chart-description');
    expect(screen.getByText(/mistake rate is green and win rate is blue/i)).toBeInTheDocument();
    expect(screen.getByTestId('progress-series-mistake-rate')).toHaveAttribute(
      'points',
      '42,130 300,130 558,175',
    );
    expect(screen.getByTestId('progress-series-win-rate')).toHaveAttribute(
      'points',
      '42,175 300,175 558,130',
    );
    expect(screen.getByTestId('progress-interval-label-baseline')).toHaveTextContent(
      `Historical baseline · 4 encounters · ${new Date(response.baseline!.occurredAt).toLocaleDateString()}`,
    );
    expect(screen.getByText('Historical baseline')).toBeInTheDocument();
    const latestInterval = await screen.findByRole('region', { name: 'Latest measured interval' });
    expect(within(latestInterval).getByText('25%')).toBeInTheDocument();
    expect(within(latestInterval).getByText('50%')).toBeInTheDocument();
    expect(within(latestInterval).getByText('8 attempts')).toBeInTheDocument();
    expect(within(latestInterval).getByText('Open interval')).toBeInTheDocument();
    expect(within(latestInterval).getByText(
      `Latest included game: ${new Date(response.points[1].occurredAt).toLocaleDateString()}`,
    )).toBeInTheDocument();
    expect(screen.getByText('4 dated encounters')).toBeInTheDocument();
    expect(screen.queryByText('Current interval is open.')).not.toBeInTheDocument();
    expect(screen.getByTestId('progress-interval-label-00000000-0000-0000-0000-000000000001'))
      .toHaveTextContent(`Interval 1 · 4 encounters · ${new Date(response.points[0].occurredAt).toLocaleDateString()}`);
    expect(screen.getByTestId('progress-interval-label-00000000-0000-0000-0000-000000000002'))
      .toHaveTextContent(`Interval 2 · 8 encounters · ${new Date(response.points[1].occurredAt).toLocaleDateString()}`);
    const tooltipTitles = [...chart.querySelectorAll('title')].map((title) => title.textContent);
    expect(tooltipTitles).toHaveLength(7);
    expect(tooltipTitles.join(' ')).not.toMatch(/open|closed/i);
    expect(screen.getByText('-50%')).toBeInTheDocument();
    expect(screen.getByText('+100%')).toBeInTheDocument();
  });

  it('shows sign-in CTA without requesting progress for unauthenticated users', () => {
    render(<PositionProgressView {...defaultProps} sessionStatus="unauthenticated" />);

    expect(screen.getByRole('link', { name: /sign in/i })).toHaveAttribute('href', '/login');
    expect(api.fetchPositionProgress).not.toHaveBeenCalled();
  });

  it('does not display a stale response when the same position is selected with a new threshold', async () => {
    let resolveOld!: (value: PositionProgressResponse) => void;
    let resolveNew!: (value: PositionProgressResponse) => void;
    vi.mocked(api.fetchPositionProgress)
      .mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve; }));
    const { rerender } = render(<PositionProgressView {...defaultProps} />);

    rerender(<PositionProgressView {...defaultProps} minEvalLoss={0.5} />);
    expect(api.fetchPositionProgress).toHaveBeenLastCalledWith('position-1', 'BLACK', 'account-1', 0.5);
    await act(async () => {
      resolveOld(response);
    });
    expect(screen.getByText('Loading position progress…')).toBeInTheDocument();
    expect(screen.queryByText(response.assessment)).not.toBeInTheDocument();
    await act(async () => {
      resolveNew({ ...response, assessment: 'New threshold report' });
    });
    expect(screen.getByText('New threshold report')).toBeInTheDocument();
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
    const latestInterval = screen.getByRole('region', { name: 'Latest measured interval' });
    expect(within(latestInterval).getByText(/no measured interval data yet/i)).toBeInTheDocument();
    expect(within(latestInterval).queryByText('Mistake rate')).not.toBeInTheDocument();
    expect(screen.getByText('4 dated encounters')).toBeInTheDocument();
    const baselineSummary = screen.getByText('Historical baseline').parentElement!;
    expect(within(baselineSummary).getByText('50%')).toBeInTheDocument();
    expect(within(baselineSummary).getByText('25%')).toBeInTheDocument();
    expect(screen.getByText(`Dated through ${new Date(response.baseline!.occurredAt).toLocaleDateString()}`))
      .toBeInTheDocument();
    expect(screen.getByText('No measured interval observations yet.')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /position progress over time/i })).toBeInTheDocument();
    expect(screen.getAllByTestId('progress-point-mistake-rate')).toHaveLength(1);
    expect(screen.getAllByTestId('progress-point-win-rate')).toHaveLength(1);
    expect(screen.getByTestId('progress-interval-label-baseline')).toHaveTextContent(
      `Historical baseline · 4 encounters · ${new Date(response.baseline!.occurredAt).toLocaleDateString()}`,
    );
    expect(screen.getByText('More played encounters are needed to assess progress.')).toBeInTheDocument();
  });

  it('shows a single measured interval after the historical baseline point', async () => {
    const singlePoint = { ...response, points: [response.points[0]] };
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce(singlePoint);

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(response.assessment)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /position progress over time/i })).toBeInTheDocument();
    expect(screen.getAllByTestId('progress-point-mistake-rate')).toHaveLength(2);
    expect(screen.getAllByTestId('progress-point-win-rate')).toHaveLength(2);
    expect(screen.getByText('-50%')).toBeInTheDocument();
    expect(screen.getByText('+100%')).toBeInTheDocument();
  });

  it('plots the observed baseline and subsequent interval as exactly two chronological points', async () => {
    const example: PositionProgressResponse = {
      ...response,
      baseline: {
        occurredAt: '2025-12-01T00:00:00Z',
        mistakeRate: (16 / 19) * 100,
        winRate: (6 / 19) * 100,
        sourceEncounterCount: 19,
      },
      points: [{
        checkpointId: '00000000-0000-0000-0000-000000000003',
        occurredAt: '2026-01-01T00:00:00Z',
        mistakeRate: 0,
        winRate: 100,
        attempts: 1,
        open: true,
      }],
    };
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce(example);

    render(<PositionProgressView {...defaultProps} />);

    const chart = await screen.findByRole('img', { name: /position progress over time/i });
    expect(screen.getAllByTestId('progress-point-mistake-rate')).toHaveLength(2);
    expect(screen.getAllByTestId('progress-point-win-rate')).toHaveLength(2);
    expect(screen.getByTestId('progress-series-mistake-rate')).toHaveAttribute(
      'points',
      '42,68.42105263157896 558,220',
    );
    expect(screen.getByTestId('progress-series-win-rate')).toHaveAttribute(
      'points',
      '42,163.1578947368421 558,40',
    );
    expect(screen.getByTestId('progress-interval-label-baseline')).toHaveTextContent(
      `Historical baseline · 19 encounters · ${new Date(example.baseline!.occurredAt).toLocaleDateString()}`,
    );
    expect(screen.getByTestId('progress-interval-label-00000000-0000-0000-0000-000000000003'))
      .toHaveTextContent(`Interval 1 · 1 encounter · ${new Date(example.points[0].occurredAt).toLocaleDateString()}`);
    expect(within(screen.getByRole('region', { name: 'Latest measured interval' })).getByText('100%'))
      .toBeInTheDocument();
    expect(chart.querySelectorAll('circle')).toHaveLength(4);
  });

  it('uses the earliest interval as the displayed baseline without duplicating its graph point', async () => {
    const intervalOnly = {
      ...response,
      baseline: null,
    };
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce(intervalOnly);

    render(<PositionProgressView {...defaultProps} />);

    await screen.findByRole('img', { name: /position progress over time/i });
    const first = intervalOnly.points[0];
    expect(screen.getByText('Historical baseline')).toBeInTheDocument();
    expect(screen.getByText(`${first.attempts} dated encounters`)).toBeInTheDocument();
    expect(screen.getByText(`Dated through ${new Date(first.occurredAt).toLocaleDateString()}`))
      .toBeInTheDocument();
    expect(screen.getAllByTestId('progress-point-mistake-rate')).toHaveLength(2);
    expect(screen.getAllByTestId('progress-point-win-rate')).toHaveLength(2);
    expect(screen.getByTestId(`progress-interval-label-${first.checkpointId}`)).toHaveTextContent(
      `Baseline · Interval 1 · ${first.attempts} encounters · ${new Date(first.occurredAt).toLocaleDateString()}`,
    );
  });

  it('keeps the empty state when neither baseline nor measured intervals exist', async () => {
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce({
      ...response,
      baseline: null,
      points: [],
      currentIntervalState: 'NO_CHECKPOINT',
      mistakeRateChange: null,
      winRateChange: null,
    });

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText('No measured interval observations yet.')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /position progress over time/i })).not.toBeInTheDocument();
    expect(screen.queryAllByTestId('progress-point-mistake-rate')).toHaveLength(0);
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

  it('keeps a measured closed interval summary while the current interval awaits evidence', async () => {
    vi.mocked(api.fetchPositionProgress).mockResolvedValueOnce({
      ...response,
      points: [response.points[0]],
      currentIntervalState: 'OPEN_AWAITING_EVIDENCE',
    });

    render(<PositionProgressView {...defaultProps} />);

    expect(await screen.findByText(/waiting for a dated game after the latest solve/i)).toBeInTheDocument();
    const latestInterval = screen.getByRole('region', { name: 'Latest measured interval' });
    expect(within(latestInterval).getByText('Closed interval')).toBeInTheDocument();
    expect(within(latestInterval).getByText('4 attempts')).toBeInTheDocument();
    expect(screen.getByTestId('progress-interval-label-00000000-0000-0000-0000-000000000001'))
      .toHaveTextContent(`Interval 1 · 4 encounters · ${new Date(response.points[0].occurredAt).toLocaleDateString()}`);
    expect(screen.getByTestId('progress-interval-label-00000000-0000-0000-0000-000000000001'))
      .not.toHaveTextContent(/open|closed/i);
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
