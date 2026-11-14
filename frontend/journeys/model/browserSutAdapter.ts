import type { SutAdapter } from './sutAdapter';
import type { PracticeState, PracticeAction } from './modelFsm';
import type { ObservedPracticeState } from './invariantEvaluator';

/**
 * Browser-Level SUT Adapter Contract (Milestone 3 Playwright, Issue #790).
 * Defines the concrete methods for driving real DOM elements, capturing rendered state,
 * and asserting application-level defects (such as DOM leaks, focus, and URL changes).
 */
export interface BrowserPracticeSutAdapter
  extends SutAdapter<PracticeState, PracticeAction, ObservedPracticeState> {
  isDomLevel(): true;

  // DOM-level user interaction contracts
  clickPuzzleCard(puzzleId: string): Promise<void>;
  dragPiece(fromSquare: string, toSquare: string): Promise<void>;
  clickExplorationButton(): Promise<void>;
  clickChallengeModeButton(): Promise<void>;
  clickBlindfoldModeButton(): Promise<void>;
  clickExitExplorationButton(): Promise<void>;
  clickRetryButton(): Promise<void>;

  // Real browser URL & layout inspection
  getCurrentRouteHash(): Promise<string>;
  isElementVisible(selector: string): Promise<boolean>;
  hasSquareSelectionHighlight(square: string): Promise<boolean>;
}
