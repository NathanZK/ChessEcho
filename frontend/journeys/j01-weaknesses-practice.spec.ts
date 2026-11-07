import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_WEAKNESS } from './fixtures/mockApi';

test.describe('Journey J01: Weaknesses Library Practice and Exit Return Flow', () => {
  test('user selects weakness, solves puzzle, enters exploration, and exits back to weaknesses tab', async ({ page }) => {
    // 1. Declare and setup mocked API boundary
    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
    });

    // 2. Load application
    await page.goto('/#weaknesses');

    // 3. Verify Weaknesses tab displays populated cards
    const practiceButton = page.getByRole('button', { name: /Practice Position/i });
    await expect(practiceButton).toBeVisible();

    // 4. Click Practice Position to enter shared practice shell
    await practiceButton.click();

    // 5. Verify practice mode mounted on puzzles tab
    await expect(page).toHaveURL(/#puzzles/);

    // 6. Execute move: Nc6 (b8 -> c6)
    // react-chessboard supports click-to-move or drag
    const b8Square = page.locator('[data-square="b8"]');
    const c6Square = page.locator('[data-square="c6"]');
    await expect(b8Square).toBeVisible();
    await b8Square.click();
    await c6Square.click();

    // 7. Verify feedback panel shows solve confirmation and continuation button
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();

    // 8. Enter Line Exploration
    await continueButton.click();

    // 9. Exit Line Exploration via exit button
    const exitButton = page.getByTitle(/Exit Exploration/i);
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 10. Verify originating Weaknesses tab is restored
    await expect(page).toHaveURL(/#weaknesses/);
    await expect(page.getByRole('button', { name: /Practice Position/i })).toBeVisible();
  });
});
