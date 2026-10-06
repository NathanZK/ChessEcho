import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { continuationService, moveEvaluationService } from '../services/continuationService';
import { activeTabStore } from '../utils/browserStores';
import { StopwatchTimer } from '../utils/timedTraining';

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
      <button
        data-testid="play-d4"
        onClick={() => options.onPieceDrop?.({ sourceSquare: 'd2', targetSquare: 'd4' })}
      >
        Play d4
      </button>
      <button
        data-testid="play-black-c5"
        onClick={() => options.onPieceDrop?.({ sourceSquare: 'c7', targetSquare: 'c5' })}
      >
        Play c5
      </button>
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
    fetchPuzzleAttemptCount: vi.fn(),
    submitTrainingAttempt: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
    recordPuzzleEvent: vi.fn(),
  };
});

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const AFTER_E4_FEN = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';
const BLACK_TO_MOVE_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1';
const AFTER_D4_FEN = 'rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1';

const getVisibleBoard = () => {
  const board = screen.getAllByTestId('board-position').find((element) => !element.closest('[hidden]'));
  if (!board) throw new Error('The active chessboard was not found.');
  return board;
};

describe('user-supplied position exploration', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    continuationService.clear();
    moveEvaluationService.clear();
    localStorage.clear();
    window.location.hash = '';
    activeTabStore.set('puzzles');
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'unauthenticated' });
    vi.mocked(api.fetchAccounts).mockResolvedValue([]);
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

    expect(getVisibleBoard()).toHaveAttribute('data-position', START_FEN);
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

  it('launches exploration from the exact black-to-move Long Decisions FEN without requiring its historical move', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'authenticated', userId: 'alice' });
    vi.mocked(api.fetchAccounts).mockResolvedValue([
      { id: 'account-a', username: 'fixture', platform: 'CHESS_COM' },
    ]);
    vi.mocked(api.fetchLongDecisions).mockResolvedValue({
      content: [{
        id: 'occurrence-a',
        positionId: 'position-a',
        fen: BLACK_TO_MOVE_FEN,
        playerColor: 'BLACK',
        plyNumber: 2,
        movePlayed: 'e5',
        decisionTimeMs: 45_000,
        timeControl: 'RAPID',
        platformGameId: 'game-a',
        playedAt: '2026-02-03T12:00:00Z',
        opponentUsername: 'opponent',
      }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
    });
    activeTabStore.set('long-decisions');
    render(<Home />);

    fireEvent.change(screen.getByLabelText('Time control'), { target: { value: 'RAPID' } });
    fireEvent.change(screen.getByLabelText('Decision-time threshold (seconds)'), {
      target: { value: '45' },
    });
    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled());
    fireEvent.click(await screen.findByRole('button', { name: 'Search' }));
    await waitFor(() => expect(api.fetchLongDecisions).toHaveBeenCalledWith(
      'account-a',
      'RAPID',
      45,
      0,
      20,
    ));
    expect(await screen.findByText('e5')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Explore this position' }));

    expect(getVisibleBoard()).toHaveAttribute('data-position', BLACK_TO_MOVE_FEN);
    expect(screen.getByRole('button', { name: 'Line Exploration' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/ }));
    fireEvent.click(within(getVisibleBoard()).getByTestId('play-black-c5'));
    await waitFor(() => expect(api.evaluateMove).toHaveBeenCalledWith(BLACK_TO_MOVE_FEN, 'c5'));
    expect(api.recordPuzzleEvent).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTitle('Exit Exploration'));
    expect(screen.getByRole('region', { name: 'Long Decisions' })).toBeInTheDocument();
    expect(screen.getByLabelText('Time control')).toHaveValue('RAPID');
    expect(screen.getByLabelText('Decision-time threshold (seconds)')).toHaveValue(45);
    expect(screen.getByText('e5')).toBeInTheDocument();
    expect(api.submitTrainingAttempt).not.toHaveBeenCalled();
  });

  it('restores the selected puzzle board history and feedback after Long Decisions exploration', async () => {
    const puzzle = {
      puzzleId: 'preserved-puzzle',
      fen: START_FEN,
      playerColor: 'WHITE' as const,
      targetMove: 'e4',
      acceptableMoves: [],
      movesPlayed: [],
      openingTitle: 'Restorable puzzle',
      priority: 1,
      timesReached: 3,
      mistakeCount: 1,
      mistakeRate: 33,
    };
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'authenticated', userId: 'alice' });
    vi.mocked(api.fetchAccounts).mockResolvedValue([
      { id: 'account-a', username: 'fixture', platform: 'CHESS_COM' },
    ]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([puzzle]);
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 0, failedCount: 0 });
    vi.mocked(api.recordPuzzleEvent).mockResolvedValue();
    vi.mocked(api.submitTrainingAttempt).mockImplementation(async (request) => ({
      ...request,
      recordedAt: '2026-02-03T12:00:00Z',
    }));
    vi.mocked(api.fetchLongDecisions).mockResolvedValue({
      content: [{
        id: 'occurrence-a',
        positionId: 'position-a',
        fen: BLACK_TO_MOVE_FEN,
        playerColor: 'BLACK',
        plyNumber: 2,
        movePlayed: 'e5',
        decisionTimeMs: 45_000,
        timeControl: 'RAPID',
        platformGameId: 'game-a',
        playedAt: '2026-02-03T12:00:00Z',
        opponentUsername: 'opponent',
      }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
    });
    const pauseStopwatch = vi.spyOn(StopwatchTimer.prototype, 'pause');
    const resumeStopwatch = vi.spyOn(StopwatchTimer.prototype, 'resume');
    render(<Home />);

    await screen.findByText('Restorable puzzle');
    fireEvent.click(screen.getByRole('button', { name: /Puzzle Settings/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Stopwatch' }));
    await new Promise((resolve) => setTimeout(resolve, 20));
    fireEvent.click(screen.getByTestId('play-d4'));
    await screen.findByText('Not the Recommended Move');
    await waitFor(() => expect(api.recordPuzzleEvent).toHaveBeenCalledTimes(1));
    const recordedPuzzleAttempts = vi.mocked(api.recordPuzzleEvent).mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: 'Long Decisions' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await screen.findByText('e5');
    fireEvent.click(screen.getByRole('button', { name: 'Explore this position' }));
    expect(pauseStopwatch).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Line Exploration' })).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('Exit Exploration'));
    expect(resumeStopwatch).toHaveBeenCalledTimes(1);

    expect(screen.getByRole('region', { name: 'Long Decisions' })).toBeInTheDocument();
    expect(screen.getByText('e5')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Practice Puzzles' }));
    expect(screen.getByText('Restorable puzzle')).toBeInTheDocument();
    expect(getVisibleBoard()).toHaveAttribute('data-position', AFTER_D4_FEN);
    expect(screen.getByText('Not the Recommended Move')).toBeInTheDocument();
    expect(screen.getByText(/0:\d{2}/)).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('Previous Move (Left Arrow ←)'));
    expect(getVisibleBoard()).toHaveAttribute('data-position', START_FEN);
    expect(screen.queryByText('Not the Recommended Move')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle('Next Move (Right Arrow →)'));
    expect(getVisibleBoard()).toHaveAttribute('data-position', AFTER_D4_FEN);
    expect(screen.getByText('Not the Recommended Move')).toBeInTheDocument();
    expect(api.recordPuzzleEvent).toHaveBeenCalledTimes(recordedPuzzleAttempts);
    expect(api.submitTrainingAttempt).not.toHaveBeenCalled();
  });

  it('keeps Long Decisions and selected puzzle state unchanged when an occurrence FEN is invalid', async () => {
    const puzzle = {
      puzzleId: 'preserved-puzzle',
      fen: START_FEN,
      playerColor: 'WHITE' as const,
      targetMove: 'e4',
      acceptableMoves: [],
      movesPlayed: [],
      openingTitle: 'Restorable puzzle',
      priority: 1,
      timesReached: 3,
      mistakeCount: 1,
      mistakeRate: 33,
    };
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'authenticated', userId: 'alice' });
    vi.mocked(api.fetchAccounts).mockResolvedValue([
      { id: 'account-a', username: 'fixture', platform: 'CHESS_COM' },
    ]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([puzzle]);
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 0, failedCount: 0 });
    vi.mocked(api.fetchLongDecisions).mockResolvedValue({
      content: [{
        id: 'invalid-occurrence',
        positionId: 'position-a',
        fen: 'not a valid FEN',
        playerColor: 'BLACK',
        plyNumber: 2,
        movePlayed: 'e5',
        decisionTimeMs: 45_000,
        timeControl: 'RAPID',
        platformGameId: 'game-a',
        playedAt: '2026-02-03T12:00:00Z',
        opponentUsername: 'opponent',
      }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
    });
    render(<Home />);

    await screen.findByText('Restorable puzzle');
    fireEvent.click(screen.getByRole('button', { name: /Puzzle Settings/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Stopwatch' }));
    await new Promise((resolve) => setTimeout(resolve, 20));
    fireEvent.click(screen.getByTestId('play-d4'));
    await screen.findByText('Not the Recommended Move');

    fireEvent.click(screen.getByRole('button', { name: 'Long Decisions' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await screen.findByText('e5');
    fireEvent.click(screen.getByRole('button', { name: 'Explore this position' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Could not start exploration');
    expect(screen.getByRole('region', { name: 'Long Decisions' })).toBeInTheDocument();
    expect(screen.getByText('e5')).toBeInTheDocument();
    expect(api.submitTrainingAttempt).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Practice Puzzles' }));
    expect(screen.getByText('Restorable puzzle')).toBeInTheDocument();
    expect(getVisibleBoard()).toHaveAttribute('data-position', AFTER_D4_FEN);
    expect(screen.getByText('Not the Recommended Move')).toBeInTheDocument();
    expect(screen.getByText(/0:\d{2}/)).toBeInTheDocument();
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
    expect(getVisibleBoard()).toHaveAttribute('data-position', selectedFen);
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
