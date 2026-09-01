import React from 'react';
import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { usePuzzleContinuation } from '../utils/usePuzzleContinuation';
import { continuationService } from '../services/continuationService';
import * as api from '../services/api';

const reducerDispatchObserver = vi.hoisted(() => ({
  current: null as null | ((action: unknown) => void),
}));

vi.mock('react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react')>();
  return {
    ...actual,
    useReducer: (reducer: React.Reducer<unknown, unknown>, initialState: unknown) => {
      const [state, dispatch] = actual.useReducer(reducer, initialState);
      const observedDispatch = actual.useCallback((action: unknown) => {
        reducerDispatchObserver.current?.(action);
        dispatch(action);
      }, [dispatch]);
      return [state, observedDispatch];
    },
  };
});

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchPuzzleContinuation: vi.fn(),
  };
});

/**
 * Issue 98 — `usePuzzleContinuation` request lifecycle guards (plan §7.2, T10–T15, T30, T31).
 */
describe('Issue 98 — continuation loading state and request ownership', () => {
  const fen1 = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
  const fen2 = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';
  const fen3 = 'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2';
  const fen4 = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2';

  const responseFor = (
    fen: string,
    requestedMode: api.ContinuationMode = 'ENGINE',
    effectiveProvider: string = 'ENGINE'
  ): api.ContinuationResponse => ({
    fen,
    requestedMode,
    effectiveProvider,
    candidates: [
      { move: 'Bb5', resultingFen: `${fen}_bb5`, providerType: effectiveProvider },
      { move: 'Bc4', resultingFen: `${fen}_bc4`, providerType: effectiveProvider },
    ],
  });

  const pendingForever = (): Promise<api.ContinuationResponse | null> =>
    new Promise<api.ContinuationResponse | null>(() => {});

  const deferred = <T,>() => {
    let resolve!: (value: T) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  };

  beforeEach(() => {
    continuationService.clear();
    vi.clearAllMocks();
    reducerDispatchObserver.current = null;
  });

  afterEach(() => {
    reducerDispatchObserver.current = null;
    vi.restoreAllMocks();
  });

  it('T10 — loading is true in the first committed render when an initialFen is supplied', () => {
    vi.mocked(api.fetchPuzzleContinuation).mockImplementation(pendingForever);

    const committedLoading: boolean[] = [];

    renderHook(() => {
      const continuation = usePuzzleContinuation(fen1, 'ENGINE');
      React.useEffect(() => {
        committedLoading.push(continuation.loading);
      });
      return continuation;
    });

    expect(committedLoading.length).toBeGreaterThan(0);
    expect(committedLoading[0]).toBe(true);
  });

  it('T11 — exposes the nine-member surface with its documented semantics', async () => {
    const { result } = renderHook(() => usePuzzleContinuation());

    expect(Object.keys(result.current).sort()).toEqual(
      [
        'candidates',
        'effectiveProvider',
        'error',
        'fetchContinuation',
        'isFallback',
        'loading',
        'response',
        'selectCandidate',
        'selectedCandidate',
      ].sort()
    );

    expect(typeof result.current.loading).toBe('boolean');
    expect(typeof result.current.error).toBe('boolean');
    expect(typeof result.current.isFallback).toBe('boolean');
    expect(typeof result.current.fetchContinuation).toBe('function');
    expect(typeof result.current.selectCandidate).toBe('function');

    // response === null ⇒ empty candidate list, null provider, no fallback
    expect(result.current.response).toBeNull();
    expect(result.current.candidates).toEqual([]);
    expect(result.current.selectedCandidate).toBeNull();
    expect(result.current.effectiveProvider).toBeNull();
    expect(result.current.isFallback).toBe(false);

    // HUMAN requested but served by the engine ⇒ fallback
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(responseFor(fen1, 'HUMAN', 'ENGINE'));
    const humanFallback = renderHook(() => usePuzzleContinuation(fen1, 'HUMAN'));
    await act(async () => {});
    expect(humanFallback.result.current.effectiveProvider).toBe('ENGINE');
    expect(humanFallback.result.current.isFallback).toBe(true);
    expect(humanFallback.result.current.candidates).toHaveLength(2);
    humanFallback.unmount();

    // ENGINE requested and served by the engine ⇒ no fallback
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(responseFor(fen2, 'ENGINE', 'ENGINE'));
    const engineOnly = renderHook(() => usePuzzleContinuation(fen2, 'ENGINE'));
    await act(async () => {});
    expect(engineOnly.result.current.effectiveProvider).toBe('ENGINE');
    expect(engineOnly.result.current.isFallback).toBe(false);
    engineOnly.unmount();

    // HUMAN requested and served by human data ⇒ no fallback
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(responseFor(fen3, 'HUMAN', 'HUMAN'));
    const humanServed = renderHook(() => usePuzzleContinuation(fen3, 'HUMAN'));
    await act(async () => {});
    expect(humanServed.result.current.effectiveProvider).toBe('HUMAN');
    expect(humanServed.result.current.isFallback).toBe(false);
    humanServed.unmount();
  });

  it('T12 — clearing the initialFen resets the surface and a repeated clear costs no extra render', async () => {
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue(responseFor(fen1));

    const commits: number[] = [];

    const { result, rerender } = renderHook(
      ({ fen }: { fen?: string }) => {
        const continuation = usePuzzleContinuation(fen, 'ENGINE');
        React.useEffect(() => {
          commits.push(1);
        });
        return continuation;
      },
      { initialProps: { fen: fen1 as string | undefined } }
    );

    await act(async () => {});
    expect(result.current.response).not.toBeNull();
    expect(result.current.selectedCandidate).not.toBeNull();

    await act(async () => {
      rerender({ fen: undefined });
    });

    expect(result.current.response).toBeNull();
    expect(result.current.selectedCandidate).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);

    const commitsAfterFirstClear = commits.length;

    await act(async () => {
      rerender({ fen: undefined });
    });

    // The second clear carries the forced rerender only — no state-driven extra render.
    expect(commits.length - commitsAfterFirstClear).toBe(1);
    expect(result.current.response).toBeNull();
    expect(result.current.selectedCandidate).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);
  });

  it('T13 — a stale in-flight response is discarded and does not disturb loading', async () => {
    let resolveFirstFetch: (value: api.ContinuationResponse | null) => void = () => {};
    const firstPromise = new Promise<api.ContinuationResponse | null>((resolve) => {
      resolveFirstFetch = resolve;
    });

    vi.mocked(api.fetchPuzzleContinuation)
      .mockImplementationOnce(() => firstPromise)
      .mockResolvedValueOnce(responseFor(fen2));

    const commits: number[] = [];

    const { result } = renderHook(() => {
      const continuation = usePuzzleContinuation();
      React.useEffect(() => {
        commits.push(1);
      });
      return continuation;
    });

    act(() => {
      void result.current.fetchContinuation(fen1, 'ENGINE');
    });

    act(() => {
      void result.current.fetchContinuation(fen2, 'ENGINE');
    });

    await act(async () => {});

    expect(result.current.response?.fen).toBe(fen2);
    expect(result.current.selectedCandidate?.move).toBe('Bb5');
    expect(result.current.loading).toBe(false);

    const commitsBeforeStaleSettle = commits.length;

    await act(async () => {
      resolveFirstFetch(responseFor(fen1));
    });

    // The stale settle changes nothing: not the response, not loading, not even a render.
    expect(result.current.response?.fen).toBe(fen2);
    expect(result.current.loading).toBe(false);
    expect(commits.length).toBe(commitsBeforeStaleSettle);
  });

  it('T14 — fetchContinuation("") is a complete no-op', async () => {
    const { result } = renderHook(() => usePuzzleContinuation());

    await act(async () => {
      await result.current.fetchContinuation('');
    });

    expect(api.fetchPuzzleContinuation).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);
    expect(result.current.response).toBeNull();
    expect(result.current.selectedCandidate).toBeNull();
  });

  it('T15 — selectCandidate honours the policy and clears on an empty candidate list', async () => {
    const emptyResponse: api.ContinuationResponse = {
      fen: fen1,
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [],
    };
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(emptyResponse);

    const emptyCase = renderHook(() => usePuzzleContinuation(fen1, 'ENGINE'));
    await act(async () => {});

    let selectedFromEmpty: api.ContinuationCandidate | null = { move: 'x', resultingFen: 'x', providerType: 'ENGINE' };
    act(() => {
      selectedFromEmpty = emptyCase.result.current.selectCandidate();
    });
    expect(selectedFromEmpty).toBeNull();
    expect(emptyCase.result.current.selectedCandidate).toBeNull();
    emptyCase.unmount();

    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(responseFor(fen2));
    const populated = renderHook(() => usePuzzleContinuation(fen2, 'ENGINE'));
    await act(async () => {});

    let selected: api.ContinuationCandidate | null = null;
    act(() => {
      selected = populated.result.current.selectCandidate((candidates) => candidates[1] || null);
    });

    expect(selected).not.toBeNull();
    expect(selected!.move).toBe('Bc4');
    expect(populated.result.current.selectedCandidate?.move).toBe('Bc4');
    populated.unmount();
  });

  it('T30 — the imperative path issues exactly one request per call, in call order', async () => {
    let resolveFirstFetch: (value: api.ContinuationResponse | null) => void = () => {};
    const firstPromise = new Promise<api.ContinuationResponse | null>((resolve) => {
      resolveFirstFetch = resolve;
    });

    vi.mocked(api.fetchPuzzleContinuation)
      .mockImplementationOnce(() => firstPromise)
      .mockResolvedValueOnce(responseFor(fen2));

    const separateActs = renderHook(() => usePuzzleContinuation());

    act(() => {
      void separateActs.result.current.fetchContinuation(fen1, 'ENGINE');
    });
    act(() => {
      void separateActs.result.current.fetchContinuation(fen2, 'ENGINE');
    });
    await act(async () => {
      resolveFirstFetch(responseFor(fen1));
    });

    expect(vi.mocked(api.fetchPuzzleContinuation)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.fetchPuzzleContinuation).mock.calls.map((call) => call[0])).toEqual([fen1, fen2]);
    separateActs.unmount();

    // Same scenario, both calls inside one act(): identical count and order.
    continuationService.clear();
    vi.mocked(api.fetchPuzzleContinuation).mockReset();
    vi.mocked(api.fetchPuzzleContinuation)
      .mockImplementationOnce(pendingForever)
      .mockResolvedValueOnce(responseFor(fen4));

    const singleAct = renderHook(() => usePuzzleContinuation());

    await act(async () => {
      void singleAct.result.current.fetchContinuation(fen3, 'ENGINE');
      void singleAct.result.current.fetchContinuation(fen4, 'ENGINE');
    });

    expect(vi.mocked(api.fetchPuzzleContinuation)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.fetchPuzzleContinuation).mock.calls.map((call) => call[0])).toEqual([fen3, fen4]);
    singleAct.unmount();
  });

  it('T31 — the declarative path issues exactly one request, and an imperative call adds exactly one more', async () => {
    let resolveMountFetch: (value: api.ContinuationResponse | null) => void = () => {};
    const mountPromise = new Promise<api.ContinuationResponse | null>((resolve) => {
      resolveMountFetch = resolve;
    });

    vi.mocked(api.fetchPuzzleContinuation)
      .mockImplementationOnce(() => mountPromise)
      .mockResolvedValueOnce(responseFor(fen2));

    const { result } = renderHook(() => usePuzzleContinuation(fen1, 'ENGINE'));

    expect(result.current.loading).toBe(true);
    expect(vi.mocked(api.fetchPuzzleContinuation)).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveMountFetch(responseFor(fen1));
    });

    expect(vi.mocked(api.fetchPuzzleContinuation)).toHaveBeenCalledTimes(1);
    expect(result.current.loading).toBe(false);
    expect(result.current.response?.fen).toBe(fen1);

    await act(async () => {
      await result.current.fetchContinuation(fen2, 'ENGINE');
    });

    expect(vi.mocked(api.fetchPuzzleContinuation)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.fetchPuzzleContinuation).mock.calls.map((call) => call[0])).toEqual([fen1, fen2]);
    expect(result.current.response?.fen).toBe(fen2);
  });

  it('orders same-FEN declarative ENGINE, HUMAN, and rating requests by generation', async () => {
    const engine = deferred<api.ContinuationResponse | null>();
    const humanDefault = deferred<api.ContinuationResponse | null>();
    const humanNewBand = deferred<api.ContinuationResponse | null>();
    const newestResponse = responseFor(fen1, 'HUMAN', 'HUMAN');
    newestResponse.candidates = [
      { move: 'Nf6', resultingFen: `${fen1}_newest`, providerType: 'HUMAN', timesPlayed: 8 },
    ];

    vi.mocked(api.fetchPuzzleContinuation)
      .mockReturnValueOnce(engine.promise)
      .mockReturnValueOnce(humanDefault.promise)
      .mockReturnValueOnce(humanNewBand.promise);

    const commits: string[] = [];
    const { result, rerender } = renderHook(
      ({ mode, ratingBand }: { mode: api.ContinuationMode; ratingBand?: string }) => {
        const continuation = usePuzzleContinuation(fen1, mode, undefined, ratingBand);
        React.useEffect(() => {
          commits.push(JSON.stringify({
            loading: continuation.loading,
            error: continuation.error,
            provider: continuation.effectiveProvider,
            move: continuation.selectedCandidate?.move,
          }));
        });
        return continuation;
      },
      { initialProps: { mode: 'ENGINE' as api.ContinuationMode, ratingBand: undefined as string | undefined } }
    );

    rerender({ mode: 'HUMAN', ratingBand: undefined });
    rerender({ mode: 'HUMAN', ratingBand: '1600-1800' });

    expect(vi.mocked(api.fetchPuzzleContinuation).mock.calls).toEqual([
      [fen1, 'ENGINE', undefined],
      [fen1, 'HUMAN', undefined],
      [fen1, 'HUMAN', '1600-1800'],
    ]);
    expect(result.current.loading).toBe(true);

    await act(async () => {
      humanNewBand.resolve(newestResponse);
    });

    expect(result.current.response).toBe(newestResponse);
    expect(result.current.selectedCandidate?.move).toBe('Nf6');
    expect(result.current.effectiveProvider).toBe('HUMAN');
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);
    const commitsAfterNewest = commits.length;

    await act(async () => {
      engine.resolve(responseFor(fen1, 'ENGINE', 'ENGINE'));
    });
    await act(async () => {
      humanDefault.reject(new Error('stale HUMAN request failed'));
    });

    expect(result.current.response).toBe(newestResponse);
    expect(result.current.selectedCandidate?.resultingFen).toBe(`${fen1}_newest`);
    expect(result.current.effectiveProvider).toBe('HUMAN');
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);
    expect(commits).toHaveLength(commitsAfterNewest);
  });

  it('orders policy-only same-FEN requests by their complete request generation', async () => {
    const staleSuccess = deferred<api.ContinuationResponse | null>();
    const staleFailure = deferred<api.ContinuationResponse | null>();
    const newest = deferred<api.ContinuationResponse | null>();
    const firstPolicy = vi.fn((candidates: api.ContinuationCandidate[]) => candidates[0] ?? null);
    const secondPolicy = vi.fn((candidates: api.ContinuationCandidate[]) => candidates[0] ?? null);
    const newestPolicy = vi.fn((candidates: api.ContinuationCandidate[]) => candidates[1] ?? null);
    const serviceSpy = vi.spyOn(continuationService, 'getContinuation');
    const sharedResponse: api.ContinuationResponse = {
      fen: fen1,
      requestedMode: 'HUMAN',
      effectiveProvider: 'HUMAN',
      candidates: [
        { move: 'Nf6', resultingFen: `${fen1}_stale`, providerType: 'HUMAN', timesPlayed: 10 },
        { move: 'd6', resultingFen: `${fen1}_newest`, providerType: 'HUMAN', timesPlayed: 5 },
      ],
    };

    vi.mocked(api.fetchPuzzleContinuation)
      .mockReturnValueOnce(staleSuccess.promise)
      .mockReturnValueOnce(staleFailure.promise)
      .mockReturnValueOnce(newest.promise);

    const commits: string[] = [];
    const { result, rerender } = renderHook(
      ({ policy }: { policy: typeof firstPolicy }) => {
        const continuation = usePuzzleContinuation(fen1, 'HUMAN', policy, '1600-1800');
        React.useEffect(() => {
          commits.push(JSON.stringify({
            loading: continuation.loading,
            error: continuation.error,
            move: continuation.selectedCandidate?.move,
          }));
        });
        return continuation;
      },
      { initialProps: { policy: firstPolicy } }
    );

    rerender({ policy: secondPolicy });
    rerender({ policy: newestPolicy });

    expect(serviceSpy.mock.calls).toEqual([
      [fen1, 'HUMAN', firstPolicy, '1600-1800'],
      [fen1, 'HUMAN', secondPolicy, '1600-1800'],
      [fen1, 'HUMAN', newestPolicy, '1600-1800'],
    ]);
    expect(vi.mocked(api.fetchPuzzleContinuation).mock.calls).toEqual([
      [fen1, 'HUMAN', '1600-1800'],
      [fen1, 'HUMAN', '1600-1800'],
      [fen1, 'HUMAN', '1600-1800'],
    ]);

    await act(async () => {
      newest.resolve(sharedResponse);
    });

    expect(result.current.response).toBe(sharedResponse);
    expect(result.current.selectedCandidate?.move).toBe('d6');
    expect(result.current.selectedCandidate?.resultingFen).toBe(`${fen1}_newest`);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);
    const commitsAfterNewest = commits.length;

    await act(async () => {
      staleSuccess.resolve(sharedResponse);
    });
    await act(async () => {
      staleFailure.reject(new Error('stale policy request failed'));
    });

    expect(result.current.response).toBe(sharedResponse);
    expect(result.current.selectedCandidate?.move).toBe('d6');
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);
    expect(commits).toHaveLength(commitsAfterNewest);
    serviceSpy.mockRestore();
  });

  it('invalidates same-FEN settlements when cleared', async () => {
    const clearingRequest = deferred<api.ContinuationResponse | null>();
    vi.mocked(api.fetchPuzzleContinuation).mockReturnValueOnce(clearingRequest.promise);
    const clearCommits: number[] = [];
    const cleared = renderHook(
      ({ fen }: { fen?: string }) => {
        const continuation = usePuzzleContinuation(fen, 'ENGINE');
        React.useEffect(() => {
          clearCommits.push(1);
        });
        return continuation;
      },
      { initialProps: { fen: fen1 as string | undefined } }
    );

    cleared.rerender({ fen: undefined });
    expect(cleared.result.current.response).toBeNull();
    expect(cleared.result.current.loading).toBe(false);
    const commitsAfterClear = clearCommits.length;

    await act(async () => {
      clearingRequest.resolve(responseFor(fen1));
    });

    expect(cleared.result.current.response).toBeNull();
    expect(cleared.result.current.selectedCandidate).toBeNull();
    expect(cleared.result.current.loading).toBe(false);
    expect(cleared.result.current.error).toBe(false);
    expect(clearCommits).toHaveLength(commitsAfterClear);
    cleared.unmount();
  });

  it.each([
    ['resolve', (request: ReturnType<typeof deferred<api.ContinuationResponse | null>>) =>
      request.resolve(responseFor(fen1))],
    ['reject', (request: ReturnType<typeof deferred<api.ContinuationResponse | null>>) =>
      request.reject(new Error('late unmounted continuation failure'))],
  ])('invalidates an in-flight continuation before unmount %s settlement can dispatch', async (_, settle) => {
    continuationService.clear();
    const unmountRequest = deferred<api.ContinuationResponse | null>();
    vi.mocked(api.fetchPuzzleContinuation).mockReset();
    vi.mocked(api.fetchPuzzleContinuation).mockReturnValueOnce(unmountRequest.promise);
    const mounted = renderHook(() => usePuzzleContinuation(fen1, 'ENGINE'));
    const postUnmountActions: unknown[] = [];
    reducerDispatchObserver.current = (action) => postUnmountActions.push(action);
    mounted.unmount();

    await act(async () => {
      settle(unmountRequest);
    });

    expect(postUnmountActions).toEqual([]);
    reducerDispatchObserver.current = null;
  });
});
