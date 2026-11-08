import type { Page } from '@playwright/test';

export interface MockOptions {
  authenticated?: boolean;
  user?: { userId: string; email: string };
  accounts?: Array<{ id: string; platform: string; username: string }>;
  weaknesses?: Array<Record<string, unknown>>;
  puzzles?: Array<Record<string, unknown>>;
  importConflict?: boolean;
  jobStatusSequence?: Array<Record<string, unknown>>;
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

export interface JourneyMockHandle {
  setAuthenticated: (val: boolean) => void;
  getPollCount: () => number;
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
    importConflict = false,
    jobStatusSequence,
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

  await page.route('**/api/weaknesses/recurring*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    });
  });

  return {
    setAuthenticated: (val: boolean) => {
      currentAuthenticated = val;
    },
    getPollCount: () => pollCount,
  };
}
