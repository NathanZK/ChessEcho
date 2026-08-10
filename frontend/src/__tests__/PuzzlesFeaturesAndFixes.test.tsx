import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import { PuzzleFeedbackPanel, formatDecimal } from '../components/PuzzleFeedbackPanel';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    startImportJob: vi.fn(),
    pollJobStatus: vi.fn(),
    fetchPuzzles: vi.fn(),
    fetchWeaknesses: vi.fn(),
  };
});

const mockPuzzles: Puzzle[] = [
  {
    puzzleId: 'puzzle-id-1',
    fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
    playerColor: 'WHITE',
    targetMove: 'e4',
    openingTitle: "King's Pawn Opening",
    acceptableMoves: [],
    movesPlayed: [{ move: 'd4', timesPlayed: 2, averageLoss: 0.8 }],
    priority: 1.0,
    timesReached: 10,
    mistakeCount: 2,
    mistakeRate: 20.0,
    evalCp: 35,
  },
  {
    puzzleId: 'puzzle-id-2',
    fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
    playerColor: 'BLACK',
    targetMove: 'Nc6',
    openingTitle: "King's Knight Opening",
    acceptableMoves: [],
    movesPlayed: [],
    priority: 2.0,
    timesReached: 5,
    mistakeCount: 1,
    mistakeRate: 20.0,
    evalCp: 15,
  },
];

