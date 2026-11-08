import { describe, it, expect } from 'vitest';
import {
  IMPORT_ABSTRACT_STATES,
  IMPORT_ACTIONS,
  IMPORT_TRANSITIONS,
  ImportAbstractState,
  ImportAction,
  ImportModelContext,
  createInitialImportContext,
  isValidImportTransition,
  getNextImportState,
  IMPORT_STATE_ORACLES,
} from '../../journeys/model/importModel';
import { exploreAbstractStates } from '../../journeys/model/bfsExplorer';

describe('Game Import State Model & Invariants', () => {
  it('defines all 7 abstract states and 8 actions for the import pipeline', () => {
    expect(IMPORT_ABSTRACT_STATES).toEqual([
      'IDLE',
      'SUBMITTING',
      'POLLING_IMPORT',
      'POLLING_ANALYSIS',
      'IMPORT_COMPLETED',
      'JOB_FAILED',
      'CONFLICT_409',
    ]);

    expect(IMPORT_ACTIONS).toEqual([
      'SUBMIT_IMPORT',
      'POLL_PROCESSING',
      'POLL_ANALYZING',
      'POLL_COMPLETE',
      'POLL_FAIL',
      'RECEIVE_CONFLICT',
      'RETRY_CONFLICT',
      'RESET_IMPORT',
    ]);
  });

  it('validates allowed lifecycle transitions including conflict recovery and terminal reset', () => {
    const ctx = createInitialImportContext();

    // IDLE -> SUBMIT_IMPORT -> SUBMITTING
    expect(isValidImportTransition('IDLE', 'SUBMIT_IMPORT', ctx)).toBe(true);
    const submitting = getNextImportState('IDLE', 'SUBMIT_IMPORT', ctx);
    expect(submitting?.state).toBe('SUBMITTING');

    // SUBMITTING -> RECEIVE_CONFLICT -> CONFLICT_409
    expect(isValidImportTransition('SUBMITTING', 'RECEIVE_CONFLICT', submitting!.context)).toBe(true);
    const conflict = getNextImportState('SUBMITTING', 'RECEIVE_CONFLICT', submitting!.context);
    expect(conflict?.state).toBe('CONFLICT_409');
    expect(conflict?.context.hasConflict).toBe(true);

    // CONFLICT_409 -> RETRY_CONFLICT -> SUBMITTING
    expect(isValidImportTransition('CONFLICT_409', 'RETRY_CONFLICT', conflict!.context)).toBe(true);
    const retried = getNextImportState('CONFLICT_409', 'RETRY_CONFLICT', conflict!.context);
    expect(retried?.state).toBe('SUBMITTING');
    expect(retried?.context.hasConflict).toBe(false);

    // SUBMITTING -> POLL_PROCESSING -> POLLING_IMPORT
    expect(isValidImportTransition('SUBMITTING', 'POLL_PROCESSING', retried!.context)).toBe(true);
    const pollingImport = getNextImportState('SUBMITTING', 'POLL_PROCESSING', retried!.context);
    expect(pollingImport?.state).toBe('POLLING_IMPORT');

    // POLLING_IMPORT -> POLL_ANALYZING -> POLLING_ANALYSIS
    expect(isValidImportTransition('POLLING_IMPORT', 'POLL_ANALYZING', pollingImport!.context)).toBe(true);
    const pollingAnalysis = getNextImportState('POLLING_IMPORT', 'POLL_ANALYZING', pollingImport!.context);
    expect(pollingAnalysis?.state).toBe('POLLING_ANALYSIS');

    // POLLING_ANALYSIS -> POLL_COMPLETE -> IMPORT_COMPLETED
    expect(isValidImportTransition('POLLING_ANALYSIS', 'POLL_COMPLETE', pollingAnalysis!.context)).toBe(true);
    const completed = getNextImportState('POLLING_ANALYSIS', 'POLL_COMPLETE', pollingAnalysis!.context);
    expect(completed?.state).toBe('IMPORT_COMPLETED');

    // IMPORT_COMPLETED -> RESET_IMPORT -> IDLE
    expect(isValidImportTransition('IMPORT_COMPLETED', 'RESET_IMPORT', completed!.context)).toBe(true);
    const reset = getNextImportState('IMPORT_COMPLETED', 'RESET_IMPORT', completed!.context);
    expect(reset?.state).toBe('IDLE');
  });

  it('strictly rejects invalid or guarded state transitions', () => {
    const ctx = createInitialImportContext();

    // Cannot poll while in IDLE
    expect(isValidImportTransition('IDLE', 'POLL_PROCESSING', ctx)).toBe(false);
    expect(isValidImportTransition('IDLE', 'POLL_COMPLETE', ctx)).toBe(false);

    // Cannot retry conflict when not in CONFLICT_409
    expect(isValidImportTransition('IDLE', 'RETRY_CONFLICT', ctx)).toBe(false);
    expect(isValidImportTransition('SUBMITTING', 'RETRY_CONFLICT', ctx)).toBe(false);
  });

  it('defines an invariant oracle definition for every abstract state', () => {
    for (const state of IMPORT_ABSTRACT_STATES) {
      expect(IMPORT_STATE_ORACLES[state]).toBeDefined();
      expect(typeof IMPORT_STATE_ORACLES[state].assert).toBe('function');
      expect(typeof IMPORT_STATE_ORACLES[state].description).toBe('string');
    }
  });

  it('reaches all 7 abstract states via bounded BFS exploration without cycles', () => {
    const result = exploreAbstractStates<ImportAbstractState, ImportAction, ImportModelContext>(
      'IDLE',
      IMPORT_TRANSITIONS,
      4,
      createInitialImportContext(),
      (state, ctx) => `${state}:${ctx.status}:${ctx.analysisStatus}:${ctx.hasConflict}`
    );

    for (const state of IMPORT_ABSTRACT_STATES) {
      expect(result.reachableStates.has(state)).toBe(true);
    }
    expect(result.paths.length).toBeGreaterThanOrEqual(6);
  });
});
