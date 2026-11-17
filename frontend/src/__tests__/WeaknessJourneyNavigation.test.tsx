import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { activeTabStore, activeAccountStore, activeUsernameStore } from '../utils/browserStores';

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options?: {
      position: string;
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
    };
  }) => (
    <div data-testid="mock-board" data-position={options?.position}>
      {options?.onPieceDrop && (
        <button
          data-testid="play-nc6-drop"
          onClick={() => options?.onPieceDrop?.({ sourceSquare: 'b8', targetSquare: 'c6' })}
        >
          Play Nc6
        </button>
      )}
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
    fetchCurrentSession: vi.fn(),
    fetchAccounts: vi.fn(),
    fetchPuzzles: vi.fn(),
    fetchWeaknesses: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

class MockIntersectionObserver {
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
}
Object.defineProperty(window, 'IntersectionObserver', { writable: true, configurable: true, value: MockIntersectionObserver });
Object.defineProperty(global, 'IntersectionObserver', { writable: true, configurable: true, value: MockIntersectionObserver });

const mockAccount: api.AccountSummary = {
  id: '550e8400-e29b-41d4-a716-446655440010',
  platform: 'CHESS_COM',
  username: 'carlsen',
};

const mockWeaknessItem: api.WeaknessResponse = {
  positionId: 'w-pos-123',
  playerColor: 'BLACK',
  fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
  timesReached: 15,
  mistakeCount: 5,
  mistakeRate: 33.3,
  averageLoss: 1.25,
  priority: 4.2,
  bestMove: 'Nc6',
  acceptableMoves: [{ move: 'Nf6', evalLoss: 0.1 }],
  movesPlayed: [
    { move: 'Bc5', timesPlayed: 4, averageLoss: 1.3 },
    { move: 'f5', timesPlayed: 1, averageLoss: 1.05 },
  ],
  gameUrls: ['https://www.chess.com/game/live/10001'],
  evalCp: 35,
};

describe('Weakness Library to Line Exploration and Return journey', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    window.location.hash = '#weakness-analysis';
    window.localStorage.setItem('chessecho_session_user', 'user-1');
    window.localStorage.setItem('chessecho_username', 'carlsen');
    activeTabStore.set('weakness-analysis');
    activeAccountStore.set(mockAccount);
    activeUsernameStore.set(mockAccount.username);
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValue([mockAccount]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeaknessItem]);
    vi.mocked(api.evaluateMove).mockResolvedValue({
      fen: mockWeaknessItem.fen,
      move: 'Nc6',
      bestMove: 'Nc6',
      bestEvalCp: 35,
      evalCp: 35,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: mockWeaknessItem.fen,
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [],
    });
  });

  it('completes full journey: Weakness Library -> Practice Position -> Continue Exploration -> Exit Exploration preserves Practice -> Back to Weaknesses restores Weaknesses Tab', async () => {
    render(<Home />);
    const practiceBtn = await screen.findByRole('button', { name: /Practice Position/i });
    expect(practiceBtn).toBeInTheDocument();

    // 2. Click Practice Position on the weakness card
    fireEvent.click(practiceBtn);

    // 3. Puzzles view is active
    await waitFor(() => {
      expect(activeTabStore.getSnapshot()).toBe('puzzles');
    });

    // 4. Play the best move (Nc6) to solve the puzzle
    const playBtn = await screen.findByTestId('play-nc6-drop');
    fireEvent.click(playBtn);

    // 5. Puzzle is solved, "Continue Exploration →" button appears
    const continueExplorationBtn = await screen.findByRole('button', { name: /Continue Exploration →/i });
    expect(continueExplorationBtn).toBeInTheDocument();

    // 6. Enter Line Exploration
    fireEvent.click(continueExplorationBtn);

    // 7. In Line Exploration, find and click "Exit Exploration"
    const exitBtn = await screen.findByTitle(/Exit Exploration/i);
    expect(exitBtn).toBeInTheDocument();
    fireEvent.click(exitBtn);

    // 8. Observable restored state: Active puzzle context in Practice is preserved, not ejected to Weaknesses
    expect(activeTabStore.getSnapshot()).toBe('puzzles');
    expect(await screen.findByRole('button', { name: /Back to Weaknesses/i })).toBeInTheDocument();

    // 9. Clicking explicit "Back to Weaknesses" returns to Weaknesses Library
    const backToWeaknessesBtn = screen.getByRole('button', { name: /Back to Weaknesses/i });
    fireEvent.click(backToWeaknessesBtn);

    await waitFor(() => {
      expect(activeTabStore.getSnapshot()).toBe('weakness-analysis');
    });
    expect(await screen.findByRole('button', { name: /Practice Position/i })).toBeInTheDocument();
  });
});
