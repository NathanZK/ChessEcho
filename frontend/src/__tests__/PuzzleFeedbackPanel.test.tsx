import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import {
  PuzzleFeedbackPanel,
  formatDecimal,
  formatTimerMs,
  formatCalculationLine,
} from '../components/PuzzleFeedbackPanel';
import type { Puzzle } from '@/types/puzzle';
import type { ContinuationCandidate } from '../services/api';

function makeCandidate(move: string): ContinuationCandidate {
  return {
    move,
    resultingFen: '8/8/8/8/8/8/8/8 w - - 0 1',
    providerType: 'ENGINE',
  };
}

const basePuzzle: Puzzle = {
  puzzleId: 'puzzle-1',
  fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
  playerColor: 'WHITE',
  targetMove: 'e4',
  openingTitle: "King's Pawn Opening",
  acceptableMoves: [],
  movesPlayed: [{ move: 'e4', timesPlayed: 5, averageLoss: 0.1 }],
  priority: 1,
  timesReached: 10,
  mistakeCount: 2,
  mistakeRate: 20,
};

function renderPanel(props: Partial<React.ComponentProps<typeof PuzzleFeedbackPanel>> = {}) {
  const defaultProps: React.ComponentProps<typeof PuzzleFeedbackPanel> = {
    puzzle: basePuzzle,
    feedback: { status: 'IDLE' },
    onNextPuzzle: vi.fn(),
    onPreviousPuzzle: vi.fn(),
    puzzleColorFilter: 'BOTH',
    onColorFilterChange: vi.fn(),
    showPuzzleSettings: true,
    onTogglePuzzleSettings: vi.fn(),
    minMistakeCount: 1,
    onMinMistakeCountChange: vi.fn(),
    onApplySettings: vi.fn(),
  };

  return {
    ...render(<PuzzleFeedbackPanel {...defaultProps} {...props} />),
    props: { ...defaultProps, ...props },
  };
}

