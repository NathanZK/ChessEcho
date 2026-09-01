import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import Home from '../app/page';
import { PuzzleFeedbackPanel } from '../components/PuzzleFeedbackPanel';
import * as api from '../services/api';
import { continuationService, moveEvaluationService } from '../services/continuationService';
import { Puzzle } from '../mock/mockData';

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options?: {
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
    };
  }) => (
    <div data-testid="mock-chessboard">
      <button
        data-testid="play-wrong-puzzle-move"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'h2', targetSquare: 'h4' })}
      >
        Play h4
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
  };
});

vi.mock('../components/PuzzleFeedbackPanel', async () => {
  const actual = await vi.importActual<typeof import('../components/PuzzleFeedbackPanel')>(
    '../components/PuzzleFeedbackPanel'
  );
  const ReactModule = await import('react');

  const InstrumentedPuzzleFeedbackPanel = (
    props: Parameters<typeof actual.PuzzleFeedbackPanel>[0] & {
      explorationDecisionMove?: string | null;
    }
  ) =>
    ReactModule.createElement(
      ReactModule.Fragment,
      null,
      ReactModule.createElement(
        'output',
        { 'aria-label': 'Stored exploration decision move' },
        props.explorationDecisionMove ?? 'none'
      ),
      ReactModule.createElement(actual.PuzzleFeedbackPanel, props)
    );

  return {
    ...actual,
    PuzzleFeedbackPanel: InstrumentedPuzzleFeedbackPanel,
  };
});

/** Minimal required settings props for direct PuzzleFeedbackPanel renders. */
const defaultSettingsProps = {
  puzzleColorFilter: 'BOTH' as const,
  onColorFilterChange: vi.fn(),
  showPuzzleSettings: false,
  onTogglePuzzleSettings: vi.fn(),
  minMistakeCount: 3,
  onMinMistakeCountChange: vi.fn(),
  onApplySettings: vi.fn(),
};

const mockPuzzle: Puzzle = {
  puzzleId: 'test-puzzle-explore',
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
};

const integrationPuzzle: Puzzle = {
  puzzleId: 'test-puzzle-wrong-move-flow',
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  playerColor: 'WHITE',
  targetMove: 'Bb5',
  openingTitle: 'Ruy Lopez',
  acceptableMoves: [],
  movesPlayed: [],
  priority: 1.0,
  timesReached: 10,
  mistakeCount: 2,
  mistakeRate: 20.0,
  evalCp: 30,
};

