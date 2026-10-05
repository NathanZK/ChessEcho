import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { usePuzzleAttemptCount, type PuzzleAttemptContext } from '../hooks/usePuzzleAttemptCount';
import * as api from '../services/api';

vi.mock('../services/api', () => ({
  fetchPuzzleAttemptCount: vi.fn(),
  recordPuzzleEvent: vi.fn(),
  PuzzleSubmissionError: class extends Error {
    constructor(message: string, public status: number) { super(message); }
  },
}));

const context: PuzzleAttemptContext = { userId: 'alice', accountId: 'account-a', puzzleId: 'puzzle-a', playerColor: 'WHITE' };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

describe('personal submitted-answer count lifecycle', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 0, failedCount: 0 });
    vi.mocked(api.recordPuzzleEvent).mockResolvedValue(undefined);
  });

  it('counts correct and incorrect submissions without using timer identities', async () => {
    const { result } = renderHook(() => usePuzzleAttemptCount(context, context.userId));
    await waitFor(() => expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 0, failedCount: 0 }));
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 0, failedCount: 1 });
    await act(async () => { await result.current.submit('d4', false); });
    expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 0, failedCount: 1 });
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 1, failedCount: 1 });
    await act(async () => { await result.current.submit('e4', true); });
    const calls = vi.mocked(api.recordPuzzleEvent).mock.calls;
    expect(calls.map(([request]) => request.eventType)).toEqual(['FAILED', 'SOLVED']);
    expect(calls[0][0]).toMatchObject({ accountId: 'account-a', positionId: 'puzzle-a', submittedMove: 'd4' });
    expect(calls[0][0].submissionId).not.toEqual(calls[1][0].submissionId);
    expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 1, failedCount: 1 });
  });

  it('retains an immutable identity for retry after a lost write response', async () => {
    const { result } = renderHook(() => usePuzzleAttemptCount(context, context.userId));
    await waitFor(() => expect(result.current.state.status).toBe('loaded'));
    vi.mocked(api.recordPuzzleEvent).mockRejectedValueOnce(new TypeError('Lost response'));
    await act(async () => { await result.current.submit('e4', true); });
    expect(result.current.pending[0]).toMatchObject({ retryable: true });
    const original = vi.mocked(api.recordPuzzleEvent).mock.calls[0][0];
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 1, failedCount: 0 });
    await act(async () => { await result.current.retry(original.submissionId!); });
    expect(vi.mocked(api.recordPuzzleEvent).mock.calls[1][0]).toEqual(original);
    expect(result.current.pending).toHaveLength(0);
    expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 1, failedCount: 0 });
  });

  it('does not fabricate zero after read failure and permits recovery', async () => {
    vi.mocked(api.fetchPuzzleAttemptCount).mockRejectedValueOnce(new Error('Unavailable'));
    const { result } = renderHook(() => usePuzzleAttemptCount(context, context.userId));
    await waitFor(() => expect(result.current.state.status).toBe('error'));
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 2, failedCount: 3 });
    await act(async () => { await result.current.reload(); });
    expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 2, failedCount: 3 });
  });

  it('does not let an older read replace a newer read for the same context', async () => {
    const old = deferred<{ solvedCount: number; failedCount: number }>();
    vi.mocked(api.fetchPuzzleAttemptCount).mockReturnValueOnce(old.promise);
    const { result } = renderHook(() => usePuzzleAttemptCount(context, context.userId));
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 1, failedCount: 1 });
    await act(async () => { await result.current.reload(); });
    await act(async () => { old.resolve({ solvedCount: 0, failedCount: 0 }); });
    expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 1, failedCount: 1 });
  });

  it('does not expose another puzzle count when a delayed read completes', async () => {
    const old = deferred<{ solvedCount: number; failedCount: number }>();
    vi.mocked(api.fetchPuzzleAttemptCount).mockReturnValueOnce(old.promise);
    const { result, rerender } = renderHook(({ selected }) => usePuzzleAttemptCount(selected, selected?.userId),
      { initialProps: { selected: context as PuzzleAttemptContext | null } });
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 3, failedCount: 4 });
    rerender({ selected: { ...context, puzzleId: 'puzzle-b' } });
    await waitFor(() => expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 3, failedCount: 4 }));
    await act(async () => { old.resolve({ solvedCount: 99, failedCount: 99 }); });
    expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 3, failedCount: 4 });
    rerender({ selected: null });
    expect(result.current.state.status).toBe('hidden');
  });

  it('retains multiple failures separately and restores them only to their own context', async () => {
    vi.mocked(api.recordPuzzleEvent).mockRejectedValue(new TypeError('Offline'));
    const { result, rerender } = renderHook(({ selected }) => usePuzzleAttemptCount(selected, context.userId),
      { initialProps: { selected: context } });
    await waitFor(() => expect(result.current.state.status).toBe('loaded'));
    await act(async () => { await result.current.submit('e4', true); await result.current.submit('e4', true); });
    expect(result.current.pending).toHaveLength(2);
    rerender({ selected: { ...context, accountId: 'account-b' } });
    expect(result.current.pending).toHaveLength(0);
    rerender({ selected: context });
    expect(result.current.pending).toHaveLength(2);
  });

  it('does not retry terminal rejection and clears pending answers on logout', async () => {
    const { result, rerender } = renderHook(({ owner }) => usePuzzleAttemptCount(owner ? context : null, owner),
      { initialProps: { owner: context.userId as string | undefined } });
    await waitFor(() => expect(result.current.state.status).toBe('loaded'));
    vi.mocked(api.recordPuzzleEvent).mockRejectedValue(new api.PuzzleSubmissionError('Conflict', 409));
    await act(async () => { await result.current.submit('e4', true); });
    expect(result.current.pending[0].retryable).toBe(false);
    await act(async () => { await result.current.retry(result.current.pending[0].id); });
    expect(api.recordPuzzleEvent).toHaveBeenCalledTimes(1);
    rerender({ owner: undefined });
    rerender({ owner: context.userId });
    expect(result.current.pending).toHaveLength(0);
  });

  it('does not let a write completion reconcile another account', async () => {
    const write = deferred<void>();
    vi.mocked(api.recordPuzzleEvent).mockReturnValueOnce(write.promise);
    const { result, rerender } = renderHook(({ selected }) => usePuzzleAttemptCount(selected, context.userId),
      { initialProps: { selected: context } });
    await waitFor(() => expect(result.current.state.status).toBe('loaded'));
    let submission!: Promise<void>;
    act(() => { submission = result.current.submit('e4', true); });
    vi.mocked(api.fetchPuzzleAttemptCount).mockResolvedValue({ solvedCount: 3, failedCount: 4 });
    rerender({ selected: { ...context, accountId: 'account-b' } });
    await waitFor(() => expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 3, failedCount: 4 }));
    await act(async () => { write.resolve(); await submission; });
    expect(result.current.state).toMatchObject({ status: 'loaded', solvedCount: 3, failedCount: 4 });
    expect(vi.mocked(api.recordPuzzleEvent).mock.calls[0][0].accountId).toBe('account-a');
  });
});
