import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchPuzzleAttemptCount, recordPuzzleEvent } from '../services/api';

describe('submitted-answer count API contract', () => {
  beforeEach(() => { vi.stubGlobal('fetch', vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('uses an explicit account/color with session credentials', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ solvedCount: 2, failedCount: 3 })));
    expect(await fetchPuzzleAttemptCount('puzzle-id', 'account-id', 'BLACK')).toEqual({ solvedCount: 2, failedCount: 3 });
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/puzzles/puzzle-id/attempt-count?accountId=account-id&playerColor=BLACK'),
      expect.objectContaining({ credentials: 'include' }));
  });

  describe.each(['solvedCount', 'failedCount'])('%s validation', (field) => {
    it.each([-1, 0.5, '5', null, undefined, Number.MAX_SAFE_INTEGER + 1])('rejects invalid count %s', async (value) => {
      vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ solvedCount: 0, failedCount: 0, [field]: value })));
      await expect(fetchPuzzleAttemptCount('puzzle', 'account', 'WHITE')).rejects.toThrow();
    });
    it('accepts the maximum safe integer', async () => {
      const counts = { solvedCount: 0, failedCount: 0, [field]: Number.MAX_SAFE_INTEGER };
      vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify(counts)));
      expect(await fetchPuzzleAttemptCount('puzzle', 'account', 'WHITE')).toEqual(counts);
    });
  });

  it('rejects the obsolete combined-only response', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ attemptCount: 5 })));
    await expect(fetchPuzzleAttemptCount('puzzle', 'account', 'WHITE')).rejects.toThrow();
  });

  it('surfaces count read rejection', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('', { status: 401 }));
    await expect(fetchPuzzleAttemptCount('puzzle', 'account', 'WHITE')).rejects.toThrow();
  });

  it('serializes a submission identity independently of timing data', async () => {
    const request = { positionId: 'puzzle', playerColor: 'WHITE' as const, accountId: 'account',
      eventType: 'FAILED' as const, submissionId: crypto.randomUUID(), submittedMove: 'd4' };
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ submissionId: request.submissionId }), { status: 202 }));
    await recordPuzzleEvent(request);
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/puzzles/events'),
      expect.objectContaining({ credentials: 'include', body: JSON.stringify(request) }));
  });

  it('rejects a receipt for a different submission', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ submissionId: 'another-answer' }), { status: 202 }));
    await expect(recordPuzzleEvent({ positionId: 'puzzle', playerColor: 'WHITE', accountId: 'account',
      eventType: 'SOLVED', submissionId: crypto.randomUUID(), submittedMove: 'e4' })).rejects.toThrow('Invalid puzzle submission receipt');
  });
});
