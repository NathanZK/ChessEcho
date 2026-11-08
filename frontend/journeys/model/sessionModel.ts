import type { Page, Locator } from '@playwright/test';
import type { AbstractTransitionRule } from './bfsExplorer';

export type SessionAbstractState =
  | 'GUEST'
  | 'AUTHENTICATING'
  | 'AUTHENTICATED'
  | 'AUTH_ERROR'
  | 'PROTECTED_ERROR';

export type SessionAction =
  | 'START_AUTH'
  | 'AUTH_SUCCESS'
  | 'AUTH_FAILURE'
  | 'DISMISS_ERROR'
  | 'TRIGGER_PROTECTED_FAIL'
  | 'LOGOUT';

export const SESSION_ABSTRACT_STATES: SessionAbstractState[] = [
  'GUEST',
  'AUTHENTICATING',
  'AUTHENTICATED',
  'AUTH_ERROR',
  'PROTECTED_ERROR',
];

export const SESSION_ACTIONS: SessionAction[] = [
  'START_AUTH',
  'AUTH_SUCCESS',
  'AUTH_FAILURE',
  'DISMISS_ERROR',
  'TRIGGER_PROTECTED_FAIL',
  'LOGOUT',
];

export interface SessionModelContext {
  authenticated: boolean;
  hasError: boolean;
}

export function createInitialSessionContext(): SessionModelContext {
  return {
    authenticated: false,
    hasError: false,
  };
}

export type SessionTransitionRule = AbstractTransitionRule<
  SessionAbstractState,
  SessionAction,
  SessionModelContext
>;

export const SESSION_TRANSITIONS: SessionTransitionRule[] = [
  {
    from: 'GUEST',
    action: 'START_AUTH',
    to: 'AUTHENTICATING',
    guard: (ctx) => !ctx.authenticated,
  },
  {
    from: 'AUTHENTICATING',
    action: 'AUTH_SUCCESS',
    to: 'AUTHENTICATED',
    effect: (ctx) => ({ ...ctx, authenticated: true, hasError: false }),
  },
  {
    from: 'AUTHENTICATING',
    action: 'AUTH_FAILURE',
    to: 'AUTH_ERROR',
    effect: (ctx) => ({ ...ctx, hasError: true }),
  },
  {
    from: 'AUTH_ERROR',
    action: 'DISMISS_ERROR',
    to: 'GUEST',
    effect: (ctx) => ({ ...ctx, hasError: false }),
  },
  {
    from: 'AUTH_ERROR',
    action: 'START_AUTH',
    to: 'AUTHENTICATING',
    effect: (ctx) => ({ ...ctx, hasError: false }),
  },
  {
    from: 'AUTHENTICATED',
    action: 'LOGOUT',
    to: 'GUEST',
    guard: (ctx) => ctx.authenticated,
    effect: (ctx) => ({ ...ctx, authenticated: false }),
  },
  {
    from: 'GUEST',
    action: 'TRIGGER_PROTECTED_FAIL',
    to: 'PROTECTED_ERROR',
    guard: (ctx) => !ctx.authenticated,
    effect: (ctx) => ({ ...ctx, hasError: true }),
  },
  {
    from: 'PROTECTED_ERROR',
    action: 'DISMISS_ERROR',
    to: 'GUEST',
    effect: (ctx) => ({ ...ctx, hasError: false }),
  },
];

export function isValidSessionTransition(
  from: SessionAbstractState,
  action: SessionAction,
  context: SessionModelContext = createInitialSessionContext()
): boolean {
  return SESSION_TRANSITIONS.some((rule) => {
    if (rule.from !== from || rule.action !== action) return false;
    return rule.guard ? rule.guard(context) : true;
  });
}

export function getNextSessionState(
  from: SessionAbstractState,
  action: SessionAction,
  context: SessionModelContext = createInitialSessionContext()
): { state: SessionAbstractState; context: SessionModelContext } | null {
  const matchingRule = SESSION_TRANSITIONS.find((rule) => {
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

export interface SessionStateOracleDefinition {
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

export const SESSION_STATE_ORACLES: Record<SessionAbstractState, SessionStateOracleDefinition> = {
  GUEST: {
    description: 'Header reflects unauthenticated guest state with Sign In and Register links.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByRole('link', { name: /Sign In/i }), expectFn);
      await verifyVisible(page.getByRole('link', { name: /Register/i }), expectFn);
    },
  },
  AUTHENTICATING: {
    description: 'Authentication form is displayed and submission is available or active.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByRole('button', { name: /Sign in|Create account/i }), expectFn);
    },
  },
  AUTHENTICATED: {
    description: 'User is authenticated; header displays user status and Sign out button.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.getByText(/Signed in to ChessProbe/i), expectFn);
      await verifyVisible(page.getByRole('button', { name: /Sign out/i }), expectFn);
    },
  },
  AUTH_ERROR: {
    description: 'Authentication failure alert is surfaced to the user.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.locator('p[role="alert"]'), expectFn);
    },
  },
  PROTECTED_ERROR: {
    description: 'Protected action failure alert or banner is surfaced without uncaught crash.',
    assert: async (page: PageLike, expectFn?: ExpectFn) => {
      await verifyVisible(page.locator('[role="alert"]'), expectFn);
    },
  },
};
