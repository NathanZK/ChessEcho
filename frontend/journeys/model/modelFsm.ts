/**
 * Abstract Finite-State Machine (FSM) specification for Practice and Discovery workflows.
 * Strictly decoupled from SUT adapters and invariant evaluators (Issue #789).
 */

export const PRACTICE_FSM_STATES = [
  'INITIAL',
  'ATTEMPTED_WRONG',
  'ATTEMPTED_RIGHT',
  'LINE_EXPLORATION',
  'CHALLENGE_MODE',
  'BLINDFOLD',
  'SOLVED',
  'EXIT',
] as const;

export type PracticeState = (typeof PRACTICE_FSM_STATES)[number];

export const PRACTICE_FSM_ACTIONS = [
  'SELECT_PUZZLE',
  'MAKE_MOVE_CORRECT',
  'MAKE_MOVE_INCORRECT',
  'ENTER_LINE_EXPLORATION',
  'ENTER_CHALLENGE_MODE',
  'ENTER_BLINDFOLD',
  'SOLVE',
  'RETRY',
  'EXIT_EXPLORATION',
  'EXIT_CHALLENGE',
  'EXIT_BLINDFOLD',
  'RESET',
] as const;

export type PracticeAction = (typeof PRACTICE_FSM_ACTIONS)[number];

export interface PracticeContext {
  puzzleId: string | null;
  boardFen: string;
  entryFen: string;
  attemptCount: number;
  currentView: 'PRACTICE' | 'WEAKNESSES' | 'OTHER';
  inExploration: boolean;
  inChallengeMode: boolean;
  inBlindfold: boolean;
  solved: boolean;
}

export function createInitialPracticeContext(overrides?: Partial<PracticeContext>): PracticeContext {
  return {
    puzzleId: null,
    boardFen: '',
    entryFen: '',
    attemptCount: 0,
    currentView: 'PRACTICE',
    inExploration: false,
    inChallengeMode: false,
    inBlindfold: false,
    solved: false,
    ...overrides,
  };
}

