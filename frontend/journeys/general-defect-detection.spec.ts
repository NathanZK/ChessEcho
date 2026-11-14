import { test, expect } from '@playwright/test';
import { PlaywrightBrowserPracticeSutAdapter } from './model/browserSutAdapter';
import { GeneralBfsExplorer } from './model/generalBfsExplorer';
import { setupJourneyApiMocks, DEFAULT_MOCK_ACCOUNT } from './fixtures/mockApi';

const TEST_PUZZLE = {
  puzzleId: 'gen-puz-101',
  source: 'weakness' as const,
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  playerColor: 'WHITE' as const,
  targetMove: 'd4',
  openingTitle: 'Ruy Lopez General Exploration Test',
  acceptableMoves: ['d4', 'Bc4', 'Bb5'],
  movesPlayed: ['e4', 'e5', 'Nf3', 'Nc6'],
  priority: 1,
  timesReached: 10,
  mistakeCount: 3,
  mistakeRate: 30,
  gameUrls: [],
  evalCp: 30,
};

test.describe('General Model-Based Defect-Detection Engine (Issue #790)', () => {
  test.beforeEach(async ({ page }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: [TEST_PUZZLE],
    });
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();
  });

  test('executes unscripted BFS action sequences against live application and verifies all general invariants', async ({
    page,
  }) => {
    test.setTimeout(240_000);
    const sut = new PlaywrightBrowserPracticeSutAdapter(page, {
      puzzleId: TEST_PUZZLE.puzzleId,
      entryFen: TEST_PUZZLE.fen,
      boardFen: TEST_PUZZLE.fen,
    });

    const explorer = new GeneralBfsExplorer();

    // Generate action sequences independently of historical bug register
    const generatedSequences = explorer.generateBfsActionSequences(
      'INITIAL',
      {
        puzzleId: TEST_PUZZLE.puzzleId,
        entryFen: TEST_PUZZLE.fen,
        boardFen: TEST_PUZZLE.fen,
        attemptCount: 0,
        currentView: 'PRACTICE',
        inExploration: false,
        inChallengeMode: false,
        inBlindfold: false,
        solved: false,
      },
      3
    );

    expect(generatedSequences.length).toBeGreaterThan(0);

    const report = await explorer.exploreLiveApplication(sut, generatedSequences, {
      puzzleId: TEST_PUZZLE.puzzleId,
      entryFen: TEST_PUZZLE.fen,
      boardFen: TEST_PUZZLE.fen,
    });
    console.log('Report result paths executed:', report.totalPathsExecuted);
    console.log('Discovered defects:', JSON.stringify(report.discoveredDefects, null, 2));

    // Verification: Zero invariant violations on unmutated clean baseline
    expect(report.discoveredDefects.length).toBe(0);
    expect(report.totalPathsExecuted).toBe(generatedSequences.length);
    expect(report.invariantsAsserted).toBeGreaterThan(0);

    for (const p of report.paths) {
      expect(p.finalObservedState.currentView).toBe('PRACTICE');
      expect(p.finalObservedState.activePuzzle?.id).toBe(TEST_PUZZLE.puzzleId);
      expect(p.finalObservedState.boardFen).toBeTruthy();
    }
  });
});
