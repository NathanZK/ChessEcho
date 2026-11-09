import type { Page, Locator } from '@playwright/test';
import type { AbstractTransitionRule, StateModel, StateOracle } from './stateModel';

export type SessionAbstractState =
  | 'GUEST'
  | 'AUTHENTICATED'
  | 'AUTH_ERROR';

export type SessionAction =
  | 'NAVIGATE_LOGIN'
  | 'SUBMIT_VALID_CREDENTIALS'
  | 'SUBMIT_INVALID_CREDENTIALS'
  | 'LOGOUT';

export const SESSION_ABSTRACT_STATES: SessionAbstractState[] = [
  'GUEST',
  'AUTHENTICATED',
  'AUTH_ERROR',
];

export const SESSION_ACTIONS: SessionAction[] = [
  'NAVIGATE_LOGIN',
  'SUBMIT_VALID_CREDENTIALS',
  'SUBMIT_INVALID_CREDENTIALS',
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
    action: 'NAVIGATE_LOGIN',
    to: 'GUEST',
    guard: (ctx) => !ctx.authenticated,
  },
  {
    from: 'GUEST',
    action: 'SUBMIT_VALID_CREDENTIALS',
    to: 'AUTHENTICATED',
    guard: (ctx) => !ctx.authenticated,
    effect: (ctx) => ({ ...ctx, authenticated: true, hasError: false }),
  },
  {
    from: 'GUEST',
    action: 'SUBMIT_INVALID_CREDENTIALS',
    to: 'AUTH_ERROR',
    guard: (ctx) => !ctx.authenticated,
    effect: (ctx) => ({ ...ctx, hasError: true }),
  },
  {
    from: 'AUTH_ERROR',
    action: 'SUBMIT_VALID_CREDENTIALS',
    to: 'AUTHENTICATED',
    effect: (ctx) => ({ ...ctx, authenticated: true, hasError: false }),
  },
  {
    from: 'AUTH_ERROR',
    action: 'SUBMIT_INVALID_CREDENTIALS',
    to: 'AUTH_ERROR',
    effect: (ctx) => ({ ...ctx, hasError: true }),
  },
  {
    from: 'AUTHENTICATED',
    action: 'LOGOUT',
    to: 'GUEST',
    guard: (ctx) => ctx.authenticated,
    effect: (ctx) => ({ ...ctx, authenticated: false }),
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
  url?: () => string;
};

export interface SessionStateOracleDefinition {
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

export const SESSION_STATE_ORACLES: Record<SessionAbstractState, SessionStateOracleDefinition> = {
  GUEST: {
    description: 'Header reflects unauthenticated guest state with Sign In and Register links, or login page reflects form input readiness.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      const currentUrl = typeof page.url === 'function' ? page.url() : '';
      if (currentUrl.includes('/login')) {
        await verifyVisible(page.getByLabel(/Email/i), contextOrExpect, maybeExpectFn);
        await verifyVisible(page.getByLabel(/^Password/i), contextOrExpect, maybeExpectFn);
        await verifyVisible(page.getByRole('button', { name: /Sign in/i }), contextOrExpect, maybeExpectFn);
      } else {
        await verifyVisible(page.getByRole('link', { name: /Sign In/i }), contextOrExpect, maybeExpectFn);
        await verifyVisible(page.getByRole('link', { name: /Register/i }), contextOrExpect, maybeExpectFn);
      }
    },
  },
  AUTHENTICATED: {
    description: 'User is authenticated; header displays user status and Sign out button.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.getByText(/Signed in to ChessProbe/i), contextOrExpect, maybeExpectFn);
      await verifyVisible(page.getByRole('button', { name: /Sign out/i }), contextOrExpect, maybeExpectFn);
    },
  },
  AUTH_ERROR: {
    description: 'Authentication failure alert is surfaced to the user.',
    assert: async (page: PageLike, contextOrExpect?: unknown, maybeExpectFn?: unknown) => {
      await verifyVisible(page.locator('p[role="alert"]'), contextOrExpect, maybeExpectFn);
    },
  },
};

/**
 * Concrete Playwright action executor for Session Authentication.
 */
export async function executeSessionAction(
  page: Page,
  action: SessionAction,
  _context: SessionModelContext
): Promise<void> {
  void _context;
  switch (action) {
    case 'NAVIGATE_LOGIN': {
      await page.goto('/login');
      break;
    }
    case 'SUBMIT_VALID_CREDENTIALS': {
      if (!page.url().includes('/login')) {
        await page.goto('/login');
      }
      await page.getByLabel(/Email/i).fill('player@example.com');
      await page.getByLabel(/^Password/i).fill('password123');
      await page.getByRole('button', { name: /Sign in/i }).click();
      await page.waitForURL('/');
      break;
    }
    case 'SUBMIT_INVALID_CREDENTIALS': {
      if (!page.url().includes('/login')) {
        await page.goto('/login');
      }
      await page.getByLabel(/Email/i).fill('invalid@example.com');
      await page.getByLabel(/^Password/i).fill('wrongpass');
      await page.getByRole('button', { name: /Sign in/i }).click();
      break;
    }
    case 'LOGOUT': {
      const signOutButton = page.getByRole('button', { name: /Sign out/i });
      await signOutButton.click();
      break;
    }
    default: {
      const _exhaustive: never = action;
      throw new Error(`Unhandled session action: ${_exhaustive}`);
    }
  }
}

/**
 * StateModel implementation for Session Authentication.
 */
export const SessionModel: StateModel<SessionAbstractState, SessionAction, SessionModelContext> = {
  domain: 'session',
  initialState: 'GUEST',
  createInitialContext: createInitialSessionContext,
  transitions: SESSION_TRANSITIONS,
  oracles: SESSION_STATE_ORACLES as Record<SessionAbstractState, StateOracle<SessionModelContext>>,
  executeAction: executeSessionAction,
  contextSignature: (ctx) => `${ctx.authenticated}:${ctx.hasError}`,
  ensureInitialState: async (page: Page) => {
    await page.goto('/');
  },
};
