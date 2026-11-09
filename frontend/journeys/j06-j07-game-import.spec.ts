import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_ACCOUNT } from './fixtures/mockApi';

test.describe('Journeys J06-J07: Game Import and Analysis Pipeline', () => {
  test('J06: guest import submission, status polling lifecycle, and transition to weaknesses', async ({ page }) => {
    // 1. Setup unauthenticated guest mocks with a progressing status sequence
    const mockHandle = await setupJourneyApiMocks(page, {
      authenticated: false,
      jobStatusSequence: [
        {
          jobId: 'mock-import-job-1',
          status: 'PROCESSING',
          gamesImported: 5,
          gamesSkipped: 0,
          analysisStatus: 'NOT_STARTED',
        },
        {
          jobId: 'mock-import-job-1',
          status: 'COMPLETED',
          gamesImported: 15,
          gamesSkipped: 1,
          analysisStatus: 'ANALYZING',
        },
        {
          jobId: 'mock-import-job-1',
          status: 'COMPLETED',
          gamesImported: 15,
          gamesSkipped: 1,
          analysisStatus: 'COMPLETED',
        },
      ],
    });

    // 2. Navigate to import tab
    await page.goto('/#import');

    // 3. Fill guest username
    const usernameInput = page.getByLabel('Chess.com Username');
    await expect(usernameInput).toBeVisible();
    await usernameInput.fill('carlsen');

    // 4. Submit import job
    const startButton = page.getByRole('button', { name: /Start Import & Engine Analysis/i });
    await expect(startButton).toBeEnabled();
    await startButton.click();

    // 5. Verify intermediate polling UI states:
    await expect(page.getByRole('button', { name: /Import Job Active.../i })).toBeVisible();
    await expect(page.getByText('Stockfish 16.1 engine analysis active in background...')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Import Completed Successfully!')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Analysis complete.')).toBeVisible({ timeout: 10_000 });

    // 6. Verify clean polling termination (poll count stabilizes upon terminal analysis status)
    await page.waitForLoadState('networkidle');
    const finalPollCount = mockHandle.getPollCount();
    await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 100)));
    expect(mockHandle.getPollCount()).toBe(finalPollCount);

    // 7. Click View Weaknesses CTA button
    const viewWeaknessesButton = page.getByRole('button', { name: /View Weaknesses/i });
    await expect(viewWeaknessesButton).toBeVisible();
    await viewWeaknessesButton.click();

    // 8. Verify transition to weaknesses tab
    await expect(page).toHaveURL(/#weaknesses/);
    await expect(page.getByRole('button', { name: /Practice Position/i })).toBeVisible();
  });

  test('J07: authenticated user submits import for connected account', async ({ page }) => {
    // 1. Setup authenticated session with connected account
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      jobStatusSequence: [
        {
          jobId: 'mock-import-job-account',
          status: 'COMPLETED',
          gamesImported: 8,
          gamesSkipped: 0,
          analysisStatus: 'COMPLETED',
          accountId: DEFAULT_MOCK_ACCOUNT.id,
        },
      ],
    });

    // 2. Navigate to import tab
    await page.goto('/#import');

    // 3. Connected username is pre-populated
    const usernameInput = page.getByLabel('Chess.com Username');
    await expect(usernameInput).toHaveValue(DEFAULT_MOCK_ACCOUNT.username);

    // 4. Submit import
    const startButton = page.getByRole('button', { name: /Start Import & Engine Analysis/i });
    await expect(startButton).toBeEnabled();
    await startButton.click();

    // 5. Verify successful import completion
    await expect(page.getByText('Import Completed Successfully!')).toBeVisible({ timeout: 10_000 });
  });

  test('J06/J07: active job conflict returns 409 and renders error banner gracefully', async ({ page }) => {
    // 1. Setup mocks with active import conflict
    await setupJourneyApiMocks(page, {
      authenticated: false,
      importConflict: true,
    });

    // 2. Navigate to import tab
    await page.goto('/#import');

    // 3. Fill username and submit
    await page.getByLabel('Chess.com Username').fill('carlsen');
    await page.getByRole('button', { name: /Start Import & Engine Analysis/i }).click();

    // 4. Verify conflict error banner renders without crashing
    const alertBanner = page.locator('div[role="alert"]:not(#__next-route-announcer__)');
    await expect(alertBanner).toBeVisible();
    await expect(alertBanner).toContainText('Active import job already in progress');

    // 5. Verify start button remains present and usable
    await expect(page.getByRole('button', { name: /Start Import & Engine Analysis/i })).toBeVisible();
  });
});
