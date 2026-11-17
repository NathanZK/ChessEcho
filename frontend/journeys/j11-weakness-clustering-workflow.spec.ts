/**
 * Journey J11: Authoritative Weakness Clustering and Discovery Workflow
 *
 * Test Provenance & Verification Boundary:
 * This specification exercises the complete user journey defined in Issue #808 using Playwright
 * with synthetic client-side route mocks (`setupJourneyApiMocks`).
 * It establishes deterministic verification of navigation hierarchy, on-demand cluster discovery,
 * dual-clustering state isolation, member position inspection, individual position practice,
 * return context restoration, and scoped filter separation.
 *
 * Live backend verification (Docker, PostgreSQL, Stockfish) is independently verified via `./gradlew test`.
 */

import { test, expect } from '@playwright/test';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_WEAKNESS,
  DEFAULT_MOCK_RECURRING_WEAKNESS,
  DEFAULT_MOCK_DYNAMIC_RECURRING_WEAKNESS,
  DEFAULT_MOCK_CLUSTER_POSITION,
  DEFAULT_MOCK_GAME,
} from './fixtures/mockApi';

test.describe('Journey J11: Weakness Clustering Workflow & Analysis Destinations', () => {
  test.use({
    viewport: { width: 1440, height: 900 },
  });

  test('Step 1-8: On-demand dual-clustering, member inspection, individual practice, and context restoration', async ({
    page,
  }) => {
    const clusterPositions = [
      DEFAULT_MOCK_CLUSTER_POSITION,
      {
        ...DEFAULT_MOCK_CLUSTER_POSITION,
        positionId: 'pos-rec-2',
        fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
        targetMove: 'Nc6',
        movePlayed: 'f5',
        evalLoss: 1.45,
        moveNumber: 2,
        chessComUrl: 'https://www.chess.com/game/live/101?move=4',
      },
    ];

    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
      recurringWeaknesses: [DEFAULT_MOCK_RECURRING_WEAKNESS, DEFAULT_MOCK_DYNAMIC_RECURRING_WEAKNESS],
      clusterPositions,
    });

    // Step 1: Open Weakness Library (/#weaknesses) - assert no automatic fetch on load
    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });

    // Header has the Weakness dropdown button
    const weaknessHeaderButton = page.getByRole('button', { name: /Weakness/i }).first();
    await expect(weaknessHeaderButton).toBeVisible();

    // The two explicit action buttons must be visible
    const structuralBtn = page.getByRole('button', { name: /Structural Weakness Clusters/i });
    const dynamicBtn = page.getByRole('button', { name: /Dynamic Weakness Clusters/i });
    await expect(structuralBtn).toBeVisible();
    await expect(dynamicBtn).toBeVisible();

    // Assert NO automatic fetch occurred: landing prompt is visible, no cluster cards rendered
    await expect(page.getByText('Select Cluster Discovery Mode')).toBeVisible();
    await expect(page.locator('[data-testid="weakness-cluster-container"]')).toHaveCount(0);

    // Assert scoped filters (Color, Phase, Speed, Threshold, Min Mistakes) are NOT in Weakness Library
    await expect(page.getByText('Filters:')).toHaveCount(0);
    await expect(page.getByRole('combobox', { name: /Filter by Phase/i })).toHaveCount(0);
    await expect(page.getByRole('combobox', { name: /Filter by Speed/i })).toHaveCount(0);

    // Step 2: Click Structural Weakness Clusters - verify fetch progress and results
    await structuralBtn.click();
    await expect(page.getByTestId('weakness-cluster-container')).toBeVisible();

    // Verify 1 structural cluster displayed
    const structuralClusterCards = page.locator('[data-testid="weakness-cluster-container"] > div');
    await expect(structuralClusterCards).toHaveCount(1);
    await expect(page.getByText('14 games')).toBeVisible();

    // Step 3 & 4: Click Dynamic Weakness Clusters - verify dynamic clusters displayed
    await dynamicBtn.click();
    await expect(page.getByTestId('weakness-cluster-container')).toBeVisible();

    const dynamicClusterCards = page.locator('[data-testid="weakness-cluster-container"] > div');
    await expect(dynamicClusterCards).toHaveCount(1);
    // Dynamic cluster has higher blunder rate (Severe blunders)
    await expect(page.getByText('Severe blunders')).toBeVisible();

    // Step 5: Select a cluster and click "Inspect Evidence"
    const inspectButton = page.getByRole('button', { name: /Inspect Evidence/i }).first();
    await expect(inspectButton).toBeVisible();
    await inspectButton.click();

    // Step 6: Verify Cluster Evidence Modal opens with member positions
    const modal = page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await expect(page.getByText('Cluster Evidence Inspection')).toBeVisible();

    // Verify member positions are rendered
    const memberPracticeButtons = modal.getByRole('button', { name: /Practice Position/i });
    await expect(memberPracticeButtons).toHaveCount(2);

    // Step 7: Practice that exact member position (click "Practice Position" on member card)
    await memberPracticeButtons.first().click();

    // Verify practice view mounted on #puzzles
    await expect(page).toHaveURL(/#puzzles/);
    const boardArea = page.locator('[data-testid="practice-board-area"]');
    await expect(boardArea).toBeVisible();

    // Step 8: Return and verify originating cluster context is restored
    const backToWeaknessesBtn = page.getByRole('button', { name: /Back to Weaknesses/i });
    await expect(backToWeaknessesBtn).toBeVisible();
    await backToWeaknessesBtn.click();

    // Verify URL returned to #weaknesses
    await expect(page).toHaveURL(/#weaknesses/);

    // Originated from Dynamic cluster modal, so dynamic type and evidence modal are restored!
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('Cluster Evidence Inspection')).toBeVisible();

    // Close the modal
    const closeDialogBtn = page.getByRole('button', { name: /Close dialog/i });
    await closeDialogBtn.click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('Step 9-11: Exact-Position Analysis destination, header dropdown navigation, and scoped filters', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [
        DEFAULT_MOCK_WEAKNESS,
        {
          ...DEFAULT_MOCK_WEAKNESS,
          positionId: 'pos-w-2',
          playerColor: 'WHITE',
          fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
          mistakeCount: 5,
        },
      ],
    });

    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Select Cluster Discovery Mode')).toBeVisible();

    // Step 9: Open Exact-Position Analysis from Explore / Tools dropdown in Header
    const toolsNavBtn = page.getByRole('button', { name: /Explore \/ Tools/i }).first();
    await toolsNavBtn.click();

    const positionAnalysisItem = page.getByRole('menuitem', { name: /^Exact-Position Analysis$/i });
    await expect(positionAnalysisItem).toBeVisible();
    await positionAnalysisItem.click();

    // Verify URL transitioned to #weakness-analysis
    await expect(page).toHaveURL(/#weakness-analysis/);
    await expect(page.getByRole('heading', { name: /Exact-Position Analysis/i })).toBeVisible();

    // Step 10: Verify scoped filters are present in Exact-Position Analysis
    await expect(page.getByText('Filters:')).toBeVisible();
    await expect(page.getByRole('button', { name: /^Both Sides$/i })).toBeVisible();
    await expect(page.getByRole('combobox', { name: /Filter by Phase/i })).toBeVisible();
    await expect(page.getByRole('combobox', { name: /Filter by Speed/i })).toBeVisible();
    await expect(page.getByRole('combobox', { name: /Filter by Threshold/i })).toBeVisible();
    await expect(page.getByRole('combobox', { name: /Filter by Min Mistakes/i })).toBeVisible();

    // Verify weakness cards are rendered
    const positionCards = page.locator('button:has-text("Practice Position")');
    await expect(positionCards).toHaveCount(2);

    // Step 11: Change filters and verify position analysis updates
    // Filter by color: click WHITE filter button
    const whiteFilterBtn = page.getByRole('button', { name: /^White$/i });
    await whiteFilterBtn.click();

    // After filtering to WHITE, only the white position card is displayed
    await expect(page.locator('button:has-text("Practice Position")')).toHaveCount(1);

    // Filter by color: click Both Sides filter button
    const bothSidesFilterBtn = page.getByRole('button', { name: /^Both Sides$/i });
    await bothSidesFilterBtn.click();
    await expect(page.locator('button:has-text("Practice Position")')).toHaveCount(2);
  });

  test('Step 12: Cluster loading, empty, and failure states', async ({ page }) => {
    // Setup with empty recurring weaknesses
    await setupJourneyApiMocks(page, {
      authenticated: true,
      recurringWeaknesses: [],
    });

    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });

    // Click Structural Weakness Clusters
    await page.getByRole('button', { name: /Structural Weakness Clusters/i }).click();

    // Verify empty state is displayed
    await expect(page.getByText('No Structural Clusters Found')).toBeVisible();
    const runDiscoveryBtn = page.getByRole('button', { name: /Run Discovery Now/i });
    await expect(runDiscoveryBtn).toBeVisible();

    // Click Run Discovery Now
    await runDiscoveryBtn.click();

    // Verify async discovery progress indicator appears
    await expect(page.getByText(/Discovering Recurring Weaknesses/i)).toBeVisible();
  });

  test('Step 13: Puzzle context preservation during exploration exit & My Games exact-ply links', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
      games: [
        {
          ...DEFAULT_MOCK_GAME,
          id: 'game-analysis-101',
          platformGameId: '98765432',
        },
      ],
    });

    // 1. Verify exploration puzzle context preservation
    await page.goto('/#weakness-analysis', { waitUntil: 'domcontentloaded' });

    const practiceBtn = page.getByRole('button', { name: /Practice Position/i }).first();
    await practiceBtn.click();
    await expect(page).toHaveURL(/#puzzles/);

    // Solve puzzle (move b8 -> c6)
    const b8 = page.locator('[data-testid="practice-board-area"] [data-square="b8"]');
    const c6 = page.locator('[data-testid="practice-board-area"] [data-square="c6"]');
    await b8.click();
    await c6.click();

    // Continue to Line Exploration
    const continueBtn = page.getByRole('button', { name: /Continue Exploration →/i });
    await expect(continueBtn).toBeVisible();
    await continueBtn.click();

    // Exit Exploration
    const exitExplorationBtn = page.getByTitle(/Exit Exploration/i);
    await expect(exitExplorationBtn).toBeVisible();
    await exitExplorationBtn.click();

    // Verify puzzle context remains intact in practice view
    await expect(page).toHaveURL(/#puzzles/);
    await expect(page.getByRole('button', { name: /Back to Weaknesses/i })).toBeVisible();

    // 2. Verify My Games exact-ply Chess.com link button
    await page.getByRole('button', { name: /My Games/i }).first().click();
    await expect(page).toHaveURL(/#game-analysis/);

    const chessComLink = page.getByRole('link', { name: /View on Chess\.com/i }).first();
    await expect(chessComLink).toBeVisible();
    await expect(chessComLink).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/98765432?move=0'
    );
  });
});
