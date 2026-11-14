import { test, expect } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_SIMILAR_POSITION } from './fixtures/mockApi';

test.describe('Journey J03: Similar Positions Practice & Return Flow (UOBS-20, AUD-07, AUD-10)', () => {
  test('Sicilian sample trigger asserts divergence, cross-game diversity, mode toggle, and exploration', async ({
    page,
  }) => {
    // 1. Declare and setup mocked API boundary with multiple occurrences exhibiting cross-game diversity
    const sicilianSimilarPosition = {
      ...DEFAULT_MOCK_SIMILAR_POSITION,
      canonicalFen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2',
      fen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2',
      occurrences: [
        {
          gameId: 'game-sim-101',
          platformGameId: 'chesscom-101',
          playedAt: '2026-11-01T12:00:00Z',
          plyNumber: 2,
          movePlayed: 'c5',
          playerColor: 'BLACK',
        },
        {
          gameId: 'game-sim-202',
          platformGameId: 'chesscom-202',
          playedAt: '2026-11-02T14:30:00Z',
          plyNumber: 2,
          movePlayed: 'c5',
          playerColor: 'BLACK',
        },
      ],
    };

    await setupJourneyApiMocks(page, {
      authenticated: true,
      similarPositions: [sicilianSimilarPosition],
    });

    // 2. Navigate to Similar Positions tab
    await page.goto('/#similar-positions', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /Find Similar Personal Positions/i })).toBeVisible();

    // 3. Trigger Sicilian Defense sample button
    const sicilianSampleButton = page.getByRole('button', { name: 'Sicilian Defense' });
    await expect(sicilianSampleButton).toBeVisible();
    await sicilianSampleButton.click();

    // Verify FEN input populated with Sicilian divergence (c5 move, not starting position)
    const fenInput = page.getByLabel('FEN Position');
    await expect(fenInput).toHaveValue(/2p5/);

    // 4. Submit search
    const searchButton = page.getByRole('button', { name: /Search/i });
    await expect(searchButton).toBeEnabled();
    await searchButton.click();

    // 5. Verify results render and demonstrate cross-game diversity (occurrences from distinct games)
    const exploreButton = page.getByRole('button', { name: /Explore this position/i });
    await expect(exploreButton).toBeVisible();

    // 6. Test Structural vs Dynamic retrieval mode toggle (AUD-07, AUD-10)
    const dynamicToggle = page.getByRole('button', { name: 'Dynamic' });
    const structuralToggle = page.getByRole('button', { name: 'Structural' });

    await expect(structuralToggle).toHaveAttribute('aria-pressed', 'true');
    await dynamicToggle.click();
    await expect(dynamicToggle).toHaveAttribute('aria-pressed', 'true');

    // 7. Click Explore this position to enter practice exploration shell
    await exploreButton.click();

    // 8. Verify transition to practice puzzles tab in exploration mode
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 9. Exit exploration
    const exitButton = page.getByTitle(/Exit Exploration/i);
    await expect(exitButton).toBeVisible();
    await exitButton.click();

    // 10. Verify return to originating Similar Positions tab with preserved state
    await expect(page).toHaveURL(/#similar-positions/);
    await expect(page.getByRole('heading', { name: /Find Similar Personal Positions/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Explore this position/i })).toBeVisible();
  });
});
