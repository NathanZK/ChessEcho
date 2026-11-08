import type { Page, Locator } from '@playwright/test';
import type { AbstractTransitionRule } from './bfsExplorer';

export type ImportAbstractState =
  | 'IDLE'
  | 'SUBMITTING'
  | 'POLLING_IMPORT'
  | 'POLLING_ANALYSIS'
  | 'IMPORT_COMPLETED'
  | 'JOB_FAILED'
  | 'CONFLICT_409';

export type ImportAction =
  | 'SUBMIT_IMPORT'
  | 'POLL_PROCESSING'
  | 'POLL_ANALYZING'
  | 'POLL_COMPLETE'
  | 'POLL_FAIL'
  | 'RECEIVE_CONFLICT'
  | 'RETRY_CONFLICT'
  | 'RESET_IMPORT';

export const IMPORT_ABSTRACT_STATES: ImportAbstractState[] = [
  'IDLE',
  'SUBMITTING',
  'POLLING_IMPORT',
  'POLLING_ANALYSIS',
  'IMPORT_COMPLETED',
  'JOB_FAILED',
  'CONFLICT_409',
];

export const IMPORT_ACTIONS: ImportAction[] = [
  'SUBMIT_IMPORT',
  'POLL_PROCESSING',
  'POLL_ANALYZING',
  'POLL_COMPLETE',
  'POLL_FAIL',
  'RECEIVE_CONFLICT',
  'RETRY_CONFLICT',
  'RESET_IMPORT',
];

export interface ImportModelContext {
  jobId: string | null;
  status: string | null;
  analysisStatus: string | null;
  hasConflict: boolean;
}

export function createInitialImportContext(): ImportModelContext {
  return {
    jobId: null,
    status: null,
    analysisStatus: null,
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
    to: 'SUBMITTING',
    guard: (ctx) => !ctx.jobId && !ctx.hasConflict,
    effect: (ctx) => ({ ...ctx, jobId: 'mock-job-id', status: 'QUEUED' }),
  },
  {
    from: 'SUBMITTING',
    action: 'RECEIVE_CONFLICT',
    to: 'CONFLICT_409',
    effect: (ctx) => ({ ...ctx, hasConflict: true }),
  },
  {
    from: 'CONFLICT_409',
    action: 'RETRY_CONFLICT',
    to: 'SUBMITTING',
    guard: (ctx) => ctx.hasConflict,
    effect: (ctx) => ({ ...ctx, hasConflict: false, jobId: 'mock-job-retry', status: 'QUEUED' }),
  },
  {
    from: 'SUBMITTING',
    action: 'POLL_PROCESSING',
    to: 'POLLING_IMPORT',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'PROCESSING' }),
  },
  {
    from: 'POLLING_IMPORT',
    action: 'POLL_ANALYZING',
    to: 'POLLING_ANALYSIS',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'COMPLETED', analysisStatus: 'ANALYZING' }),
  },
  {
    from: 'POLLING_IMPORT',
    action: 'POLL_COMPLETE',
    to: 'IMPORT_COMPLETED',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'COMPLETED', analysisStatus: 'COMPLETED' }),
  },
  {
    from: 'POLLING_ANALYSIS',
    action: 'POLL_COMPLETE',
    to: 'IMPORT_COMPLETED',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'COMPLETED', analysisStatus: 'COMPLETED' }),
  },
  {
    from: 'POLLING_IMPORT',
    action: 'POLL_FAIL',
    to: 'JOB_FAILED',
    guard: (ctx) => Boolean(ctx.jobId),
    effect: (ctx) => ({ ...ctx, status: 'FAILED' }),
  },
  {
    from: 'POLLING_ANALYSIS',
    action: 'POLL_FAIL',
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

export const IMPORT_STATE_ORACLES: Record<ImportAbstractState, ImportStateOracleDefinition> = {
  IDLE: {
    description: 'Import view is ready for input; username input and submit button are visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByLabel(/Chess\.com Username/i), expectFn);
      await verifyVisible(page.getByRole('button', { name: /Start Import & Engine Analysis/i }), expectFn);
    },
  },
  SUBMITTING: {
    description: 'Submission network request in flight; button indicates active request.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByRole('button', { name: /Import Job Active\.\.\.|Start Import/i }), expectFn);
    },
  },
  POLLING_IMPORT: {
    description: 'Import job is active and polling; status indicates active import job.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByRole('button', { name: /Import Job Active\.\.\./i }), expectFn);
    },
  },
  POLLING_ANALYSIS: {
    description: 'Import completed and Stockfish background analysis is polling.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/Stockfish 16\.1 engine analysis active in background\.\.\./i), expectFn);
    },
  },
  IMPORT_COMPLETED: {
    description: 'Import and analysis successfully finished; View Weaknesses button visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/Import Completed Successfully!/i), expectFn);
      await verifyVisible(page.getByRole('button', { name: /View Weaknesses/i }), expectFn);
    },
  },
  JOB_FAILED: {
    description: 'Import or analysis terminated with error; failure alert is visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.locator('[role="alert"]'), expectFn);
    },
  },
  CONFLICT_409: {
    description: 'Import was rejected due to concurrent running job; conflict alert is visible.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/already in progress|Another import job is already running|already running/i), expectFn);
    },
  },
};
