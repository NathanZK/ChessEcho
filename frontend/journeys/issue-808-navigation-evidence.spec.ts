import { test, expect } from '@playwright/test';
import path from 'path';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_WEAKNESS,
  DEFAULT_MOCK_RECURRING_WEAKNESS,
  DEFAULT_MOCK_DYNAMIC_RECURRING_WEAKNESS,
  DEFAULT_MOCK_CLUSTER_POSITION,
  DEFAULT_MOCK_GAME,
} from './fixtures/mockApi';

// Evidence captures are test outputs, not source. Default to the gitignored
// Playwright output directory so running the journey suite never rewrites
// tracked files; EVIDENCE_DIR can point at a documentation directory when a
// reviewer asks for committed evidence.
const EVIDENCE_DIR = process.env.EVIDENCE_DIR
  ? path.resolve(process.env.EVIDENCE_DIR)
  : path.resolve(__dirname, '../test-results/evidence/issue-808');

test.describe('Issue #808 Multimodal Desktop Runtime Visual Evidence Capture', () => {
  test.use({
    viewport: { width: 1440, height: 900 },
  });

  test('captures complete 10-artifact desktop runtime evidence suite', async ({ page }) => {
    const clusterPositions = [
      DEFAULT_MOCK_CLUSTER_POSITION,
      {
        ...DEFAULT_MOCK_CLUSTER_POSITION,
        positionId: 'pos-rec-2',
        fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
        targetMove: 'Nc6',
        movePlayed: 'f5',
        evalLoss: 1.4,
        moveNumber: 2,
        chessComUrl: 'https://www.chess.com/game/live/101?move=4',
      },
    ];

    // 1. Setup mocked API boundary
    await setupJourneyApiMocks(page, {
      authenticated: true,
      weaknesses: [DEFAULT_MOCK_WEAKNESS],
      recurringWeaknesses: [DEFAULT_MOCK_RECURRING_WEAKNESS, DEFAULT_MOCK_DYNAMIC_RECURRING_WEAKNESS],
      clusterPositions,
      games: [
        {
          ...DEFAULT_MOCK_GAME,
          id: 'game-evidence-1',
          platformGameId: '12345678',
          timeControl: 'RAPID',
        },
      ],
    });

    // 2. Load application at #weaknesses
    await page.goto('/#weaknesses', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('header')).toBeVisible();

    // Verify all 4 primary desktop tabs and dropdown are present in header
    const weaknessTabBtn = page.getByRole('button', { name: /Weakness/i }).first();
    const puzzlesTab = page.getByRole('button', { name: /Practice Puzzles/i }).first();
    const myGamesTab = page.getByRole('button', { name: /My Games/i }).first();
    const importTab = page.getByRole('button', { name: /Import Games/i }).first();
    const exploreDropdown = page.getByRole('button', { name: /Explore \/ Tools/i }).first();

    await expect(weaknessTabBtn).toBeVisible();
    await expect(puzzlesTab).toBeVisible();
    await expect(myGamesTab).toBeVisible();
    await expect(importTab).toBeVisible();
    await expect(exploreDropdown).toBeVisible();

    await weaknessTabBtn.click();
    await expect(page.getByText('Select Cluster Discovery Mode')).toBeVisible();

    // Evidence 01: Weakness Library landing state before fetching
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '01-weakness-library-landing-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 02: Weakness Library showing Structural Clusters after fetch
    const structuralBtn = page.getByRole('button', { name: /Structural Weakness Clusters/i });
    await structuralBtn.click();
    await expect(page.getByTestId('weakness-cluster-container')).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '02-weakness-structural-clusters-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 03: Weakness Library showing Dynamic Clusters after fetch
    const dynamicBtn = page.getByRole('button', { name: /Dynamic Weakness Clusters/i });
    await dynamicBtn.click();
    await expect(page.getByTestId('weakness-cluster-container')).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '03-weakness-dynamic-clusters-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 04: Expanded cluster member positions with individual Practice actions
    const inspectBtn = page.getByRole('button', { name: /Inspect Evidence/i }).first();
    await inspectBtn.click();
    const modal = page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await expect(page.getByText('Cluster Evidence Inspection')).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '04-expanded-cluster-member-positions-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 05: Individual position practice shell
    const practiceMemberBtn = modal.getByRole('button', { name: /Practice Position/i }).first();
    await practiceMemberBtn.click();
    await expect(page).toHaveURL(/#puzzles/);
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '05-individual-position-practice-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 06: Return to Weakness Library restoring originating cluster context
    const backBtn = page.getByRole('button', { name: /Back to Weaknesses/i });
    await expect(backBtn).toBeVisible();
    await backBtn.click();
    await expect(page).toHaveURL(/#weaknesses/);
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '06-return-expanded-cluster-context-desktop-1440.png'),
      fullPage: false,
    });

    // Close the evidence modal
    const closeDialogBtn = page.getByRole('button', { name: /Close dialog/i });
    await closeDialogBtn.click();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // Evidence 07: Explore / Tools dropdown open in desktop header
    const toolsDropdownBtn = page.getByRole('button', { name: /^Explore \/ Tools$/i });
    await toolsDropdownBtn.click();
    const dropdownMenu = page.getByRole('menu', { name: /Explore and tools/i });
    await expect(dropdownMenu).toBeVisible();
    await expect(page.getByRole('menuitem', { name: /Explore a position/i })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: /^Exact-Position Analysis$/i })).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '07-explore-tools-dropdown-open-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 08: Exact-Position Analysis with scoped filters bar
    const exactPositionAnalysisItem = page.getByRole('menuitem', { name: /^Exact-Position Analysis$/i });
    await exactPositionAnalysisItem.click();
    await expect(page).toHaveURL(/#weakness-analysis/);
    await expect(page.getByRole('heading', { name: /Exact-Position Analysis/i })).toBeVisible();
    await expect(page.getByText('Filters:')).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '08-exact-position-analysis-scoped-filters-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 09: Primary navigation reordered (Import Games immediately right of My Games)
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '09-primary-navigation-reordered-desktop-1440.png'),
      fullPage: false,
    });

    // Evidence 10: My Games row showing exact-ply link button (?move=0)
    await myGamesTab.click();
    await expect(page).toHaveURL(/#game-analysis/);
    await expect(page.getByRole('heading', { name: 'My Games' })).toBeVisible();

    const chessComLink = page.getByRole('link', { name: /View on Chess\.com/i }).first();
    await expect(chessComLink).toBeVisible();
    await expect(chessComLink).toHaveAttribute(
      'href',
      'https://www.chess.com/game/live/12345678?move=0'
    );
    await expect(chessComLink).toHaveAttribute('target', '_blank');

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '10-my-games-exact-ply-link-desktop-1440.png'),
      fullPage: false,
    });
  });
});
