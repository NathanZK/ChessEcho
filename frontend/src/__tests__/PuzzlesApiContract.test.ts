import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fetchPuzzles } from '../services/api';

describe('Puzzles API Contract Serialization', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('serializes platform=CHESS_COM, playerColor in uppercase, and removes acceptableThreshold', async () => {
    const mockResponse = [
      {
        puzzleId: 'puzzle-uuid-1',
        fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
        playerColor: 'WHITE',
        targetMove: 'Bb5',
        acceptableMoves: [{ move: 'Bc4', evalLoss: 0.1 }],
        movesPlayed: [{ move: 'd3', timesPlayed: 4, averageLoss: 1.2 }],
        priority: 4.8,
        timesReached: 10,
        mistakeCount: 4,
        mistakeRate: 0.4,
        evalCp: 35,
      },
    ];

    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => mockResponse,
    } as Response);

    const puzzles = await fetchPuzzles('hikaru', 'chess_com', 'white', 0.8, 3, 5, 0);

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const calledUrl = vi.mocked(global.fetch).mock.calls[0][0] as string;

    // Verify query parameters match backend contract exactly
    expect(calledUrl).toContain('platform=CHESS_COM');
    expect(calledUrl).toContain('username=hikaru');
    expect(calledUrl).toContain('playerColor=WHITE');
    expect(calledUrl).toContain('minEvalLoss=0.8');
    expect(calledUrl).toContain('minMistakeCount=3');
    expect(calledUrl).toContain('limit=5');
    expect(calledUrl).toContain('page=0');

    // Verify acceptableThreshold is NOT sent in query string
    expect(calledUrl).not.toContain('acceptableThreshold');

    // Verify response parsing and default fallbacks
    expect(puzzles).toHaveLength(1);
    expect(puzzles[0].puzzleId).toBe('puzzle-uuid-1');
    expect(puzzles[0].targetMove).toBe('Bb5');
    expect(puzzles[0].openingTitle).toBe('Weakness Position #1');
    expect(puzzles[0].evalCp).toBe(35);
  });

  // T1: A non-2xx response is a load failure, not empty success. It must throw
  // (previously this swallowed the failure and resolved with []).
  it('throws when backend returns a non-ok response instead of resolving []', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: false,
      status: 500,
    } as Response);

    await expect(fetchPuzzles('hikaru')).rejects.toThrow();
  });

  it('throws on a 4xx non-ok response', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: false,
      status: 400,
    } as Response);

    await expect(fetchPuzzles('hikaru')).rejects.toThrow();
  });

  // T2: A network failure (fetch rejects) must propagate, not resolve [].
  it('throws when the network request fails (fetch rejects)', async () => {
    vi.mocked(global.fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(fetchPuzzles('hikaru')).rejects.toThrow();
  });

  // T17: A 2xx whose body is not the expected JSON array is a load error, never []
  // (the backend returns a typed List<...> on 200).
  it('throws when a 2xx body is not a JSON array (e.g. an object)', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ unexpected: 'shape' }),
    } as Response);

    await expect(fetchPuzzles('hikaru')).rejects.toThrow();
  });

  it('throws when a 2xx body cannot be parsed as JSON', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce(
      new Response('<html>not json</html>', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await expect(fetchPuzzles('hikaru')).rejects.toThrow();
  });

  // Guard: the deliberate no-request short-circuit for a missing username is NOT a
  // failed load and must still resolve with an empty array without calling fetch.
  it('resolves [] without a network call when username is missing', async () => {
    const puzzles = await fetchPuzzles('');
    expect(puzzles).toEqual([]);
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