describe('PuzzleFeedbackPanel Unit Tests', () => {
  describe('Helper Functions', () => {
    it('formatCalculationLine formats alternating and single-color lines correctly', () => {
      expect(formatCalculationLine([{ san: 'e4', isWhite: true }])).toBe('1. e4');
      expect(formatCalculationLine([{ san: 'e5', isWhite: false }])).toBe('1... e5');
      expect(
        formatCalculationLine([
          { san: 'e4', isWhite: true },
          { san: 'e5', isWhite: false },
          { san: 'Nf3', isWhite: true },
          { san: 'Nc6', isWhite: false },
        ]),
      ).toBe('1. e4 e5 2. Nf3 Nc6');
    });

    it('formatDecimal formats decimals with custom precision and point separator', () => {
      expect(formatDecimal(1.5, 2)).toBe('1.50');
      expect(formatDecimal(0, 2)).toBe('0.00');
    });

    it('formatTimerMs formats milliseconds into mm:ss with clamping', () => {
      expect(formatTimerMs(65000)).toBe('1:05');
      expect(formatTimerMs(0)).toBe('0:00');
      expect(formatTimerMs(-500)).toBe('0:00');
    });
  });

  describe('Keyboard Navigation', () => {
    it('calls onNextPuzzle when Enter is pressed on solved puzzle', () => {
      const onNextPuzzle = vi.fn();
      renderPanel({
        feedback: { status: 'CORRECT' },
        onNextPuzzle,
      });

      fireEvent.keyDown(window, { key: 'Enter' });
      expect(onNextPuzzle).toHaveBeenCalledTimes(1);

      fireEvent.keyDown(window, { key: 'Escape' });
      expect(onNextPuzzle).toHaveBeenCalledTimes(1);
    });

    it('does not call onNextPuzzle when Enter is pressed on unsolved puzzle', () => {
      const onNextPuzzle = vi.fn();
      renderPanel({
        feedback: { status: 'INCORRECT' },
        onNextPuzzle,
      });

      fireEvent.keyDown(window, { key: 'Enter' });
      expect(onNextPuzzle).not.toHaveBeenCalled();
    });
  });

  describe('Color Filter Buttons', () => {
    it('applies active class strictly to the button matching puzzleColorFilter and dispatches callback', () => {
      const onColorFilterChange = vi.fn();
      renderPanel({
        showPuzzleSettings: true,
        puzzleColorFilter: 'WHITE',
        onColorFilterChange,
      });

      const whiteBtn = screen.getByRole('button', { name: 'White' });
      const bothBtn = screen.getByRole('button', { name: 'Both' });
      const blackBtn = screen.getByRole('button', { name: 'Black' });

      expect(whiteBtn.className).toContain('bg-emerald-600 text-white shadow-sm');
      expect(bothBtn.className).toContain('text-slate-400');
      expect(blackBtn.className).toContain('text-slate-400');

      fireEvent.click(blackBtn);
      expect(onColorFilterChange).toHaveBeenCalledWith('BLACK');
    });
  });

  describe('Timed Training Settings', () => {
    it('applies active styling to the selected timerMode and handles duration change and errors', () => {
      const onTimerModeChange = vi.fn();
      const onTimerAllowedMsChange = vi.fn();

      renderPanel({
        showPuzzleSettings: true,
        timerMode: 'COUNTDOWN',
        timerAllowedMs: 60000,
        timerSubmissionError: 'Timer submission failed',
        onTimerModeChange,
        onTimerAllowedMsChange,
      });

      const countdownBtn = screen.getByRole('button', { name: 'Countdown' });
      const offBtn = screen.getByRole('button', { name: 'Off' });

      expect(countdownBtn.className).toContain('bg-emerald-600 text-white shadow-sm');
      expect(offBtn.className).toContain('text-slate-400');

      fireEvent.click(offBtn);
      expect(onTimerModeChange).toHaveBeenCalledWith(null);

      const durationSelect = screen.getByRole('combobox');
      fireEvent.change(durationSelect, { target: { value: '120000' } });
      expect(onTimerAllowedMsChange).toHaveBeenCalledWith(120000);

      expect(screen.getByText('Timer submission failed')).toBeInTheDocument();
      expect(screen.getByText('Timer submission failed').className).toContain('text-red-400');
    });
  });

  describe('Historical Mistake Banner', () => {
    it('renders singular "1 game" when timesPlayed is 1', () => {
      renderPanel({
        feedback: {
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'c5',
          historicalInfo: { timesPlayed: 1, averageLoss: 1.5 },
        },
      });

      expect(screen.getByText(/1 game/)).toBeInTheDocument();
      expect(screen.getByText(/1.50 pawns worse/)).toBeInTheDocument();
    });

    it('renders plural "N games" when timesPlayed is greater than 1', () => {
      renderPanel({
        feedback: {
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'c5',
          historicalInfo: { timesPlayed: 3, averageLoss: 2.25 },
        },
      });

      expect(screen.getByText(/3 games/)).toBeInTheDocument();
      expect(screen.getByText(/2.25 pawns worse/)).toBeInTheDocument();
    });
  });

  describe('Exploration Mode and Continuation Selectors', () => {
    it('handles exploration play mode tab active classes and callbacks', () => {
      const onExplorationPlayModeChange = vi.fn();

      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        onExplorationPlayModeChange,
      });

      const challengeTab = screen.getByRole('button', { name: 'Challenge Mode' });
      expect(challengeTab.className).toContain('bg-purple-600 text-white shadow-sm');

      const chessechoTab = screen.getByRole('button', { name: 'vs ChessProbe' });
      expect(chessechoTab.className).toContain('text-slate-400');

      fireEvent.click(chessechoTab);
      expect(onExplorationPlayModeChange).toHaveBeenCalledWith('CHESSPROBE');
    });

    it('does not render continuation mode toggle (ENGINE / HUMAN) and defaults to ENGINE', () => {
      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHESSPROBE',
      });

      expect(screen.queryByRole('button', { name: 'ENGINE' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'HUMAN' })).not.toBeInTheDocument();
    });

    it('renders evaluative move quality badge on played move card instead of Quiet badge when plies is 0', () => {
      const bestMoveCandidate = {
        ...makeCandidate('e4'),
        evalLoss: 0,
        pliesResolved: 0,
        continuationSanMoves: [],
      };
      const acceptableCandidate = {
        ...makeCandidate('d4'),
        evalLoss: 0.15,
        pliesResolved: 0,
        continuationSanMoves: [],
      };
      const inaccuracyCandidate = {
        ...makeCandidate('c4'),
        evalLoss: 0.45,
        pliesResolved: 0,
        continuationSanMoves: [],
      };

      const { rerender } = renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHESSPROBE',
        lastContinuationCandidates: {
          parentFen: 'some_fen',
          selected: bestMoveCandidate,
          candidates: [bestMoveCandidate],
        },
      });

      expect(screen.getByText('Best move')).toBeInTheDocument();
      expect(screen.queryByText('Quiet')).not.toBeInTheDocument();

      rerender(
        <PuzzleFeedbackPanel
          puzzle={basePuzzle}
          feedback={{ status: 'CORRECT', lastMove: 'e4' }}
          onNextPuzzle={() => {}}
          puzzleColorFilter="BOTH"
          onColorFilterChange={() => {}}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={() => {}}
          minMistakeCount={1}
          onMinMistakeCountChange={() => {}}
          onApplySettings={() => {}}
          isExplorationActive={true}
          explorationPlayMode="CHESSPROBE"
          lastContinuationCandidates={{
            parentFen: 'some_fen',
            selected: acceptableCandidate,
            candidates: [acceptableCandidate],
          }}
        />
      );
      expect(screen.getByText('Acceptable move (+0.15 pawns)')).toBeInTheDocument();
      expect(screen.queryByText('Quiet')).not.toBeInTheDocument();

      rerender(
        <PuzzleFeedbackPanel
          puzzle={basePuzzle}
          feedback={{ status: 'CORRECT', lastMove: 'e4' }}
          onNextPuzzle={() => {}}
          puzzleColorFilter="BOTH"
          onColorFilterChange={() => {}}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={() => {}}
          minMistakeCount={1}
          onMinMistakeCountChange={() => {}}
          onApplySettings={() => {}}
          isExplorationActive={true}
          explorationPlayMode="CHESSPROBE"
          lastContinuationCandidates={{
            parentFen: 'some_fen',
            selected: inaccuracyCandidate,
            candidates: [inaccuracyCandidate],
          }}
        />
      );
      expect(screen.getByText('Inaccuracy')).toBeInTheDocument();
      expect(screen.queryByText('Quiet')).not.toBeInTheDocument();
    });
  });

  describe('Challenge Mode Submissions and Validation', () => {
    it('displays Math.min bounded prompt for fewer than 3 candidates', () => {
      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        challengeCandidates: [makeCandidate('Nf3'), makeCandidate('d4')],
      });

      expect(screen.getByText(/Find up to 2 strong candidate moves\./)).toBeInTheDocument();
    });

    it('displays prompt for 3 or more candidates clamped to 3', () => {
      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        challengeCandidates: [
          makeCandidate('Nf3'),
          makeCandidate('d4'),
          makeCandidate('c4'),
          makeCandidate('e4'),
        ],
      });

      expect(screen.getByText(/Find up to 3 strong candidate moves\./)).toBeInTheDocument();
    });

    it('renders "Choose a candidate to calculate." when some moves are strong', () => {
      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        challengeCandidates: [makeCandidate('Nf3')],
        challengeSubmission: {
          isComplete: false,
          foundCount: 1,
          targetCount: 2,
          moves: [
            { san: 'Nf3', status: 'strong' },
            { san: 'h3', status: 'weak' },
          ],
        },
      });

      expect(screen.getByText('Choose a candidate to calculate.')).toBeInTheDocument();
    });

    it('disables candidate submit button for empty or whitespace-only inputs', () => {
      const { rerender } = renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        challengeCandidates: [makeCandidate('Nf3')],
        challengeInput: '   ',
      });

      const submitBtn = screen.getByRole('button', { name: 'Submit Candidates' });
      expect(submitBtn).toBeDisabled();

      rerender(
        <PuzzleFeedbackPanel
          puzzle={basePuzzle}
          feedback={{ status: 'IDLE' }}
          onNextPuzzle={vi.fn()}
          onPreviousPuzzle={vi.fn()}
          puzzleColorFilter="BOTH"
          onColorFilterChange={vi.fn()}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={vi.fn()}
          minMistakeCount={1}
          onMinMistakeCountChange={vi.fn()}
          onApplySettings={vi.fn()}
          isExplorationActive={true}
          explorationPlayMode="CHALLENGE"
          challengeCandidates={[makeCandidate('Nf3')]}
          challengeInput="Nf3"
        />,
      );

      expect(screen.getByRole('button', { name: 'Submit Candidates' })).not.toBeDisabled();
    });

    it('renders explored checkmark and feedback styling correctly', () => {
      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        challengeCandidates: [makeCandidate('Nf3')],
        challengeBranches: {
          Nf3: [{ san: 'Nf3', fenAfter: 'fen', isWhite: true }],
        },
        challengeSubmission: {
          isComplete: true,
          foundCount: 1,
          targetCount: 1,
          moves: [{ san: 'Nf3', status: 'strong' }],
        },
        challengeFeedback: { type: 'success', message: 'All candidates found!' },
      });

      expect(screen.getByText('✓ All candidates found!')).toBeInTheDocument();
      expect(screen.getByText('✓ All candidates found!').className).toContain('border-emerald-500/30 text-emerald-300');
    });

    it('renders error challenge feedback with rose styling and without checkmark prefix', () => {
      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        challengeCandidates: [makeCandidate('Nf3')],
        challengeFeedback: { type: 'error', message: 'Invalid move submitted' },
      });

      expect(screen.getByText('Invalid move submitted')).toBeInTheDocument();
      expect(screen.getByText('Invalid move submitted').className).toContain('border-rose-500/30 text-rose-300');
    });
  });

  describe('Calculation Mode Interactions', () => {
    it('disables back button when line has 1 or fewer moves and enables when > 1', () => {
      const onCalculationBack = vi.fn();
      const { rerender } = renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        activeChallengeCandidate: 'Nf3',
        challengeBranches: {
          Nf3: [{ san: 'Nf3', fenAfter: 'fen1', isWhite: true }],
        },
        onCalculationBack,
      });

      const backBtn = screen.getByRole('button', { name: 'Back to candidates' }).nextElementSibling as HTMLButtonElement;
      expect(backBtn).toBeDisabled();

      rerender(
        <PuzzleFeedbackPanel
          puzzle={basePuzzle}
          feedback={{ status: 'IDLE' }}
          onNextPuzzle={vi.fn()}
          onPreviousPuzzle={vi.fn()}
          puzzleColorFilter="BOTH"
          onColorFilterChange={vi.fn()}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={vi.fn()}
          minMistakeCount={1}
          onMinMistakeCountChange={vi.fn()}
          onApplySettings={vi.fn()}
          isExplorationActive={true}
          explorationPlayMode="CHALLENGE"
          activeChallengeCandidate="Nf3"
          challengeBranches={{
            Nf3: [
              { san: 'Nf3', fenAfter: 'fen1', isWhite: true },
              { san: 'Nc6', fenAfter: 'fen2', isWhite: false },
            ],
          }}
          onCalculationBack={onCalculationBack}
        />,
      );

      const activeBackBtn = screen.getByRole('button', { name: 'Back to candidates' }).nextElementSibling as HTMLButtonElement;
      expect(activeBackBtn).not.toBeDisabled();
      fireEvent.click(activeBackBtn);
      expect(onCalculationBack).toHaveBeenCalledTimes(1);
    });

    it('disables calculation submit button for whitespace-only input and renders feedback', () => {
      renderPanel({
        isExplorationActive: true,
        explorationPlayMode: 'CHALLENGE',
        activeChallengeCandidate: 'Nf3',
        challengeBranches: {
          Nf3: [{ san: 'Nf3', fenAfter: 'fen1', isWhite: true }],
        },
        calculationInput: '   ',
        calculationFeedback: { type: 'success', message: 'Calculation verified' },
      });

      expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
      expect(screen.getByText('✓ Calculation verified')).toBeInTheDocument();
      expect(screen.getByText('✓ Calculation verified').className).toContain('border-emerald-500/30 text-emerald-300');
    });
  });

  describe('Attempt Count Indicators and Retry', () => {
    it('renders loading state when status is loading', () => {
      renderPanel({
        attemptCountState: { status: 'loading' },
      });

      expect(screen.getByText('Loading your attempt counts...')).toBeInTheDocument();
    });

    it('renders error state and invokes onReloadAttemptCount on retry click', () => {
      const onReloadAttemptCount = vi.fn();
      renderPanel({
        attemptCountState: { status: 'error' },
        onReloadAttemptCount,
      });

      expect(screen.getByText('Your attempt counts are unavailable.')).toBeInTheDocument();
      const retryBtn = screen.getByRole('button', { name: 'Retry counts' });
      fireEvent.click(retryBtn);
      expect(onReloadAttemptCount).toHaveBeenCalledTimes(1);
    });

    it('renders recording indicator strictly when attemptRecording is true', () => {
      const { rerender } = renderPanel({
        attemptCountState: { status: 'loaded', solvedCount: 5, failedCount: 2 },
        attemptRecording: true,
      });

      expect(screen.getByText('Recording your answer...')).toBeInTheDocument();

      rerender(
        <PuzzleFeedbackPanel
          puzzle={basePuzzle}
          feedback={{ status: 'IDLE' }}
          onNextPuzzle={vi.fn()}
          onPreviousPuzzle={vi.fn()}
          puzzleColorFilter="BOTH"
          onColorFilterChange={vi.fn()}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={vi.fn()}
          minMistakeCount={1}
          onMinMistakeCountChange={vi.fn()}
          onApplySettings={vi.fn()}
          attemptCountState={{ status: 'loaded', solvedCount: 5, failedCount: 2 }}
          attemptRecording={false}
        />,
      );

      expect(screen.queryByText('Recording your answer...')).not.toBeInTheDocument();
    });

    it('invokes onRetryAttempt when clicking retry on pending attempt', () => {
      const onRetryAttempt = vi.fn();
      renderPanel({
        attemptCountState: { status: 'loaded', solvedCount: 1, failedCount: 1 },
        pendingAttempts: [{ id: 'attempt-123', retryable: true }],
        onRetryAttempt,
      });

      const retryBtn = screen.getByRole('button', { name: 'Retry recording answer 1' });
      fireEvent.click(retryBtn);
      expect(onRetryAttempt).toHaveBeenCalledWith('attempt-123');
    });
  });

  describe('Blindfold Entry Tooltip and State', () => {
    it('displays disabled tooltip and disabled button when board is not settled', () => {
      renderPanel({
        feedback: { status: 'CORRECT' },
        onEnterBlindfold: vi.fn(),
        isBlindfoldEntryDisabled: true,
      });

      const btn = screen.getByRole('button', { name: /train blindfold/i });
      expect(btn).toBeDisabled();
      expect(btn).toHaveAttribute('title', 'Available once the board settles');
    });

    it('displays enabled tooltip and enabled button when board is settled', () => {
      renderPanel({
        feedback: { status: 'CORRECT' },
        onEnterBlindfold: vi.fn(),
        isBlindfoldEntryDisabled: false,
      });

      const btn = screen.getByRole('button', { name: /train blindfold/i });
      expect(btn).not.toBeDisabled();
      expect(btn).toHaveAttribute('title', 'Continue this position blindfolded');
    });
  });

  describe('Move-Quality Classification and Exploration Guard', () => {
    it('unmounts top-level feedback card when isExplorationActive is true', () => {
      renderPanel({
        feedback: { status: 'CORRECT', lastMove: 'e4' },
        puzzle: { ...basePuzzle, targetMove: 'e4' },
        isExplorationActive: true,
        explorationPlayMode: 'BOTH_SIDES',
      });

      expect(screen.queryByText('Puzzle Solved! 🎉')).not.toBeInTheDocument();
      expect(screen.queryByText(/is the best move!/i)).not.toBeInTheDocument();
      expect(screen.getByText(/Play Both Sides/i)).toBeInTheDocument();
    });

    it('renders best move feedback when lastMove matches puzzle.targetMove', () => {
      renderPanel({
        feedback: { status: 'CORRECT', lastMove: 'e4' },
        puzzle: { ...basePuzzle, targetMove: 'e4' },
        isExplorationActive: false,
      });

      expect(screen.getByText('Puzzle Solved! 🎉')).toBeInTheDocument();
      expect(screen.getAllByText('e4').length).toBeGreaterThan(0);
      expect(screen.getByText(/is the best move!/i)).toBeInTheDocument();
      expect(screen.queryByText(/is an acceptable alternative!/i)).not.toBeInTheDocument();
    });

    it('renders acceptable alternative feedback when lastMove differs from puzzle.targetMove', () => {
      renderPanel({
        feedback: { status: 'CORRECT', lastMove: 'c5' },
        puzzle: { ...basePuzzle, targetMove: 'e4' },
        isExplorationActive: false,
      });

      expect(screen.getByText('Good Move! 👍')).toBeInTheDocument();
      expect(screen.getByText(/is an acceptable alternative!/i)).toBeInTheDocument();
      expect(screen.getByText(/is #1 best/i)).toBeInTheDocument();
      expect(screen.queryByText('Puzzle Solved! 🎉')).not.toBeInTheDocument();
    });
  });

  describe('Context-Aware Practice and Truthful Statistics (Issue #662)', () => {
    it('renders Target Opening Weakness and recurrence statistics for weakness and puzzle sources', () => {
      renderPanel({
        puzzle: { ...basePuzzle, source: 'weakness', mistakeRate: 25.5, timesReached: 4 },
      });

      expect(screen.getByText('Target Opening Weakness')).toBeInTheDocument();
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
      expect(screen.getByText('Mistake Rate')).toBeInTheDocument();
      expect(screen.getByText('25.5%')).toBeInTheDocument();
      expect(screen.getByText('4 games')).toBeInTheDocument();
    });

    it('renders Decision Timing header and omits recurrence statistics for long-decision source', () => {
      renderPanel({
        puzzle: {
          ...basePuzzle,
          source: 'long-decision',
          timesReached: 0,
          mistakeRate: 0,
          longDecisionContext: {
            timeControl: 'RAPID',
            decisionTimeMs: 45_000,
            movePlayed: 'e5',
            severity: 'CRITICAL',
            gameUrl: 'https://www.chess.com/game/live/12345',
          },
        },
      });

      expect(screen.getByText('Decision Timing')).toBeInTheDocument();
      expect(screen.getByText('RAPID')).toBeInTheDocument();
      expect(screen.getByText('CRITICAL')).toBeInTheDocument();
      // Pre-attempt secrecy: movePlayed and decisionTimeMs must NOT be rendered
      expect(screen.queryByText('e5')).not.toBeInTheDocument();
      expect(screen.queryByText('(0:45)')).not.toBeInTheDocument();
      expect(
        screen.getByText(/Find White's best move\. Can you improve upon your game decision/i)
      ).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Open game/i })).toHaveAttribute(
        'href',
        'https://www.chess.com/game/live/12345'
      );

      // Must suppress Target Opening Weakness
      expect(screen.queryByText('Target Opening Weakness')).not.toBeInTheDocument();

      // Recurrence statistics must NOT be rendered (no 0.0% or 0 games)
      expect(screen.queryByText('Mistake Rate')).not.toBeInTheDocument();
      expect(screen.queryByText('Times Reached')).not.toBeInTheDocument();
      expect(screen.queryByText(/0\.0%/)).not.toBeInTheDocument();
      expect(screen.queryByText(/0 games/)).not.toBeInTheDocument();
    });

    it('renders running practice stopwatch badge for long-decision before first move while keeping game move concealed', () => {
      renderPanel({
        puzzle: {
          ...basePuzzle,
          source: 'long-decision',
          timesReached: 0,
          mistakeRate: 0,
          longDecisionContext: {
            timeControl: 'RAPID',
            decisionTimeMs: 45_000,
            movePlayed: 'e5',
            severity: 'CRITICAL',
          },
        },
        timerMode: 'STOPWATCH',
        timerElapsedMs: 14200,
        feedback: { status: 'IDLE' },
      });

      // Practice stopwatch must be visible before the first move
      expect(screen.getByText('0:14')).toBeInTheDocument();

      // Original game decision and original duration remain strictly concealed
      expect(screen.queryByText('e5')).not.toBeInTheDocument();
      expect(screen.queryByText('(0:45)')).not.toBeInTheDocument();
    });

    it('reveals played move, duration, and multi-dimensional comparison post-attempt for long-decision', () => {
      renderPanel({
        puzzle: {
          ...basePuzzle,
          fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
          targetMove: 'e4',
          source: 'long-decision',
          timesReached: 0,
          mistakeRate: 0,
          longDecisionContext: {
            timeControl: 'RAPID',
            decisionTimeMs: 45_000,
            movePlayed: 'a3',
            severity: 'SIGNIFICANT',
          },
        },
        feedback: {
          status: 'CORRECT',
          lastMove: 'e4',
          attemptElapsedMs: 12500,
        },
      });

      expect(screen.getByText('Played:')).toBeInTheDocument();
      expect(screen.getByText('a3')).toBeInTheDocument();
      expect(screen.getByText('(0:45)')).toBeInTheDocument();
      expect(screen.getByText('Decision Improved 🚀')).toBeInTheDocument();
      expect(
        screen.getByText(/You improved upon your game decision! In the game you played a3 \(0:45\), but in practice you found the best move e4\./)
      ).toBeInTheDocument();
      expect(
        screen.getByText(/Solved in 12\.5s vs\. original game thinking time of 0:45\./)
      ).toBeInTheDocument();
    });

    it('strictly hides played move and duration in IDLE, EVALUATING, and EVALUATION_ERROR and reveals only on evaluation', () => {
      const decisionPuzzle: Puzzle = {
        ...basePuzzle,
        fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        targetMove: 'e4',
        source: 'long-decision',
        timesReached: 0,
        mistakeRate: 0,
        longDecisionContext: {
          timeControl: 'RAPID',
          decisionTimeMs: 45_000,
          movePlayed: 'a3',
          severity: 'SIGNIFICANT',
        },
      };

      // 1. IDLE: strictly hidden
      const { rerender } = renderPanel({
        puzzle: decisionPuzzle,
        feedback: { status: 'IDLE' },
      });
      expect(screen.queryByText('Played:')).not.toBeInTheDocument();
      expect(screen.queryByText('a3')).not.toBeInTheDocument();
      expect(screen.queryByText('(0:45)')).not.toBeInTheDocument();
      expect(screen.getByText('Critical game moment — find the best continuation under clock pressure.')).toBeInTheDocument();

      // 2. EVALUATING: strictly hidden
      rerender(
        <PuzzleFeedbackPanel
          puzzle={decisionPuzzle}
          feedback={{ status: 'EVALUATING', lastMove: 'e4' }}
          onNextPuzzle={vi.fn()}
          puzzleColorFilter="BOTH"
          onColorFilterChange={vi.fn()}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={vi.fn()}
          minMistakeCount={1}
          onMinMistakeCountChange={vi.fn()}
          onApplySettings={vi.fn()}
        />
      );
      expect(screen.getByText('Evaluating move...')).toBeInTheDocument();
      expect(screen.queryByText('Played:')).not.toBeInTheDocument();
      expect(screen.queryByText('a3')).not.toBeInTheDocument();
      expect(screen.queryByText('(0:45)')).not.toBeInTheDocument();

      // 3. EVALUATION_ERROR: strictly hidden during retryable failure
      rerender(
        <PuzzleFeedbackPanel
          puzzle={decisionPuzzle}
          feedback={{
            status: 'EVALUATION_ERROR',
            lastMove: 'e4',
            errorMessage: 'Network timeout',
            onRetry: vi.fn(),
          }}
          onNextPuzzle={vi.fn()}
          puzzleColorFilter="BOTH"
          onColorFilterChange={vi.fn()}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={vi.fn()}
          minMistakeCount={1}
          onMinMistakeCountChange={vi.fn()}
          onApplySettings={vi.fn()}
        />
      );
      expect(screen.getByText('Move Evaluation Failed')).toBeInTheDocument();
      expect(screen.queryByText('Played:')).not.toBeInTheDocument();
      expect(screen.queryByText('a3')).not.toBeInTheDocument();
      expect(screen.queryByText('(0:45)')).not.toBeInTheDocument();

      // 4. CORRECT: revealed post-evaluation
      rerender(
        <PuzzleFeedbackPanel
          puzzle={decisionPuzzle}
          feedback={{ status: 'CORRECT', lastMove: 'e4', attemptElapsedMs: 12000 }}
          onNextPuzzle={vi.fn()}
          puzzleColorFilter="BOTH"
          onColorFilterChange={vi.fn()}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={vi.fn()}
          minMistakeCount={1}
          onMinMistakeCountChange={vi.fn()}
          onApplySettings={vi.fn()}
        />
      );
      expect(screen.getByText('Played:')).toBeInTheDocument();
      expect(screen.getByText('a3')).toBeInTheDocument();
      expect(screen.getByText('(0:45)')).toBeInTheDocument();

      // 5. INCORRECT: revealed post-evaluation
      rerender(
        <PuzzleFeedbackPanel
          puzzle={decisionPuzzle}
          feedback={{ status: 'INCORRECT', lastMove: 'h3', attemptElapsedMs: 8000 }}
          onNextPuzzle={vi.fn()}
          puzzleColorFilter="BOTH"
          onColorFilterChange={vi.fn()}
          showPuzzleSettings={false}
          onTogglePuzzleSettings={vi.fn()}
          minMistakeCount={1}
          onMinMistakeCountChange={vi.fn()}
          onApplySettings={vi.fn()}
        />
      );
      expect(screen.getByText('Played:')).toBeInTheDocument();
      expect(screen.getByText('a3')).toBeInTheDocument();
      expect(screen.getByText('(0:45)')).toBeInTheDocument();
    });

    it('renders unified puzzle prompt and header Exit button for supplied source without bypass card', () => {
      const onExitExploration = vi.fn();

      renderPanel({
        puzzle: {
          ...basePuzzle,
          source: 'supplied',
          playerColor: 'BLACK',
          timesReached: 0,
          mistakeRate: 0,
        },
        feedback: { status: 'IDLE' },
        isExplorationActive: false,
        onExitExploration,
      });

      expect(screen.getByText("Find Black's best move in this position.")).toBeInTheDocument();
      // Ensure bypass card "Continue Exploration" is NOT rendered in IDLE
      expect(screen.queryByRole('button', { name: /Continue Exploration/i })).not.toBeInTheDocument();

      // Exit button is available in header
      const exitBtn = screen.getByRole('button', { name: /Exit/i });
      expect(exitBtn).toBeInTheDocument();
      fireEvent.click(exitBtn);
      expect(onExitExploration).toHaveBeenCalled();
    });

    it('renders evaluation state and error state with retry action', () => {
      const onRetry = vi.fn();
      const { rerender } = renderPanel({
        feedback: { status: 'EVALUATING', lastMove: 'e4' },
      });

      expect(screen.getByText('Evaluating move...')).toBeInTheDocument();

      rerender(
        <PuzzleFeedbackPanel
          {...{
            puzzle: basePuzzle,
            feedback: {
              status: 'EVALUATION_ERROR',
              lastMove: 'e4',
              errorMessage: 'Network timeout',
              onRetry,
            },
            onNextPuzzle: vi.fn(),
            puzzleColorFilter: 'BOTH',
            onColorFilterChange: vi.fn(),
            showPuzzleSettings: true,
            onTogglePuzzleSettings: vi.fn(),
            minMistakeCount: 1,
            onMinMistakeCountChange: vi.fn(),
            onApplySettings: vi.fn(),
          }}
        />
      );

      expect(screen.getByText('Move Evaluation Failed')).toBeInTheDocument();
      expect(screen.getByText('Network timeout')).toBeInTheDocument();
      const retryBtn = screen.getByRole('button', { name: /Retry evaluation/i });
      expect(retryBtn).toBeInTheDocument();
      fireEvent.click(retryBtn);
      expect(onRetry).toHaveBeenCalled();
    });

    it('renders and toggles Puzzle Settings across all sources', () => {
      const onToggle = vi.fn();
      const sources: Array<Puzzle['source']> = ['weakness', 'long-decision', 'similar', 'supplied'];

      for (const source of sources) {
        const { unmount } = renderPanel({
          puzzle: { ...basePuzzle, source },
          showPuzzleSettings: true,
          onTogglePuzzleSettings: onToggle,
        });

        const settingsButton = screen.getByRole('button', { name: /Puzzle Settings/i });
        expect(settingsButton).toBeInTheDocument();
        expect(screen.getByText('Color')).toBeInTheDocument();
        fireEvent.click(settingsButton);
        expect(onToggle).toHaveBeenCalled();
        unmount();
      }
    });
  });
});
