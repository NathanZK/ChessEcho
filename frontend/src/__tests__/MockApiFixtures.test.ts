import { describe, it, expect, vi } from 'vitest';
import {
  DEFAULT_MOCK_PUZZLE,
  BLACK_TO_MOVE_MOCK_PUZZLE,
  TACTICAL_SACRIFICE_MOCK_PUZZLE,
  MULTI_MOVE_CONTINUATION_MOCK_PUZZLE,
  ENDGAME_MOCK_PUZZLE,
  WEAKNESS_BACKED_MOCK_PUZZLE,
  MOCK_PUZZLES,
  setupJourneyApiMocks,
  type JourneyRoutablePage,
  type JourneyRouteLike,
} from '../../journeys/fixtures/mockApi';
import { deriveMoveSquares } from '../../journeys/model/practiceModel';

describe('Task 1: Curated Multi-Position Puzzle Fixtures', () => {
  it('exports MOCK_PUZZLES containing all 6 distinct puzzle geometries including weakness-backed', () => {
    expect(MOCK_PUZZLES).toHaveLength(6);
    expect(MOCK_PUZZLES).toContain(DEFAULT_MOCK_PUZZLE);
    expect(MOCK_PUZZLES).toContain(BLACK_TO_MOVE_MOCK_PUZZLE);
    expect(MOCK_PUZZLES).toContain(TACTICAL_SACRIFICE_MOCK_PUZZLE);
    expect(MOCK_PUZZLES).toContain(MULTI_MOVE_CONTINUATION_MOCK_PUZZLE);
    expect(MOCK_PUZZLES).toContain(ENDGAME_MOCK_PUZZLE);
    expect(MOCK_PUZZLES).toContain(WEAKNESS_BACKED_MOCK_PUZZLE);
  });

  it('verifies WEAKNESS_BACKED_MOCK_PUZZLE has source "weakness" for blindfold eligibility', () => {
    expect(WEAKNESS_BACKED_MOCK_PUZZLE.source).toBe('weakness');
    expect(WEAKNESS_BACKED_MOCK_PUZZLE.puzzleId).toBe('puzzle-weakness-1');
  });

  it('verifies BLACK_TO_MOVE_MOCK_PUZZLE has playerColor BLACK and active Black turn in FEN', () => {
    expect(BLACK_TO_MOVE_MOCK_PUZZLE.playerColor).toBe('BLACK');
    const fenParts = BLACK_TO_MOVE_MOCK_PUZZLE.fen.split(' ');
    expect(fenParts[1]).toBe('b');
    expect(BLACK_TO_MOVE_MOCK_PUZZLE.targetMove).toBeTruthy();
  });

  it('verifies TACTICAL_SACRIFICE_MOCK_PUZZLE has valid FEN, White to move, and target move', () => {
    expect(TACTICAL_SACRIFICE_MOCK_PUZZLE.playerColor).toBe('WHITE');
    const fenParts = TACTICAL_SACRIFICE_MOCK_PUZZLE.fen.split(' ');
    expect(fenParts[1]).toBe('w');
    expect(TACTICAL_SACRIFICE_MOCK_PUZZLE.targetMove).toBe('Bxf7+');
    expect(TACTICAL_SACRIFICE_MOCK_PUZZLE.evalCp).toBeGreaterThan(100);
  });

  it('verifies MULTI_MOVE_CONTINUATION_MOCK_PUZZLE has multi-move continuation candidate structure', () => {
    expect(MULTI_MOVE_CONTINUATION_MOCK_PUZZLE.puzzleId).toBe('puzzle-continuation-1');
    expect(MULTI_MOVE_CONTINUATION_MOCK_PUZZLE.targetMove).toBeTruthy();
    expect(MULTI_MOVE_CONTINUATION_MOCK_PUZZLE.acceptableMoves.length).toBeGreaterThan(0);
  });

  it('verifies ENDGAME_MOCK_PUZZLE represents a simplified endgame geometry with few pieces', () => {
    expect(ENDGAME_MOCK_PUZZLE.puzzleId).toBe('puzzle-endgame-1');
    const piecePlacement = ENDGAME_MOCK_PUZZLE.fen.split(' ')[0];
    const pieceCount = piecePlacement.replace(/[^a-zA-Z]/g, '').length;
    // Endgame positions should feature sparse boards (<= 6 pieces)
    expect(pieceCount).toBeLessThanOrEqual(6);
    expect(ENDGAME_MOCK_PUZZLE.targetMove).toBe('Kd4');
  });

  it('verifies all puzzle fixtures satisfy required schema properties and explicit move semantics', () => {
    for (const puzzle of MOCK_PUZZLES) {
      expect(puzzle.puzzleId).toMatch(/^puzzle-/);
      expect(['WHITE', 'BLACK']).toContain(puzzle.playerColor);
      expect(typeof puzzle.fen).toBe('string');
      expect(typeof puzzle.targetMove).toBe('string');
      expect(typeof puzzle.mistakeMove).toBe('string');
      expect(typeof puzzle.blunderMove).toBe('string');
      expect(Array.isArray(puzzle.solutionLine)).toBe(true);
      expect(puzzle.solutionLine!.length).toBeGreaterThan(0);
      expect(Array.isArray(puzzle.acceptableMoves)).toBe(true);
      expect(Array.isArray(puzzle.movesPlayed)).toBe(true);
      expect(typeof puzzle.priority).toBe('number');
      expect(typeof puzzle.evalCp).toBe('number');
    }
  });

  it('dynamically derives source and destination squares via deriveMoveSquares for all puzzle targets', () => {
    // White move d4 in baseline FEN
    const defaultMove = deriveMoveSquares(DEFAULT_MOCK_PUZZLE.fen, DEFAULT_MOCK_PUZZLE.targetMove);
    expect(defaultMove).toEqual({ from: 'd2', to: 'd4' });

    // Black move d6 in Sicilian FEN
    const blackMove = deriveMoveSquares(BLACK_TO_MOVE_MOCK_PUZZLE.fen, BLACK_TO_MOVE_MOCK_PUZZLE.targetMove);
    expect(blackMove).toEqual({ from: 'd7', to: 'd6' });

    // White tactical sacrifice Bxf7+
    const tacticsMove = deriveMoveSquares(TACTICAL_SACRIFICE_MOCK_PUZZLE.fen, TACTICAL_SACRIFICE_MOCK_PUZZLE.targetMove);
    expect(tacticsMove).toEqual({ from: 'c4', to: 'f7' });

    // Throws DynamicMoveDerivationError on illegal SAN
    expect(() => deriveMoveSquares(DEFAULT_MOCK_PUZZLE.fen, 'Qxe8#')).toThrow(
      /\[DynamicMoveDerivationError\] Move "Qxe8#" is not legal/
    );
  });
});

