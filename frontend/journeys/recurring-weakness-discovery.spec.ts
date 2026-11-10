import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_RECURRING_WEAKNESS,
  DEFAULT_MOCK_CLUSTER_POSITION,
} from './fixtures/mockApi';

test.describe('Journey: Recurring Weakness Discovery Lifecycle & Thematic Cluster Practice', () => {
  test('user triggers discovery, tracks progress to completion, and practices thematic cluster', async ({ page }) => {
    // 1. Declare and setup mocked API boundary with recurring weakness cluster
    await setupJourneyApiMocks(page, {
      authenticated: true,
      recurringWeaknesses: [DEFAULT_MOCK_RECURRING_WEAKNESS],
      clusterPositions: [DEFAULT_MOCK_CLUSTER_POSITION],
    });

    // 2. Navigate to Weaknesses tab
    await page.goto('/#weaknesses');

    // 3. Verify "Find Recurring Weaknesses" action button is visible
    const findButton = page.getByRole('button', { name: /Find Recurring Weaknesses/i });
    await expect(findButton).toBeVisible();
    await findButton.click();

    // 4. Verify discovery modal opens
    const dialog = page.getByRole('dialog', { name: /Discover Recurring Weaknesses/i });
    await expect(dialog).toBeVisible();

    // 5. Submit discovery parameters
    const startButton = page.getByRole('button', { name: /Start Discovery/i });
    await startButton.click();

    // 6. Verify modal closes and thematic clusters are visible
    await expect(page.getByText(/Thematic Clusters/i)).toBeVisible();
    await expect(page.getByText(/Mistake Freq: 55%/i)).toBeVisible();

    // 7. Click "Practice This Weakness" on the thematic cluster card
    const practiceClusterButton = page.getByRole('button', { name: /Practice This Weakness/i });
    await expect(practiceClusterButton).toBeVisible();
    await practiceClusterButton.click();

    // 8. Verify transition to practice puzzles tab
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 9. Exit practice mode back to Weaknesses library
    const exitButton = page.getByRole('button', { name: /Back to Weaknesses/i });
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 10. Verify return to Weaknesses tab with thematic clusters still rendered
    await expect(page).toHaveURL(/#weaknesses/);
    await expect(page.getByText(/Thematic Clusters/i)).toBeVisible();
  });

  test('user triggers discovery, handles job failure, and sees visible error banner with retry action', async ({ page }) => {
    // 1. Declare and setup mocked API boundary with failed discovery job
    await setupJourneyApiMocks(page, {
      authenticated: true,
      discoveryJob: {
        jobId: 'discovery-job-failed-1',
        accountId: '550e8400-e29b-41d4-a716-446655440010',
        status: 'FAILED',
        progressPercent: 25,
        stage: 'FAILED',
        errorMessage: 'Clustering generation failed: STRUCTURAL: out of memory',
        warningMessage: null,
        createdAt: '2026-11-09T00:00:00Z',
        updatedAt: '2026-11-09T00:01:00Z',
      },
    });

    // 2. Navigate to Weaknesses tab
    await page.goto('/#weaknesses');

    // 3. Open discovery modal and submit
    const findButton = page.getByRole('button', { name: /Find Recurring Weaknesses/i });
    await expect(findButton).toBeVisible();
    await findButton.click();

    const startButton = page.getByRole('button', { name: /Start Discovery/i });
    await startButton.click();

    // 4. Verify visible error banner appears with sanitized diagnostic and retry action
    const errorBanner = page.locator('[role="alert"]').filter({ hasText: 'Discovery Failed' });
    await expect(errorBanner).toBeVisible();
    await expect(page.getByText('Clustering generation failed: STRUCTURAL: out of memory')).toBeVisible();
    const retryButton = page.getByRole('button', { name: /Retry Discovery/i });
    await expect(retryButton).toBeVisible();

    // 5. Dismiss error banner
    const dismissButton = page.getByRole('button', { name: /Dismiss banner/i });
    await expect(dismissButton).toBeVisible();
    await dismissButton.click();

    // 6. Verify error banner is dismissed
    await expect(errorBanner).not.toBeVisible();
  });
});
