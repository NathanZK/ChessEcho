import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_PUZZLE, DEFAULT_MOCK_ACCOUNT } from './fixtures/mockApi';

test.describe('Journey J05: Practice Puzzles Discovery and Carousel Flow', () => {
  test('authenticated user loads practice puzzle, verifies attempts, solves puzzle, and exits back to catalog', async ({ page }) => {
    // 1. Setup mocked API boundary with authenticated account and puzzle catalog
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });

    // 2. Load application on Practice Puzzles tab
    await page.goto('/#puzzles');

    // 3. Verify interactive puzzle shell header and attempt counts
    await expect(page.getByText('Puzzle 1 of 1')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Ruy Lopez / Italian Game' })).toBeVisible();
    await expect(page.getByText('Solved: 0')).toBeVisible();
    await expect(page.getByText('Failed: 0')).toBeVisible();

    // 4. Execute target move: d4 (d2 -> d4)
    const d2Square = page.locator('[data-square="d2"]');
    const d4Square = page.locator('[data-square="d4"]');
    await expect(d2Square).toBeVisible();
    await d2Square.click();
    await d4Square.click();

    // 5. Verify feedback panel indicates solved status, continuation option, and incremented attempt counter
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();
    await expect(page.getByText('Solved: 1')).toBeVisible();

    // 6. Exit practice mode back to discovery catalog via Back to Puzzles
    const backButton = page.getByRole('button', { name: /Back to Puzzles/i });
    await expect(backButton).toBeVisible();
    await backButton.click();

    // 7. Verify discovery catalog is restored
    await expect(page.getByRole('button', { name: /Start Practice/i })).toBeVisible();
    await expect(page.getByText('Strengthen your play by solving recurring mistake positions')).toBeVisible();
  });
});
