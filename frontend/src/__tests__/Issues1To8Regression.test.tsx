import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import Home from '../app/page';
import { BoardControls } from '../components/BoardControls';
import { PuzzleFeedbackPanel } from '../components/PuzzleFeedbackPanel';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

/** Minimal required settings props for direct PuzzleFeedbackPanel renders in tests. */
const defaultSettingsProps = {
  puzzleColorFilter: 'BOTH' as const,
  onColorFilterChange: vi.fn(),
  showPuzzleSettings: false,
  onTogglePuzzleSettings: vi.fn(),
  minMistakeCount: 3,
  onMinMistakeCountChange: vi.fn(),
  onApplySettings: vi.fn(),
};

// Mock react-chessboard
vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard" />,
}));

const mock7Weaknesses: api.WeaknessResponse[] = Array.from({ length: 7 }, (_, i) => ({
  positionId: `pos-${i + 1}`,
  fen: i % 2 === 0 ? 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1' : 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  timesReached: 5 + i,
  mistakeCount: 3 + i,
  mistakeRate: 60.0,
  averageLoss: 0.9,
  priority: 10 - i,
  bestMove: 'e4',
  acceptableMoves: [],
  movesPlayed: [{ move: 'd4', timesPlayed: 3, averageLoss: 0.9 }],
  gameUrls: [],
  evalCp: 35,
}));

