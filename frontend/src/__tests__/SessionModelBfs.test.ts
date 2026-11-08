import { describe, it, expect } from 'vitest';
import {
  SESSION_ABSTRACT_STATES,
  SESSION_ACTIONS,
  SESSION_TRANSITIONS,
  SessionAbstractState,
  SessionAction,
  SessionModelContext,
  createInitialSessionContext,
  isValidSessionTransition,
  getNextSessionState,
  SESSION_STATE_ORACLES,
} from '../../journeys/model/sessionModel';
import { exploreAbstractStates } from '../../journeys/model/bfsExplorer';

describe('Session Authentication State Model & Invariants', () => {
  it('defines all 5 abstract states and 6 actions for the session lifecycle', () => {
    expect(SESSION_ABSTRACT_STATES).toEqual([
      'GUEST',
      'AUTHENTICATING',
      'AUTHENTICATED',
      'AUTH_ERROR',
      'PROTECTED_ERROR',
    ]);

    expect(SESSION_ACTIONS).toEqual([
      'START_AUTH',
      'AUTH_SUCCESS',
      'AUTH_FAILURE',
      'DISMISS_ERROR',
      'TRIGGER_PROTECTED_FAIL',
      'LOGOUT',
    ]);
  });

  it('validates allowed session transitions across authentication, error recovery, and logout', () => {
    const ctx = createInitialSessionContext();

    // GUEST -> START_AUTH -> AUTHENTICATING
    expect(isValidSessionTransition('GUEST', 'START_AUTH', ctx)).toBe(true);
    const authenticating = getNextSessionState('GUEST', 'START_AUTH', ctx);
    expect(authenticating?.state).toBe('AUTHENTICATING');

    // AUTHENTICATING -> AUTH_FAILURE -> AUTH_ERROR
    expect(isValidSessionTransition('AUTHENTICATING', 'AUTH_FAILURE', authenticating!.context)).toBe(true);
    const authError = getNextSessionState('AUTHENTICATING', 'AUTH_FAILURE', authenticating!.context);
    expect(authError?.state).toBe('AUTH_ERROR');
    expect(authError?.context.hasError).toBe(true);

    // AUTH_ERROR -> DISMISS_ERROR -> GUEST
    expect(isValidSessionTransition('AUTH_ERROR', 'DISMISS_ERROR', authError!.context)).toBe(true);
    const dismissed = getNextSessionState('AUTH_ERROR', 'DISMISS_ERROR', authError!.context);
    expect(dismissed?.state).toBe('GUEST');
    expect(dismissed?.context.hasError).toBe(false);

    // GUEST -> START_AUTH -> AUTH_SUCCESS -> AUTHENTICATED
    const authSuccess = getNextSessionState('AUTHENTICATING', 'AUTH_SUCCESS', authenticating!.context);
    expect(authSuccess?.state).toBe('AUTHENTICATED');
    expect(authSuccess?.context.authenticated).toBe(true);

    // AUTHENTICATED -> LOGOUT -> GUEST
    expect(isValidSessionTransition('AUTHENTICATED', 'LOGOUT', authSuccess!.context)).toBe(true);
    const loggedOut = getNextSessionState('AUTHENTICATED', 'LOGOUT', authSuccess!.context);
    expect(loggedOut?.state).toBe('GUEST');
    expect(loggedOut?.context.authenticated).toBe(false);

    // GUEST -> TRIGGER_PROTECTED_FAIL -> PROTECTED_ERROR
    expect(isValidSessionTransition('GUEST', 'TRIGGER_PROTECTED_FAIL', loggedOut!.context)).toBe(true);
    const protectedErr = getNextSessionState('GUEST', 'TRIGGER_PROTECTED_FAIL', loggedOut!.context);
    expect(protectedErr?.state).toBe('PROTECTED_ERROR');
  });

  it('strictly rejects invalid or guarded state transitions', () => {
    const ctx = createInitialSessionContext();

    // Cannot logout while already GUEST
    expect(isValidSessionTransition('GUEST', 'LOGOUT', ctx)).toBe(false);

    // Cannot succeed auth without entering AUTHENTICATING
    expect(isValidSessionTransition('GUEST', 'AUTH_SUCCESS', ctx)).toBe(false);
  });

  it('defines an invariant oracle definition for every session state', () => {
    for (const state of SESSION_ABSTRACT_STATES) {
      expect(SESSION_STATE_ORACLES[state]).toBeDefined();
      expect(typeof SESSION_STATE_ORACLES[state].assert).toBe('function');
      expect(typeof SESSION_STATE_ORACLES[state].description).toBe('string');
    }
  });

  it('reaches all 5 session states via bounded BFS exploration without cycles', () => {
    const result = exploreAbstractStates<SessionAbstractState, SessionAction, SessionModelContext>(
      'GUEST',
      SESSION_TRANSITIONS,
      4,
      createInitialSessionContext(),
      (state, ctx) => `${state}:${ctx.authenticated}:${ctx.hasError}`
    );

    for (const state of SESSION_ABSTRACT_STATES) {
      expect(result.reachableStates.has(state)).toBe(true);
    }
    expect(result.paths.length).toBeGreaterThanOrEqual(4);
  });
});
