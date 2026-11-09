import type { Page, Locator } from '@playwright/test';
import type { AbstractTransitionRule, StateModel, StateOracle } from './stateModel';

export type ImportAbstractState =
  | 'IDLE'
  | 'POLLING_IMPORT'
  | 'POLLING_ANALYSIS'
  | 'IMPORT_COMPLETED'
  | 'JOB_FAILED'
  | 'CONFLICT_409';

export type ImportAction =
  | 'SUBMIT_IMPORT'
  | 'SUBMIT_IMPORT_CONFLICT'
  | 'RETRY_IMPORT'
  | 'RESET_IMPORT'
  | 'POLL_ANALYSIS_PROGRESS'
  | 'POLL_COMPLETED'
  | 'POLL_FAILURE';

export const IMPORT_ABSTRACT_STATES: ImportAbstractState[] = [
  'IDLE',
  'POLLING_IMPORT',
  'POLLING_ANALYSIS',
  'IMPORT_COMPLETED',
  'JOB_FAILED',
  'CONFLICT_409',
];

export const IMPORT_ACTIONS: ImportAction[] = [
  'SUBMIT_IMPORT',
  'SUBMIT_IMPORT_CONFLICT',
  'RETRY_IMPORT',
  'RESET_IMPORT',
  'POLL_ANALYSIS_PROGRESS',
  'POLL_COMPLETED',
  'POLL_FAILURE',
];

export interface ImportModelContext {
  jobId: string | null;
  status: 'IDLE' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CONFLICT' | null;
  analysisStatus: 'NOT_STARTED' | 'ANALYZING' | 'COMPLETED' | 'FAILED' | null;
  hasConflict: boolean;
}

export function createInitialImportContext(): ImportModelContext {
  return {
    jobId: null,
    status: 'IDLE',
    analysisStatus: 'NOT_STARTED',
    hasConflict: false,
  };
}

export type ImportTransitionRule = AbstractTransitionRule<
  ImportAbstractState,
  ImportAction,
  ImportModelContext
>;

export const IMPORT_TRANSITIONS: ImportTransitionRule[] = [
  {
    from: 'IDLE',
    action: 'SUBMIT_IMPORT',
    to: 'POLLING_IMPORT',
    guard: (ctx) => !ctx.hasConflict,
    effect: (ctx) => ({ ...ctx, jobId: 'mock-job-id', status: 'PROCESSING' }),
  },
  {
    from: 'IDLE',
    action: 'SUBMIT_IMPORT_CONFLICT',
    to: 'CONFLICT_409',
    effect: (ctx) => ({ ...ctx, hasConflict: true, status: 'CONFLICT' }),
  },
  {
    from: 'CONFLICT_409',
    action: 'RETRY_IMPORT',
    to: 'POLLING_IMPORT',
    guard: (ctx) => ctx.hasConflict,
    effect: (ctx) => ({ ...ctx, hasConflict: false, jobId: 'mock-job-retry', status: 'PROCESSING' }),
  },
  {
    from: 'POLLING_IMPORT',
    action: 'POLL_ANALYSIS_PROGRESS',
    to: 'POLLING_ANALYSIS',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'COMPLETED', analysisStatus: 'ANALYZING' }),
  },
  {
    from: 'POLLING_IMPORT',
    action: 'POLL_COMPLETED',
    to: 'IMPORT_COMPLETED',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'COMPLETED', analysisStatus: 'COMPLETED' }),
  },
  {
    from: 'POLLING_IMPORT',
    action: 'POLL_FAILURE',
    to: 'JOB_FAILED',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'FAILED' }),
  },
  {
    from: 'POLLING_ANALYSIS',
    action: 'POLL_COMPLETED',
    to: 'IMPORT_COMPLETED',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'COMPLETED', analysisStatus: 'COMPLETED' }),
  },
  {
    from: 'POLLING_ANALYSIS',
    action: 'POLL_FAILURE',
    to: 'JOB_FAILED',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, analysisStatus: 'FAILED' }),
  },
  {
    from: 'IMPORT_COMPLETED',
    action: 'RESET_IMPORT',
    to: 'IDLE',
    effect: () => createInitialImportContext(),
  },
  {
    from: 'JOB_FAILED',
    action: 'RESET_IMPORT',
    to: 'IDLE',
    effect: () => createInitialImportContext(),
  },
];

export function isValidImportTransition(
  from: ImportAbstractState,
  action: ImportAction,
  context: ImportModelContext = createInitialImportContext()
): boolean {
  return IMPORT_TRANSITIONS.some((rule) => {
    if (rule.from !== from || rule.action !== action) return false;
    return rule.guard ? rule.guard(context) : true;
  });
}

