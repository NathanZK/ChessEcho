import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { activeTabStore } from '../utils/browserStores';

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options: {
      position: string;
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
    };
  }) => (
    <div data-testid="mock-board" data-position={options.position}>
      {options.position}
    </div>
  ),
}));

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playSound: vi.fn(),
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  },
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchPuzzles: vi.fn(),
    fetchCurrentSession: vi.fn(),
    fetchAccounts: vi.fn(),
    fetchLongDecisions: vi.fn(),
    fetchSimilarPositions: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

const mockAccount: api.AccountSummary = {
  id: '550e8400-e29b-41d4-a716-446655440010',
  platform: 'CHESS_COM',
  username: 'carlsen',
};

const validFen = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
const similarFen = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3';

const mockSimilarResponse: api.SimilarPositionsResponse = {
  queryFen: validFen,
  candidatesEvaluated: 150,
  results: [
    {
      rank: 1,
      score: 19.5,
      positionId: 'pos-abc-1',
      fen: similarFen,
      canonicalFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq -',
      occurrences: [
        {
          id: 'occ-abc-1',
          platformGameId: 'https://www.chess.com/game/live/999888',
          playedMove: 'Nc3',
          plyNumber: 5,
          playerColor: 'WHITE',
          playedAt: '2026-03-01T12:00:00Z',
          opponentUsername: 'nakamura',
          timeControl: '180',
          gameResult: 'win',
          chessComUrl: 'https://www.chess.com/game/live/999888?move=5',
        },
      ],
    },
  ],
};

describe('SimilarPositions navigation and exploration bridge', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    window.location.hash = '';
    activeTabStore.set('puzzles');
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValue([mockAccount]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue(mockSimilarResponse);
  });

  it('switches to Similar Positions tab and displays search view', async () => {
    render(<Home />);

    const similarTabBtn = await screen.findByRole('button', { name: /Similar Positions/i });
    fireEvent.click(similarTabBtn);

    expect(await screen.findByRole('heading', { name: /Find Similar Personal Positions/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/FEN Position/i)).toBeInTheDocument();
  });

  it('explores position from SimilarPositionsView and restores state upon Exit Exploration', async () => {
    render(<Home />);

    // 1. Navigate to Similar Positions tab
    const similarTabBtn = await screen.findByRole('button', { name: /Similar Positions/i });
    fireEvent.click(similarTabBtn);

    // 2. Perform search
    const fenInput = screen.getByLabelText(/FEN Position/i);
    fireEvent.change(fenInput, { target: { value: validFen } });
    fireEvent.click(screen.getByRole('button', { name: /Search/i }));

    expect(await screen.findByText(/Rank #1/i)).toBeInTheDocument();
    expect(screen.getByText(/Score: 19.50/i)).toBeInTheDocument();

    // 3. Click "Explore this position"
    const exploreBtn = screen.getByRole('button', { name: /Explore this position/i });
    fireEvent.click(exploreBtn);

    // 4. Verify transition to Line Exploration
    expect(activeTabStore.getSnapshot()).toBe('puzzles');
    const exitBtn = await screen.findByTitle(/Exit Exploration/i);
    expect(exitBtn).toBeInTheDocument();

    // 5. Exit Exploration and verify restoration of Similar Positions view
    fireEvent.click(exitBtn);

    await waitFor(() => {
      expect(activeTabStore.getSnapshot()).toBe('similar-positions');
    });

    expect(await screen.findByRole('heading', { name: /Find Similar Personal Positions/i })).toBeInTheDocument();
    expect(screen.getByText(/Rank #1/i)).toBeInTheDocument();
    expect(screen.getByText(/Score: 19.50/i)).toBeInTheDocument();
    // Verify no redundant API re-fetch was triggered upon returning
    expect(api.fetchSimilarPositions).toHaveBeenCalledTimes(1);
  });
});
