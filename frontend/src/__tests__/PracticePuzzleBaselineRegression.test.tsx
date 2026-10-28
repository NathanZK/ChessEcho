import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { activeTabStore } from '../utils/browserStores';

let capturedBoardOptions: {
  position?: string;
  onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
  squareStyles?: Record<string, Record<string, string>>;
} = {};

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options: {
      position: string;
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
      squareStyles?: Record<string, Record<string, string>>;
    };
  }) => {
    capturedBoardOptions = options;
    return (
      <div data-testid="board-position" data-position={options.position}>
        <button
          data-testid="play-e4"
          onClick={() => options.onPieceDrop?.({ sourceSquare: 'e2', targetSquare: 'e4' })}
        >
          Play e4
        </button>
        <button
          data-testid="play-d4"
          onClick={() => options.onPieceDrop?.({ sourceSquare: 'd2', targetSquare: 'd4' })}
        >
          Play d4
        </button>
      </div>
    );
  },
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
    fetchPuzzleAttemptCount: vi.fn(),
    submitTrainingAttempt: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
    recordPuzzleEvent: vi.fn(),
  };
});

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

const MOCK_NORMAL_PUZZLE = {
  puzzleId: 'puzzle-normal-1',
  fen: START_FEN,
  playerColor: 'WHITE' as const,
  targetMove: 'e4',
  acceptableMoves: [],
  movesPlayed: [],
  openingTitle: "King's Pawn Opening",
  priority: 1,
  timesReached: 42,
  mistakeCount: 5,
  mistakeRate: 11.9,
};

describe('Practice Puzzles baseline regression tests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    window.location.hash = '';
    activeTabStore.set('puzzles');
    capturedBoardOptions = {};

    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'authenticated', userId: 'alice' });
    vi.mocked(api.fetchAccounts).mockResolvedValue([
      { id: 'acc-1', username: 'alice', platform: 'CHESS_COM' },
    ]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([MOCK_NORMAL_PUZZLE]);
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 10, failedCount: 2 });
    vi.mocked(api.recordPuzzleEvent).mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders ordinary Practice Puzzles with all board controls, settings, and weakness statistics in IDLE', async () => {
    render(<Home />);
    await screen.findByText("King's Pawn Opening");

    // Ordinary board controls must be present and enabled
    expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Flip' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Hint' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Prev' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next Puzzle' })).toBeInTheDocument();

    // Feedback panel components
    expect(screen.getByRole('button', { name: /Puzzle Settings/i })).toBeInTheDocument();
    expect(screen.getByText(/Target Opening Weakness/i)).toBeInTheDocument();
    expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    expect(screen.getByText("Find White's best move or an acceptable alternative to fix your opening habit.")).toBeInTheDocument();

    // Weakness statistics
    expect(screen.getByText('Mistake Rate')).toBeInTheDocument();
    expect(screen.getByText('11.9%')).toBeInTheDocument();
    expect(screen.getByText('Times Reached')).toBeInTheDocument();
    expect(screen.getByText('42 games')).toBeInTheDocument();

    // Supplied-only controls must NOT leak into ordinary puzzles
    expect(screen.queryByTitle('Exit Exploration')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Exit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Continue Exploration/i })).not.toBeInTheDocument();
  });

  it('submitting an incorrect move on an ordinary puzzle follows the Explore this decision path and records an attempt', async () => {
    render(<Home />);
    await screen.findByText("King's Pawn Opening");

    const board = screen.getByTestId('board-position');
    fireEvent.click(within(board).getByTestId('play-d4'));

    await screen.findByText('Not the Recommended Move');
    expect(screen.getByText(/is not the recommended move/i)).toBeInTheDocument();

    // Verify attempt is recorded as failed
    await waitFor(() => {
      expect(api.recordPuzzleEvent).toHaveBeenCalledWith(
        expect.objectContaining({
          eventType: 'FAILED',
        })
      );
    });

    // Action button label must remain "Explore this decision", NOT "Continue Exploration →"
    expect(screen.getByRole('button', { name: /Explore this decision/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Continue Exploration →/i })).not.toBeInTheDocument();
    expect(screen.queryByTitle('Exit Exploration')).not.toBeInTheDocument();
  });

  it('submitting a correct move exposes normal puzzle progression controls without supplied exit button', async () => {
    render(<Home />);
    await screen.findByText("King's Pawn Opening");

    const board = screen.getByTestId('board-position');
    fireEvent.click(within(board).getByTestId('play-e4'));

    await screen.findByText('Puzzle Solved! 🎉');
    expect(screen.getByText(/is the best move!/i)).toBeInTheDocument();

    // Verify attempt is recorded as solved
    await waitFor(() => {
      expect(api.recordPuzzleEvent).toHaveBeenCalledWith(
        expect.objectContaining({
          eventType: 'SOLVED',
        })
      );
    });

    // Normal progression button present
    expect(screen.getAllByRole('button', { name: /Next Puzzle/i }).length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Enter ↵')).toBeInTheDocument();
    expect(screen.queryByTitle('Exit Exploration')).not.toBeInTheDocument();
  });

  it('ordinary puzzle hint targeting uses the root puzzle target move and clears on move', async () => {
    render(<Home />);
    await screen.findByText("King's Pawn Opening");

    // Click Hint
    const hintButton = screen.getByRole('button', { name: 'Hint' });
    fireEvent.click(hintButton);

    // e4 originates from e2
    expect(capturedBoardOptions.squareStyles).toBeDefined();
    expect(Object.keys(capturedBoardOptions.squareStyles || {})).toContain('e2');

    // Playing move clears hint styling
    const board = screen.getByTestId('board-position');
    fireEvent.click(within(board).getByTestId('play-e4'));

    expect(capturedBoardOptions.squareStyles).toEqual({});
  });
});
