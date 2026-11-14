import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  DEFAULT_MOCK_PUZZLE,
} from '../fixtures/mockApi';
import { PlaywrightBrowserPracticeSutAdapter } from '../model/browserSutAdapter';
import { InvariantEvaluator } from '../model/invariantEvaluator';

test.describe('Historical Defect Detection and Remediation (Issue #790)', () => {
  const evaluator = new InvariantEvaluator();

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
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();

    const sut = new PlaywrightBrowserPracticeSutAdapter(page, {
      puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
      entryFen: DEFAULT_MOCK_PUZZLE.fen,
      boardFen: DEFAULT_MOCK_PUZZLE.fen,
    });

    // 1. Solve the puzzle
    await sut.executeAction('MAKE_MOVE_CORRECT');
    await sut.executeAction('ENTER_LINE_EXPLORATION');

    // Verify in exploration
    const exitButton = page.locator('button[title*="Exit Exploration"]').or(
      page.locator('[data-testid="puzzle-feedback-panel"] button:has-text("Exit")')
    ).first();
    await expect(exitButton).toBeVisible();

    // 2. Exit exploration back to practice mode
    await sut.executeAction('EXIT_EXPLORATION');

    // 3. Observe live application state and evaluate UOBS-13 invariant
    const observed = await sut.observeState();

    evaluator.assertExplorationExitInvariant(
      {
        puzzleId: DEFAULT_MOCK_PUZZLE.puzzleId,
        entryFen: DEFAULT_MOCK_PUZZLE.fen,
        boardFen: DEFAULT_MOCK_PUZZLE.fen,
        attemptCount: 1,
        currentView: 'PRACTICE',
        inExploration: false,
        inChallengeMode: false,
        inBlindfold: false,
        solved: true,
      },
      observed
    );

    // Assert that active puzzle is retained and feedback is interactive
    expect(observed.activePuzzle?.id).toBe(DEFAULT_MOCK_PUZZLE.puzzleId);
    expect(observed.boardFen).toBe(DEFAULT_MOCK_PUZZLE.fen);
    expect(observed.currentView).toBe('PRACTICE');
    expect(observed.feedbackPanel.isInteractive).toBe(true);
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

    const sut = new PlaywrightBrowserPracticeSutAdapter(page);

    // 1. Click a piece square to highlight it
    const d2Square = page.locator('[data-square="d2"]').first();
    await d2Square.click();

    // Verify square highlight state is active
    const isHighlightedInitially = await sut.hasSquareSelectionHighlight('d2');
    expect(isHighlightedInitially).toBe(true);

    // 2. Click Reset
    const resetBtn = page.getByRole('button', { name: /Reset/i }).first();
    if (await resetBtn.isVisible().catch(() => false)) {
      await resetBtn.click();
    } else {
      await sut.executeAction('RESET');
    }

    // 3. Verify selection highlight is completely cleared
    const isHighlightedPostReset = await sut.hasSquareSelectionHighlight('d2');
    expect(isHighlightedPostReset).toBe(false);

    // 4. Click a different piece square (c2) and ensure clean selection from new square
    const c2Square = page.locator('[data-square="c2"]').first();
    await c2Square.click();
    const isC2Highlighted = await sut.hasSquareSelectionHighlight('c2');
    expect(isC2Highlighted).toBe(true);
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

    const sut = new PlaywrightBrowserPracticeSutAdapter(page);

    // 1. Solve puzzle and enter line exploration
    await sut.executeAction('MAKE_MOVE_CORRECT');
    await sut.executeAction('ENTER_LINE_EXPLORATION');

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

    // Re-setup API mocks with consecutive same-FEN puzzles
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
    const sut = new PlaywrightBrowserPracticeSutAdapter(page);
    await sut.dragPiece('f1', 'c4');
    await expect(boardArea).toHaveAttribute('data-feedback-status', /CORRECT|SOLVED/);
  });
});
