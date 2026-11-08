import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  triggerRecurringDiscovery,
  getRecurringDiscoveryJobStatus,
  cancelRecurringDiscoveryJob,
  fetchRecurringWeaknesses,
  type RecurringDiscoveryRequest,
  type RecurringDiscoveryJobResponse,
  type RecurringWeaknessResponse,
} from '../services/api';

describe('Recurring Weakness API client', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  const mockJob: RecurringDiscoveryJobResponse = {
    jobId: '11111111-2222-3333-4444-555555555555',
    accountId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    status: 'PROCESSING',
    progressPercent: 45,
    stage: 'CLUSTERING',
    errorMessage: null,
    createdAt: '2026-11-09T00:00:00Z',
    updatedAt: '2026-11-09T00:01:00Z',
  };

  const mockWeakness: RecurringWeaknessResponse = {
    clusterId: 'c1111111-2222-3333-4444-555555555555',
    representativeFen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
    memberPositionCount: 5,
    totalOccurrences: 20,
    distinctGamesCount: 15,
    mistakeCount: 12,
    mistakeFrequency: 0.6,
    blunderRate: 0.25,
    evalLossDistribution: { '0.8-1.5': 8, '1.5-3.0': 4 },
    evalLossPercentages: { '0.8-1.5': 66.7, '1.5-3.0': 33.3 },
    timingProfile: { 'FAST': 3, 'NORMAL': 10, 'SLOW': 7 },
    formatBreakdown: { 'RAPID': 12, 'BLITZ': 8 },
    practiceContinuationUrl: '/api/puzzles/continuation?clusterId=c1111111',
  };

  it('triggers recurring discovery with request payload and double-submit CSRF headers', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 202,
        json: async () => mockJob,
      })
    );

    const request: RecurringDiscoveryRequest = {
      accountId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      timeControls: ['RAPID', 'BLITZ'],
      minEvalLoss: 0.8,
    };

    const result = await triggerRecurringDiscovery(request);
    expect(result).toEqual(mockJob);

    const [url, options] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/weaknesses/recurring/discover');
    expect(options.method).toBe('POST');
    expect(options.headers).toMatchObject({
      'Content-Type': 'application/json',
    });
    expect(JSON.parse(options.body as string)).toEqual(request);
  });

  it('polls job status by ID', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => mockJob,
      })
    );

    const result = await getRecurringDiscoveryJobStatus('11111111-2222-3333-4444-555555555555');
    expect(result).toEqual(mockJob);

    const [url, options] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/weaknesses/recurring/jobs/11111111-2222-3333-4444-555555555555');
    expect(options.method).toBe('GET');
  });

  it('cancels discovery job with account query parameters', async () => {
    const cancelledJob = { ...mockJob, status: 'CANCELLED' as const };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => cancelledJob,
      })
    );

    const result = await cancelRecurringDiscoveryJob(
      '11111111-2222-3333-4444-555555555555',
      'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
    );
    expect(result).toEqual(cancelledJob);

    const [url, options] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/weaknesses/recurring/jobs/11111111-2222-3333-4444-555555555555/cancel?accountId=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
    expect(options.method).toBe('POST');
  });

  it('fetches recurring weaknesses with accountId selector', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => [mockWeakness],
      })
    );

    const result = await fetchRecurringWeaknesses('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
    expect(result).toEqual([mockWeakness]);

    const [url, options] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/weaknesses/recurring?accountId=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
    expect(options.method).toBe('GET');
  });

  it('fetches recurring weaknesses with username selector for guests', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => [mockWeakness],
      })
    );

    const result = await fetchRecurringWeaknesses(undefined, 'magnus');
    expect(result).toEqual([mockWeakness]);

    const [url] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/weaknesses/recurring?username=magnus');
  });

  it('throws descriptive error on failed discovery trigger', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        json: async () => ({ error: 'Invalid time controls' }),
      })
    );

    await expect(
      triggerRecurringDiscovery({ accountId: 'uuid', timeControls: ['INVALID'] })
    ).rejects.toThrow('Invalid time controls');
  });
});
