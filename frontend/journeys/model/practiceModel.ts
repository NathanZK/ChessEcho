import type { StateModel, StateOracle } from './stateModel';

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
  solved?: boolean;
}

export function createInitialContext(): PracticeModelContext {
  return {
    puzzleLoaded: false,
    attemptCount: 0,
    inExploration: false,
    solved: false,
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
    effect: (ctx) => ({ ...ctx, attemptCount: ctx.attemptCount + 1, solved: true }),
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
  assert: (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => Promise<void>;
}

async function verifyVisible(locator: unknown, arg1?: unknown, arg2?: unknown): Promise<void> {
  const expectFn = typeof arg1 === 'function' ? (arg1 as ExpectFn) : (typeof arg2 === 'function' ? (arg2 as ExpectFn) : undefined);
  if (expectFn) {
    await expectFn(locator as Locator).toBeVisible({ timeout: 10_000 });
    return;
  }
  const loc = locator as {
    waitFor?: (options?: { state?: string; timeout?: number }) => Promise<void>;
    toBeVisible?: () => Promise<boolean>;
  } | null | undefined;
  if (loc && typeof loc.waitFor === 'function') {
    await loc.waitFor({ state: 'visible', timeout: 10_000 });
  } else if (loc && typeof loc.toBeVisible === 'function') {
    await loc.toBeVisible();
  }
}

export const STATE_ORACLES: Record<PracticeAbstractState, StateOracleDefinition> = {
  DISCOVERY: {
    description: 'Catalog view is active; Start Practice button and catalog description are visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByRole('button', { name: /Start Practice/i }), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.getByText(/Strengthen your play by solving recurring mistake positions/i), contextOrExpect, maybeExpectFn);
    },
  },
  PRACTICE_IDLE: {
    description: 'Interactive board is ready; square d2 is visible and Back to Puzzles is interactive.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.locator('[data-square="d2"]'), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.getByRole('button', { name: /Back to Puzzles/i }), contextOrExpect, maybeExpectFn);
    },
  },
  EVALUATING: {
    description: 'Move evaluation is in-flight; evaluating indicator is active.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/Evaluating move/i), contextOrExpect, maybeExpectFn);
    },
  },
  CORRECT: {
    description: 'Puzzle solved optimally; Continue Exploration button visible and solved count incremented.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByRole('button', { name: /Continue Exploration →/i }), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.getByText(/Solved: [1-9]\d*/i), contextOrExpect, maybeExpectFn);
    },
  },
  INCORRECT: {
    description: 'Inaccurate move played; rollback to baseline FEN and Not the Recommended Move banner visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByRole('heading', { name: /Not the Recommended Move/i }), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.locator('[data-square="d2"]'), contextOrExpect, maybeExpectFn);
    },
  },
  HISTORICAL_MISTAKE: {
    description: 'Historical mistake repeated; mistake banner and hint/retry action visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/Recurring Weakness Detected!|Matches your previous blunder|blunder/i), contextOrExpect, maybeExpectFn);
    },
  },
  EVALUATION_ERROR: {
    description: 'Evaluation failed or timed out; error banner visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/Evaluation service returned an empty response|Error/i), contextOrExpect, maybeExpectFn);
    },
  },
  EXPLORING: {
    description: 'Line exploration mode active; exploration controls and Exit Exploration visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/Line Exploration/i), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.getByRole('button', { name: /^Exit$|Exit Exploration/i }), contextOrExpect, maybeExpectFn);
    },
  },
};

/**
 * Reliably transitions the application into DISCOVERY state from an initial page load.
 * Avoids race conditions on cold loads where the practice board or catalog has not yet mounted.
 */
export async function ensureDiscoveryState(page: Page): Promise<void> {
  const startPracticeBtn = page.getByRole('button', { name: /Start Practice/i });
  const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });

  await backBtn.or(startPracticeBtn).first().waitFor({ state: 'visible', timeout: 10000 });

  if (await backBtn.isVisible()) {
    await backBtn.click();
    await startPracticeBtn.waitFor({ state: 'visible', timeout: 10000 });
  }
}

/**
 * Translates a high-level practice model action into concrete Playwright DOM interactions.
 */
export async function executeConcreteAction(page: Page, action: PracticeAction): Promise<void> {
  switch (action) {
    case 'SELECT_PUZZLE': {
      const button = page.getByRole('button', { name: /Start Practice/i });
      await button.waitFor({ state: 'visible', timeout: 5000 });
      await button.click();
      break;
    }

    case 'SUBMIT_OPTIMAL': {
      // White optimal first move: d2 -> d4
      const from = page.locator('[data-square="d2"]');
      const to = page.locator('[data-square="d4"]');
      await from.waitFor({ state: 'visible', timeout: 5000 });
      await from.click();
      await page.waitForTimeout(60);
      await to.click();
      break;
    }

    case 'SUBMIT_MISTAKE': {
      // Inaccurate move: a2 -> a3
      const from = page.locator('[data-square="a2"]');
      const to = page.locator('[data-square="a3"]');
      await from.waitFor({ state: 'visible', timeout: 5000 });
      await from.click();
      await page.waitForTimeout(60);
      await to.click();
      break;
    }

    case 'SUBMIT_BLUNDER': {
      // Severe mistake: h2 -> h4
      const from = page.locator('[data-square="h2"]');
      const to = page.locator('[data-square="h4"]');
      await from.waitFor({ state: 'visible', timeout: 5000 });
      await from.click();
      await page.waitForTimeout(60);
      await to.click();
      break;
    }

    case 'RETRY': {
      // Board is automatically rolled back to baseline FEN upon incorrect move.
      // If Reset button is available, click it to clear selection without touching board pieces.
      const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
      if (await resetButton.isVisible().catch(() => false)) {
        await resetButton.click();
      }
      await page.waitForTimeout(100);
      break;
    }

    case 'RESET': {
      // Resets board to baseline FEN
      const resetButton = page.getByRole('button', { name: /Reset Board|Reset/i });
      if (await resetButton.isVisible().catch(() => false)) {
        await resetButton.click();
      }
      await page.waitForTimeout(100);
      break;
    }

    case 'CONTINUE_EXPLORATION': {
      const continueBtn = page.getByRole('button', { name: /Continue Exploration →/i });
      await continueBtn.waitFor({ state: 'visible', timeout: 5000 });
      await continueBtn.click();
      break;
    }

    case 'EXIT_EXPLORATION': {
      const exitBtn = page.getByRole('button', { name: /^Exit$|Exit Exploration/i });
      await exitBtn.waitFor({ state: 'visible', timeout: 5000 });
      await exitBtn.click();
      break;
    }

    case 'BACK_TO_PUZZLES': {
      const backBtn = page.getByRole('button', { name: /Back to Puzzles/i });
      await backBtn.waitFor({ state: 'visible', timeout: 5000 });
      await backBtn.click();
      break;
    }

    default:
      throw new Error(`Unsupported practice action: ${action}`);
  }
}

/**
 * StateModel implementation for Practice Puzzles.
 */
export const PracticeModel: StateModel<
  PracticeAbstractState,
  PracticeAction,
  PracticeModelContext
> = {
  domain: 'practice',
  initialState: 'DISCOVERY',
  createInitialContext,
  transitions: PRACTICE_TRANSITIONS,
  oracles: STATE_ORACLES as Record<PracticeAbstractState, StateOracle<PracticeModelContext>>,
  executeAction: executeConcreteAction,
  contextSignature: (ctx) => `${ctx.puzzleLoaded}:${ctx.inExploration}:${ctx.attemptCount}:${ctx.solved}`,
  ensureInitialState: ensureDiscoveryState,
};

