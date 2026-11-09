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
  ImportModel,
} from '../../journeys/model/importModel';
import { exploreAbstractStates } from '../../journeys/model/bfsExplorer';
import { findBfsPath } from '../../journeys/model/modelRunner';

describe('Game Import State Model & Invariants', () => {
  it('defines all 6 abstract states and 7 actions for the realigned import pipeline', () => {
    expect(IMPORT_ABSTRACT_STATES).toEqual([
      'IDLE',
      'POLLING_IMPORT',
      'POLLING_ANALYSIS',
      'IMPORT_COMPLETED',
      'JOB_FAILED',
      'CONFLICT_409',
    ]);

    expect(IMPORT_ACTIONS).toEqual([
      'SUBMIT_IMPORT',
      'SUBMIT_IMPORT_CONFLICT',
      'RETRY_IMPORT',
      'RESET_IMPORT',
      'POLL_ANALYSIS_PROGRESS',
      'POLL_COMPLETED',
      'POLL_FAILURE',
    ]);
  });

  it('validates allowed lifecycle transitions including direct conflict and polling progression', () => {
    const ctx = createInitialImportContext();

    // IDLE -> SUBMIT_IMPORT -> POLLING_IMPORT
    expect(isValidImportTransition('IDLE', 'SUBMIT_IMPORT', ctx)).toBe(true);
    const pollingImport = getNextImportState('IDLE', 'SUBMIT_IMPORT', ctx);
    expect(pollingImport?.state).toBe('POLLING_IMPORT');
    expect(pollingImport?.context.status).toBe('PROCESSING');

    // IDLE -> SUBMIT_IMPORT_CONFLICT -> CONFLICT_409
    expect(isValidImportTransition('IDLE', 'SUBMIT_IMPORT_CONFLICT', ctx)).toBe(true);
    const conflict = getNextImportState('IDLE', 'SUBMIT_IMPORT_CONFLICT', ctx);
    expect(conflict?.state).toBe('CONFLICT_409');
    expect(conflict?.context.hasConflict).toBe(true);

    // CONFLICT_409 -> RETRY_IMPORT -> POLLING_IMPORT
    expect(isValidImportTransition('CONFLICT_409', 'RETRY_IMPORT', conflict!.context)).toBe(true);
    const retried = getNextImportState('CONFLICT_409', 'RETRY_IMPORT', conflict!.context);
    expect(retried?.state).toBe('POLLING_IMPORT');
    expect(retried?.context.hasConflict).toBe(false);

    // POLLING_IMPORT -> POLL_ANALYSIS_PROGRESS -> POLLING_ANALYSIS
    expect(isValidImportTransition('POLLING_IMPORT', 'POLL_ANALYSIS_PROGRESS', pollingImport!.context)).toBe(true);
    const pollingAnalysis = getNextImportState('POLLING_IMPORT', 'POLL_ANALYSIS_PROGRESS', pollingImport!.context);
    expect(pollingAnalysis?.state).toBe('POLLING_ANALYSIS');
    expect(pollingAnalysis?.context.analysisStatus).toBe('ANALYZING');

    // POLLING_ANALYSIS -> POLL_COMPLETED -> IMPORT_COMPLETED
    expect(isValidImportTransition('POLLING_ANALYSIS', 'POLL_COMPLETED', pollingAnalysis!.context)).toBe(true);
    const completed = getNextImportState('POLLING_ANALYSIS', 'POLL_COMPLETED', pollingAnalysis!.context);
    expect(completed?.state).toBe('IMPORT_COMPLETED');
    expect(completed?.context.status).toBe('COMPLETED');
    expect(completed?.context.analysisStatus).toBe('COMPLETED');

    // IMPORT_COMPLETED -> RESET_IMPORT -> IDLE
    expect(isValidImportTransition('IMPORT_COMPLETED', 'RESET_IMPORT', completed!.context)).toBe(true);
    const reset = getNextImportState('IMPORT_COMPLETED', 'RESET_IMPORT', completed!.context);
    expect(reset?.state).toBe('IDLE');
    expect(reset?.context.hasConflict).toBe(false);
  });

  it('strictly rejects invalid or guarded state transitions', () => {
    const ctx = createInitialImportContext();

    // Cannot poll while in IDLE
    expect(isValidImportTransition('IDLE', 'POLL_ANALYSIS_PROGRESS', ctx)).toBe(false);
    expect(isValidImportTransition('IDLE', 'POLL_COMPLETED', ctx)).toBe(false);

    // Cannot retry conflict when not in CONFLICT_409
    expect(isValidImportTransition('IDLE', 'RETRY_IMPORT', ctx)).toBe(false);
    expect(isValidImportTransition('POLLING_IMPORT', 'RETRY_IMPORT', ctx)).toBe(false);
  });

  it('defines an invariant oracle definition for every abstract state', () => {
    for (const state of IMPORT_ABSTRACT_STATES) {
      expect(IMPORT_STATE_ORACLES[state]).toBeDefined();
      expect(typeof IMPORT_STATE_ORACLES[state].assert).toBe('function');
      expect(typeof IMPORT_STATE_ORACLES[state].description).toBe('string');
    }
  });

  it('reaches all 6 abstract states via bounded BFS exploration without cycles', () => {
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
    expect(result.paths.length).toBeGreaterThanOrEqual(5);
  });

  it('finds expected shortest BFS paths using ImportModel and findBfsPath', () => {
    expect(findBfsPath(ImportModel, 'CONFLICT_409')).toEqual(['SUBMIT_IMPORT_CONFLICT']);
    expect(findBfsPath(ImportModel, 'POLLING_IMPORT')).toEqual(['SUBMIT_IMPORT']);
    expect(findBfsPath(ImportModel, 'IMPORT_COMPLETED')).toEqual(['SUBMIT_IMPORT', 'POLL_COMPLETED']);
    expect(findBfsPath(ImportModel, 'POLLING_ANALYSIS')).toEqual(['SUBMIT_IMPORT', 'POLL_ANALYSIS_PROGRESS']);
  });
});