export const DISCOVERY_FSM_STATES = [
  'QUEUED',
  'PROCESSING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const;

export type DiscoveryState = (typeof DISCOVERY_FSM_STATES)[number];

export const DISCOVERY_FSM_ACTIONS = [
  'START_PROCESSING',
  'COMPLETE',
  'FAIL',
  'CANCEL',
] as const;

export type DiscoveryAction = (typeof DISCOVERY_FSM_ACTIONS)[number];

export interface DiscoveryContext {
  jobId: string;
  accountId: string;
  stage: string;
  progressPercent: number;
  errorMessage?: string;
}

export function createInitialDiscoveryContext(
  jobId = 'default-job',
  accountId = 'default-account'
): DiscoveryContext {
  return {
    jobId,
    accountId,
    stage: 'INITIALIZING',
    progressPercent: 0,
  };
}

export interface TransitionResult<TState, TContext> {
  nextState: TState;
  nextContext: TContext;
}

/**
 * Returns next PracticeState and updated context if valid, or null if transition is illegal.
 */
export function getPracticeFsmNextState(
  from: PracticeState,
  action: PracticeAction,
  ctx: PracticeContext
): TransitionResult<PracticeState, PracticeContext> | null {
  // Terminal states cannot transition
  if (from === 'SOLVED' || from === 'EXIT') {
    return null;
  }

  switch (action) {
    case 'SELECT_PUZZLE':
      if (from === 'INITIAL') {
        return {
          nextState: 'INITIAL',
          nextContext: { ...ctx, attemptCount: 0, solved: false },
        };
      }
      return null;

    case 'MAKE_MOVE_CORRECT':
      if (from === 'INITIAL' || from === 'ATTEMPTED_WRONG') {
        return {
          nextState: 'ATTEMPTED_RIGHT',
          nextContext: { ...ctx, attemptCount: ctx.attemptCount + 1 },
        };
      }
      return null;

    case 'MAKE_MOVE_INCORRECT':
      if (from === 'INITIAL' || from === 'ATTEMPTED_WRONG') {
        return {
          nextState: 'ATTEMPTED_WRONG',
          nextContext: { ...ctx, attemptCount: ctx.attemptCount + 1 },
        };
      }
      return null;

    case 'SOLVE':
      if (from === 'ATTEMPTED_RIGHT') {
        return {
          nextState: 'SOLVED',
          nextContext: { ...ctx, solved: true },
        };
      }
      return null;

    case 'RETRY':
      if (from === 'ATTEMPTED_WRONG') {
        return {
          nextState: 'INITIAL',
          nextContext: { ...ctx, boardFen: ctx.entryFen },
        };
      }
      return null;

    case 'ENTER_LINE_EXPLORATION':
      if (from === 'INITIAL' || from === 'ATTEMPTED_WRONG' || from === 'ATTEMPTED_RIGHT') {
        return {
          nextState: 'LINE_EXPLORATION',
          nextContext: { ...ctx, inExploration: true },
        };
      }
      return null;

    case 'EXIT_EXPLORATION':
      if (from === 'LINE_EXPLORATION') {
        return {
          nextState: 'EXIT',
          nextContext: {
            ...ctx,
            inExploration: false,
            boardFen: ctx.entryFen, // Restores entry ply per UOBS-13
          },
        };
      }
      return null;

    case 'ENTER_CHALLENGE_MODE':
      if (from === 'INITIAL' || from === 'ATTEMPTED_WRONG') {
        return {
          nextState: 'CHALLENGE_MODE',
          nextContext: { ...ctx, inChallengeMode: true },
        };
      }
      return null;

    case 'EXIT_CHALLENGE':
      if (from === 'CHALLENGE_MODE') {
        return {
          nextState: 'EXIT',
          nextContext: {
            ...ctx,
            inChallengeMode: false,
            boardFen: ctx.entryFen, // Restores entry ply per UOBS-13
          },
        };
      }
      return null;

    case 'ENTER_BLINDFOLD':
      if (from === 'INITIAL' || from === 'ATTEMPTED_WRONG') {
        return {
          nextState: 'BLINDFOLD',
          nextContext: { ...ctx, inBlindfold: true },
        };
      }
      return null;

    case 'EXIT_BLINDFOLD':
      if (from === 'BLINDFOLD') {
        return {
          nextState: 'EXIT',
          nextContext: {
            ...ctx,
            inBlindfold: false,
            boardFen: ctx.entryFen,
          },
        };
      }
      return null;

    case 'RESET':
      return {
        nextState: 'INITIAL',
        nextContext: createInitialPracticeContext({ puzzleId: ctx.puzzleId, entryFen: ctx.entryFen }),
      };

    default:
      return null;
  }
}

export function isPracticeFsmTransitionValid(
  from: PracticeState,
  action: PracticeAction,
  ctx: PracticeContext
): boolean {
  return getPracticeFsmNextState(from, action, ctx) !== null;
}

/**
 * Returns next DiscoveryState and updated context if valid, or null if transition is illegal.
 */
export function getDiscoveryFsmNextState(
  from: DiscoveryState,
  action: DiscoveryAction,
  ctx: DiscoveryContext
): TransitionResult<DiscoveryState, DiscoveryContext> | null {
  // Terminal states cannot transition
  if (from === 'COMPLETED' || from === 'FAILED' || from === 'CANCELLED') {
    return null;
  }

  switch (action) {
    case 'START_PROCESSING':
      if (from === 'QUEUED') {
        return {
          nextState: 'PROCESSING',
          nextContext: { ...ctx, stage: 'INGESTION', progressPercent: 10 },
        };
      }
      return null;

    case 'COMPLETE':
      if (from === 'PROCESSING') {
        return {
          nextState: 'COMPLETED',
          nextContext: { ...ctx, stage: 'COMPLETE', progressPercent: 100 },
        };
      }
      return null;

    case 'FAIL':
      if (from === 'QUEUED' || from === 'PROCESSING') {
        return {
          nextState: 'FAILED',
          nextContext: { ...ctx, stage: 'FAILED', errorMessage: 'Failure reported' },
        };
      }
      return null;

    case 'CANCEL':
      if (from === 'QUEUED' || from === 'PROCESSING') {
        return {
          nextState: 'CANCELLED',
          nextContext: { ...ctx, stage: 'CANCELLED' },
        };
      }
      return null;

    default:
      return null;
  }
}

export function isDiscoveryFsmTransitionValid(
  from: DiscoveryState,
  action: DiscoveryAction,
  ctx: DiscoveryContext
): boolean {
  return getDiscoveryFsmNextState(from, action, ctx) !== null;
}
