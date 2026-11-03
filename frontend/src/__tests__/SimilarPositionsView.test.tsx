import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  SimilarPositionsView,
  type SimilarPositionsReturnSnapshot,
} from '../components/SimilarPositionsView';
import * as api from '../services/api';
import type { SimilarPositionsResponse } from '../services/api';

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: { position: string } }) => (
    <div data-testid="mock-chessboard">{options.position}</div>
  ),
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchSimilarPositions: vi.fn(),
  };
});

const mockConnectedAccount: api.AccountSummary = {
  id: '550e8400-e29b-41d4-a716-446655440010',
  platform: 'CHESS_COM',
  username: 'testgrandmaster',
};

const validFen = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';

const mockResponse: SimilarPositionsResponse = {
  queryFen: validFen,
  candidatesEvaluated: 120,
  shortlistedCandidatesCount: 100,
  engineCompletedCount: 98,
  dynamicSimilarityApplied: true,
  fallbackReason: null,
  queryStatus: 'COMPLETED',
  queryAchievedDepth: 8,
  queryPrincipalVariations: [{ rank: 1, moves: ['Nf3'], achievedDepth: 8 }],
  results: [
    {
      rank: 1,
      score: 18.75,
      staticScore: 15.0,
      dynamicScore: 18.75,
      positionId: 'pos-1',
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3',
      canonicalFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq -',
      searchStatus: 'COMPLETED',
      achievedDepth: 8,
      principalVariations: [{ rank: 1, moves: ['Nc3'], achievedDepth: 8 }],
      occurrences: [
        {
          id: 'occ-1',
          platformGameId: 'https://www.chess.com/game/live/123456789',
          playedMove: 'Nc3',
          plyNumber: 5,
          playerColor: 'WHITE',
          playedAt: '2026-02-15T10:00:00Z',
          opponentUsername: 'rival_player',
          timeControl: '600',
          gameResult: 'win',
          chessComUrl: 'https://www.chess.com/game/live/123456789?move=5',
        },
      ],
    },
    {
      rank: 2,
      score: 15.2,
      staticScore: 12.0,
      dynamicScore: 15.2,
      positionId: 'pos-2',
      fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 4 4',
      canonicalFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq -',
      searchStatus: 'COMPLETED',
      achievedDepth: 8,
      principalVariations: [{ rank: 1, moves: ['d3'], achievedDepth: 8 }],
      occurrences: [
        {
          id: 'occ-2',
          platformGameId: 'unverified-id-999',
          playedMove: 'd3',
          plyNumber: 7,
          playerColor: 'WHITE',
          playedAt: null,
          opponentUsername: null,
          timeControl: null,
          gameResult: null,
          chessComUrl: null,
        },
      ],
    },
  ],
};

