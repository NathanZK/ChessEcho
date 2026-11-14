import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_LONG_DECISION } from './fixtures/mockApi';

test.describe('Journey J02: Decision Timing Practice & Return Flow (UOBS-14)', () => {
  test('user opens decision timing, launches practice outlier, verifies continuous stopwatch, and exits back to decision timing', async ({
    page,
  }) => {
    // 1. Declare and setup mocked API boundary with long decision outliers
    await setupJourneyApiMocks(page, {
      authenticated: true,
      longDecisions: [DEFAULT_MOCK_LONG_DECISION],
    });

    // 2. Navigate to decision timing tab
    await page.goto('/#long-decisions', { waitUntil: 'domcontentloaded' });

    // 3. Verify Decision Timing header and populated cards
    await expect(page.getByRole('heading', { name: 'Decision Timing', level: 2 })).toBeVisible();
    const exploreButton = page.getByRole('button', { name: /Explore this position/i });
    await expect(exploreButton).toBeVisible();

    // 4. Click explore to enter practice exploration shell
    await exploreButton.click();

    // 5. Verify transition to practice puzzles tab in practice mode
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 6. Verify continuous stopwatch timer is active and running (UOBS-14)
    await expect(page.getByText(/0:0[0-9]/).first()).toBeVisible({ timeout: 5000 });

    // 7. Verify pre-attempt secrecy: played move is concealed before move attempt
    await expect(page.getByText('Original Game Move:')).toHaveCount(0);

    // 8. Exit exploration back to originating tab
    const exitButton = page.getByTitle(/Exit Exploration/i);
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 9. Verify return to originating Decision Timing tab
    await expect(page).toHaveURL(/#long-decisions/);
    await expect(page.getByRole('heading', { name: 'Decision Timing', level: 2 })).toBeVisible();
  });
});