describe('Task 2: Deterministic Route Fault Injection in setupJourneyApiMocks', () => {
  interface RouteHandler {
    pattern: string | RegExp;
    handler: (route: JourneyRouteLike) => Promise<void> | void;
  }

  interface FulfilledResponse {
    status?: number;
    contentType?: string;
    body?: string;
  }

  interface TriggerRouteResult {
    fulfilled: FulfilledResponse | null;
    aborted: string | null;
  }

  function createMockPage() {
    const routeHandlers: RouteHandler[] = [];
    const mockPage: JourneyRoutablePage = {
      context: () => ({
        addCookies: vi.fn().mockResolvedValue(undefined),
      }),
      route: vi.fn(async (pattern, handler) => {
        if (typeof pattern === 'string' || pattern instanceof RegExp) {
          routeHandlers.push({ pattern, handler });
        }
      }),
    };

    const triggerRoute = async (urlStr: string): Promise<TriggerRouteResult> => {
      let matchedHandler: ((route: JourneyRouteLike) => Promise<void> | void) | null = null;
      for (const entry of routeHandlers) {
        if (typeof entry.pattern === 'string') {
          const regexStr = entry.pattern.replace(/\*\*/g, '.*').replace(/\*/g, '[^/]*');
          if (new RegExp(regexStr).test(urlStr)) {
            matchedHandler = entry.handler;
          }
        } else if (entry.pattern instanceof RegExp && entry.pattern.test(urlStr)) {
          matchedHandler = entry.handler;
        }
      }

      if (!matchedHandler) {
        throw new Error(`No route handler matched URL: ${urlStr}`);
      }

      let fulfilled: FulfilledResponse | null = null;
      let aborted: string | null = null;

      const mockRoute: JourneyRouteLike = {
        request: () => ({
          url: () => urlStr,
          headers: () => ({ 'x-xsrf-token': 'mock-csrf-token' }),
          postDataJSON: () => ({}),
        }),
        fulfill: vi.fn(async (opts?: FulfilledResponse) => {
          fulfilled = opts ?? null;
        }),
        abort: vi.fn(async (errorCode?: string) => {
          aborted = errorCode || 'failed';
        }),
      };

      await matchedHandler(mockRoute);
      return { fulfilled, aborted };
    };

    return { mockPage, triggerRoute };
  }

  it('injects HTTP 500 server error on evaluate-move', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    await setupJourneyApiMocks(mockPage, {
      evaluateMoveFault: { fault: 'server_error_500' },
    });

    const res = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res.fulfilled?.status).toBe(500);
    expect(res.fulfilled?.body).toContain('Internal Server Error');
  });

  it('injects HTTP 504 gateway timeout on evaluate-move', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    await setupJourneyApiMocks(mockPage, {
      evaluateMoveFault: { fault: 'server_error_504' },
    });

    const res = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res.fulfilled?.status).toBe(504);
    expect(res.fulfilled?.body).toContain('Gateway Timeout');
  });

  it('injects network abort on evaluate-move', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    await setupJourneyApiMocks(mockPage, {
      evaluateMoveFault: { fault: 'aborted' },
    });

    const res = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res.aborted).toBe('failed');
  });

  it('injects malformed JSON response on evaluate-move', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    await setupJourneyApiMocks(mockPage, {
      evaluateMoveFault: { fault: 'malformed_json' },
    });

    const res = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res.fulfilled?.status).toBe(200);
    expect(res.fulfilled?.body).toBe('<<<NOT_JSON>>>');
  });

  it('injects empty response body on evaluate-move', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    await setupJourneyApiMocks(mockPage, {
      evaluateMoveFault: { fault: 'empty_body' },
    });

    const res = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res.fulfilled?.status).toBe(200);
    expect(res.fulfilled?.body).toBe('');
  });

  it('respects failureCount parameter by failing N times then recovering', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    await setupJourneyApiMocks(mockPage, {
      evaluateMoveFault: { fault: 'server_error_500', failureCount: 1 },
    });

    // 1st request fails with 500
    const res1 = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res1.fulfilled?.status).toBe(500);

    // 2nd request succeeds with 200
    const res2 = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res2.fulfilled?.status).toBe(200);
    expect(JSON.parse(res2.fulfilled?.body || '{}').bestMove).toBe('d4');
  });

  it('supports deterministic Promise gate to hold evaluation in-flight', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    let releaseGate: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      releaseGate = resolve;
    });

    await setupJourneyApiMocks(mockPage, {
      evaluateMoveFault: { gate },
    });

    let completed = false;
    const requestPromise = triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4').then(
      (res) => {
        completed = true;
        return res;
      }
    );

    await Promise.resolve();
    expect(completed).toBe(false);

    releaseGate();
    const res = await requestPromise;
    expect(completed).toBe(true);
    expect(res.fulfilled?.status).toBe(200);
  });

  it('supports dynamic fault toggling via JourneyMockHandle', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    const handle = await setupJourneyApiMocks(mockPage);

    // Initial call succeeds
    const res1 = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res1.fulfilled?.status).toBe(200);

    // Dynamically inject 500
    handle.setEvaluateMoveFault({ fault: 'server_error_500' });
    const res2 = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res2.fulfilled?.status).toBe(500);

    // Clear fault dynamically
    handle.setEvaluateMoveFault(undefined);
    const res3 = await triggerRoute('http://localhost:3000/api/puzzles/evaluate-move?move=d4');
    expect(res3.fulfilled?.status).toBe(200);
  });

  it('supports continuation route fault injection', async () => {
    const { mockPage, triggerRoute } = createMockPage();
    const handle = await setupJourneyApiMocks(mockPage, {
      continuationFault: { fault: 'server_error_504' },
    });

    const res1 = await triggerRoute('http://localhost:3000/api/puzzles/puzzle-1/continuation');
    expect(res1.fulfilled?.status).toBe(504);

    handle.setContinuationFault(undefined);
    const res2 = await triggerRoute('http://localhost:3000/api/puzzles/puzzle-1/continuation');
    expect(res2.fulfilled?.status).toBe(200);
  });
});