describe('Explore Decision After Wrong Puzzle Move', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    continuationService.clear();
    moveEvaluationService.clear();
    localStorage.clear();
    window.location.hash = '';
    localStorage.setItem('chessecho_username', 'testuser');
    vi.mocked(api.fetchPuzzles).mockResolvedValue([integrationPuzzle]);
  });

  // --- AC: Applicable wrong-move entries expose "Explore this decision" ---

  it('shows "Explore this decision" button after INCORRECT move', () => {
    const onEnterExploration = vi.fn();
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'INCORRECT', lastMove: 'e5' }}
        onNextPuzzle={vi.fn()}
        onEnterExploration={onEnterExploration}
        {...defaultSettingsProps}
      />
    );

    const button = screen.getByText('Explore this decision');
    expect(button).toBeInTheDocument();

    // AC: Correct callback payload — passes decisionMove
    fireEvent.click(button.closest('button')!);
    expect(onEnterExploration).toHaveBeenCalledWith(undefined, 'e5');
  });

  it('shows "Explore this decision" button after HISTORICAL_MISTAKE', () => {
    const onEnterExploration = vi.fn();
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'd4',
          historicalInfo: { timesPlayed: 2, averageLoss: 0.8 },
        }}
        onNextPuzzle={vi.fn()}
        onEnterExploration={onEnterExploration}
        {...defaultSettingsProps}
      />
    );

    const button = screen.getByText('Explore this decision');
    expect(button).toBeInTheDocument();

    // AC: Correct callback payload for HISTORICAL_MISTAKE
    fireEvent.click(button.closest('button')!);
    expect(onEnterExploration).toHaveBeenCalledWith(undefined, 'd4');
  });

  // --- AC: Guard — no button without sufficient move info ---

  it('does NOT show "Explore this decision" when feedback.lastMove is falsy', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'INCORRECT' }}
        onNextPuzzle={vi.fn()}
        onEnterExploration={vi.fn()}
        {...defaultSettingsProps}
      />
    );

    expect(screen.queryByText('Explore this decision')).not.toBeInTheDocument();
  });

  // --- AC: Button not shown during active exploration ---

  it('does NOT show "Explore this decision" when exploration is already active', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'INCORRECT', lastMove: 'e5' }}
        isExplorationActive={true}
        onNextPuzzle={vi.fn()}
        onEnterExploration={vi.fn()}
        {...defaultSettingsProps}
      />
    );

    expect(screen.queryByText('Explore this decision')).not.toBeInTheDocument();
  });

  // --- AC: Button NOT shown in CORRECT or IDLE states ---

  it('does NOT show "Explore this decision" in CORRECT state', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Nf6' }}
        onNextPuzzle={vi.fn()}
        onEnterExploration={vi.fn()}
        {...defaultSettingsProps}
      />
    );

    // CORRECT state has "Continue Exploration →", not "Explore this decision"
    expect(screen.queryByText('Explore this decision')).not.toBeInTheDocument();
    expect(screen.getByText('Continue Exploration →')).toBeInTheDocument();
  });

  it('does NOT show "Explore this decision" in IDLE state', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'IDLE' }}
        onNextPuzzle={vi.fn()}
        onEnterExploration={vi.fn()}
        {...defaultSettingsProps}
      />
    );

    expect(screen.queryByText('Explore this decision')).not.toBeInTheDocument();
  });

  // --- AC: Decision move label in exploration header ---

  it('shows decision move label in exploration card header', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'EXPLORING' }}
        isExplorationActive={true}
        explorationDecisionMove="e5"
        onExitExploration={vi.fn()}
        onNextPuzzle={vi.fn()}
        {...defaultSettingsProps}
      />
    );

    expect(screen.getByText(/from e5/)).toBeInTheDocument();
  });

  it('does NOT show decision move label when explorationDecisionMove is null', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'EXPLORING' }}
        isExplorationActive={true}
        onExitExploration={vi.fn()}
        onNextPuzzle={vi.fn()}
        {...defaultSettingsProps}
      />
    );

    expect(screen.queryByText(/from /)).not.toBeInTheDocument();
  });

  // --- AC: Continuation/opponent compatibility (existing button still works) ---

  it('existing "Continue Exploration →" button still calls onEnterExploration with no args for CORRECT', () => {
    const onEnterExploration = vi.fn();
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'e4' }}
        onNextPuzzle={vi.fn()}
        onEnterExploration={onEnterExploration}
        {...defaultSettingsProps}
      />
    );

    fireEvent.click(screen.getByText('Continue Exploration →').closest('button')!);
    expect(onEnterExploration).toHaveBeenCalledWith();
  });

  it('exits wrong-move exploration to IDLE and clears the stored decision move through Home', async () => {
    render(<Home />);

    await waitFor(() => expect(screen.getByText('Ruy Lopez')).toBeInTheDocument());
    expect(screen.getByLabelText('Stored exploration decision move')).toHaveTextContent('none');

    fireEvent.click(screen.getByTestId('play-wrong-puzzle-move'));

    await waitFor(() => {
      expect(screen.getByText('Not the Recommended Move')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole('button', { name: 'Explore this decision' }));

    await waitFor(() => {
      expect(screen.getByText(/from h4/i)).toBeInTheDocument();
      expect(screen.getByLabelText('Stored exploration decision move')).toHaveTextContent('h4');
    });

    fireEvent.click(screen.getByRole('button', { name: 'Exit' }));

    await waitFor(() => {
      expect(
        screen.getByText(/Find White's best move or an acceptable alternative/i)
      ).toBeInTheDocument();
      expect(screen.getByLabelText('Stored exploration decision move')).toHaveTextContent('none');
    });
    expect(screen.queryByText(/from h4/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Puzzle Solved! 🎉')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Continue Exploration/i })).not.toBeInTheDocument();
  });
});
