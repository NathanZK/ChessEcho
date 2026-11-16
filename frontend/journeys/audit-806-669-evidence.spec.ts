import { test, expect } from '@playwright/test';
import path from 'path';
import { setupJourneyApiMocks } from './fixtures/mockApi';

const EVIDENCE_DIR = path.resolve(__dirname, '../../docs/engineering/evidence/audit-806-669');

const MOCK_GAMES = [
  {
    id: 'game-101',
    platformGameId: 'chesscom-101',
    timeControl: 'RAPID',
    playedAt: '2026-11-01T12:00:00Z',
    result: 'win',
    whiteUsername: 'carlsen',
    blackUsername: 'nakamura',
    chessComUrl: 'https://www.chess.com/game/live/101',
    pgn: '1. e4 e5 2. Nf3 Nc6 3. Bb5 a6',
  },
  {
    id: 'game-102',
    platformGameId: 'chesscom-102',
    timeControl: 'BLITZ',
    playedAt: '2026-11-02T15:30:00Z',
    result: 'loss',
    whiteUsername: 'carlsen',
    blackUsername: 'GrandmasterBlitz',
    chessComUrl: 'https://www.chess.com/game/live/102',
    pgn: '1. d4 d5 2. c4 c6',
  },
  {
    id: 'game-103',
    platformGameId: 'chesscom-103',
    timeControl: 'BULLET',
    playedAt: '2026-11-03T18:45:00Z',
    result: 'win',
    whiteUsername: 'BulletWizard',
    blackUsername: 'carlsen',
    chessComUrl: 'https://www.chess.com/game/live/103',
    pgn: '1. e4 c5 2. Nf3 d6',
  },
  {
    id: 'game-104',
    platformGameId: 'chesscom-104',
    timeControl: 'CLASSICAL',
    playedAt: '2026-11-04T09:00:00Z',
    result: 'draw',
    whiteUsername: 'carlsen',
    blackUsername: 'DeepMind',
    chessComUrl: null,
    pgn: '1. e4 e6 2. d4 d5',
  },
];

const MOCK_GAME_ANALYSIS = {
  gameId: 'game-101',
  whiteUsername: 'carlsen',
  blackUsername: 'nakamura',
  timeControl: 'RAPID',
  playedAt: '2026-11-01T12:00:00Z',
  result: 'win',
  analysisStatus: 'COMPLETE',
  plies: [
    {
      plyNumber: 5,
      movePlayed: 'Bc4',
      playerColor: 'WHITE',
      preMoveFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
      postMoveFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      bestMove: 'Bb5',
      evalCp: 40,
      bestEvalCp: 180,
      evalLoss: 1.4,
      weaknessSeverity: 'SIGNIFICANT',
      decisionTimeMs: 14500,
      decisionSeverity: 'SIGNIFICANT',
      hasEvaluation: true,
      hasTiming: true,
    },
    {
      plyNumber: 9,
      movePlayed: 'd3??',
      playerColor: 'WHITE',
      preMoveFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4',
      postMoveFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R b KQkq - 0 4',
      bestMove: 'O-O',
      evalCp: -160,
      bestEvalCp: 60,
      evalLoss: 2.2,
      weaknessSeverity: 'CRITICAL',
      decisionTimeMs: 25000,
      decisionSeverity: 'CRITICAL',
      hasEvaluation: true,
      hasTiming: true,
    },
  ],
};

const MOCK_STRUCTURAL_SIMILAR = [
  {
    rank: 1,
    score: 0.94,
    staticScore: 0.94,
    dynamicScore: 0.0,
    positionId: 'sim-struct-1',
    fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R b KQkq - 5 4',
    canonicalFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R b KQkq -',
    searchStatus: 'COMPLETED',
    achievedDepth: 16,
    principalVariations: [{ rank: 1, moves: ['Bc5'], achievedDepth: 16 }],
    occurrences: [
      {
        id: 'occ-struct-1',
        platformGameId: 'other-game-801',
        playedAt: '2026-10-14T10:00:00Z',
        plyNumber: 8,
        playedMove: 'Bc5',
        playerColor: 'BLACK',
        opponentUsername: 'GrandmasterA',
        timeControl: 'blitz',
        gameResult: 'win',
        chessComUrl: null,
      },
    ],
  },
  {
    rank: 2,
    score: 0.91,
    staticScore: 0.91,
    dynamicScore: 0.0,
    positionId: 'sim-struct-2',
    fen: 'r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5',
    canonicalFen: 'r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq -',
    searchStatus: 'COMPLETED',
    achievedDepth: 16,
    principalVariations: [{ rank: 1, moves: ['d3'], achievedDepth: 16 }],
    occurrences: [
      {
        id: 'occ-struct-2',
        platformGameId: 'other-game-802',
        playedAt: '2026-10-18T14:30:00Z',
        plyNumber: 10,
        playedMove: 'd3',
        playerColor: 'WHITE',
        opponentUsername: 'TacticianB',
        timeControl: 'rapid',
        gameResult: 'loss',
        chessComUrl: null,
      },
    ],
  },
];

