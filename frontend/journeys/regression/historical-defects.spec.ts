import { test, expect, type Locator, type Page } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from '../fixtures/mockApi';
import { playSquareMove } from '../support/practiceDom';

/**
 * Historical defect regressions, originally driven through the model-based browser
 * SUT adapter. The adapter is retired; the assertions are preserved verbatim in
 * plain DOM terms (plus the explicit invariant checks the adapter used to derive).
 */

async function squareHasAmberHighlight(page: Page, square: string): Promise<boolean> {
  const element: Locator = page.locator(`[data-square="${square}"]`).first();
  if (!(await element.isVisible().catch(() => false))) {
    return false;
  }
  return element.evaluate((node) => {
    const isAmber = (value?: string | null) =>
      value ? value.includes('245, 158, 11') || value.includes('245,158,11') : false;

    if (isAmber((node as HTMLElement).style?.backgroundColor)) {
      return true;
    }
    if (Array.from(node.querySelectorAll('*')).some((child) => isAmber((child as HTMLElement).style?.backgroundColor))) {
      return true;
    }
    return node.querySelector('.highlight, [data-highlight="true"]') !== null;
  });
}

test.describe('Historical Defect Detection and Remediation (Issue #790)', () => {
  /**
   * AC11 / UOBS-13 Remediation Verification:
   * Exploration exit must NOT drop active puzzle context via setActivePuzzle(null).
   * It must restore the entry FEN and maintain an interactive practice view.
   */
  test('UOBS-13: preserves active puzzle context and restores entry FEN upon exploration exit', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });

    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 1. Solve the puzzle
    await playSquareMove(page, 'd2', 'd4');
    const continueButton = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueButton).toBeVisible();
    await continueButton.click();

    // Verify in exploration
    const exitButton = page.locator('button[title*="Exit Exploration"]').or(
      page.locator('[data-testid="puzzle-feedback-panel"] button:has-text("Exit")')
    ).first();
    await expect(exitButton).toBeVisible();

    // 2. Exit exploration back to practice mode
    await exitButton.click();
    await expect(exitButton).not.toBeVisible();

    // 3. Observed live application state, evaluated against the UOBS-13 invariant:
    //    same puzzle is still active, entry FEN is restored, practice view is
    //    current, the feedback panel is interactive, and exploration is over.
    await expect(boardArea).toHaveAttribute('data-puzzle-id', DEFAULT_MOCK_PUZZLE.puzzleId);
    await expect(boardArea).toHaveAttribute('data-board-fen', DEFAULT_MOCK_PUZZLE.fen);
    await expect(page).toHaveURL(/#puzzles/);
    await expect(boardArea).toBeVisible();
    await expect(page.locator('[data-testid="puzzle-feedback-panel"]')).toHaveAttribute(
      'data-interactive',
      'true'
    );
    await expect(boardArea).toHaveAttribute('data-feedback-status', 'CORRECT');
  });

  /**
   * AC12 / ADD-01 Remediation Verification:
   * Residual square selection highlight must be cleared on board resets and rollbacks.
   */
  test('ADD-01: clears residual square selection highlight on board reset and rollbacks', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();

    // 1. Click a piece square to highlight it
    await page.locator('[data-square="d2"]').first().click();
    expect(await squareHasAmberHighlight(page, 'd2')).toBe(true);

    // 2. Click Reset
    const resetBtn = page.getByRole('button', { name: /Reset/i }).first();
    if (await resetBtn.isVisible().catch(() => false)) {
      await resetBtn.click();
    } else {
      await page.locator('button[title="Reset Position"]').first().click();
    }

    // 3. Verify selection highlight is completely cleared
    expect(await squareHasAmberHighlight(page, 'd2')).toBe(false);

    // 4. Click a different piece square (c2) and ensure clean selection from new square
    await page.locator('[data-square="c2"]').first().click();
    expect(await squareHasAmberHighlight(page, 'c2')).toBe(true);
  });

  /**
   * AC13 / UOBS-17 Board History Synchronization:
   * In Line Exploration, undo and redo maintain strict synchronization between board FEN and move history.
   */
  test('UOBS-17: maintains strict synchronization across undo and redo in line exploration', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [DEFAULT_MOCK_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // 1. Solve puzzle and enter line exploration
    await playSquareMove(page, 'd2', 'd4');
    await page.getByRole('button', { name: /Continue Exploration →/i }).click();

    const solvedFen = await boardArea.getAttribute('data-board-fen');
    expect(solvedFen).not.toBe(DEFAULT_MOCK_PUZZLE.fen);

    // 2. Undo move (rewind to initial FEN)
    const prevButton = page.getByRole('button', { name: /Previous Move/i }).first();
    await expect(prevButton).toBeEnabled();
    await prevButton.click();

    const undoneFen = await boardArea.getAttribute('data-board-fen');
    expect(undoneFen).toBe(DEFAULT_MOCK_PUZZLE.fen);

    // 3. Redo move (advance back to solved FEN)
    const nextButton = page.getByRole('button', { name: /Next Move/i }).first();
    await expect(nextButton).toBeEnabled();
    await nextButton.click();

    const redoneFen = await boardArea.getAttribute('data-board-fen');
    expect(redoneFen).toBe(solvedFen);
  });

  /**
   * AC14 / #782 Component Re-initialization on Identical Starting FEN:
   * Switching between two distinct puzzles sharing identical FEN triggers clean component remounting
   * and updates target move and puzzle metadata without stale state.
   */
  test('#782: cleans and remounts board between distinct puzzles sharing identical starting FEN', async ({
    page,
  }) => {
    const SAME_FEN = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
    const PUZZLE_A = {
      ...DEFAULT_MOCK_PUZZLE,
      puzzleId: 'puzzle-782-a',
      fen: SAME_FEN,
      targetMove: 'd4',
    };
    const PUZZLE_B = {
      ...DEFAULT_MOCK_PUZZLE,
      puzzleId: 'puzzle-782-b',
      fen: SAME_FEN,
      targetMove: 'Bc4',
    };

    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [PUZZLE_A, PUZZLE_B],
    });

    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // Verify first puzzle loaded
    await expect(boardArea).toHaveAttribute('data-puzzle-id', 'puzzle-782-a');
    await expect(boardArea).toHaveAttribute('data-target-move', 'd4');

    // Advance to next puzzle with identical starting FEN
    const nextPuzzleBtn = page.getByRole('button', { name: /Next Puzzle/i }).first();
    await expect(nextPuzzleBtn).toBeVisible();
    await nextPuzzleBtn.click();

    // Verify board remounted and updated cleanly to puzzle B
    await expect(boardArea).toHaveAttribute('data-puzzle-id', 'puzzle-782-b');
    await expect(boardArea).toHaveAttribute('data-target-move', 'Bc4');
    await expect(boardArea).toHaveAttribute('data-board-fen', SAME_FEN);

    // Verify board accepts moves cleanly without stale state
    await playSquareMove(page, 'f1', 'c4');
    await expect(boardArea).toHaveAttribute('data-feedback-status', /CORRECT|SOLVED/);
  });
});
