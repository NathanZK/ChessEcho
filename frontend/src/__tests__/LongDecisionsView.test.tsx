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
    platformGameId: 'https://www.chess.com/game/live/172923974924',
    chessComUrl: 'https://www.chess.com/game/live/172923974924?move=1',
    playedAt: '2026-02-03T12:00:00Z',
    opponentUsername: 'opponent',
    severity: 'CRITICAL',
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
      expect(api.fetchLongDecisions).toHaveBeenCalledWith(accountId, 'RAPID', 120, 0, 20, 120, undefined),
    );
    expect(await screen.findByTestId('long-decision-board')).toHaveTextContent(
      '8/8/8/8/8/8/8/8 w - -',
    );
    expect(screen.getByText('120 seconds')).toBeInTheDocument();
    expect(screen.getByText('Nf3')).toBeInTheDocument();
    expect(screen.getByText('opponent')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open game' })).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/172923974924?move=1',
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
      'https://www.chess.com/game/live/172923974924?move=1',
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
      .mockResolvedValueOnce(
        page([occurrence('current', { chessComUrl: 'https://www.chess.com/game/live/111?move=1' })], {
          hasNext: true,
          totalElements: 2,
          totalPages: 2,
        }),
      )
      .mockResolvedValueOnce(
        page([occurrence('next', { chessComUrl: 'https://www.chess.com/game/live/222?move=2' })], {
          page: 1,
          totalElements: 2,
        }),
      );
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.selectOptions(screen.getByLabelText('Time control'), 'BLITZ');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('link', { name: 'Open game' })).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/111?move=1',
    );
    resolveFirst(page([occurrence('stale')]));

    await user.click(screen.getByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(screen.getAllByRole('link', { name: 'Open game' })).toHaveLength(2));
    expect(screen.getAllByRole('link', { name: 'Open game' })[1]).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/222?move=2',
    );
    expect(screen.getAllByTestId('long-decision-board')).toHaveLength(2);
    expect(screen.queryByText('game-stale')).not.toBeInTheDocument();
  });

  it('renders ChessProbe session error alert when session check fails', () => {
    render(
      <LongDecisionsView
        {...connectedProps}
        accountId={undefined}
        sessionStatus="error"
        accountStatus="unconnected"
      />
    );

    expect(
      screen.getByText('Unable to check your ChessProbe session. Refresh the page to try again.')
    ).toBeInTheDocument();
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

  it('adopts valid backend chessComUrl with target="_blank" and rel="noreferrer"', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([
        occurrence('valid-backend', {
          chessComUrl: 'https://www.chess.com/game/live/999?move=7',
          platformGameId: '12345',
        }),
      ]),
    );
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    const link = await screen.findByRole('link', { name: 'Open game' });
    expect(link).toHaveAttribute('href', 'https://www.chess.com/game/live/999?move=7');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noreferrer');
  });

  it('falls back to buildExactPlyChessComUrl when chessComUrl is null or undefined', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([
        occurrence('null-backend', {
          chessComUrl: null,
          platformGameId: 'https://www.chess.com/game/live/123456789',
          plyNumber: 4,
          timeControl: 'BLITZ',
        }),
      ]),
    );
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    const link = await screen.findByRole('link', { name: 'Open game' });
    expect(link).toHaveAttribute('href', 'https://www.chess.com/game/live/123456789?move=4');
  });

  it('falls back to buildExactPlyChessComUrl when chessComUrl is empty or whitespace', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([
        occurrence('empty-backend', {
          chessComUrl: '   ',
          platformGameId: 'https://www.chess.com/game/daily/987654',
          plyNumber: 15,
          timeControl: 'RAPID',
        }),
      ]),
    );
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    const link = await screen.findByRole('link', { name: 'Open game' });
    expect(link).toHaveAttribute('href', 'https://www.chess.com/game/daily/987654?move=15');
  });

  it('falls back to buildExactPlyChessComUrl when chessComUrl is malformed', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([
        occurrence('malformed-backend', {
          chessComUrl: 'https://untrusted-phishing.com/game/live/123',
          platformGameId: 'https://www.chess.com/game/live/123',
          plyNumber: 2,
        }),
      ]),
    );
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    const link = await screen.findByRole('link', { name: 'Open game' });
    expect(link).toHaveAttribute('href', 'https://www.chess.com/game/live/123?move=2');
  });

  it('omits Open game link when both chessComUrl and fallback resolution are unresolvable', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([
        occurrence('unresolvable', {
          chessComUrl: null,
          platformGameId: 'not-a-valid-id-or-url',
        }),
      ]),
    );
    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('120 seconds')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open game' })).not.toBeInTheDocument();
  });

  it('filters by selected severity and passes it to fetchLongDecisions', async () => {
    const user = userEvent.setup();
    render(<LongDecisionsView {...connectedProps} />);

    expect(screen.getByLabelText('Severity')).toBeInTheDocument();
    expect(screen.getByLabelText('Severity')).toHaveValue('ALL');

    await user.selectOptions(screen.getByLabelText('Severity'), 'SIGNIFICANT');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() =>
      expect(api.fetchLongDecisions).toHaveBeenCalledWith(
        accountId,
        'BULLET',
        30,
        0,
        20,
        30,
        'SIGNIFICANT',
      ),
    );
  });

  it('renders loaded count badge and toggles between grid and list views', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([occurrence('occ-1'), occurrence('occ-2')]),
    );
    render(<LongDecisionsView {...connectedProps} />);

    await user.click(screen.getByRole('button', { name: 'Search' }));

    const badge = await screen.findByTestId('decision-timing-count-indicator');
    expect(badge).toHaveTextContent('2');

    // Default view mode is grid
    const gridButton = screen.getByRole('button', { name: 'Grid' });
    const listButton = screen.getByRole('button', { name: 'List' });
    expect(gridButton).toBeInTheDocument();
    expect(listButton).toBeInTheDocument();

    // Toggle to list view
    await user.click(listButton);
    expect(listButton).toHaveClass('font-semibold');

    // Toggle back to grid view
    await user.click(gridButton);
    expect(gridButton).toHaveClass('font-semibold');
  });

  it('renders severity pills with appropriate labels and color styling for each severity level', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([
        occurrence('crit', { severity: 'CRITICAL' }),
        occurrence('sig', { severity: 'SIGNIFICANT' }),
        occurrence('min', { severity: 'MINOR' }),
        occurrence('none', { severity: 'NO_CONCERN' }),
        occurrence('unk', { severity: 'UNKNOWN' }),
      ]),
    );
    render(<LongDecisionsView {...connectedProps} />);

    await user.click(screen.getByRole('button', { name: 'Search' }));

    const critPill = await screen.findByTestId('severity-pill-crit');
    expect(critPill).toHaveTextContent('Critical');
    expect(critPill).toHaveClass('text-rose-400');

    const sigPill = screen.getByTestId('severity-pill-sig');
    expect(sigPill).toHaveTextContent('Significant');
    expect(sigPill).toHaveClass('text-amber-400');

    const minPill = screen.getByTestId('severity-pill-min');
    expect(minPill).toHaveTextContent('Minor');
    expect(minPill).toHaveClass('text-blue-400');

    const nonePill = screen.getByTestId('severity-pill-none');
    expect(nonePill).toHaveTextContent('No concern');
    expect(nonePill).toHaveClass('text-slate-300');

    const unkPill = screen.getByTestId('severity-pill-unk');
    expect(unkPill).toHaveTextContent('Unclassified');
  });

  it('formats duration correctly for singular second and displays date unavailable for missing/invalid dates', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockResolvedValue(
      page([
        occurrence('one-sec', { decisionTimeMs: 1_000, playedAt: null }),
        occurrence('bad-date', { decisionTimeMs: 1_500, playedAt: 'not-a-date' }),
      ]),
    );
    render(<LongDecisionsView {...connectedProps} />);

    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('1 second')).toBeInTheDocument();
    expect(screen.getByText('1.5 seconds')).toBeInTheDocument();
    expect(screen.getAllByText('Date unavailable')).toHaveLength(2);
  });

  it('renders loadMore failure error and successfully retries loading more', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions)
      .mockResolvedValueOnce(
        page([occurrence('first')], { hasNext: true, totalElements: 2, totalPages: 2 }),
      )
      .mockRejectedValueOnce(new Error('pagination error'))
      .mockResolvedValueOnce(
        page([occurrence('second')], { page: 1, hasNext: false, totalElements: 2, totalPages: 2 }),
      );

    render(<LongDecisionsView {...connectedProps} />);
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByRole('button', { name: 'Load more' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText(/Could not load more occurrences. pagination error/i)).toBeInTheDocument();
    const retryBtn = screen.getByRole('button', { name: 'Retry loading more' });
    expect(retryBtn).toBeInTheDocument();

    await user.click(retryBtn);
    await waitFor(() => expect(screen.getAllByRole('link', { name: 'Open game' })).toHaveLength(2));
    expect(screen.queryByText(/pagination error/i)).not.toBeInTheDocument();
  });

  it('falls back to default message when error thrown is not an Error instance', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchLongDecisions).mockRejectedValue('string error');
    render(<LongDecisionsView {...connectedProps} />);

    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText(/Please try again./i)).toBeInTheDocument();
  });
});
