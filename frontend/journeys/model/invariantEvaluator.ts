import type { PracticeState, PracticeAction, PracticeContext } from './modelFsm';

export interface ObservedPracticeState {
  activePuzzle: { id: string; targetMove?: string; source?: string } | null;
  boardFen: string;
  currentView: 'PRACTICE' | 'WEAKNESSES' | 'OTHER';
  feedbackPanel: {
    status: string;
    isInteractive: boolean;
    lastMove?: string;
  };
}

export class InvariantEvaluator {
  /**
   * Asserts safety invariants between expected abstract state and observed SUT state.
   */
  assertInvariants(
    expected: PracticeState,
    observed: ObservedPracticeState,
    action?: PracticeAction
  ): void {
    if (action === 'SOLVE' || expected === 'SOLVED') {
      if (observed.feedbackPanel.status !== 'SOLVED' && observed.feedbackPanel.status !== 'CORRECT') {
        throw new Error(
          `Invariant violation: expected SOLVED state, observed status: ${observed.feedbackPanel.status}`
        );
      }
    }
  }

  /**
   * UOBS-13 Exploration/Modal Exit Invariant Evaluation:
   * When exiting line exploration, finishing Challenge Mode, or closing an exploration modal:
   * 1. observedState.activePuzzle.id === expectedState.activePuzzle.id (active puzzle retained)
   * 2. observedState.boardFen === expectedState.boardFen (restores entry ply)
   * 3. observedState.currentView === 'PRACTICE' (remains in practice solving context)
   * 4. observedState.feedbackPanel displays interactive puzzle solving prompt
   */
  assertExplorationExitInvariant(
    expected: PracticeContext,
    observed: ObservedPracticeState
  ): void {
    // 1. Puzzle context retention
    if (!observed.activePuzzle || observed.activePuzzle.id !== expected.puzzleId) {
      throw new Error(
        `UOBS-13 violation: active puzzle context was lost upon exploration exit. Expected puzzle ${expected.puzzleId}, got ${observed.activePuzzle?.id ?? 'null'}`
      );
    }

    // 2. Board position restoration
    if (observed.boardFen !== expected.entryFen) {
      throw new Error(
        `UOBS-13 violation: board position did not restore entry ply upon exploration exit. Expected ${expected.entryFen}, got ${observed.boardFen}`
      );
    }

    // 3. Current view retention
    if (observed.currentView !== 'PRACTICE') {
      throw new Error(
        `UOBS-13 violation: view navigated away from PRACTICE to ${observed.currentView} upon exploration exit.`
      );
    }

    // 4. Feedback panel interactivity
    if (!observed.feedbackPanel.isInteractive) {
      throw new Error(
        `UOBS-13 violation: feedback panel is not interactive upon exploration exit.`
      );
    }
  }
}
