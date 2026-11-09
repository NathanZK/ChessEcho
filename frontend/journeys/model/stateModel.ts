import type { Page } from '@playwright/test';
import type { AbstractTransitionRule } from './bfsExplorer';

export type { AbstractTransitionRule };

/**
 * State assertion oracle evaluated upon reaching each abstract state.
 * Supports either a direct async function or an object with an assert method and optional description.
 */
export type StateOracle<TContext = unknown> =
  | ((page: Page, context: TContext) => Promise<void>)
  | { description?: string; assert: (page: Page, context?: TContext, expectFn?: unknown) => Promise<void> };

/**
 * Unified abstract state machine contract for model-driven Playwright exploration.
 * Defines the state space, legal transitions, state oracles, and concrete browser executors.
 */
export interface StateModel<
  TState extends string = string,
  TAction extends string = string,
  TContext = unknown
> {
  /** Domain identifier (e.g. 'practice', 'import', 'session') */
  domain: string;

  /** Starting state for BFS path exploration and journey runs */
  initialState: TState;

  /** Factory producing clean starting context */
  createInitialContext: () => TContext;

  /** Set of valid transitions, guards, and context effects */
  transitions: readonly AbstractTransitionRule<TState, TAction, TContext>[];

  /** State assertion oracles evaluated upon reaching each abstract state */
  oracles: Record<TState, StateOracle<TContext>>;

  /** Concrete Playwright action executor interacting with the DOM */
  executeAction: (page: Page, action: TAction, context: TContext) => Promise<void>;

  /** Optional context signature generator to distinguish cycle states during BFS */
  contextSignature?: (context: TContext) => string;

  /** Optional hook to ensure the browser is navigated and aligned with the initial state */
  ensureInitialState?: (page: Page) => Promise<void>;
}
