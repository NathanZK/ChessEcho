import type { Page } from '@playwright/test';
import { Chess } from 'chess.js';

export type NetworkFaultType =
  | 'server_error_500'
  | 'server_error_504'
  | 'aborted'
  | 'malformed_json'
  | 'empty_body';

export interface RouteFaultConfig {
  fault?: NetworkFaultType;
  gate?: Promise<void>;
  failureCount?: number;
}

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
  evaluateMoveFault?: RouteFaultConfig;
  continuationFault?: RouteFaultConfig;
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
  source: 'weakness',
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  playerColor: 'WHITE',
  targetMove: 'd4',
  mistakeMove: 'a3',
  blunderMove: 'h4',
  solutionLine: ['d4'],
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

export const BLACK_TO_MOVE_MOCK_PUZZLE = {
  puzzleId: 'puzzle-black-1',
  source: 'weakness',
  fen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
  playerColor: 'BLACK',
  targetMove: 'd6',
  mistakeMove: 'g6',
  blunderMove: 'a6',
  solutionLine: ['d6'],
  openingTitle: 'Sicilian Defense',
  acceptableMoves: [
    { move: 'Nc6', evalLoss: 0.02 },
    { move: 'e6', evalLoss: 0.08 },
  ],
  movesPlayed: [
    { move: 'a6', timesPlayed: 3, averageLoss: 1.4 },
  ],
  priority: 1.5,
  timesReached: 6,
  mistakeCount: 2,
  mistakeRate: 33.3,
  gameUrls: [],
  evalCp: -25,
};

export const TACTICAL_SACRIFICE_MOCK_PUZZLE = {
  puzzleId: 'puzzle-tactics-1',
  source: 'weakness',
  fen: 'r1bq1rk1/ppp2ppp/2np4/2b1p1N1/2B1P1n1/2NP4/PPP2PPP/R1BQ1RK1 w - - 0 8',
  playerColor: 'WHITE',
  targetMove: 'Bxf7+',
  mistakeMove: 'a3',
  blunderMove: 'd4',
  solutionLine: ['Bxf7+'],
  openingTitle: 'Tactical Bishop Sacrifice',
  acceptableMoves: [
    { move: 'h3', evalLoss: 0.1 },
  ],
  movesPlayed: [
    { move: 'd4', timesPlayed: 1, averageLoss: 1.8 },
  ],
  priority: 2.0,
  timesReached: 3,
  mistakeCount: 1,
  mistakeRate: 33.3,
  gameUrls: [],
  evalCp: 180,
};

export const MULTI_MOVE_CONTINUATION_MOCK_PUZZLE = {
  puzzleId: 'puzzle-continuation-1',
  source: 'weakness',
  fen: 'r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQK2R b KQkq - 0 5',
  playerColor: 'BLACK',
  targetMove: 'O-O',
  mistakeMove: 'a6',
  blunderMove: 'h6',
  solutionLine: ['O-O'],
  openingTitle: 'Giuoco Piano Multi-Move Sequence',
  acceptableMoves: [
    { move: 'd6', evalLoss: 0.04 },
  ],
  movesPlayed: [
    { move: 'h6', timesPlayed: 2, averageLoss: 1.2 },
  ],
  priority: 1.2,
  timesReached: 4,
  mistakeCount: 1,
  mistakeRate: 25.0,
  gameUrls: [],
  evalCp: 15,
};

export const ENDGAME_MOCK_PUZZLE = {
  puzzleId: 'puzzle-endgame-1',
  source: 'weakness',
  fen: '8/8/4k3/8/4K3/3P4/8/8 w - - 0 1',
  playerColor: 'WHITE',
  targetMove: 'Kd4',
  mistakeMove: 'Ke3',
  blunderMove: 'Kf4',
  solutionLine: ['Kd4'],
  openingTitle: 'King and Pawn Endgame',
  acceptableMoves: [
    { move: 'd4', evalLoss: 0.0 },
  ],
  movesPlayed: [
    { move: 'Kf4', timesPlayed: 1, averageLoss: 3.5 },
  ],
  priority: 2.5,
  timesReached: 2,
  mistakeCount: 1,
  mistakeRate: 50.0,
  gameUrls: [],
  evalCp: 300,
};

export const DYNAMIC_EVAL_MOCK_PUZZLE = {
  puzzleId: 'puzzle-eval-1',
  source: 'weakness',
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  playerColor: 'WHITE',
  targetMove: '',
  mistakeMove: 'a3',
  blunderMove: 'h4',
  solutionLine: ['d4'],
  openingTitle: 'Dynamic Evaluation Practice',
  acceptableMoves: [],
  movesPlayed: [],
  priority: 1.0,
  timesReached: 1,
  mistakeCount: 0,
  mistakeRate: 0.0,
  gameUrls: [],
  evalCp: 40,
};

export const WEAKNESS_BACKED_MOCK_PUZZLE = {
  ...DEFAULT_MOCK_PUZZLE,
  puzzleId: 'puzzle-weakness-1',
  source: 'weakness',
  openingTitle: 'Weakness Drill / Scotch Game',
};