describe('Puzzles Tab Features and Fixes', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchPuzzles).mockResolvedValue(mockPuzzles);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows loading spinner without empty-state flash while restoring/loading puzzles', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    let resolvePuzzles: (val: Puzzle[]) => void;
    const pendingPromise = new Promise<Puzzle[]>((resolve) => {
      resolvePuzzles = resolve;
    });
    vi.mocked(api.fetchPuzzles).mockReturnValue(pendingPromise);

    render(<Home />);

    // Verifies loading state is rendered during fetch
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
    expect(screen.queryByText(/No Practice Puzzles Available/i)).not.toBeInTheDocument();

    // Resolve promise
    await waitFor(() => {
      resolvePuzzles!(mockPuzzles);
    });

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });
  });

  it('restores the specific puzzle by puzzleId from localStorage on mount/refresh', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_puzzle_id', 'puzzle-id-2');

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Knight Opening")).toBeInTheDocument();
      expect(screen.queryByText("King's Pawn Opening")).not.toBeInTheDocument();
    });
  });

  it('filters puzzles by player color selection (Both -> White -> Black -> Both) and persists choice in localStorage', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');

    render(<Home />);

    // 1. Initial Both selection fetches WHITE and BLACK
    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BLACK', 0.8, 3, 10, 0);
    });

    vi.clearAllMocks();

    // 2. Select 'White'
    const whiteFilterBtn = screen.getByRole('button', { name: /^White$/i });
    fireEvent.click(whiteFilterBtn);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).not.toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BLACK', 0.8, 3, 10, 0);
    });
    expect(localStorage.getItem('chessecho_puzzle_color_filter')).toBe('WHITE');

    vi.clearAllMocks();

    // 3. Select 'Black'
    const blackFilterBtn = screen.getByRole('button', { name: /^Black$/i });
    fireEvent.click(blackFilterBtn);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BLACK', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).not.toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 10, 0);
    });
    expect(localStorage.getItem('chessecho_puzzle_color_filter')).toBe('BLACK');

    vi.clearAllMocks();

    // 4. Select 'Both' again
    const bothFilterBtn = screen.getByRole('button', { name: /^Both$/i });
    fireEvent.click(bothFilterBtn);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BLACK', 0.8, 3, 10, 0);
    });
    expect(localStorage.getItem('chessecho_puzzle_color_filter')).toBe('BOTH');
  });

  it('refetches puzzles with updated minEvalLoss and minMistakeCount when Apply is clicked, displaying loading spinner and updating active puzzle', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    const newFilteredPuzzle: Puzzle = {
      puzzleId: 'puzzle-id-3',
      fen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2',
      playerColor: 'WHITE',
      targetMove: 'Nf3',
      openingTitle: 'Sicilian Defense Weakness',
      acceptableMoves: [],
      movesPlayed: [],
      priority: 3.0,
      timesReached: 8,
      mistakeCount: 4,
      mistakeRate: 50.0,
      evalCp: 25,
    };

    let resolvePuzzles: (val: Puzzle[]) => void;
    const pendingPromise = new Promise<Puzzle[]>((resolve) => {
      resolvePuzzles = resolve;
    });
    vi.mocked(api.fetchPuzzles).mockReturnValue(pendingPromise);

    // Toggle settings toolbar
    const settingsToggle = screen.getByRole('button', { name: /Puzzle Settings/i });
    fireEvent.click(settingsToggle);

    // Update Min Eval Loss & Min Mistakes inputs
    const minEvalInput = screen.getByLabelText(/Min Eval Loss/i);
    fireEvent.change(minEvalInput, { target: { value: '1.2' } });

    const minMistakesInput = screen.getByLabelText(/Min Mistakes/i);
    fireEvent.change(minMistakesInput, { target: { value: '5' } });

    // Click Apply
    const applyBtn = screen.getByRole('button', { name: /^Apply$/i });
    fireEvent.click(applyBtn);

    // 1. Verifies loading spinner appears while fetching
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

    // Resolve promise with new filtered puzzle
    resolvePuzzles!([newFilteredPuzzle]);

    // 2. Verifies API call parameters
    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 1.2, 5, 10, 0);
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BLACK', 1.2, 5, 10, 0);
    });

    // 3. Verifies current puzzle is updated to the first returned puzzle since previous puzzle is no longer present
    await waitFor(() => {
      expect(screen.getByText('Sicilian Defense Weakness')).toBeInTheDocument();
      expect(screen.queryByText("King's Pawn Opening")).not.toBeInTheDocument();
    });
  });

  it('formats decimal values using period instead of comma', () => {
    expect(formatDecimal(1.5)).toBe('1.50');
    expect(formatDecimal(0.8, 1)).toBe('0.8');

    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'CORRECT', lastMove: 'e4' }}
        moveHistory={['e4']}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('-0.80 pawns')).toBeInTheDocument();
    expect(screen.getByText('20.0%')).toBeInTheDocument();
  });

  it('completely removes Historical Games section from PuzzleFeedbackPanel', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'IDLE' }}
        moveHistory={[]}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.queryByText(/Historical Games/i)).not.toBeInTheDocument();
  });

  it('renders neutral feedback for incorrect moves without fabricated explanations', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'INCORRECT', lastMove: 'h3' }}
        moveHistory={['h3']}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('h3')).toBeInTheDocument();
    expect(screen.getByText(/is not the recommended move/i)).toBeInTheDocument();
    expect(screen.queryByText(/control of central squares/i)).not.toBeInTheDocument();
  });

  it('does not evaluate opponent moves (e.g. Qxb4) against initial targetMove or overwrite user mistake feedback (regression bug fix)', () => {
    // 1. Initial user move attempt: user makes a mistake ('d6') on move 1
    const { rerender } = render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzles[0]}
        feedback={{
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'd6',
          historicalInfo: { timesPlayed: 2, averageLoss: 0.8 },
        }}
        moveHistory={['d6']}
        onNextPuzzle={vi.fn()}
      />
    );

    // Verifies initial mistake feedback (d6) is displayed
    expect(screen.getByText('Historical Mistake Detected!')).toBeInTheDocument();
    expect(screen.getByText('d6')).toBeInTheDocument();
    expect(screen.queryByText(/Qxb4 is not the recommended move/i)).not.toBeInTheDocument();

    // 2. Opponent plays follow-up move ('Qxb4') on move 2. Feedback remains unchanged (still displaying d6 mistake)!
    rerender(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzles[0]}
        feedback={{
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'd6',
          historicalInfo: { timesPlayed: 2, averageLoss: 0.8 },
        }}
        moveHistory={['d6', 'Qxb4']}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('Historical Mistake Detected!')).toBeInTheDocument();
    expect(screen.getByText('d6')).toBeInTheDocument();
    expect(screen.queryByText(/Qxb4 is not the recommended move/i)).not.toBeInTheDocument();
  });

  it('evaluates initial correct move properly and transitions to exploration mode on follow-up moves', () => {
    // 1. User makes correct move ('e4')
    const { rerender } = render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'CORRECT', lastMove: 'e4' }}
        moveHistory={['e4']}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('Puzzle Solved! 🎉')).toBeInTheDocument();
    expect(screen.getByText('e4')).toBeInTheDocument();

    rerender(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'EXPLORING', lastMove: 'Nf3' }}
        moveHistory={['e4', 'Nf3']}
        onNextPuzzle={vi.fn()}
      />
    );
    expect(screen.getByText('Line Exploration 🔍')).toBeInTheDocument();
    expect(screen.getByText('Nf3')).toBeInTheDocument();
  });

  it('clears feedback and resets interaction state when changing player color filter', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    const blackPuzzle: Puzzle = {
      puzzleId: 'puzzle-black-99',
      fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      playerColor: 'BLACK',
      targetMove: 'Nc6',
      openingTitle: "King's Knight Opening",
      acceptableMoves: [],
      movesPlayed: [],
      priority: 2.0,
      timesReached: 5,
      mistakeCount: 1,
      mistakeRate: 20.0,
      evalCp: 15,
    };

    vi.mocked(api.fetchPuzzles).mockResolvedValue([blackPuzzle]);

    // Change player color filter to 'Black'
    const blackFilterBtn = screen.getByRole('button', { name: /^Black$/i });
    fireEvent.click(blackFilterBtn);

    // 3. Verify newly selected puzzle ("King's Knight Opening") is displayed
    await waitFor(() => {
      expect(screen.getByText("King's Knight Opening")).toBeInTheDocument();
    });

    // 4. Verify previous puzzle feedback is no longer present and new puzzle is in untouched initial state
    expect(screen.queryByText(/Historical Mistake Detected!/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Not the Recommended Move/i)).not.toBeInTheDocument();
    expect(screen.getByText(/Find Black's best move or an acceptable alternative/i)).toBeInTheDocument();
  });

  it('does not display acceptableThreshold input or state anywhere in UI', () => {
    render(<Home />);

    expect(screen.queryByText(/Acceptable Threshold/i)).not.toBeInTheDocument();
  });
});
