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
  SessionModel,
} from '../../journeys/model/sessionModel';
import { exploreAbstractStates } from '../../journeys/model/bfsExplorer';
import { findBfsPath } from '../../journeys/model/modelRunner';

describe('Session Authentication State Model & Invariants', () => {
  it('defines all 3 abstract states and 4 actions for the realigned session lifecycle', () => {
    expect(SESSION_ABSTRACT_STATES).toEqual([
      'GUEST',
      'AUTHENTICATED',
      'AUTH_ERROR',
    ]);

    expect(SESSION_ACTIONS).toEqual([
      'NAVIGATE_LOGIN',
      'SUBMIT_VALID_CREDENTIALS',
      'SUBMIT_INVALID_CREDENTIALS',
      'LOGOUT',
    ]);
  });

  it('validates allowed session transitions across authentication, error recovery, and logout', () => {
    const ctx = createInitialSessionContext();

    // GUEST -> NAVIGATE_LOGIN -> GUEST
    expect(isValidSessionTransition('GUEST', 'NAVIGATE_LOGIN', ctx)).toBe(true);

    // GUEST -> SUBMIT_INVALID_CREDENTIALS -> AUTH_ERROR
    expect(isValidSessionTransition('GUEST', 'SUBMIT_INVALID_CREDENTIALS', ctx)).toBe(true);
    const authError = getNextSessionState('GUEST', 'SUBMIT_INVALID_CREDENTIALS', ctx);
    expect(authError?.state).toBe('AUTH_ERROR');
    expect(authError?.context.hasError).toBe(true);

    // AUTH_ERROR -> SUBMIT_VALID_CREDENTIALS -> AUTHENTICATED
    expect(isValidSessionTransition('AUTH_ERROR', 'SUBMIT_VALID_CREDENTIALS', authError!.context)).toBe(true);
    const authSuccess = getNextSessionState('AUTH_ERROR', 'SUBMIT_VALID_CREDENTIALS', authError!.context);
    expect(authSuccess?.state).toBe('AUTHENTICATED');
    expect(authSuccess?.context.authenticated).toBe(true);
    expect(authSuccess?.context.hasError).toBe(false);

    // AUTHENTICATED -> LOGOUT -> GUEST
    expect(isValidSessionTransition('AUTHENTICATED', 'LOGOUT', authSuccess!.context)).toBe(true);
    const loggedOut = getNextSessionState('AUTHENTICATED', 'LOGOUT', authSuccess!.context);
    expect(loggedOut?.state).toBe('GUEST');
    expect(loggedOut?.context.authenticated).toBe(false);
  });

  it('strictly rejects invalid or guarded state transitions', () => {
    const ctx = createInitialSessionContext();

    // Cannot logout while already GUEST
    expect(isValidSessionTransition('GUEST', 'LOGOUT', ctx)).toBe(false);

    // Cannot submit credentials if already authenticated
    const authCtx: SessionModelContext = { authenticated: true, hasError: false };
    expect(isValidSessionTransition('GUEST', 'SUBMIT_VALID_CREDENTIALS', authCtx)).toBe(false);
  });

  it('defines an invariant oracle definition for every session state', () => {
    for (const state of SESSION_ABSTRACT_STATES) {
      expect(SESSION_STATE_ORACLES[state]).toBeDefined();
      expect(typeof SESSION_STATE_ORACLES[state].assert).toBe('function');
      expect(typeof SESSION_STATE_ORACLES[state].description).toBe('string');
    }
  });

  it('reaches all 3 session states via bounded BFS exploration without cycles', () => {
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
    expect(result.paths.length).toBeGreaterThanOrEqual(2);
  });

  it('finds expected shortest BFS paths using SessionModel and findBfsPath', () => {
    expect(findBfsPath(SessionModel, 'AUTHENTICATED')).toEqual(['SUBMIT_VALID_CREDENTIALS']);
    expect(findBfsPath(SessionModel, 'AUTH_ERROR')).toEqual(['SUBMIT_INVALID_CREDENTIALS']);
  });
});
