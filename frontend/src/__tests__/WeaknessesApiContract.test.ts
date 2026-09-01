import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fetchWeaknesses, WeaknessResponse } from '../services/api';

/**
 * Contract tests for the weakness reader in the shared API client.
 *
 * Issue #86: a request failure must be preserved as a failure and never collapsed
 * into an empty successful result. These tests exercise the REAL client with a
 * stubbed global fetch (mirroring PuzzlesApiContract.test.ts) and assert that
 * `fetchWeaknesses` now throws — consistent with `startImportJob`/`pollJobStatus` —
 * on non-2xx, network failure, and malformed 2xx bodies, instead of resolving [].
 */
describe('Weaknesses API Contract – failure signalling', () => {
  const mockWeakness: WeaknessResponse = {
    positionId: 'w-pos-1',
    fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
    timesReached: 15,
    mistakeCount: 5,
    mistakeRate: 33.3,
    averageLoss: 1.25,
    priority: 4.2,
    bestMove: 'Nc6',
    acceptableMoves: [{ move: 'Nf6', evalLoss: 0.1 }],
    movesPlayed: [{ move: 'Bc5', timesPlayed: 4, averageLoss: 1.3 }],
    gameUrls: ['https://www.chess.com/game/live/10001'],
    evalCp: 35,
  };

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('still resolves a populated array on a successful 2xx array body', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => [mockWeakness],
    } as Response);

    const res = await fetchWeaknesses('hikaru', 'chess_com', 'both', 0.8, 3, 0, 20);
    expect(res).toEqual([mockWeakness]);
  });

  it('still resolves [] on a successful 2xx empty array body (empty success is not an error)', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => [],
    } as Response);

    const res = await fetchWeaknesses('hikaru');
    expect(res).toEqual([]);
  });

  // T3: non-2xx must throw, not resolve [].
  it('throws when backend returns a non-ok response (e.g. 500)', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: false,
      status: 500,
    } as Response);

    await expect(fetchWeaknesses('hikaru')).rejects.toThrow();
  });

  // T4: network failure must propagate.
  it('throws when the network request fails (fetch rejects)', async () => {
    vi.mocked(global.fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(fetchWeaknesses('hikaru')).rejects.toThrow();
  });

  // T18: a 2xx body that is not the expected JSON array is a load error, never [].
  it('throws when a 2xx body is not a JSON array (e.g. an object)', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ unexpected: 'shape' }),
    } as Response);

    await expect(fetchWeaknesses('hikaru')).rejects.toThrow();
  });

  it('throws when a 2xx body cannot be parsed as JSON', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce(
      new Response('<html>not json</html>', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await expect(fetchWeaknesses('hikaru')).rejects.toThrow();
  });

  // Guard: the deliberate no-request short-circuit for a missing username is not a
  // failed load and must still resolve [] without touching the network.
  it('resolves [] without a network call when username is missing', async () => {
    const res = await fetchWeaknesses('');
    expect(res).toEqual([]);
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
