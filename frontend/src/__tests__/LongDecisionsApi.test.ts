import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchLongDecisions, type LongDecisionPageResponse } from '../services/api';

const response: LongDecisionPageResponse = {
  content: [
    {
      id: '550e8400-e29b-41d4-a716-446655440001',
      positionId: '550e8400-e29b-41d4-a716-446655440002',
      fen: '8/8/8/8/8/8/8/8 w - -',
      playerColor: 'WHITE',
      plyNumber: 1,
      movePlayed: 'Nf3',
      decisionTimeMs: 30_000,
      timeControl: 'BULLET',
      platformGameId: 'game-1',
      playedAt: '2026-02-03T12:00:00Z',
      opponentUsername: 'opponent',
    },
  ],
  page: 1,
  size: 20,
  totalElements: 21,
  totalPages: 2,
  hasNext: true,
};

describe('fetchLongDecisions', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('serializes account, time-control, absolute threshold, and pagination independently', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => response,
      }),
    );

    await expect(
      fetchLongDecisions('550e8400-e29b-41d4-a716-446655440010', 'BULLET', 30, 1, 20),
    ).resolves.toEqual(response);

    const [url, options] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/positions/long-decisions?');
    expect(url).toContain('accountId=550e8400-e29b-41d4-a716-446655440010');
    expect(url).toContain('timeControl=BULLET');
    expect(url).toContain('thresholdSeconds=30');
    expect(url).toContain('page=1');
    expect(url).toContain('size=20');
    expect(options.credentials).toBe('include');
  });

  it('rejects failed requests and malformed page payloads', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(fetchLongDecisions('account', 'BLITZ', 30, 0, 20)).rejects.toThrow(
      'Failed to load long decisions: 500',
    );

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ content: 'not-a-list' }),
      }),
    );
    await expect(fetchLongDecisions('account', 'BLITZ', 30, 0, 20)).rejects.toThrow(
      'unexpected response body',
    );
  });
});
