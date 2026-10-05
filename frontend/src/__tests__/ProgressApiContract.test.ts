import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchPositionProgress, type PositionProgressResponse } from '../services/api';

const progress: PositionProgressResponse = {
  positionId: '00000000-0000-0000-0000-000000000010',
  playerColor: 'WHITE',
  baseline: {
    occurredAt: '2025-12-01T00:00:00Z',
    mistakeRate: 50,
    winRate: 25,
    sourceEncounterCount: 4,
  },
  points: [
    {
      checkpointId: '00000000-0000-0000-0000-000000000001',
      occurredAt: '2026-01-01T00:00:00Z',
      mistakeRate: 50,
      winRate: 25,
      attempts: 4,
      open: false,
    },
    {
      checkpointId: '00000000-0000-0000-0000-000000000002',
      occurredAt: '2026-02-01T00:00:00Z',
      mistakeRate: 25,
      winRate: 50,
      attempts: 8,
      open: true,
    },
  ],
  currentIntervalState: 'MEASURED_OPEN',
  excludedUndatedEncounters: 0,
  mistakeRateChange: -50,
  winRateChange: 100,
  assessment: 'You are making fewer mistakes at this position.',
};

describe('Position progress API contract', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('requests exact position progress with authenticated credentials', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => progress,
    } as Response);

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0.3)).resolves.toEqual(progress);

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining(
        `/positions/${progress.positionId}/progress?playerColor=WHITE&minEvalLoss=0.3&accountId=account-1`,
      ),
      { credentials: 'include' },
    );
  });

  it('accepts a valid empty history and nullable changes', async () => {
    const empty: PositionProgressResponse = {
      ...progress,
      positionId: '00000000-0000-0000-0000-000000000011',
      playerColor: 'BLACK',
      baseline: null,
      points: [],
      currentIntervalState: 'NO_CHECKPOINT',
      mistakeRateChange: null,
      winRateChange: null,
    };
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => empty,
    } as Response);

    await expect(fetchPositionProgress(empty.positionId, 'BLACK', 'account-1', 1.2)).resolves.toEqual(empty);
    const url = new URL(vi.mocked(global.fetch).mock.calls[0][0] as string);
    expect([...url.searchParams.entries()]).toEqual([
      ['playerColor', 'BLACK'],
      ['minEvalLoss', '1.2'],
      ['accountId', 'account-1'],
    ]);
  });

  it('accepts unknown response properties for forward compatibility', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ ...progress, positionId: '00000000-0000-0000-0000-000000000011', futureField: true }),
    } as Response);

    await expect(fetchPositionProgress('00000000-0000-0000-0000-000000000011', 'WHITE', 'account-1', 0.3)).resolves.toMatchObject({
      ...progress,
      positionId: '00000000-0000-0000-0000-000000000011',
    });
  });

  it.each([
    ['a different position', { ...progress, positionId: '00000000-0000-0000-0000-000000000011' }],
    ['a different player color', { ...progress, playerColor: 'BLACK' }],
  ])('rejects a response for %s', async (_description, body) => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => body,
    } as Response);

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0.3)).rejects.toThrow(
      /unexpected response body/i,
    );
  });

  it('throws when the backend response is not successful', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({ ok: false, status: 401 } as Response);

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0.3)).rejects.toThrow();
  });

  it('surfaces a backend threshold validation failure without returning progress', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce(new Response(JSON.stringify({
      error: 'VALIDATION_ERROR',
      details: { minEvalLoss: 'must be positive' },
    }), { status: 400 }));

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0)).rejects.toThrow(
      'Failed to load position progress: 400',
    );
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('playerColor=WHITE&minEvalLoss=0&accountId=account-1'),
      { credentials: 'include' },
    );
  });

  it('propagates network errors', async () => {
    vi.mocked(global.fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0.3)).rejects.toThrow(
      'Failed to fetch',
    );
  });

  it('throws when the response is not valid JSON', async () => {
    const invalidJsonResponse = new Response();
    vi.spyOn(invalidJsonResponse, 'json').mockRejectedValueOnce(new SyntaxError('Invalid JSON'));
    vi.mocked(global.fetch).mockResolvedValueOnce(invalidJsonResponse);

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0.3)).rejects.toThrow();
  });

  it.each([
    ['invalid position ID', { ...progress, positionId: 'position/1' }],
    ['unknown player color', { ...progress, playerColor: 'BOTH' }],
    ['missing points', { ...progress, points: undefined }],
    ['invalid timestamp', { ...progress, points: [{ ...progress.points[0], occurredAt: 'not-a-date' }] }],
    ['rate outside range', { ...progress, points: [{ ...progress.points[0], mistakeRate: 101 }] }],
    ['non-positive attempts', { ...progress, points: [{ ...progress.points[0], attempts: 0 }] }],
    ['missing baseline', { ...progress, baseline: undefined }],
    ['invalid checkpoint ID', { ...progress, points: [{ ...progress.points[0], checkpointId: 'not-a-uuid' }] }],
    ['invalid point lifecycle flag', { ...progress, points: [{ ...progress.points[0], open: 'true' }] }],
    ['unknown current interval state', { ...progress, currentIntervalState: 'CLOSED' }],
    ['negative undated count', { ...progress, excludedUndatedEncounters: -1 }],
    ['infinite change', { ...progress, mistakeRateChange: Infinity }],
    ['missing assessment', { ...progress, assessment: undefined }],
  ])('rejects an invalid response shape: %s', async (_description, body) => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => body,
    } as Response);

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0.3)).rejects.toThrow(
      /unexpected response body/i,
    );
  });

  it.each([
    ['invalid baseline timestamp', { ...progress, baseline: { ...progress.baseline, occurredAt: 'not-a-date' } }],
    ['empty baseline count', { ...progress, baseline: { ...progress.baseline, sourceEncounterCount: 0 } }],
  ])('rejects an invalid baseline: %s', async (_description, body) => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => body,
    } as Response);

    await expect(fetchPositionProgress(progress.positionId, 'WHITE', 'account-1', 0.3)).rejects.toThrow(
      /unexpected response body/i,
    );
  });
});
