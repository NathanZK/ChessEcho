import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_GAME,
  DEFAULT_MOCK_GAME_ANALYSIS,
} from './fixtures/mockApi';

test.describe('Journey: Individual Game Analysis Practice & Return Flow', () => {
  test('user opens game analysis, selects game, practices mistake with concealed answer, and returns to game view', async ({ page }) => {
    // 1. Declare and setup mocked API boundary with games and game analysis
    await setupJourneyApiMocks(page, {
      authenticated: true,
      games: [DEFAULT_MOCK_GAME],
      gameAnalysis: DEFAULT_MOCK_GAME_ANALYSIS,
    });

    // 2. Navigate to Game Analysis tab
    await page.goto('/#game-analysis');

    // 3. Verify Games list is displayed and click Analyze
    const analyzeButton = page.getByRole('button', { name: /Analyze/i });
    await expect(analyzeButton).toBeVisible();
    await analyzeButton.click();

    // 4. Verify Game Analysis dashboard loaded
    await expect(page.getByText(/Back to My Games/i)).toBeVisible();
    await expect(page.getByText(/carlsen vs nakamura/i)).toBeVisible();

    // 5. Verify Move Detail shows blunder and "Practice this mistake" button
    const practiceMistakeButton = page.getByRole('button', { name: /Practice this mistake/i });
    await expect(practiceMistakeButton).toBeVisible();
    await practiceMistakeButton.click();

    // 6. Verify transition to practice puzzles tab in practice mode
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 7. Exit practice mode back to Game Analysis
    const backButton = page.getByRole('button', { name: /Back to Game Analysis/i });
    await expect(backButton).toBeVisible();
    await backButton.click();

    // 8. Verify return to originating Game Analysis view preserving game analysis state
    await expect(page).toHaveURL(/#game-analysis/);
    await expect(page.getByText(/Back to My Games/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /Practice this mistake/i })).toBeVisible();
  });
});