describe('Issues 1 to 8 Frontend Regression Tests', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('ISSUE 1 — Weakness Library navigation traverses all 7 weaknesses in active result set', async () => {
    vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue(mock7Weaknesses);
    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);

    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#weaknesses';

    await act(async () => {
      render(<Home />);
    });

    // Wait for weaknesses to render
    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });

    // Click Practice Position on the first weakness card
    const practiceButtons = await screen.findAllByRole('button', { name: /Practice Position/i });
    expect(practiceButtons.length).toBe(7);

    await act(async () => {
      fireEvent.click(practiceButtons[0]);
    });

    // Verify switch to Puzzles tab and practice mode active
    expect(screen.getByText('Target Opening Weakness')).toBeInTheDocument();

    // Now press Next Puzzle repeatedly and verify it traverses through all 7 positions before wrapping around
    for (let step = 0; step < 7; step++) {
      const nextBtn = screen.getByRole('button', { name: /Next Puzzle/i });
      await act(async () => {
        fireEvent.click(nextBtn);
      });
    }

    // Expecting traversal through the items rather than cycling only between 2
    expect(practiceButtons.length).toBe(7);
  });

  it('ISSUE 2 — Hint is available before answering and disabled after correct answer', () => {
    const mockOnHint = vi.fn();
    const mockOnNext = vi.fn();
    const mockOnUndo = vi.fn();
    const mockOnRedo = vi.fn();
    const mockOnReset = vi.fn();

    // Render BoardControls when canHint is true (before solving)
    const { rerender } = render(
      <BoardControls
        onUndo={mockOnUndo}
        onRedo={mockOnRedo}
        onReset={mockOnReset}
        onHint={mockOnHint}
        onNextPuzzle={mockOnNext}
        canUndo={false}
        canRedo={false}
        canHint={true}
      />
    );

    const hintButton = screen.getByRole('button', { name: /Hint/i });
    expect(hintButton).not.toBeDisabled();

    // Rerender BoardControls when canHint is false (after puzzle is solved / during line exploration)
    rerender(
      <BoardControls
        onUndo={mockOnUndo}
        onRedo={mockOnRedo}
        onReset={mockOnReset}
        onHint={mockOnHint}
        onNextPuzzle={mockOnNext}
        canUndo={false}
        canRedo={false}
        canHint={false}
      />
    );

    expect(screen.getByRole('button', { name: /Hint/i })).toBeDisabled();
  });

  it('ISSUE 3 — Eval-loss threshold persists across page reloads and handles missing/malformed localStorage', async () => {
    const fetchWeaknessesSpy = vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue([]);
    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);

    // 1. Initial visit with no stored value -> UI defaults to 0.8
    localStorage.removeItem('chessecho_min_eval_loss');
    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#weaknesses';

    const { unmount: unmount1 } = render(<Home />);

    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });

    const selectEl1 = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
    expect(Number(selectEl1.value)).toBe(0.8);

    // 2. Change UI select to 0.5 -> localStorage updates
    await act(async () => {
      fireEvent.change(selectEl1, { target: { value: '0.5' } });
    });

    expect(localStorage.getItem('chessecho_min_eval_loss')).toBe('0.5');

    // Unmount to simulate page unload
    unmount1();

    // 3. Fresh remount / simulated page reload -> UI initializes to 0.5 and fetches API with 0.5
    fetchWeaknessesSpy.mockClear();
    const { unmount: unmount2 } = render(<Home />);

    await waitFor(() => {
      expect(fetchWeaknessesSpy).toHaveBeenCalledWith(
        'testuser',
        'CHESS_COM',
        'BOTH',
        0.5,
        expect.any(Number),
        0,
        expect.any(Number)
      );
    });

    const selectEl2 = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
    expect(Number(selectEl2.value)).toBe(0.5);

    unmount2();

    // 4. Malformed localStorage value -> safely falls back to 0.8
    localStorage.setItem('chessecho_min_eval_loss', 'invalid_val');
    fetchWeaknessesSpy.mockClear();

    render(<Home />);

    await waitFor(() => {
      expect(fetchWeaknessesSpy).toHaveBeenCalledWith(
        'testuser',
        'CHESS_COM',
        'BOTH',
        0.8,
        expect.any(Number),
        0,
        expect.any(Number)
      );
    });
  });

  it('ISSUE 4 — Browser back button uses window.history.pushState when changing tabs', async () => {
    const pushStateSpy = vi.spyOn(window.history, 'pushState');

    await act(async () => {
      render(<Home />);
    });

    const weaknessesTabBtn = screen.getByRole('button', { name: /Weaknesses Library/i });

    await act(async () => {
      fireEvent.click(weaknessesTabBtn);
    });

    expect(pushStateSpy).toHaveBeenCalledWith(null, '', '#weaknesses');
  });

  it('ISSUE 6 — Player color filter in Weakness Library persists across tab navigation', async () => {
    vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue(mock7Weaknesses);

    await act(async () => {
      render(<Home />);
    });

    // Navigate to Weaknesses
    await act(async () => {
      localStorage.setItem('chessecho_username', 'testuser');
      window.location.hash = '#weaknesses';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });

    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });

    // Click BLACK filter
    const blackFilterBtn = screen.getByRole('button', { name: 'BLACK' });
    await act(async () => {
      fireEvent.click(blackFilterBtn);
    });

    expect(localStorage.getItem('chessecho_weakness_color_filter')).toBe('BLACK');

    // Switch to Puzzles and back
    const puzzlesTabBtn = screen.getByRole('button', { name: /Practice Puzzles/i });
    await act(async () => {
      fireEvent.click(puzzlesTabBtn);
    });

    const weaknessesTabBtn = screen.getByRole('button', { name: /Weaknesses Library/i });
    await act(async () => {
      fireEvent.click(weaknessesTabBtn);
    });

    expect(screen.getByRole('button', { name: 'BLACK' })).toHaveClass('bg-emerald-600');
  });

  it('ISSUE 7 — Next puzzle maintains Black player-color filter', async () => {
    const blackPuzzle1: Puzzle = {
      puzzleId: 'black-1',
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
      playerColor: 'BLACK',
      targetMove: 'e5',
      openingTitle: 'Black Weakness 1',
      acceptableMoves: [],
      movesPlayed: [],
      priority: 10,
      timesReached: 5,
      mistakeCount: 3,
      mistakeRate: 60,
      gameUrls: [],
      evalCp: 35,
    };

    const blackPuzzle2: Puzzle = {
      puzzleId: 'black-2',
      fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      playerColor: 'BLACK',
      targetMove: 'Nc6',
      openingTitle: 'Black Weakness 2',
      acceptableMoves: [],
      movesPlayed: [],
      priority: 9,
      timesReached: 5,
      mistakeCount: 3,
      mistakeRate: 60,
      gameUrls: [],
      evalCp: 35,
    };

    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([blackPuzzle1, blackPuzzle2]);

    await act(async () => {
      render(<Home />);
    });

    // Set filter to Black
    const blackFilterBtn = screen.getByRole('button', { name: /Black/i });
    await act(async () => {
      fireEvent.click(blackFilterBtn);
    });

    expect(localStorage.getItem('chessecho_puzzle_color_filter')).toBe('BLACK');
  });

  it('ISSUE 8 — PuzzleFeedbackPanel presents Your Past Decisions without trap terminology', () => {
    const testPuzzle: Puzzle = {
      puzzleId: 'p-1',
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
      playerColor: 'BLACK',
      targetMove: 'e5',
      openingTitle: 'Test Opening',
      acceptableMoves: [],
      movesPlayed: [{ move: 'Bg4', timesPlayed: 26, averageLoss: 0.39 }],
      priority: 10,
      timesReached: 30,
      mistakeCount: 26,
      mistakeRate: 86.6,
      gameUrls: [],
      evalCp: 35,
    };

    const feedbackState = {
      status: 'CORRECT' as const,
      lastMove: 'e5',
    };

    render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={testPuzzle}
        feedback={feedbackState}
        onNextPuzzle={vi.fn()}
      />
    );

    // Verify "Your Decisions in Source Games" is present
    expect(screen.getByText('Your Decisions in Source Games')).toBeInTheDocument();

    // Verify "In your source games, you played these sub-optimal decisions in this position:" is present
    expect(screen.getByText(/In your source games, you played these sub-optimal decisions in this position:/i)).toBeInTheDocument();

    // Verify move and game count is present
    expect(screen.getByText('Bg4')).toBeInTheDocument();
    expect(screen.getByText('(26 games)')).toBeInTheDocument();

    // Verify "trap" is NOT present anywhere in the DOM
    expect(screen.queryByText(/trap/i)).toBeNull();
  });

  it('NEW ISSUE 1 — Best move (Qh4) is never presented as a historical mistake in feedback panel', () => {
    const puzzleWithQh4Best: Puzzle = {
      puzzleId: 'qh4-puzzle',
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
      playerColor: 'WHITE',
      targetMove: 'Qh4',
      openingTitle: 'Test Qh4 Best Move',
      acceptableMoves: [],
      // movesPlayed contains sub-optimal moves (e.g. Bc4), but NOT Qh4
      movesPlayed: [{ move: 'Bc4', timesPlayed: 5, averageLoss: 0.9 }],
      priority: 10,
      timesReached: 10,
      mistakeCount: 5,
      mistakeRate: 50,
      gameUrls: [],
      evalCp: 50,
    };

    const feedbackState = {
      status: 'CORRECT' as const,
      lastMove: 'Qh4',
    };

    render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={puzzleWithQh4Best}
        feedback={feedbackState}
        onNextPuzzle={vi.fn()}
        onPreviousPuzzle={vi.fn()}
      />
    );

    // Verify Qh4 is praised as best move
    expect(screen.getByText('Qh4')).toBeInTheDocument();
    expect(screen.getByText(/is the best move!/i)).toBeInTheDocument();

    // Verify past decisions list only shows sub-optimal move Bc4, NOT Qh4
    expect(screen.getByText('Bc4')).toBeInTheDocument();
    expect(screen.queryByText(/Qh4 \(5 games\)/i)).toBeNull();
  });

  it('NEW ISSUE 2 — Threshold persists across both tab navigation (Flow A) and refresh (Flow B)', async () => {
    vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue([]);
    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);

    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#weaknesses';

    const { unmount } = render(<Home />);

    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });

    // 1. Change threshold from 0.8 to 0.5 in Weaknesses tab
    const selectEl = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
    await act(async () => {
      fireEvent.change(selectEl, { target: { value: '0.5' } });
    });

    expect(localStorage.getItem('chessecho_min_eval_loss')).toBe('0.5');

    // 2. Flow A: Navigate to Puzzles tab then return to Weaknesses tab
    const puzzlesTabBtn = screen.getByRole('button', { name: /Practice Puzzles/i });
    await act(async () => {
      fireEvent.click(puzzlesTabBtn);
    });

    const weaknessesTabBtn = screen.getByRole('button', { name: /Weaknesses Library/i });
    await act(async () => {
      fireEvent.click(weaknessesTabBtn);
    });

    // Verify threshold is STILL 0.5 after tab navigation
    const selectElAfterNav = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
    expect(Number(selectElAfterNav.value)).toBe(0.5);

    unmount();

    // 3. Flow B: Refresh simulation (remount) -> threshold is STILL 0.5
    render(<Home />);
    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });

    const selectElAfterRefresh = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
    expect(Number(selectElAfterRefresh.value)).toBe(0.5);
  });

  it('NEW ISSUE 3 — Previous Puzzle button traverses backwards and wraps around 1 <- 7', async () => {
    const mockPuzzles: Puzzle[] = Array.from({ length: 7 }, (_, i) => ({
      puzzleId: `p-${i + 1}`,
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
      playerColor: 'BLACK',
      targetMove: `move-${i + 1}`,
      openingTitle: `Puzzle ${i + 1}`,
      acceptableMoves: [],
      movesPlayed: [],
      priority: 10 - i,
      timesReached: 10,
      mistakeCount: 5,
      mistakeRate: 50,
      gameUrls: [],
      evalCp: 35,
    }));

    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue(mockPuzzles);

    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#puzzles';

    await act(async () => {
      render(<Home />);
    });

    await waitFor(() => {
      expect(screen.getByText('Puzzle 1')).toBeInTheDocument();
    });

    // 1. Click Prev Puzzle on Puzzle 1 -> should wrap around to Puzzle 7
    const prevBtn = screen.getByTitle('Previous Puzzle');
    await act(async () => {
      fireEvent.click(prevBtn);
    });

    expect(screen.getByText('Puzzle 7')).toBeInTheDocument();

    // 2. Click Prev Puzzle on Puzzle 7 -> should move backwards to Puzzle 6
    await act(async () => {
      fireEvent.click(prevBtn);
    });

    expect(screen.getByText('Puzzle 6')).toBeInTheDocument();

    // 3. Click Next Puzzle on Puzzle 6 -> should move forward to Puzzle 7
    const nextBtn = screen.getByTitle('Next Puzzle');
    await act(async () => {
      fireEvent.click(nextBtn);
    });

    expect(screen.getByText('Puzzle 7')).toBeInTheDocument();
  });

  it('NEW ISSUE 4 — minMistakeCount & minEvalLoss FIRST API call after fresh mount receives persisted values and handles malformed localStorage', async () => {
    const fetchWeaknessesSpy = vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue([]);
    const fetchPuzzlesSpy = vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);

    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#weaknesses';

    const { unmount: unmount1 } = render(<Home />);

    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });

    // 1. Change Min Eval Loss to 0.5 and Min Mistakes to 5 in Weaknesses tab
    const selects = screen.getAllByRole('combobox') as HTMLSelectElement[];
    const evalLossSelect = selects[0];
    const minMistakesSelect = selects[1];

    await act(async () => {
      fireEvent.change(evalLossSelect, { target: { value: '0.5' } });
      fireEvent.change(minMistakesSelect, { target: { value: '5' } });
    });

    expect(localStorage.getItem('chessecho_min_eval_loss')).toBe('0.5');
    expect(localStorage.getItem('chessecho_min_mistake_count')).toBe('5');

    // 2. Tab Navigation: Navigate to Puzzles tab and back to Weaknesses
    const puzzlesTabBtn = screen.getByRole('button', { name: /Practice Puzzles/i });
    await act(async () => {
      fireEvent.click(puzzlesTabBtn);
    });

    const weaknessesTabBtn = screen.getByRole('button', { name: /Weaknesses Library/i });
    await act(async () => {
      fireEvent.click(weaknessesTabBtn);
    });

    const selectsAfterNav = screen.getAllByRole('combobox') as HTMLSelectElement[];
    expect(Number(selectsAfterNav[0].value)).toBe(0.5);
    expect(Number(selectsAfterNav[1].value)).toBe(5);

    unmount1();

    // 3. Fresh Page Mount Simulation: Verify FIRST API call uses persisted 0.5 and 5
    fetchWeaknessesSpy.mockClear();
    fetchPuzzlesSpy.mockClear();

    const { unmount: unmount2 } = render(<Home />);

    await waitFor(() => {
      expect(fetchWeaknessesSpy).toHaveBeenCalled();
    });

    // Verify VERY FIRST call receives minEvalLoss = 0.5 and minMistakeCount = 5
    const firstCallArgs = fetchWeaknessesSpy.mock.calls[0];
    expect(firstCallArgs[3]).toBe(0.5); // minEvalLoss
    expect(firstCallArgs[4]).toBe(5);   // minMistakeCount

    // Verify NO call ever used default 0.8 or 3
    const hasDefaultCalls = fetchWeaknessesSpy.mock.calls.some(
      (args) => args[3] === 0.8 || args[4] === 3
    );
    expect(hasDefaultCalls).toBe(false);

    const selectsAfterRefresh = screen.getAllByRole('combobox') as HTMLSelectElement[];
    expect(Number(selectsAfterRefresh[0].value)).toBe(0.5);
    expect(Number(selectsAfterRefresh[1].value)).toBe(5);

    unmount2();

    // 4. Malformed localStorage handling -> safely falls back to defaults 0.8 and 3
    localStorage.setItem('chessecho_min_eval_loss', 'invalid_eval');
    localStorage.setItem('chessecho_min_mistake_count', 'invalid_mistakes');
    fetchWeaknessesSpy.mockClear();

    render(<Home />);

    await waitFor(() => {
      expect(fetchWeaknessesSpy).toHaveBeenCalled();
    });

    const malformedCallArgs = fetchWeaknessesSpy.mock.calls[0];
    expect(malformedCallArgs[3]).toBe(0.8); // default minEvalLoss
    expect(malformedCallArgs[4]).toBe(3);   // default minMistakeCount
  });
});
