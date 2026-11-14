import { describe, it, expect } from 'vitest';
import { GeneralPracticeInvariants } from './generalInvariants';
import { GeneralBfsExplorer } from './generalBfsExplorer';
import { createInitialPracticeContext } from './modelFsm';
import type { ObservedPracticeState } from './invariantEvaluator';

describe('General Behavioral Invariants (generalInvariants.ts)', () => {
  const invariants = new GeneralPracticeInvariants();

  it('G-INV-1: detects view context eviction away from PRACTICE', () => {
    const observed: ObservedPracticeState = {
      activePuzzle: { id: 'puz-1' },
      boardFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      currentView: 'WEAKNESSES',
      feedbackPanel: { status: 'IDLE', isInteractive: true },
    };
    expect(() => invariants.assertViewConsistency(observed, 'RESET')).toThrow(
      /\[G-INV-1: ViewContextIntegrity\]/
    );
  });

  it('G-INV-2: detects unexpected active puzzle context loss (e.g. UOBS-13 style nullification)', () => {
    const ctx = createInitialPracticeContext({ puzzleId: 'puz-42', entryFen: 'fen_entry' });
    const observed: ObservedPracticeState = {
      activePuzzle: null,
      boardFen: 'fen_entry',
      currentView: 'PRACTICE',
      feedbackPanel: { status: 'IDLE', isInteractive: true },
    };
    expect(() => invariants.assertActivePuzzleContinuity(ctx, observed, 'EXIT_EXPLORATION')).toThrow(
      /\[G-INV-2: ActiveSessionContinuity\]/
    );
  });

  it('G-INV-3: detects blank or corrupt board FEN', () => {
    const observedEmpty: ObservedPracticeState = {
      activePuzzle: { id: 'puz-1' },
      boardFen: '',
      currentView: 'PRACTICE',
      feedbackPanel: { status: 'IDLE', isInteractive: true },
    };
    expect(() => invariants.assertValidBoardFen(observedEmpty, 'RESET')).toThrow(
      /\[G-INV-3: ValidBoardFen\]/
    );

    const observedCorrupt: ObservedPracticeState = {
      activePuzzle: { id: 'puz-1' },
      boardFen: 'corrupted_fen_string_without_ranks',
      currentView: 'PRACTICE',
      feedbackPanel: { status: 'IDLE', isInteractive: true },
    };
    expect(() => invariants.assertValidBoardFen(observedCorrupt, 'MAKE_MOVE_CORRECT')).toThrow(
      /\[G-INV-3: ValidBoardFen\]/
    );
  });

  it('G-INV-4: detects locked or non-interactive feedback panel', () => {
    const observed: ObservedPracticeState = {
      activePuzzle: { id: 'puz-1' },
      boardFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      currentView: 'PRACTICE',
      feedbackPanel: { status: 'RESET', isInteractive: false },
    };
    expect(() => invariants.assertFeedbackInteractivity(observed, 'EXIT_EXPLORATION')).toThrow(
      /\[G-INV-4: UIInteractivity\]/
    );
  });

  it('G-INV-5: detects residual square selection highlight (e.g. ADD-01 style persistence)', () => {
    const observed: ObservedPracticeState = {
      activePuzzle: { id: 'puz-1' },
      boardFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      currentView: 'PRACTICE',
      feedbackPanel: { status: 'IDLE', isInteractive: true },
      hasResidualHighlight: true,
    };
    expect(() => invariants.assertNoResidualHighlight(observed, 'RESET')).toThrow(
      /\[G-INV-5: NoResidualHighlight\]/
    );
  });

  it('G-INV-6: detects reset failing to restore entry ply', () => {
    const ctx = createInitialPracticeContext({ puzzleId: 'puz-1', entryFen: 'fen_start' });
    const observed: ObservedPracticeState = {
      activePuzzle: { id: 'puz-1' },
      boardFen: 'fen_moved',
      currentView: 'PRACTICE',
      feedbackPanel: { status: 'IDLE', isInteractive: true },
    };
    expect(() => invariants.assertReversibleReset(ctx, observed)).toThrow(
      /\[G-INV-6: ReversibleReset\]/
    );
  });
});

describe('GeneralBfsExplorer Sequence Generation', () => {
  it('generates multi-step BFS action sequences independently of defect register', () => {
    const explorer = new GeneralBfsExplorer();
    const sequences = explorer.generateBfsActionSequences('INITIAL', createInitialPracticeContext(), 3);

    expect(sequences.length).toBeGreaterThan(0);
    // Sequences generated strictly from FSM graph transitions:
    expect(sequences).toContainEqual(['MAKE_MOVE_CORRECT']);
    expect(sequences).toContainEqual(['MAKE_MOVE_CORRECT', 'ENTER_LINE_EXPLORATION']);
    expect(sequences).toContainEqual(['MAKE_MOVE_CORRECT', 'ENTER_LINE_EXPLORATION', 'EXIT_EXPLORATION']);
  });
});
