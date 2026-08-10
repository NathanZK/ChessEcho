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

  it('returns empty array when backend returns non-ok response', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: false,
      status: 400,
    } as Response);

    const puzzles = await fetchPuzzles('hikaru');
    expect(puzzles).toEqual([]);
  });
});