describe('SimilarPositionsView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders FEN input, search button, limit selector with supported choices (default 20), and sample positions', () => {
    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    expect(screen.getByLabelText(/FEN Position/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search/i })).toBeDisabled();

    const limitSelect = screen.getByLabelText(/Results limit/i) as HTMLSelectElement;
    expect(limitSelect).toBeInTheDocument();
    expect(limitSelect.value).toBe('20');
    expect(Array.from(limitSelect.options).map((opt) => opt.value)).toEqual([
      '5',
      '10',
      '20',
      '30',
      '40',
      '50',
    ]);

    expect(screen.getByText('Italian Game')).toBeInTheDocument();
    expect(screen.getByText('Sicilian Defense')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Min BM25 Score/i)).not.toBeInTheDocument();
  });

  it('populates FEN input when clicking a sample position', async () => {
    const user = userEvent.setup();
    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    const italianBtn = screen.getByText('Italian Game');
    await user.click(italianBtn);

    const input = screen.getByLabelText(/FEN Position/i) as HTMLInputElement;
    expect(input.value).toContain('r1bqk1nr/pppp1ppp');
  });

  it('shows error on invalid FEN submission', async () => {
    const user = userEvent.setup();
    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    const input = screen.getByLabelText(/FEN Position/i);
    await user.type(input, 'invalid-fen-string');
    await user.click(screen.getByRole('button', { name: /Search/i }));

    expect(screen.getByText(/cannot be used as a chess position|invalid/i)).toBeInTheDocument();
    expect(api.fetchSimilarPositions).not.toHaveBeenCalled();
  });

  it('searches similar positions and displays ranked results with chessboard thumbnails', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockResponse);

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    const input = screen.getByLabelText(/FEN Position/i);
    await user.type(input, validFen);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith({
        fen: validFen,
        accountId: mockConnectedAccount.id,
        username: undefined,
        minScore: undefined,
        limit: 20,
      });
    });

    expect(screen.queryByText(/Evaluated 120 candidate positions/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Rank #1/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Score: 18.75/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Score: 15.20/i)).not.toBeInTheDocument();

    const boards = screen.getAllByTestId('mock-chessboard');
    expect(boards).toHaveLength(2);
    expect(boards[0]).toHaveTextContent('r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3');
  });

  it('renders verified Chess.com exact-ply URL and null link unavailable note', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockResponse);

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    await user.type(screen.getByLabelText(/FEN Position/i), validFen);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    const verifiedLink = await screen.findByRole('link', { name: /View move on Chess.com/i });
    expect(verifiedLink).toHaveAttribute('href', 'https://www.chess.com/game/live/123456789?move=5');
    expect(verifiedLink).toHaveAttribute('target', '_blank');

    expect(screen.getByText(/Chess.com link unavailable/i)).toBeInTheDocument();
  });

  it('shows empty state when no positions meet criteria', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue({
      queryFen: validFen,
      candidatesEvaluated: 50,
      shortlistedCandidatesCount: 50,
      engineCompletedCount: 0,
      dynamicSimilarityApplied: false,
      fallbackReason: null,
      queryStatus: null,
      queryAchievedDepth: 0,
      queryPrincipalVariations: [],
      results: [],
    });

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    await user.type(screen.getByLabelText(/FEN Position/i), validFen);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    expect(await screen.findByText(/No Similar Positions Found/i)).toBeInTheDocument();
    expect(screen.getByText(/Try searching a different position or opening line/i)).toBeInTheDocument();
    expect(screen.queryByText(/lowering the minimum score threshold/i)).not.toBeInTheDocument();
  });

  it('navigates to Line Exploration on "Explore this position" click', async () => {
    const user = userEvent.setup();
    const onExplorePosition = vi.fn();
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockResponse);

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={onExplorePosition}
      />
    );

    await user.type(screen.getByLabelText(/FEN Position/i), validFen);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    const exploreButtons = await screen.findAllByRole('button', { name: /Explore this position/i });
    await user.click(exploreButtons[0]);

    expect(onExplorePosition).toHaveBeenCalledWith({
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3',
      sanHistory: [],
      historyStartFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3',
    });
  });

  it('restores state and selected limit from initialSnapshot', () => {
    const snapshot: SimilarPositionsReturnSnapshot = {
      queryFen: validFen,
      fenInput: validFen,
      results: mockResponse.results,
      selectedOccurrenceId: 'occ-1',
      activeAccountId: mockConnectedAccount.id,
      limit: 30,
    };

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        initialSnapshot={snapshot}
        onExplorePosition={vi.fn()}
      />
    );

    const input = screen.getByLabelText(/FEN Position/i) as HTMLInputElement;
    expect(input.value).toBe(validFen);
    const limitSelect = screen.getByLabelText(/Results limit/i) as HTMLSelectElement;
    expect(limitSelect.value).toBe('30');
    expect(screen.queryByText(/Rank #1/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Score: 18.75/i)).not.toBeInTheDocument();
  });

  it('triggers immediate re-fetch with new limit when limit changes and results are displayed', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockResponse);

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    const input = screen.getByLabelText(/FEN Position/i);
    await user.type(input, validFen);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith({
        fen: validFen,
        accountId: mockConnectedAccount.id,
        username: undefined,
        minScore: undefined,
        limit: 20,
      });
    });

    // Results are now displayed. Changing limit should trigger an immediate re-fetch.
    const limitSelect = screen.getByLabelText(/Results limit/i);
    await user.selectOptions(limitSelect, '10');

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith({
        fen: validFen,
        accountId: mockConnectedAccount.id,
        username: undefined,
        minScore: undefined,
        limit: 10,
      });
    });
  });

  it('does not trigger fetch on limit change if search has not been performed', async () => {
    const user = userEvent.setup();
    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    const limitSelect = screen.getByLabelText(/Results limit/i);
    await user.selectOptions(limitSelect, '50');

    expect(api.fetchSimilarPositions).not.toHaveBeenCalled();
    expect((limitSelect as HTMLSelectElement).value).toBe('50');
  });

  it('emits updated snapshot with limit on search and exploration', async () => {
    const user = userEvent.setup();
    const onSnapshotChange = vi.fn();
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockResponse);

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
        onSnapshotChange={onSnapshotChange}
      />
    );

    const limitSelect = screen.getByLabelText(/Results limit/i);
    await user.selectOptions(limitSelect, '40');

    const input = screen.getByLabelText(/FEN Position/i);
    await user.type(input, validFen);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith({
        fen: validFen,
        accountId: mockConnectedAccount.id,
        username: undefined,
        minScore: undefined,
        limit: 40,
      });
    });

    expect(onSnapshotChange).toHaveBeenCalledWith(
      expect.objectContaining({
        queryFen: validFen,
        limit: 40,
      })
    );
  });

  it('prompts user with active-color buttons when input is piece placement only', async () => {
    const user = userEvent.setup();
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockResponse);

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    const placement = 'r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R';
    await user.type(screen.getByLabelText(/FEN Position/i), placement);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    expect(screen.getByText(/Active color missing/i)).toBeInTheDocument();
    const whiteBtn = screen.getByRole('button', { name: /White to move/i });
    const blackBtn = screen.getByRole('button', { name: /Black to move/i });
    expect(whiteBtn).toBeInTheDocument();
    expect(blackBtn).toBeInTheDocument();

    await user.click(whiteBtn);

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          fen: `${placement} w - - 0 1`,
        })
      );
    });

    const input = screen.getByLabelText(/FEN Position/i) as HTMLInputElement;
    expect(input.value).toBe(`${placement} w - - 0 1`);
  });

  it('does not show active-color buttons for structurally invalid 8-rank input', async () => {
    const user = userEvent.setup();
    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    await user.type(screen.getByLabelText(/FEN Position/i), '8/8/8/8/8/8/8/8');
    await user.click(screen.getByRole('button', { name: /Search/i }));

    expect(screen.queryByRole('button', { name: /White to move/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Black to move/i })).not.toBeInTheDocument();
    expect(screen.getByText(/invalid/i)).toBeInTheDocument();
  });

  it('correctly styles checkmate win in emerald, checkmate loss in rose, and draws in amber', async () => {
    const user = userEvent.setup();
    const customResponse: SimilarPositionsResponse = {
      ...mockResponse,
      results: [
        {
          ...mockResponse.results[0],
          positionId: 'pos-1',
          occurrences: [
            {
              ...mockResponse.results[0].occurrences[0],
              id: 'occ-won-mate',
              gameResult: 'Won by checkmate',
            },
          ],
        },
        {
          ...mockResponse.results[0],
          positionId: 'pos-2',
          occurrences: [
            {
              ...mockResponse.results[0].occurrences[0],
              id: 'occ-lost-mate',
              gameResult: 'Lost by checkmate',
            },
          ],
        },
        {
          ...mockResponse.results[0],
          positionId: 'pos-3',
          occurrences: [
            {
              ...mockResponse.results[0].occurrences[0],
              id: 'occ-stalemate',
              gameResult: 'stalemate',
            },
          ],
        },
      ],
    };

    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(customResponse);

    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    await user.type(screen.getByLabelText(/FEN Position/i), validFen);
    await user.click(screen.getByRole('button', { name: /Search/i }));

    await waitFor(() => {
      expect(screen.getByText('Won by checkmate')).toBeInTheDocument();
      expect(screen.getByText('Lost by checkmate')).toBeInTheDocument();
      expect(screen.getByText('stalemate')).toBeInTheDocument();
    });

    const winBadge = screen.getByText('Won by checkmate');
    expect(winBadge).toHaveClass('text-emerald-400');

    const lossBadge = screen.getByText('Lost by checkmate');
    expect(lossBadge).toHaveClass('text-rose-400');

    const drawBadge = screen.getByText('stalemate');
    expect(drawBadge).toHaveClass('text-amber-400');
  });
});