export const MOCK_PUZZLES = [
  DEFAULT_MOCK_PUZZLE,
  BLACK_TO_MOVE_MOCK_PUZZLE,
  TACTICAL_SACRIFICE_MOCK_PUZZLE,
  MULTI_MOVE_CONTINUATION_MOCK_PUZZLE,
  ENDGAME_MOCK_PUZZLE,
  WEAKNESS_BACKED_MOCK_PUZZLE,
];

export interface JourneyMockHandle {
  setAuthenticated: (val: boolean) => void;
  getPollCount: () => number;
  getDiscoveryPollCount: () => number;
  setEvaluateMoveFault: (fault?: RouteFaultConfig) => void;
  setContinuationFault: (fault?: RouteFaultConfig) => void;
}

async function applyRouteFault(
  route: import('@playwright/test').Route | JourneyRouteLike,
  faultConfig?: RouteFaultConfig
): Promise<boolean> {
  if (!faultConfig) return false;

  if (faultConfig.gate) {
    await faultConfig.gate;
  }

  if (faultConfig.fault) {
    if (faultConfig.failureCount !== undefined) {
      if (faultConfig.failureCount <= 0) {
        return false;
      }
      faultConfig.failureCount -= 1;
    }

    switch (faultConfig.fault) {
      case 'server_error_500':
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Internal Server Error' }),
        });
        return true;
      case 'server_error_504':
        await route.fulfill({
          status: 504,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Gateway Timeout' }),
        });
        return true;
      case 'aborted':
        await route.abort('failed');
        return true;
      case 'malformed_json':
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: '<<<NOT_JSON>>>',
        });
        return true;
      case 'empty_body':
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: '',
        });
        return true;
    }
  }

  return false;
}

export interface JourneyRouteLike {
  request: () => {
    url: () => string;
    headers: () => Record<string, string>;
    postDataJSON: () => unknown;
  };
  fulfill: (response: {
    status?: number;
    contentType?: string;
    headers?: Record<string, string>;
    body?: string;
    json?: unknown;
  }) => Promise<void>;
  abort: (errorCode?: string) => Promise<void>;
}

export interface JourneyRoutablePage {
  context: () => {
    addCookies: (cookies: Array<{ name: string; value: string; domain?: string; path?: string }>) => Promise<unknown>;
  };
  route: (
    url: string | RegExp | ((url: URL) => boolean),
    handler: (route: import('@playwright/test').Route | JourneyRouteLike) => Promise<void> | void
  ) => Promise<unknown> | void;
}

export async function setupJourneyApiMocks(
  page: Page | JourneyRoutablePage,
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
    evaluateMoveFault,
    continuationFault,
  } = options;

  let currentAuthenticated = authenticated;
  let currentUser = { ...user };
  const currentAccounts = [...accounts];
  let currentEvaluateMoveFault = evaluateMoveFault ? { ...evaluateMoveFault } : undefined;
  let currentContinuationFault = continuationFault ? { ...continuationFault } : undefined;

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

  const checkCsrf = async (route: import('@playwright/test').Route | JourneyRouteLike): Promise<boolean> => {
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
    if (await applyRouteFault(route, currentEvaluateMoveFault)) {
      return;
    }
    const url = new URL(route.request().url());
    const move = url.searchParams.get('move') || '';
    const fen = url.searchParams.get('fen') || '';
    const currentPuzzle = puzzles.find((p) => p.fen === fen) || puzzles[0];
    const target = (currentPuzzle?.targetMove as string) || 'd4';
    const isTarget =
      move.toLowerCase() === target.toLowerCase() ||
      move.toLowerCase() === 'nc6' ||
      move.toLowerCase() === 'd4' ||
      move.toLowerCase() === 'd6' ||
      move.toLowerCase() === 'bxf7+' ||
      move.toLowerCase() === 'kd4';

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        fen,
        move,
        bestMove: isTarget ? move : target,
        bestEvalCp: 40,
        evalCp: isTarget ? 40 : -120,
        evalLoss: isTarget ? 0.0 : 1.6,
        maxEvalLoss: 2.0,
        threshold: 0.8,
        acceptable: isTarget,
      }),
    });
  });

  await page.route(/.*\/api\/puzzles\/.*continuation.*/, async (route) => {
    if (await applyRouteFault(route, currentContinuationFault)) {
      return;
    }
    const url = new URL(route.request().url());
    const fen = url.searchParams.get('fen') || '';
    let legalMove = 'd4';
    if (fen) {
      try {
        const chess = new Chess(fen);
        const legalMoves = chess.moves();
        if (legalMoves.length > 0) {
          legalMove = legalMoves.includes('exd4')
            ? 'exd4'
            : legalMoves.includes('Nf6')
            ? 'Nf6'
            : legalMoves.includes('Bc5')
            ? 'Bc5'
            : legalMoves[0];
        }
      } catch {
        // fallback
      }
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        source: 'ENGINE',
        continuationMove: legalMove,
        candidates: [{ move: legalMove, evalCp: 45, pv: [legalMove] }],
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
    setEvaluateMoveFault: (fault?: RouteFaultConfig) => {
      currentEvaluateMoveFault = fault ? { ...fault } : undefined;
    },
    setContinuationFault: (fault?: RouteFaultConfig) => {
      currentContinuationFault = fault ? { ...fault } : undefined;
    },
  };
}
