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
  results: [
    {
      rank: 1,
      score: 18.75,
      positionId: 'pos-1',
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3',
      canonicalFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq -',
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
      positionId: 'pos-2',
      fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 4 4',
      canonicalFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq -',
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

  it('renders FEN input, search button, and sample positions', () => {
    render(
      <SimilarPositionsView
        connectedAccount={mockConnectedAccount}
        onExplorePosition={vi.fn()}
      />
    );

    expect(screen.getByLabelText(/FEN Position/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search/i })).toBeDisabled();
    expect(screen.getByText('Italian Game')).toBeInTheDocument();
    expect(screen.getByText('Sicilian Defense')).toBeInTheDocument();
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

    expect(await screen.findByText(/Rank #1/i)).toBeInTheDocument();
    expect(screen.getByText(/Score: 18.75/i)).toBeInTheDocument();
    expect(screen.getByText(/Score: 15.20/i)).toBeInTheDocument();

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

  it('restores state from initialSnapshot', () => {
    const snapshot: SimilarPositionsReturnSnapshot = {
      queryFen: validFen,
      fenInput: validFen,
      results: mockResponse.results,
      selectedOccurrenceId: 'occ-1',
      activeAccountId: mockConnectedAccount.id,
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
    expect(screen.getByText(/Rank #1/i)).toBeInTheDocument();
    expect(screen.getByText(/Score: 18.75/i)).toBeInTheDocument();
  });
});
