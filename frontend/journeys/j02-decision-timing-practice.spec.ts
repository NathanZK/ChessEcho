import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_LONG_DECISION } from './fixtures/mockApi';

test.describe('Journey J02: Decision Timing Practice & Return Flow', () => {
  test('user opens decision timing, launches exploration, and exits back to decision timing', async ({ page }) => {
    // 1. Declare and setup mocked API boundary with long decisions
    await setupJourneyApiMocks(page, {
      authenticated: true,
      longDecisions: [DEFAULT_MOCK_LONG_DECISION],
    });

    // 2. Navigate to decision timing tab
    await page.goto('/#long-decisions');

    // 3. Verify Decision Timing header and populated cards
    await expect(page.getByRole('heading', { name: 'Decision Timing', level: 2 })).toBeVisible();
    const exploreButton = page.getByRole('button', { name: /Explore this position/i });
    await expect(exploreButton).toBeVisible();

    // 4. Click explore to enter practice exploration shell
    await exploreButton.click();

    // 5. Verify transition to practice puzzles tab in exploration mode
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 6. Exit exploration
    const exitButton = page.getByTitle(/Exit Exploration/i);
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 7. Verify return to originating Decision Timing tab
    await expect(page).toHaveURL(/#long-decisions/);
    await expect(page.getByRole('heading', { name: 'Decision Timing', level: 2 })).toBeVisible();
  });
});
