import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_PUZZLE } from './fixtures/mockApi';

test.describe('Journey J04: Explore Position Practice & Return Flow', () => {
  test('user opens explore position modal from tools menu, enters FEN, enters exploration, and exits cleanly', async ({ page }) => {
    // 1. Declare and setup mocked API boundary
    await setupJourneyApiMocks(page, {
      authenticated: true,
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Load application on weaknesses tab
    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });

    // 3. Open Explore / Tools dropdown
    const toolsButton = page.getByRole('button', { name: /Explore \/ Tools/i });
    await expect(toolsButton).toBeVisible();
    await toolsButton.click();

    // 4. Click "Explore a position" menu item
    const exploreMenuItem = page.getByRole('menuitem', { name: /Explore a position/i });
    await expect(exploreMenuItem).toBeVisible();
    await exploreMenuItem.click();

    // 5. Verify Explore Position dialog is open
    const modal = page.getByRole('dialog', { name: /Explore a position/i });
    await expect(modal).toBeVisible();

    // 6. Enter FEN and submit
    const fenTextarea = page.locator('#position-fen');
    await expect(fenTextarea).toBeVisible();
    await fenTextarea.fill(DEFAULT_MOCK_PUZZLE.fen);

    const continueButton = page.getByRole('button', { name: /Continue from FEN/i });
    await continueButton.click();

    // 7. Verify transition to practice shell in exploration mode
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 8. Exit exploration
    const exitButton = page.getByTitle(/Exit Exploration/i);
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 9. Verify return to originating Weaknesses tab
    await expect(page).toHaveURL(/#weaknesses/);
  });
});
