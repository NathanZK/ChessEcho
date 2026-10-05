import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { continuationService, moveEvaluationService } from '../services/continuationService';
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
    <div data-testid="board-position" data-position={options.position}>
      <button
        data-testid="play-e4"
        onClick={() => options.onPieceDrop?.({ sourceSquare: 'e2', targetSquare: 'e4' })}
      >
        Play e4
      </button>
    </div>
  ),
}));

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  },
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchPuzzles: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
    recordPuzzleEvent: vi.fn(),
  };
});

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const AFTER_E4_FEN = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';

describe('user-supplied position exploration', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    continuationService.clear();
    moveEvaluationService.clear();
    localStorage.clear();
    window.location.hash = '';
    activeTabStore.set('puzzles');
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.evaluateMove).mockResolvedValue({
      fen: START_FEN,
      move: 'e4',
      bestMove: 'e4',
      bestEvalCp: 20,
      evalCp: 20,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: AFTER_E4_FEN,
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [],
    });
  });

  it('starts Line Exploration from a supplied FEN when no puzzle exists', async () => {
    render(<Home />);
    await waitFor(() => expect(screen.getByText('No Practice Puzzles Available')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('FEN'), { target: { value: START_FEN } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Continue from FEN' }));

    expect(screen.getByTestId('board-position')).toHaveAttribute('data-position', START_FEN);
    expect(screen.getByRole('button', { name: 'Line Exploration' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Practice Puzzles' })).not.toBeInTheDocument();
    expect(screen.getByText(/Choose how you want to explore/i)).toBeInTheDocument();
    expect(screen.getByText('? N/A')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));
    fireEvent.click(screen.getByTestId('play-e4'));
    await waitFor(
      () => expect(api.evaluateMove).toHaveBeenCalledWith(START_FEN, 'e4'),
      { timeout: 1500 },
    );
    await waitFor(
      () => expect(api.fetchPuzzleContinuation).toHaveBeenCalledWith(AFTER_E4_FEN, 'ENGINE', '1200-1400'),
      { timeout: 1500 },
    );
    expect(api.recordPuzzleEvent).not.toHaveBeenCalled();
  });

  it('starts from the selected PGN ply', async () => {
    render(<Home />);
    await waitFor(() => expect(screen.getByText('No Practice Puzzles Available')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('tab', { name: 'PGN' }));
    fireEvent.change(within(dialog).getByLabelText('PGN'), {
      target: { value: '1. e4 e5 2. Nf3' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Load PGN' }));
    fireEvent.change(within(dialog).getByLabelText('Choose a position'), { target: { value: '3' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Continue from this position' }));

    const selectedFen = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2';
    expect(screen.getByTestId('board-position')).toHaveAttribute('data-position', selectedFen);
    expect(screen.getByRole('button', { name: 'Line Exploration' })).toBeInTheDocument();
  });

  it('does not frame a supplied position as a puzzle and exits without a solved result', async () => {
    render(<Home />);
    await waitFor(() => expect(screen.getByText('No Practice Puzzles Available')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('FEN'), { target: { value: START_FEN } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Continue from FEN' }));

    expect(screen.getByRole('button', { name: 'Line Exploration' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Practice Puzzles' })).not.toBeInTheDocument();
    expect(screen.queryByText('Puzzle Settings')).not.toBeInTheDocument();
    expect(screen.queryByText('Target Opening Weakness')).not.toBeInTheDocument();
    expect(screen.queryByText('Find White\'s best move or an acceptable alternative to fix your opening habit.')).not.toBeInTheDocument();
    expect(screen.queryByText('Mistake Rate')).not.toBeInTheDocument();
    expect(screen.queryByText('Times Reached')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hint' })).not.toBeInTheDocument();
    expect(screen.queryByTitle('Previous Puzzle')).not.toBeInTheDocument();
    expect(screen.queryByTitle('Next Puzzle')).not.toBeInTheDocument();
    expect(screen.queryByText('Train Blindfold')).not.toBeInTheDocument();
    expect(api.recordPuzzleEvent).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTitle('Exit Exploration'));
    expect(screen.getByText('No Practice Puzzles Available')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Practice Puzzles' })).toBeInTheDocument();
    expect(screen.queryByText('Puzzle Solved! 🎉')).not.toBeInTheDocument();
  });
});
