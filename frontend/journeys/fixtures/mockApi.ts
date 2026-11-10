import type { Page } from '@playwright/test';

export interface MockOptions {
  authenticated?: boolean;
  user?: { userId: string; email: string };
  accounts?: Array<{ id: string; platform: string; username: string }>;
  weaknesses?: Array<Record<string, unknown>>;
  puzzles?: Array<Record<string, unknown>>;
  recurringWeaknesses?: Array<Record<string, unknown>>;
  clusterPositions?: Array<Record<string, unknown>>;
  longDecisions?: Array<Record<string, unknown>>;
  similarPositions?: Array<Record<string, unknown>>;
  games?: Array<Record<string, unknown>>;
  gameAnalysis?: Record<string, unknown>;
  importConflict?: boolean;
  jobStatusSequence?: Array<Record<string, unknown>>;
  discoveryJob?: Record<string, unknown>;
}

export const DEFAULT_MOCK_ACCOUNT = {
  id: '550e8400-e29b-41d4-a716-446655440010',
  platform: 'CHESS_COM',
  username: 'carlsen',
};

export const DEFAULT_MOCK_WEAKNESS = {
  positionId: 'pos-w-1',
  playerColor: 'BLACK',
  fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
  timesReached: 12,
  mistakeCount: 4,
  mistakeRate: 33.3,
  averageLoss: 1.25,
  priority: 4.5,
  bestMove: 'Nc6',
  acceptableMoves: [{ move: 'Nf6', evalLoss: 0.1 }],
  movesPlayed: [
    { move: 'Bc5', timesPlayed: 3, averageLoss: 1.3 },
    { move: 'f5', timesPlayed: 1, averageLoss: 1.1 },
  ],
  gameUrls: ['https://www.chess.com/game/live/10001'],
  evalCp: 30,
};

export const DEFAULT_MOCK_PUZZLE = {
  puzzleId: 'puzzle-1',
  source: 'puzzle',
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  playerColor: 'WHITE',
  targetMove: 'd4',
  openingTitle: 'Ruy Lopez / Italian Game',
  acceptableMoves: [{ move: 'Bc4', evalLoss: 0.05 }],
  movesPlayed: [
    { move: 'h4', timesPlayed: 2, averageLoss: 2.1 },
  ],
  priority: 1.0,
  timesReached: 5,
  mistakeCount: 1,
  mistakeRate: 20.0,
  gameUrls: [],
  evalCp: 40,
};

export const DEFAULT_MOCK_LONG_DECISION = {
  id: '6ba7b810-9dad-11d1-80b4-00c04fd430c8',
  positionId: '6ba7b811-9dad-11d1-80b4-00c04fd430c8',
  fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
  playerColor: 'BLACK' as const,
  plyNumber: 4,
  movePlayed: 'Nf6',
  decisionTimeMs: 15400,
  timeControl: 'RAPID' as const,
  platformGameId: 'chesscom-1001',
  playedAt: '2026-11-01T12:00:00Z',
  opponentUsername: 'opp-grandmaster',
  chessComUrl: 'https://www.chess.com/game/live/1001',
  severity: 'CRITICAL' as const,
};

export const DEFAULT_MOCK_SIMILAR_POSITION = {
  rank: 1,
  score: 0.95,
  staticScore: 0.90,
  dynamicScore: 0.98,
  positionId: '6ba7b812-9dad-11d1-80b4-00c04fd430c8',
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  canonicalFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  searchStatus: 'COMPLETED',
  achievedDepth: 16,
  principalVariations: [],
  occurrences: [
    {
      gameId: 'game-sim-1',
      platformGameId: 'chesscom-sim-1',
      playedAt: '2026-11-01T12:00:00Z',
      plyNumber: 5,
      movePlayed: 'Bb5',
      playerColor: 'WHITE',
    },
  ],
};

export const DEFAULT_MOCK_GAME = {
  id: 'game-101',
  platformGameId: 'chesscom-101',
  timeControl: 'RAPID',
  playedAt: '2026-11-01T12:00:00Z',
  result: 'win',
  whiteUsername: 'carlsen',
  blackUsername: 'nakamura',
  pgn: '1. e4 e5 2. Nf3 Nc6 3. Bb5 a6',
};