export function getNextImportState(
  from: ImportAbstractState,
  action: ImportAction,
  context: ImportModelContext = createInitialImportContext()
): { state: ImportAbstractState; context: ImportModelContext } | null {
  const matchingRule = IMPORT_TRANSITIONS.find((rule) => {
    if (rule.from !== from || rule.action !== action) return false;
    return rule.guard ? rule.guard(context) : true;
  });

  if (!matchingRule) return null;
  const nextContext = matchingRule.effect ? matchingRule.effect(context) : { ...context };
  return { state: matchingRule.to, context: nextContext };
}

type ExpectFn = (locator: Locator) => { toBeVisible: (options?: { timeout?: number }) => Promise<void> };
type PageLike = Page | {
  getByRole: (role: string, options?: { name?: string | RegExp }) => Locator | unknown;
  getByText: (text: string | RegExp) => Locator | unknown;
  locator: (selector: string) => Locator | unknown;
  getByLabel: (label: string | RegExp) => Locator | unknown;
};

export interface ImportStateOracleDefinition {
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

export const IMPORT_STATE_ORACLES: Record<ImportAbstractState, ImportStateOracleDefinition> = {
  IDLE: {
    description: 'Import view is ready for input; username input and submit button are visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByLabel(/Chess\.com Username/i), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.getByRole('button', { name: /Start Import & Engine Analysis/i }), contextOrExpect, maybeExpectFn);
    },
  },
  POLLING_IMPORT: {
    description: 'Import job is active and polling; status indicates active import job.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByRole('button', { name: /Import Job Active\.\.\./i }), contextOrExpect, maybeExpectFn);
    },
  },
  POLLING_ANALYSIS: {
    description: 'Import completed and Stockfish background analysis is polling.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/Stockfish 16\.1 engine analysis active in background\.\.\./i), contextOrExpect, maybeExpectFn);
    },
  },
  IMPORT_COMPLETED: {
    description: 'Import and analysis successfully finished; View Weaknesses button visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/Import Completed Successfully!/i), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.getByRole('button', { name: /View Weaknesses/i }), contextOrExpect, maybeExpectFn);
    },
  },
  JOB_FAILED: {
    description: 'Import or analysis terminated with error; failure alert is visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.locator('[role="alert"]'), contextOrExpect, maybeExpectFn);
    },
  },
  CONFLICT_409: {
    description: 'Import was rejected due to concurrent running job; conflict alert is visible.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/already in progress|Another import job is already running|already running/i), contextOrExpect, maybeExpectFn);
    },
  },
};

/**
 * Concrete Playwright action executor for Game Import.
 */
export async function executeImportAction(
  page: Page,
  action: ImportAction,
  _context: ImportModelContext
): Promise<void> {
  void _context;
  switch (action) {
    case 'SUBMIT_IMPORT': {
      const usernameInput = page.getByLabel(/Chess\.com Username/i);
      await usernameInput.fill('carlsen');
      const startBtn = page.getByRole('button', { name: /Start Import & Engine Analysis/i });
      await startBtn.click();
      break;
    }
    case 'SUBMIT_IMPORT_CONFLICT': {
      const usernameInput = page.getByLabel(/Chess\.com Username/i);
      await usernameInput.fill('carlsen');
      const startBtn = page.getByRole('button', { name: /Start Import & Engine Analysis/i });
      await startBtn.click();
      break;
    }
    case 'RETRY_IMPORT': {
      const startBtn = page.getByRole('button', { name: /Start Import & Engine Analysis/i });
      await startBtn.click();
      break;
    }
    case 'RESET_IMPORT': {
      const viewWeaknesses = page.getByRole('button', { name: /View Weaknesses/i });
      if (await viewWeaknesses.isVisible().catch(() => false)) {
        await viewWeaknesses.click();
      }
      break;
    }
    case 'POLL_ANALYSIS_PROGRESS':
    case 'POLL_COMPLETED':
    case 'POLL_FAILURE':
      // Autonomous background polling events; no direct user DOM click required.
      break;
    default: {
      const _exhaustive: never = action;
      throw new Error(`Unhandled import action: ${_exhaustive}`);
    }
  }
}

/**
 * StateModel implementation for Game Import.
 */
export const ImportModel: StateModel<ImportAbstractState, ImportAction, ImportModelContext> = {
  domain: 'import',
  initialState: 'IDLE',
  createInitialContext: createInitialImportContext,
  transitions: IMPORT_TRANSITIONS,
  oracles: IMPORT_STATE_ORACLES as Record<ImportAbstractState, StateOracle<ImportModelContext>>,
  executeAction: executeImportAction,
  contextSignature: (ctx) => `${ctx.status}:${ctx.analysisStatus}:${ctx.hasConflict}`,
  ensureInitialState: async (page: Page) => {
    await page.goto('/#import');
  },
};
