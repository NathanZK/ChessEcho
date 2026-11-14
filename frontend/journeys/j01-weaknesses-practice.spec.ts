import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_WEAKNESS,
  DEFAULT_MOCK_RECURRING_WEAKNESS,
  DEFAULT_MOCK_DYNAMIC_RECURRING_WEAKNESS,
  DEFAULT_MOCK_CLUSTER_POSITION,
} from './fixtures/mockApi';

test.describe('Journey J01: Weaknesses Library Practice and Exit Return Flow', () => {
  test('user selects weakness, solves puzzle, enters exploration, and exits back to weaknesses tab', async ({ page }) => {
    // 1. Declare and setup mocked API boundary
    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
    });

    // 2. Load application
    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });

    // 3. Verify Weaknesses tab displays populated cards
    const practiceButton = page.getByRole('button', { name: /Practice Position/i });
    await expect(practiceButton).toBeVisible();

    // 4. Click Practice Position to enter shared practice shell
    await practiceButton.click();

    // 5. Verify practice mode mounted on puzzles tab
    await expect(page).toHaveURL(/#puzzles/);

    // 6. Execute move: Nc6 (b8 -> c6)
    // react-chessboard supports click-to-move or drag
    const b8Square = page.locator('[data-testid="practice-board-area"] [data-square="b8"]');
    const c6Square = page.locator('[data-testid="practice-board-area"] [data-square="c6"]');
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

  test('user practices from both structural and dynamic cluster cards with scoped board locator', async ({ page }) => {
    // 1. Declare and setup mocked API boundary with both structural and dynamic clusters
    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
      recurringWeaknesses: [DEFAULT_MOCK_RECURRING_WEAKNESS, DEFAULT_MOCK_DYNAMIC_RECURRING_WEAKNESS],
    });

    // 2. Load application
    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });

    // 3. Verify thematic cluster cards are visible
    await expect(page.getByText(/Thematic Clusters/i)).toBeVisible();
    const practiceButtons = page.getByRole('button', { name: /Practice This Weakness/i });
    await expect(practiceButtons).toHaveCount(2);

    // 4. Click Practice on first (structural) cluster card
    await practiceButtons.first().click();

    // 5. Verify practice mode mounted on puzzles tab with scoped board locator
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 6. Exit practice back to weaknesses
    const exitButton = page.getByRole('button', { name: /Back to Weaknesses/i });
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 7. Verify return to weaknesses tab with thematic clusters still visible
    await expect(page).toHaveURL(/#weaknesses/);
    await expect(page.getByText(/Thematic Clusters/i)).toBeVisible();

    // 8. Click Practice on second (dynamic) cluster card
    const dynamicPracticeButtons = page.getByRole('button', { name: /Practice This Weakness/i });
    await expect(dynamicPracticeButtons).toHaveCount(2);
    await dynamicPracticeButtons.nth(1).click();

    // 9. Verify practice mode mounted again with scoped board
    await expect(page).toHaveURL(/#puzzles/);
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();

    // 10. Exit back to weaknesses
    const backBtn = page.getByRole('button', { name: /Back to Weaknesses/i });
    await expect(backBtn).toBeVisible();
    await backBtn.click();
    await expect(page).toHaveURL(/#weaknesses/);
  });

  test('user inspects cluster member positions in modal and progresses through practice playlist', async ({ page }) => {
    // 1. Declare and setup mock cluster with member positions
    const clusterPositions = [
      DEFAULT_MOCK_CLUSTER_POSITION,
      {
        ...DEFAULT_MOCK_CLUSTER_POSITION,
        positionId: 'pos-rec-2',
        fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
        targetMove: 'Nc6',
        movePlayed: 'f5',
      },
    ];

    await setupJourneyApiMocks(page, {
      authenticated: true,
      recurringWeaknesses: [DEFAULT_MOCK_RECURRING_WEAKNESS],
      clusterPositions,
    });

    // 2. Open weaknesses tab
    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });

    // 3. Click Inspect Evidence to view member positions
    const inspectButton = page.getByRole('button', { name: /Inspect Evidence/i });
    await expect(inspectButton).toBeVisible();
    await inspectButton.click();

    // 4. Verify member positions modal dialog opens and renders member cards
    const modal = page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await expect(page.getByText('Cluster Evidence Inspection')).toBeVisible();
    await expect(page.getByText('Played:').first()).toBeVisible();

    // 5. Close evidence modal via close button
    const closeButton = modal.getByRole('button', { name: /Close dialog/i });
    await expect(closeButton).toBeVisible();
    await closeButton.click();
    await expect(modal).toBeHidden();

    // 6. Click Practice This Weakness to launch playlist
    const practiceButton = page.getByRole('button', { name: /Practice This Weakness/i });
    await expect(practiceButton).toBeVisible();
    await practiceButton.click();

    // 7. Verify transition to practice shell mounted on puzzles tab
    await expect(page).toHaveURL(/#puzzles/);
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();

    // 8. Progress to next puzzle in playlist
    const nextButton = page.getByRole('button', { name: /Next Puzzle/i });
    if (await nextButton.isVisible()) {
      await nextButton.click();
      await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();
    }
  });
});
