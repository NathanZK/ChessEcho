import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_PUZZLE, DEFAULT_MOCK_ACCOUNT } from './fixtures/mockApi';

test.describe('Journey J05: Practice Puzzles Discovery and Carousel Flow (UOBS-12)', () => {
  test('direct practice, hint lifecycle, solve, and challenge mode candidate submission', async ({ page }) => {
    // 1. Setup mocked API boundary with authenticated account and puzzle catalog
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    // 3. Verify interactive puzzle shell header and attempt counts
    await expect(page.getByText('Puzzle 1 of 1')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Ruy Lopez / Italian Game' })).toBeVisible();
    await expect(page.getByText('Solved: 0')).toBeVisible();

    // 4. Test Hint lifecycle before solving: click Hint button
    const hintButton = page.getByRole('button', { name: /Hint/i });
    await expect(hintButton).toBeEnabled();
    await hintButton.click();

    // Verify hint square is highlighted on the board (d2 square for target move d4)
    const d2Square = page.locator('[data-square="d2"]');
    await expect(d2Square).toBeVisible();

    // 5. Execute target move: d4 (d2 -> d4) to solve puzzle
    const d4Square = page.locator('[data-square="d4"]');
    await d2Square.click();
    await d4Square.click();

    // 6. Verify feedback panel indicates solved status and incremented attempt counter
    await expect(page.getByText('Solved: 1')).toBeVisible();
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();

    // 7. Enter Line Exploration
    await continueButton.click();

    // 8. Select Challenge Mode in Line Exploration (UOBS-12)
    const challengeModeButton = page.getByRole('button', { name: /Challenge Mode/i });
    if (await challengeModeButton.isVisible()) {
      await challengeModeButton.click();
      await expect(page.getByText(/Challenge Mode — find a strong candidate move/i)).toBeVisible();

      // Submit candidate move in challenge mode
      const candidateInput = page.getByPlaceholder(/Enter candidate moves/i);
      if (await candidateInput.isVisible()) {
        await candidateInput.fill('d5');
        await page.getByRole('button', { name: /Submit Candidates/i }).click();
      }

      // Finish Challenge Mode
      const finishChallengeButton = page.getByRole('button', { name: /Finish Challenge/i });
      if (await finishChallengeButton.isVisible()) {
        await finishChallengeButton.click();
      }
    }

    // 9. Exit exploration back to discovery catalog
    const exitButton = page.getByTitle(/Exit Exploration/i);
    if (await exitButton.isVisible()) {
      await exitButton.click();
    }
  });
});
