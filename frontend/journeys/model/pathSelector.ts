import {
  PracticeAbstractState,
  PracticeAction,
  TransitionRule,
} from './practiceModel';
import { BfsExplorationResult, GeneratedPath } from './bfsExplorer';

export interface SelectedPath {
  id: string;
  name: string;
  description: string;
  states: PracticeAbstractState[];
  actions: PracticeAction[];
  riskCategory: 'evaluation-retry' | 'pending-navigation' | 'mode-boundary-exploration';
}

export function selectHighRiskPaths(
  exploration: BfsExplorationResult,
  transitions?: TransitionRule[]
): SelectedPath[] {
  const selected: SelectedPath[] = [];

  // Validate that transitions table is present if provided
  if (transitions && transitions.length === 0) {
    return [];
  }

  // Path 1: Evaluation -> Retry -> Recovery
  // DISCOVERY -> SELECT_PUZZLE -> SUBMIT_MISTAKE -> RETRY
  const retryCandidate: GeneratedPath | undefined = exploration.paths.find(
    (p) =>
      p.actions.includes('SUBMIT_MISTAKE') &&
      p.actions.includes('RETRY') &&
      p.targetState === 'PRACTICE_IDLE'
  ) || {
    targetState: 'PRACTICE_IDLE',
    actions: ['SELECT_PUZZLE', 'SUBMIT_MISTAKE', 'RETRY'],
    states: ['DISCOVERY', 'PRACTICE_IDLE', 'INCORRECT', 'PRACTICE_IDLE'],
  };

  selected.push({
    id: 'path-01-eval-retry',
    name: 'Evaluation Mistake and Board Retry',
    description: 'User enters practice, plays an inaccurate move, verifies piece rollback to baseline FEN, and triggers retry to restore interactive state.',
    states: retryCandidate.states,
    actions: retryCandidate.actions,
    riskCategory: 'evaluation-retry',
  });

  // Path 2: Mode Boundary Crossing (Solve -> Line Exploration -> Return)
  // DISCOVERY -> SELECT_PUZZLE -> SUBMIT_OPTIMAL -> CONTINUE_EXPLORATION -> EXIT_EXPLORATION
  const exploreCandidate: GeneratedPath | undefined = exploration.paths.find(
    (p) =>
      p.actions.includes('CONTINUE_EXPLORATION') &&
      p.actions.includes('EXIT_EXPLORATION')
  ) || {
    targetState: 'PRACTICE_IDLE',
    actions: ['SELECT_PUZZLE', 'SUBMIT_OPTIMAL', 'CONTINUE_EXPLORATION', 'EXIT_EXPLORATION'],
    states: ['DISCOVERY', 'PRACTICE_IDLE', 'CORRECT', 'EXPLORING', 'PRACTICE_IDLE'],
  };

  selected.push({
    id: 'path-02-mode-boundary-exploration',
    name: 'Mode Boundary Crossing (Puzzle to Exploration and Return)',
    description: 'User solves puzzle, transitions to line exploration mode, verifies continuation controls, and exits back to interactive puzzle.',
    states: exploreCandidate.states,
    actions: exploreCandidate.actions,
    riskCategory: 'mode-boundary-exploration',
  });

  // Path 3: Concurrency / Pending Navigation (Evaluation In-Flight -> Discovery Return)
  // DISCOVERY -> SELECT_PUZZLE -> PRACTICE_IDLE (submit move, hold evaluation route, navigate back to discovery)
  selected.push({
    id: 'path-03-pending-navigation',
    name: 'Navigation During In-Flight Move Evaluation',
    description: 'User submits a move, triggers navigation back to discovery while evaluation network request is held in-flight, verifying zero state corruption or leak.',
    states: ['DISCOVERY', 'PRACTICE_IDLE', 'EVALUATING', 'DISCOVERY'],
    actions: ['SELECT_PUZZLE', 'SUBMIT_OPTIMAL', 'BACK_TO_PUZZLES'],
    riskCategory: 'pending-navigation',
  });

  return selected;
}
