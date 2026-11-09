import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks } from './fixtures/mockApi';
import {
  IMPORT_STATE_ORACLES,
  ImportModel,
} from './model/importModel';
import { findBfsPath, executeModelPath } from './model/modelRunner';

test.describe('Model-Based Testing: Asynchronous Game Import & Polling Lifecycle', () => {
  test('BFS exploration finds shortest paths to high-risk states', () => {
    const conflictPath = findBfsPath(ImportModel, 'CONFLICT_409');
    expect(conflictPath).toBeDefined();
    expect(conflictPath).toEqual(['SUBMIT_IMPORT_CONFLICT']);

    const pollingPath = findBfsPath(ImportModel, 'POLLING_IMPORT');
    expect(pollingPath).toBeDefined();
    expect(pollingPath).toEqual(['SUBMIT_IMPORT']);

    const completedPath = findBfsPath(ImportModel, 'IMPORT_COMPLETED');
    expect(completedPath).toBeDefined();
    expect(completedPath).toEqual(['SUBMIT_IMPORT', 'POLL_COMPLETED']);

    const analysisPath = findBfsPath(ImportModel, 'POLLING_ANALYSIS');
    expect(analysisPath).toBeDefined();
    expect(analysisPath).toEqual(['SUBMIT_IMPORT', 'POLL_ANALYSIS_PROGRESS']);
  });

  test('Path 1: 409 Active conflict recovery and retryable submission', async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: false,
      importConflict: true,
    });

    const conflictPath = findBfsPath(ImportModel, 'CONFLICT_409')!;
    const result = await executeModelPath(page, ImportModel, conflictPath);

    expect(result.success).toBe(true);
    expect(result.finalState).toBe('CONFLICT_409');
    expect(result.visitedStates.has('CONFLICT_409')).toBe(true);
    expect(result.executedTransitions.has('IDLE->SUBMIT_IMPORT_CONFLICT->CONFLICT_409')).toBe(true);

    const startButton = page.getByRole('button', { name: /Start Import & Engine Analysis/i });
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

    // Execute model path to initiate import and reach polling state
    const submitResult = await executeModelPath(page, ImportModel, ['SUBMIT_IMPORT']);
    expect(submitResult.success).toBe(true);
    expect(submitResult.finalState).toBe('POLLING_IMPORT');
    expect(submitResult.visitedStates.has('POLLING_IMPORT')).toBe(true);

    // Verify intermediate analysis presentation
    await IMPORT_STATE_ORACLES.POLLING_ANALYSIS.assert(page, expect);
    await expect(page.getByText(/Import Completed Successfully!/i)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/Analysis complete\./i)).toBeVisible({ timeout: 10_000 });
    await IMPORT_STATE_ORACLES.IMPORT_COMPLETED.assert(page, expect);

    // Deterministic terminal polling cleanup verification
    await page.waitForLoadState('networkidle');
    const finalPollCount = mockHandle.getPollCount();
    await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 100)));
    expect(mockHandle.getPollCount()).toBe(finalPollCount);
  });
});
