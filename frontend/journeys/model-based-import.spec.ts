import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks } from './fixtures/mockApi';
import {
  IMPORT_TRANSITIONS,
  IMPORT_STATE_ORACLES,
  createInitialImportContext,
  ImportAbstractState,
  ImportAction,
  ImportModelContext,
} from './model/importModel';
import { exploreAbstractStates } from './model/bfsExplorer';

test.describe('Model-Based Testing: Asynchronous Game Import & Polling Lifecycle', () => {
  test('BFS exploration finds shortest paths to high-risk states', () => {
    const exploration = exploreAbstractStates<ImportAbstractState, ImportAction, ImportModelContext>(
      'IDLE',
      IMPORT_TRANSITIONS,
      4,
      createInitialImportContext(),
      (state, ctx) => `${state}:${ctx.status}:${ctx.analysisStatus}:${ctx.hasConflict}`
    );

    expect(exploration.reachableStates.has('CONFLICT_409')).toBe(true);
    expect(exploration.reachableStates.has('POLLING_ANALYSIS')).toBe(true);
    expect(exploration.reachableStates.has('IMPORT_COMPLETED')).toBe(true);

    const conflictPath = exploration.paths.find((p) => p.targetState === 'CONFLICT_409');
    expect(conflictPath).toBeDefined();
    expect(conflictPath?.actions).toEqual(['SUBMIT_IMPORT', 'RECEIVE_CONFLICT']);

    const completedPath = exploration.paths.find((p) => p.targetState === 'IMPORT_COMPLETED');
    expect(completedPath).toBeDefined();
  });

  test('Path 1: 409 Active conflict recovery and retryable submission', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: false,
      importConflict: true,
    });

    await page.goto('/#import');
    await IMPORT_STATE_ORACLES.IDLE.assert(page, expect);

    const usernameInput = page.getByLabel(/Chess\.com Username/i);
    await usernameInput.fill('carlsen');

    const startButton = page.getByRole('button', { name: /Start Import & Engine Analysis/i });
    await expect(startButton).toBeEnabled();
    await startButton.click();

    await IMPORT_STATE_ORACLES.CONFLICT_409.assert(page, expect);
    await expect(startButton).toBeVisible();
  });

  test('Path 2: Asynchronous background analysis polling and terminal completion', async ({ page }) => {
    const mockHandle = await setupJourneyApiMocks(page, {
      authenticated: false,
      jobStatusSequence: [
        {
          jobId: 'bfs-import-job-1',
          status: 'PROCESSING',
          gamesImported: 5,
          gamesSkipped: 0,
          analysisStatus: 'NOT_STARTED',
        },
        {
          jobId: 'bfs-import-job-1',
          status: 'COMPLETED',
          gamesImported: 15,
          gamesSkipped: 1,
          analysisStatus: 'ANALYZING',
        },
        {
          jobId: 'bfs-import-job-1',
          status: 'COMPLETED',
          gamesImported: 15,
          gamesSkipped: 1,
          analysisStatus: 'COMPLETED',
        },
      ],
    });

    await page.goto('/#import');
    await IMPORT_STATE_ORACLES.IDLE.assert(page, expect);

    await page.getByLabel(/Chess\.com Username/i).fill('carlsen');
    await page.getByRole('button', { name: /Start Import & Engine Analysis/i }).click();

    await expect(page.getByRole('button', { name: /Import Job Active\.\.\./i })).toBeVisible();
    await IMPORT_STATE_ORACLES.POLLING_ANALYSIS.assert(page, expect);
    await expect(page.getByText(/Import Completed Successfully!/i)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/Analysis complete\./i)).toBeVisible({ timeout: 10_000 });
    await IMPORT_STATE_ORACLES.IMPORT_COMPLETED.assert(page, expect);

    // Terminal polling cleanup verification (polling loop stabilizes upon COMPLETED)
    const finalPollCount = mockHandle.getPollCount();
    await page.waitForTimeout(2500);
    expect(mockHandle.getPollCount()).toBe(finalPollCount);
  });
});
