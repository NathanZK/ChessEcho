export type PracticeAbstractState =
  | 'DISCOVERY'
  | 'PRACTICE_IDLE'
  | 'EVALUATING'
  | 'CORRECT'
  | 'INCORRECT'
  | 'HISTORICAL_MISTAKE'
  | 'EVALUATION_ERROR'
  | 'EXPLORING';

export type PracticeAction =
  | 'SELECT_PUZZLE'
  | 'SUBMIT_OPTIMAL'
  | 'SUBMIT_MISTAKE'
  | 'SUBMIT_BLUNDER'
  | 'RETRY'
  | 'RESET'
  | 'CONTINUE_EXPLORATION'
  | 'EXIT_EXPLORATION'
  | 'BACK_TO_PUZZLES';

export const PRACTICE_ABSTRACT_STATES: PracticeAbstractState[] = [
  'DISCOVERY',
  'PRACTICE_IDLE',
  'EVALUATING',
  'CORRECT',
  'INCORRECT',
  'HISTORICAL_MISTAKE',
  'EVALUATION_ERROR',
  'EXPLORING',
];

export const PRACTICE_ACTIONS: PracticeAction[] = [
  'SELECT_PUZZLE',
  'SUBMIT_OPTIMAL',
  'SUBMIT_MISTAKE',
  'SUBMIT_BLUNDER',
  'RETRY',
  'RESET',
  'CONTINUE_EXPLORATION',
  'EXIT_EXPLORATION',
  'BACK_TO_PUZZLES',
];

export interface PracticeModelContext {
  puzzleLoaded: boolean;
  attemptCount: number;
  inExploration: boolean;
}

export function createInitialContext(): PracticeModelContext {
  return {
    puzzleLoaded: false,
    attemptCount: 0,
    inExploration: false,
  };
}

export interface TransitionRule {
  from: PracticeAbstractState;
  action: PracticeAction;
  to: PracticeAbstractState;
  guard?: (context: PracticeModelContext) => boolean;
  effect?: (context: PracticeModelContext) => PracticeModelContext;
}

export const PRACTICE_TRANSITIONS: TransitionRule[] = [
  // Discovery to Practice
  {
    from: 'DISCOVERY',
    action: 'SELECT_PUZZLE',
    to: 'PRACTICE_IDLE',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: true, attemptCount: 0, inExploration: false }),
  },
  // Submitting moves from Idle
  {
    from: 'PRACTICE_IDLE',
    action: 'SUBMIT_OPTIMAL',
    to: 'CORRECT',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => ({ ...ctx, attemptCount: ctx.attemptCount + 1 }),
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'SUBMIT_MISTAKE',
    to: 'INCORRECT',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => ({ ...ctx, attemptCount: ctx.attemptCount + 1 }),
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'SUBMIT_BLUNDER',
    to: 'HISTORICAL_MISTAKE',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => ({ ...ctx, attemptCount: ctx.attemptCount + 1 }),
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'RESET',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded,
  },
  {
    from: 'PRACTICE_IDLE',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false }),
  },
  // Recovery from Incorrect / Mistake / Error
  {
    from: 'INCORRECT',
    action: 'RETRY',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded,
  },
  {
    from: 'HISTORICAL_MISTAKE',
    action: 'RETRY',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded,
  },
  {
    from: 'HISTORICAL_MISTAKE',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false }),
  },
  {
    from: 'EVALUATION_ERROR',
    action: 'RETRY',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.puzzleLoaded,
  },
  {
    from: 'INCORRECT',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false }),
  },
  // Transition to Line Exploration
  {
    from: 'CORRECT',
    action: 'CONTINUE_EXPLORATION',
    to: 'EXPLORING',
    guard: (ctx) => ctx.puzzleLoaded,
    effect: (ctx) => ({ ...ctx, inExploration: true }),
  },
  {
    from: 'CORRECT',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false }),
  },
  // Line Exploration back to Idle or Discovery
  {
    from: 'EXPLORING',
    action: 'EXIT_EXPLORATION',
    to: 'PRACTICE_IDLE',
    guard: (ctx) => ctx.inExploration,
    effect: (ctx) => ({ ...ctx, inExploration: false }),
  },
  {
    from: 'EXPLORING',
    action: 'BACK_TO_PUZZLES',
    to: 'DISCOVERY',
    effect: (ctx) => ({ ...ctx, puzzleLoaded: false, inExploration: false }),
  },
];