export const DEFAULT_MOCK_GAME_ANALYSIS = {
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
      evalLoss: 1.25,
      mistakeSeverity: 'MINOR',
      decisionTimeMs: 12000,
      decisionSeverity: 'NO_CONCERN',
      hasEvaluation: true,
      hasTiming: true,
    },
  ],
};

export const DEFAULT_MOCK_RECURRING_WEAKNESS = {
  clusterId: 'cluster-uuid-1',
  clusterType: 'STRUCTURAL',
  recommendationPriority: 0.85,
  representativeFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  memberPositionCount: 4,
  totalOccurrences: 18,
  distinctGamesCount: 14,
  mistakeCount: 10,
  mistakeFrequency: 0.55,
  blunderRate: 0.3,
  evaluatedOccurrences: 18,
  unevaluatedOccurrences: 0,
  evalLossDistribution: { '0.8-1.5': 6, '1.5-3.0': 4 },
  evalLossPercentages: { '0.8-1.5': 60, '1.5-3.0': 40 },
  timingProfile: { FAST: 2, NORMAL: 9, SLOW: 7 },
  formatBreakdown: { RAPID: 10, BLITZ: 8 },
};

export const DEFAULT_MOCK_DYNAMIC_RECURRING_WEAKNESS = {
  ...DEFAULT_MOCK_RECURRING_WEAKNESS,
  clusterId: 'cluster-uuid-2',
  clusterType: 'DYNAMIC',
  mistakeFrequency: 0.65,
  blunderRate: 0.4,
};

export const DEFAULT_MOCK_CLUSTER_POSITION = {
  positionId: 'pos-rec-1',
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  playerColor: 'WHITE',
  movePlayed: 'Bc4',
  targetMove: 'Bb5',
  acceptableMoves: [{ move: 'Bb5', evalLoss: 0.0 }],
  evalLoss: 0.85,
  mistakeSeverity: 'MINOR',
  decisionTimeMs: 3200,
  decisionSeverity: 'NO_CONCERN',
  timeControl: 'RAPID',
  gameId: 'game-rec-1',
  platformGameId: 'chesscom_rec_1',
  plyNumber: 5,
  moveNumber: 3,
};

export interface JourneyMockHandle {
  setAuthenticated: (val: boolean) => void;
  getPollCount: () => number;
  getDiscoveryPollCount: () => number;
}