const MOCK_DYNAMIC_SIMILAR = [
  {
    rank: 1,
    score: 0.89,
    staticScore: 0.0,
    dynamicScore: 0.89,
    positionId: 'sim-dyn-1',
    fen: 'r2qk2r/ppp2ppp/2n1bn2/3pp3/2B1P3/2NP1N2/PPP2PPP/R1BQK2R w KQkq - 1 6',
    canonicalFen: 'r2qk2r/ppp2ppp/2n1bn2/3pp3/2B1P3/2NP1N2/PPP2PPP/R1BQK2R w KQkq -',
    searchStatus: 'COMPLETED',
    achievedDepth: 16,
    principalVariations: [{ rank: 1, moves: ['exd5'], achievedDepth: 16 }],
    occurrences: [
      {
        id: 'occ-dyn-1',
        platformGameId: 'other-game-901',
        playedAt: '2026-10-25T16:00:00Z',
        plyNumber: 12,
        playedMove: 'exd5',
        playerColor: 'WHITE',
        opponentUsername: 'DynamicMaster',
        timeControl: 'blitz',
        gameResult: 'win',
        chessComUrl: null,
      },
    ],
  },
];

test.describe('Audit #806 & #669: Playwright Multi-Viewport Evidence Capture', () => {
  test('desktop 1440px: full journey with populated list, filters, mistake detail, similarity, and practice', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      games: MOCK_GAMES,
      gameAnalysis: MOCK_GAME_ANALYSIS,
      similarPositions: MOCK_STRUCTURAL_SIMILAR,
    });

    // 1. My Games Populated View
    await page.goto('/#game-analysis', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'My Games' })).toBeVisible();
    await expect(page.getByText('nakamura')).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '01-my-games-populated-desktop-1440.png'),
    });

    // 2. Filter chips selection (Blitz)
    const blitzChip = page.getByRole('button', { name: /^Blitz$/i });
    await blitzChip.click();
    await expect(page.getByText('GrandmasterBlitz')).toBeVisible();
    await expect(page.getByText('nakamura')).not.toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '02-my-games-filter-blitz-desktop-1440.png'),
    });

    // Reset filter to All and open analysis
    const allChip = page.getByRole('button', { name: /^All$/i });
    await allChip.click();
    await expect(page.getByText('nakamura')).toBeVisible();

    const analyzeBtn = page.getByRole('button', { name: /Analyze/i }).first();
    await analyzeBtn.click();

    // 3. Analysis Dashboard & Threshold Configuration
    await expect(page.getByText(/Back to My Games/i)).toBeVisible();
    await expect(page.getByText('Qualifying Mistake Moves')).toBeVisible();
    await expect(page.getByLabel(/Eval Loss Threshold/i)).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '03-analysis-dashboard-detail-desktop-1440.png'),
    });

    // 4. Expand Structural Similarities
    const structuralBtn = page.getByRole('button', { name: /Structural Similarities/i });
    await expect(structuralBtn).toBeVisible();
    await structuralBtn.click();

    await expect(page.getByText('GrandmasterA')).toBeVisible();
    await expect(page.getByText('TacticianB')).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '04-similarity-structural-desktop-1440.png'),
    });

    // 5. Expand Dynamic Similarities independently
    // Setup route to return dynamic candidates
    await page.route('**/api/positions/similar*mode=DYNAMIC*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          queryFen: MOCK_GAME_ANALYSIS.plies[0].preMoveFen,
          candidatesEvaluated: 40,
          shortlistedCandidatesCount: MOCK_DYNAMIC_SIMILAR.length,
          engineCompletedCount: MOCK_DYNAMIC_SIMILAR.length,
          dynamicSimilarityApplied: true,
          fallbackReason: null,
          queryStatus: 'COMPLETED',
          queryAchievedDepth: 16,
          queryPrincipalVariations: [],
          results: MOCK_DYNAMIC_SIMILAR,
        }),
      });
    });

    const dynamicBtn = page.getByRole('button', { name: /Dynamic Similarities/i });
    await dynamicBtn.click();
    await expect(page.getByText('DynamicMaster')).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '05-similarity-dynamic-desktop-1440.png'),
    });

    // 6. Transition to Practice from Similar Position
    const practiceBtn = page.getByRole('button', { name: /^Practice$/i }).first();
    await practiceBtn.click();

    await expect(page).toHaveURL(/#puzzles/);
    await expect(page.locator('[data-testid="practice-board-area"]')).toBeVisible();
    await expect(page.getByRole('button', { name: /Back to Game Analysis/i })).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '06-practice-transition-desktop-1440.png'),
    });

    // 7. Return to Game Analysis and verify restored context
    const backToAnalysisBtn = page.getByRole('button', { name: /Back to Game Analysis/i });
    await backToAnalysisBtn.click();

    await expect(page).toHaveURL(/#game-analysis/);
    await expect(page.getByText(/Back to My Games/i)).toBeVisible();
    await expect(page.getByText('GrandmasterA')).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '07-practice-return-restored-desktop-1440.png'),
    });
  });

  test('mobile 390px: My Games list and analysis dashboard', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      games: MOCK_GAMES,
      gameAnalysis: MOCK_GAME_ANALYSIS,
      similarPositions: MOCK_STRUCTURAL_SIMILAR,
    });

    await page.goto('/#game-analysis', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'My Games' })).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '01-my-games-populated-mobile-390.png'),
    });

    // Open analysis
    const analyzeBtn = page.getByRole('button', { name: /Analyze/i }).first();
    await analyzeBtn.click();

    await expect(page.getByText(/Back to My Games/i)).toBeVisible();
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '03-analysis-dashboard-detail-mobile-390.png'),
    });
  });

  test('desktop 1440px: empty state with filter-specific Import CTA', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });

    await setupJourneyApiMocks(page, {
      authenticated: true,
      games: [],
    });

    await page.goto('/#game-analysis', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'My Games' })).toBeVisible();
    await expect(page.getByText('No games found')).toBeVisible();

    await page.screenshot({
      path: path.join(EVIDENCE_DIR, '02-my-games-empty-state-desktop-1440.png'),
    });
  });
});