export function findMatchingTransition(
  from: PracticeAbstractState,
  action: PracticeAction,
  context: PracticeModelContext = createInitialContext()
): TransitionRule | null {
  for (const rule of PRACTICE_TRANSITIONS) {
    if (rule.from === from && rule.action === action) {
      if (!rule.guard || rule.guard(context)) {
        return rule;
      }
    }
  }
  return null;
}

export function isValidTransition(
  from: PracticeAbstractState,
  action: PracticeAction,
  context: PracticeModelContext = createInitialContext()
): boolean {
  return findMatchingTransition(from, action, context) !== null;
}

export function getNextState(
  from: PracticeAbstractState,
  action: PracticeAction,
  context: PracticeModelContext = createInitialContext()
): { state: PracticeAbstractState; context: PracticeModelContext } | null {
  const rule = findMatchingTransition(from, action, context);
  if (!rule) return null;
  const nextContext = rule.effect ? rule.effect(context) : context;
  return {
    state: rule.to,
    context: nextContext,
  };
}

import type { Page, Expect, Locator } from '@playwright/test';

export type ExpectFn = Expect;

export type PageLike = Pick<Page, 'getByRole' | 'getByText' | 'locator'>;

export interface StateOracleDefinition {
  description: string;
  assert: (page: PageLike, expectFn?: ExpectFn) => Promise<void>;
}

async function verifyVisible(locator: unknown, expectFn?: ExpectFn): Promise<void> {
  if (expectFn) {
    await expectFn(locator as Locator).toBeVisible();
  } else if (locator && typeof (locator as { waitFor?: (options?: { state?: string; timeout?: number }) => Promise<void> }).waitFor === 'function') {
    await (locator as { waitFor: (options?: { state?: string; timeout?: number }) => Promise<void> }).waitFor({ state: 'visible', timeout: 5000 });
  } else if (locator && typeof (locator as { toBeVisible?: () => Promise<void> }).toBeVisible === 'function') {
    await (locator as { toBeVisible: () => Promise<void> }).toBeVisible();
  }
}

export const STATE_ORACLES: Record<PracticeAbstractState, StateOracleDefinition> = {
  DISCOVERY: {
    description: 'Catalog view is active; Start Practice button and catalog description are visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByRole('button', { name: /Start Practice/i }), expectFn);
      await verifyVisible(page.getByText(/Strengthen your play by solving recurring mistake positions/i), expectFn);
    },
  },
  PRACTICE_IDLE: {
    description: 'Interactive board is ready; square d2 is visible and Back to Puzzles is interactive.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.locator('[data-square="d2"]'), expectFn);
      await verifyVisible(page.getByRole('button', { name: /Back to Puzzles/i }), expectFn);
    },
  },
  EVALUATING: {
    description: 'Move evaluation is in-flight; evaluating indicator is active.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/Evaluating move/i), expectFn);
    },
  },
  CORRECT: {
    description: 'Puzzle solved optimally; Continue Exploration button visible and solved count incremented.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByRole('button', { name: /Continue Exploration →/i }), expectFn);
      await verifyVisible(page.getByText(/Solved: [1-9]\d*/i), expectFn);
    },
  },
  INCORRECT: {
    description: 'Inaccurate move played; rollback to baseline FEN and Not the Recommended Move banner visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByRole('heading', { name: /Not the Recommended Move/i }), expectFn);
      await verifyVisible(page.locator('[data-square="d2"]'), expectFn);
    },
  },
  HISTORICAL_MISTAKE: {
    description: 'Historical mistake repeated; mistake banner and hint/retry action visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/Recurring Weakness Detected!|Matches your previous blunder|blunder/i), expectFn);
    },
  },
  EVALUATION_ERROR: {
    description: 'Evaluation failed or timed out; error banner visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/Evaluation service returned an empty response|Error/i), expectFn);
    },
  },
  EXPLORING: {
    description: 'Line exploration mode active; exploration controls and Exit Exploration visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/Line Exploration/i), expectFn);
      await verifyVisible(page.getByRole('button', { name: /^Exit$|Exit Exploration/i }), expectFn);
    },
  },
};