export async function setupJourneyApiMocks(
  page: Page,
  options: MockOptions = {}
): Promise<JourneyMockHandle> {
  const {
    authenticated = false,
    user = { userId: 'user-1', email: 'player@example.com' },
    accounts = authenticated ? [DEFAULT_MOCK_ACCOUNT] : [],
    weaknesses = [DEFAULT_MOCK_WEAKNESS],
    puzzles = [DEFAULT_MOCK_PUZZLE],
    recurringWeaknesses = [],
    clusterPositions = [DEFAULT_MOCK_CLUSTER_POSITION],
    longDecisions = [DEFAULT_MOCK_LONG_DECISION],
    similarPositions = [DEFAULT_MOCK_SIMILAR_POSITION],
    games = [DEFAULT_MOCK_GAME],
    gameAnalysis = DEFAULT_MOCK_GAME_ANALYSIS,
    importConflict = false,
    jobStatusSequence,
    discoveryJob,
  } = options;

  let currentAuthenticated = authenticated;
  let currentUser = { ...user };
  const currentAccounts = [...accounts];

  try {
    await page.context().addCookies([
      {
        name: 'XSRF-TOKEN',
        value: 'mock-csrf-token',
        domain: 'localhost',
        path: '/',
      },
    ]);
  } catch {
    // Context may already be initialized or handled
  }

  const checkCsrf = async (route: import('@playwright/test').Route): Promise<boolean> => {
    const token = route.request().headers()['x-xsrf-token'];
    if (token !== 'mock-csrf-token') {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'CSRF_VALIDATION_FAILED' }),
      });
      return false;
    }
    return true;
  };

  let attemptCount = { solvedCount: 0, failedCount: 0 };

  await page.route('**/api/me', async (route) => {
    if (currentAuthenticated) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(currentUser),
      });
    } else {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Unauthorized' }),
      });
    }
  });

  await page.route('**/api/accounts', async (route) => {
    if (!currentAuthenticated) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(currentAccounts),
    });
  });

  await page.route('**/api/register', async (route) => {
    if (!(await checkCsrf(route))) return;
    let data: { email?: string; password?: string } = {};
    try {
      data = route.request().postDataJSON() || {};
    } catch {
      // ignore
    }
    if (!data.email || !data.password || data.password.length < 8) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Password must be at least 8 characters' }),
      });
      return;
    }
    currentAuthenticated = true;
    currentUser = { userId: user.userId, email: data.email };
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(currentUser),
    });
  });

  await page.route('**/api/login', async (route) => {
    if (!(await checkCsrf(route))) return;
    let data: { email?: string; password?: string } = {};
    try {
      data = route.request().postDataJSON() || {};
    } catch {
      // ignore
    }
    if (data.email === 'invalid@example.com' || data.password === 'wrongpass') {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Invalid email or password.' }),
      });
      return;
    }
    currentAuthenticated = true;
    currentUser = { userId: user.userId, email: data.email || user.email };
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(currentUser),
    });
  });

  await page.route('**/api/logout', async (route) => {
    if (!(await checkCsrf(route))) return;
    currentAuthenticated = false;
    await route.fulfill({
      status: 204,
    });
  });

  await page.route('**/api/games/import', async (route) => {
    if (!(await checkCsrf(route))) return;
    if (importConflict) {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'CONFLICT',
          details: ['Active import job already in progress for this account.'],
        }),
      });
      return;
    }
    let data: Record<string, unknown> = {};
    try {
      data = route.request().postDataJSON() || {};
    } catch {
      // ignore
    }
    if (currentAuthenticated && !data.accountId) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'MISSING_ACCOUNT_ID', details: ['Missing account identifier for authenticated import'] }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        jobId: 'mock-import-job-1',
        status: 'QUEUED',
        gamesImported: 0,
        gamesSkipped: 0,
        platform: data.platform || 'CHESS_COM',
        username: data.username || 'carlsen',
        timeControls: data.timeControls || ['BLITZ', 'RAPID'],
        playerColor: data.playerColor || 'BOTH',
        fromDate: data.fromDate || null,
        toDate: data.toDate || null,
        configurationState: 'READY',
      }),
    });
  });

  let pollCount = 0;
  let pollIndex = 0;
  const defaultJobSequence = [
    {
      jobId: 'mock-import-job-1',
      status: 'PROCESSING',
      gamesImported: 5,
      gamesSkipped: 0,
      analysisStatus: 'NOT_STARTED',
    },
    {
      jobId: 'mock-import-job-1',
      status: 'COMPLETED',
      gamesImported: 12,
      gamesSkipped: 1,
      analysisStatus: 'ANALYZING',
    },
    {
      jobId: 'mock-import-job-1',
      status: 'COMPLETED',
      gamesImported: 12,
      gamesSkipped: 1,
      analysisStatus: 'COMPLETED',
    },
  ];
  const sequence = jobStatusSequence || defaultJobSequence;

  await page.route('**/api/jobs/*', async (route) => {
    pollCount++;
    const status = sequence[Math.min(pollIndex, sequence.length - 1)];
    pollIndex++;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(status),
    });
  });

  await page.route('**/api/positions/weaknesses*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(weaknesses),
    });
  });

  await page.route('**/api/puzzles/evaluate-move*', async (route) => {
    const url = new URL(route.request().url());
    const move = url.searchParams.get('move') || '';
    const fen = url.searchParams.get('fen') || '';
    const isTarget = move.toLowerCase() === 'nc6' || move.toLowerCase() === 'd4';

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        fen,
        move,
        bestMove: isTarget ? move : 'Nc6',
        bestEvalCp: 40,
        evalCp: isTarget ? 40 : -120,
        evalLoss: isTarget ? 0.0 : 1.6,
        maxEvalLoss: 2.0,
        threshold: 0.8,
        acceptable: isTarget,
      }),
    });
  });

  await page.route('**/api/puzzles/*/continuation*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        source: 'ENGINE',
        continuationMove: 'd4',
        explanation: 'Engine continuation',
        evalCp: 45,
      }),
    });
  });

  await page.route('**/api/puzzles/*/attempt-count*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(attemptCount),
    });
  });

  await page.route('**/api/puzzles/attempt', async (route) => {
    attemptCount = { solvedCount: attemptCount.solvedCount + 1, failedCount: attemptCount.failedCount };
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true }),
    });
  });

  await page.route('**/api/puzzles/events', async (route) => {
    let submissionId: string | undefined;
    try {
      const data = route.request().postDataJSON() as { submissionId?: string; eventType?: string };
      submissionId = data?.submissionId;
      if (data?.eventType === 'SOLVED') {
        attemptCount = { solvedCount: attemptCount.solvedCount + 1, failedCount: attemptCount.failedCount };
      } else if (data?.eventType === 'FAILED') {
        attemptCount = { solvedCount: attemptCount.solvedCount, failedCount: attemptCount.failedCount + 1 };
      }
    } catch {
      // ignore
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, ...(submissionId ? { submissionId } : {}) }),
    });
  });

  // Match only the puzzle list endpoint, ignoring sub-resources like evaluate-move or attempt
  await page.route(/\/api\/puzzles(\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(puzzles),
    });
  });

  // Decision timing endpoint
  await page.route('**/api/positions/long-decisions*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        content: longDecisions,
        page: 0,
        size: 20,
        totalElements: longDecisions.length,
        totalPages: 1,
        hasNext: false,
      }),
    });
  });

  // Similar positions endpoint
  await page.route('**/api/positions/similar*', async (route) => {
    const url = new URL(route.request().url());
    const fen = url.searchParams.get('fen') || DEFAULT_MOCK_SIMILAR_POSITION.fen;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        queryFen: fen,
        candidatesEvaluated: 10,
        shortlistedCandidatesCount: 5,
        engineCompletedCount: 5,
        dynamicSimilarityApplied: true,
        fallbackReason: null,
        queryStatus: 'COMPLETED',
        queryAchievedDepth: 16,
        queryPrincipalVariations: [],
        results: similarPositions,
      }),
    });
  });

  // Game analysis endpoints
  await page.route('**/api/games/*/analyze', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(gameAnalysis),
    });
  });

  await page.route(/\/api\/games(\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        content: games,
        totalElements: games.length,
        totalPages: 1,
        number: 0,
        size: 20,
      }),
    });
  });

  // Recurring discovery job endpoints
  let discoveryPollCount = 0;
  const defaultDiscoveryJob = {
    jobId: 'discovery-job-1',
    accountId: DEFAULT_MOCK_ACCOUNT.id,
    status: 'COMPLETED',
    progressPercent: 100,
    stage: 'COMPLETE',
    errorMessage: null,
    warningMessage: null,
    createdAt: '2026-11-09T00:00:00Z',
    updatedAt: '2026-11-09T00:01:00Z',
  };
  const activeDiscoveryJob = discoveryJob || defaultDiscoveryJob;

  await page.route('**/api/weaknesses/recurring/discover', async (route) => {
    if (discoveryJob && discoveryJob.status === 'FAILED') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(discoveryJob),
      });
      return;
    }
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({
        ...activeDiscoveryJob,
        status: 'PROCESSING',
        stage: 'CLUSTERING',
        progressPercent: 35,
      }),
    });
  });

  await page.route('**/api/weaknesses/recurring/jobs/*', async (route) => {
    discoveryPollCount++;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(activeDiscoveryJob),
    });
  });

  await page.route('**/api/weaknesses/recurring/*/positions*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        page: 0,
        size: 100,
        totalElements: clusterPositions.length,
        totalPages: 1,
        hasNext: false,
        positions: clusterPositions,
      }),
    });
  });

  await page.route('**/api/weaknesses/recurring*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(recurringWeaknesses),
    });
  });

  return {
    setAuthenticated: (val: boolean) => {
      currentAuthenticated = val;
    },
    getPollCount: () => pollCount,
    getDiscoveryPollCount: () => discoveryPollCount,
  };
}
