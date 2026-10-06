import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LongDecisionsView } from '../components/LongDecisionsView';
import * as api from '../services/api';
import type { LongDecisionOccurrence, LongDecisionPageResponse } from '../services/api';

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: { position: string } }) => (
    <div data-testid="long-decision-board">{options.position}</div>
  ),
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchLongDecisions: vi.fn(),
  };
});

const accountId = '550e8400-e29b-41d4-a716-446655440010';

function occurrence(id: string, overrides: Partial<LongDecisionOccurrence> = {}): LongDecisionOccurrence {
  return {
    id,
    positionId: `position-${id}`,
    fen: '8/8/8/8/8/8/8/8 w - -',
    playerColor: 'WHITE',
    plyNumber: 1,
    movePlayed: 'Nf3',
    decisionTimeMs: 120_000,
    timeControl: 'RAPID',
    platformGameId: `game-${id}`,
    playedAt: '2026-02-03T12:00:00Z',
    opponentUsername: 'opponent',
    ...overrides,
  };
}

function page(
  content: LongDecisionOccurrence[],
  overrides: Partial<LongDecisionPageResponse> = {},
): LongDecisionPageResponse {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length > 0 ? 1 : 0,
    hasNext: false,
    ...overrides,
  };
}

const connectedProps = {
  accountId,
  sessionStatus: 'authenticated' as const,
  accountStatus: 'connected' as const,
  onRetryAccountLoad: vi.fn(),
  onNavigateImport: vi.fn(),
};

describe('LongDecisionsView', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(page([occurrence('occurrence-1')]));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps time-control selection separate from an absolute threshold and shows an individual result neutrally', async () => {
    const user = userEvent.setup();
    render(<LongDecisionsView {...connectedProps} />);

    expect(screen.getByLabelText('Time control')).toBeInTheDocument();
    expect(screen.getByLabelText('Decision-time threshold (seconds)')).toHaveValue(30);
    expect(screen.getByText(/Time control chooses which games to search/i)).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Time control'), 'RAPID');
    await user.clear(screen.getByLabelText('Decision-time threshold (seconds)'));
    await user.type(screen.getByLabelText('Decision-time threshold (seconds)'), '120');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() =>
      expect(api.fetchLongDecisions).toHaveBeenCalledWith(accountId, 'RAPID', 120, 0, 20),
    );
    expect(await screen.findByTestId('long-decision-board')).toHaveTextContent(
      '8/8/8/8/8/8/8/8 w - -',
    );
    expect(screen.getByText('120 seconds')).toBeInTheDocument();
    expect(screen.getByText('Nf3')).toBeInTheDocument();
    expect(screen.getByText('opponent')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open game' })).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/game-occurrence-1',
    );
    expect(screen.getByText(/This result describes decision time, not move quality/i)).toBeInTheDocument();
    expect(screen.queryByText(/mistake|incorrect/i)).not.toBeInTheDocument();
  });

  it('validates positive whole seconds and displays an empty result state', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(page([]));
    render(<LongDecisionsView {...connectedProps} />);

    await user.clear(screen.getByLabelText('Decision-time threshold (seconds)'));
    await user.type(screen.getByLabelText('Decision-time threshold (seconds)'), '0');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(screen.getByText('Enter a positive whole number of seconds.')).toBeInTheDocument();
    expect(api.fetchLongDecisions).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText('Decision-time threshold (seconds)'));
    await user.type(screen.getByLabelText('Decision-time threshold (seconds)'), '30');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText(/No occurrences met this threshold/i)).toBeInTheDocument();
  });

  it('renders loading, request error, and retry states', async () => {
    const user = userEvent.setup();
    let resolvePage!: (value: LongDecisionPageResponse) => void;
    vi.mocked(api.fetchLongDecisions).mockReturnValue(
      new Promise<LongDecisionPageResponse>((resolve) => {
        resolvePage = resolve;
      }),
    );
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Searching long-decision occurrences');
    resolvePage(page([occurrence('loaded')]));
    expect(await screen.findByText('Open game')).toBeInTheDocument();

    vi.mocked(api.fetchLongDecisions)
      .mockRejectedValueOnce(new Error('service unavailable'))
      .mockResolvedValueOnce(page([occurrence('retried')]));
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText(/service unavailable/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry search' }));
    expect(await screen.findByRole('link', { name: 'Open game' })).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/game-retried',
    );
  });

  it('loads more individual occurrences and ignores stale query responses', async () => {
    const user = userEvent.setup();
    let resolveFirst!: (value: LongDecisionPageResponse) => void;
    vi.mocked(api.fetchLongDecisions)
      .mockImplementationOnce(
        () =>
          new Promise<LongDecisionPageResponse>((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockResolvedValueOnce(page([occurrence('current')], { hasNext: true, totalElements: 2, totalPages: 2 }))
      .mockResolvedValueOnce(page([occurrence('next')], { page: 1, totalElements: 2 }));
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.selectOptions(screen.getByLabelText('Time control'), 'BLITZ');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('link', { name: 'Open game' })).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/game-current',
    );
    resolveFirst(page([occurrence('stale')]));

    await user.click(screen.getByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(screen.getAllByRole('link', { name: 'Open game' })).toHaveLength(2));
    expect(screen.getAllByRole('link', { name: 'Open game' })[1]).toHaveAttribute(
      'href', 'https://www.chess.com/game/live/game-next',
    );
    expect(screen.getAllByTestId('long-decision-board')).toHaveLength(2);
    expect(screen.queryByText('game-stale')).not.toBeInTheDocument();
  });

  it('offers account loading, retry, and no-account states without querying', () => {
    const onRetry = vi.fn();
    const onNavigateImport = vi.fn();
    const { rerender } = render(
      <LongDecisionsView
        {...connectedProps}
        accountId={undefined}
        sessionStatus="loading"
        accountStatus="loading"
      />,
    );
    expect(screen.getByText('Checking your Chess.com account')).toBeInTheDocument();

    rerender(
      <LongDecisionsView
        {...connectedProps}
        accountId={undefined}
        accountStatus="error"
        onRetryAccountLoad={onRetry}
        onNavigateImport={onNavigateImport}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Retry account loading' }));
    expect(onRetry).toHaveBeenCalledOnce();

    rerender(
      <LongDecisionsView
        {...connectedProps}
        accountId={undefined}
        accountStatus="unconnected"
        onNavigateImport={onNavigateImport}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Go to Import Games' }));
    expect(onNavigateImport).toHaveBeenCalledOnce();
    expect(api.fetchLongDecisions).not.toHaveBeenCalled();
  });
});
