import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_SIMILAR_POSITION } from './fixtures/mockApi';

test.describe('Journey J03: Similar Positions Practice & Return Flow', () => {
  test('user queries similar positions, enters exploration, and exits back to similar positions with preserved state', async ({ page }) => {
    // 1. Declare and setup mocked API boundary with similar positions
    await setupJourneyApiMocks(page, {
      authenticated: true,
      similarPositions: [DEFAULT_MOCK_SIMILAR_POSITION],
    });

    // 2. Navigate to Similar Positions tab
    await page.goto('/#similar-positions');

    // 3. Verify page header
    await expect(page.getByRole('heading', { name: /Find Similar Personal Positions/i })).toBeVisible();

    // 4. Fill FEN input and submit search
    const fenInput = page.getByLabel('FEN Position');
    await fenInput.fill(DEFAULT_MOCK_SIMILAR_POSITION.fen);

    const searchButton = page.getByRole('button', { name: /Search/i });
    await expect(searchButton).toBeEnabled();
    await searchButton.click();

    // 5. Verify results render and click Explore this position
    const exploreButton = page.getByRole('button', { name: /Explore this position/i });
    await expect(exploreButton).toBeVisible();
    await exploreButton.click();

    // 6. Verify transition to practice puzzles tab in exploration mode
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 7. Exit exploration
    const exitButton = page.getByTitle(/Exit Exploration/i);
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 8. Verify return to originating Similar Positions tab with preserved results
    await expect(page).toHaveURL(/#similar-positions/);
    await expect(page.getByRole('heading', { name: /Find Similar Personal Positions/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Explore this position/i })).toBeVisible();
  });
});
