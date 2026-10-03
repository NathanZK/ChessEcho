import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchPositionProgress, type PositionProgressResponse } from '../services/api';

const progress: PositionProgressResponse = {
  positionId: 'position/1',
  playerColor: 'WHITE',
  points: [
    {
      occurredAt: '2026-01-01T00:00:00Z',
      mistakeRate: 50,
      winRate: 25,
      attempts: 4,
    },
    {
      occurredAt: '2026-02-01T00:00:00Z',
      mistakeRate: 25,
      winRate: 50,
      attempts: 8,
    },
  ],
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

  it('requests exact position progress with encoded identity and authenticated credentials', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => progress,
    } as Response);

    await expect(fetchPositionProgress('position/1', 'WHITE')).resolves.toEqual(progress);

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/positions/position%2F1/progress?playerColor=WHITE'),
      { credentials: 'include' },
    );
  });

  it('accepts a valid empty history and nullable changes', async () => {
    const empty: PositionProgressResponse = {
      ...progress,
      positionId: 'position-1',
      playerColor: 'BLACK',
      points: [],
      mistakeRateChange: null,
      winRateChange: null,
    };
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => empty,
    } as Response);

    await expect(fetchPositionProgress('position-1', 'BLACK')).resolves.toEqual(empty);
  });

  it('accepts unknown response properties for forward compatibility', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ ...progress, positionId: 'position-1', futureField: true }),
    } as Response);

    await expect(fetchPositionProgress('position-1', 'WHITE')).resolves.toMatchObject({
      ...progress,
      positionId: 'position-1',
    });
  });

  it.each([
    ['a different position', { ...progress, positionId: 'position-2' }],
    ['a different player color', { ...progress, playerColor: 'BLACK' }],
  ])('rejects a response for %s', async (_description, body) => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => body,
    } as Response);

    await expect(fetchPositionProgress('position-1', 'WHITE')).rejects.toThrow(
      /unexpected response body/i,
    );
  });

  it('throws when the backend response is not successful', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({ ok: false, status: 401 } as Response);

    await expect(fetchPositionProgress('position-1', 'WHITE')).rejects.toThrow();
  });

  it('propagates network errors', async () => {
    vi.mocked(global.fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(fetchPositionProgress('position-1', 'WHITE')).rejects.toThrow('Failed to fetch');
  });

  it('throws when the response is not valid JSON', async () => {
    const invalidJsonResponse = new Response();
    vi.spyOn(invalidJsonResponse, 'json').mockRejectedValueOnce(new SyntaxError('Invalid JSON'));
    vi.mocked(global.fetch).mockResolvedValueOnce(invalidJsonResponse);

    await expect(fetchPositionProgress('position-1', 'WHITE')).rejects.toThrow();
  });

  it.each([
    ['empty position ID', { ...progress, positionId: '' }],
    ['unknown player color', { ...progress, playerColor: 'BOTH' }],
    ['missing points', { ...progress, points: undefined }],
    ['invalid timestamp', { ...progress, points: [{ ...progress.points[0], occurredAt: 'not-a-date' }] }],
    ['rate outside range', { ...progress, points: [{ ...progress.points[0], mistakeRate: 101 }] }],
    ['non-positive attempts', { ...progress, points: [{ ...progress.points[0], attempts: 0 }] }],
    ['infinite change', { ...progress, mistakeRateChange: Infinity }],
    ['missing assessment', { ...progress, assessment: undefined }],
  ])('rejects an invalid response shape: %s', async (_description, body) => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      json: async () => body,
    } as Response);

    await expect(fetchPositionProgress('position-1', 'WHITE')).rejects.toThrow(
      /unexpected response body/i,
    );
  });
});
