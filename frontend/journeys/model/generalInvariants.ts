import type { PracticeState, PracticeAction, PracticeContext } from './modelFsm';
import type { ObservedPracticeState } from './invariantEvaluator';

export interface InvariantViolation {
  invariantName: string;
  message: string;
  action?: PracticeAction;
  expectedState?: PracticeState;
  observedState: ObservedPracticeState;
}

/**
 * General behavioral invariants for an interactive chess practice application.
 * Completely decoupled from historical defect identities, specific bug IDs,
 * or hardcoded failure expectations.
 */
export class GeneralPracticeInvariants {
  /**
   * Asserts that all general practice invariants hold after any action.
   */
  assertAll(
    expectedState: PracticeState,
    expectedContext: PracticeContext,
    observed: ObservedPracticeState,
    action?: PracticeAction
  ): void {
    this.assertViewConsistency(observed, action);
    this.assertActivePuzzleContinuity(expectedContext, observed, action);
    this.assertValidBoardFen(observed, action);
    this.assertFeedbackInteractivity(observed, action);

    if (action === 'RESET') {
      this.assertReversibleReset(expectedContext, observed);
    }

    if (action === 'EXIT_EXPLORATION') {
      this.assertExplorationSymmetry(expectedContext, observed);
    }

    if (
      action === 'RESET' ||
      action === 'MAKE_MOVE_CORRECT' ||
      action === 'EXIT_EXPLORATION' ||
      expectedState === 'INITIAL'
    ) {
      this.assertNoResidualHighlight(observed, action);
    }
  }

  /**
   * G-INV-1: View Context Integrity.
   * An action within practice solving must never evict the user away from PRACTICE.
   */
  assertViewConsistency(observed: ObservedPracticeState, action?: PracticeAction): void {
    if (observed.currentView !== 'PRACTICE') {
      throw new Error(
        `[G-INV-1: ViewContextIntegrity] Expected current view to remain PRACTICE after ${action ?? 'action'}, but observed ${observed.currentView}.`
      );
    }
  }

  /**
   * G-INV-2: Active Session Continuity.
   * When an active puzzle is loaded, its identity must remain retained across solving,
   * exploration, and reset steps.
   */
  assertActivePuzzleContinuity(
    expectedContext: PracticeContext,
    observed: ObservedPracticeState,
    action?: PracticeAction
  ): void {
    if (expectedContext.puzzleId && (!observed.activePuzzle || observed.activePuzzle.id !== expectedContext.puzzleId)) {
      throw new Error(
        `[G-INV-2: ActiveSessionContinuity] Expected active puzzle ${expectedContext.puzzleId} to be retained after ${action ?? 'action'}, but observed ${observed.activePuzzle?.id ?? 'null'}.`
      );
    }
  }

  /**
   * G-INV-3: Valid Board FEN.
   * Board FEN must never become blank, null, or corrupt chess notation.
   */
  assertValidBoardFen(observed: ObservedPracticeState, action?: PracticeAction): void {
    if (!observed.boardFen || observed.boardFen.trim().length === 0) {
      throw new Error(
        `[G-INV-3: ValidBoardFen] Observed blank or missing board FEN after ${action ?? 'action'}.`
      );
    }
    const parts = observed.boardFen.trim().split(/\s+/);
    if (parts.length < 2 || parts[0].split('/').length !== 8) {
      throw new Error(
        `[G-INV-3: ValidBoardFen] Board FEN is structurally corrupt after ${action ?? 'action'}: "${observed.boardFen}".`
      );
    }
  }

  /**
   * G-INV-4: UI Interactivity.
   * In non-transient practice states, feedback and controls must remain interactive.
   */
  assertFeedbackInteractivity(observed: ObservedPracticeState, action?: PracticeAction): void {
    if (!observed.feedbackPanel.isInteractive) {
      throw new Error(
        `[G-INV-4: UIInteractivity] Practice feedback panel is locked or non-interactive after ${action ?? 'action'}.`
      );
    }
  }

  /**
   * G-INV-5: No Residual Square Highlight.
   * In idle/settled states (INITIAL, post-RESET, post-EXIT), there must be no uncleared
   * square selection highlight on the board.
   */
  assertNoResidualHighlight(observed: ObservedPracticeState, action?: PracticeAction): void {
    if (observed.hasResidualHighlight === true) {
      throw new Error(
        `[G-INV-5: NoResidualHighlight] Residual square selection highlight remained on the board after ${action ?? 'action'}.`
      );
    }
  }

  /**
   * G-INV-6: Reversible Reset.
   * A board reset must restore the puzzle's initial entry FEN and clear transient move state.
   */
  assertReversibleReset(expectedContext: PracticeContext, observed: ObservedPracticeState): void {
    if (expectedContext.entryFen && observed.boardFen !== expectedContext.entryFen) {
      throw new Error(
        `[G-INV-6: ReversibleReset] Reset did not restore initial entry FEN. Expected ${expectedContext.entryFen}, got ${observed.boardFen}.`
      );
    }
  }

  /**
   * G-INV-7: Auxiliary Exploration Symmetry.
   * Exiting exploration mode must return to the initial puzzle ply, retain the active puzzle,
   * and leave the interface interactive.
   */
  assertExplorationSymmetry(expectedContext: PracticeContext, observed: ObservedPracticeState): void {
    if (expectedContext.entryFen && observed.boardFen !== expectedContext.entryFen) {
      throw new Error(
        `[G-INV-7: ExplorationSymmetry] Exploration exit failed to restore entry ply. Expected ${expectedContext.entryFen}, got ${observed.boardFen}.`
      );
    }
  }
}
