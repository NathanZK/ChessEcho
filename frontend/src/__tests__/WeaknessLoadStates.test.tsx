import React from 'react';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { WeaknessesList } from '../components/WeaknessesList';
import * as api from '../services/api';
import { WeaknessResponse } from '../services/api';

/**
 * Issue #86 – weakness loading/pagination failure handling and stale-request
 * (request-generation ownership) behavior.
 *
 * Written test-first. Assertions that target not-yet-implemented behavior — a
 * working Retry, a stable pagination error state, and disconnect/stale-completion
 * invalidation — are expected to be red until implementation.
 */

vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard">Mock Chessboard</div>,
}));

// Controllable IntersectionObserver so tests can drive sentinel intersections.
type IOCallback = (entries: Array<{ isIntersecting: boolean }>) => void;
let observers: Array<{ cb: IOCallback }> = [];

class MockIntersectionObserver {
  cb: IOCallback;
  constructor(cb: IOCallback) {
    this.cb = cb;
    observers.push(this);
  }
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn(() => {
    observers = observers.filter((o) => o !== this);
  });
}

function fireSentinelIntersect() {
  act(() => {
    observers.forEach((o) => o.cb([{ isIntersecting: true }]));
  });
}

Object.defineProperty(window, 'IntersectionObserver', {
  writable: true,
  configurable: true,
  value: MockIntersectionObserver,
});
Object.defineProperty(global, 'IntersectionObserver', {
  writable: true,
  configurable: true,
  value: MockIntersectionObserver,
});

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchWeaknesses: vi.fn(),
  };
});

const baseItem: WeaknessResponse = {
  positionId: 'w-pos-base',
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

function makeItem(id: string, over: Partial<WeaknessResponse> = {}): WeaknessResponse {
  return { ...baseItem, positionId: id, ...over };
}

const PAGE_SIZE = 20;
const fullPage = (prefix: string) =>
  Array.from({ length: PAGE_SIZE }, (_, i) => makeItem(`${prefix}-${i}`));

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (v: T) => void;
  reject: (e: unknown) => void;
}
function deferred<T>(): Deferred<T> {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('Weakness load & pagination failure states (Issue #86)', () => {
  beforeEach(() => {
    observers = [];
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // T12: the initial-load Retry must actually refetch (the current handler is a
  // no-op) and render cards on a subsequent success.
  it('T12: Retry after an initial-load error refetches and renders weakness cards', async () => {
    vi.mocked(api.fetchWeaknesses)
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValueOnce([makeItem('w-retry')]);

    render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

    const retry = await screen.findByRole('button', { name: /Retry/i });
    expect(api.fetchWeaknesses).toHaveBeenCalledTimes(1);

    fireEvent.click(retry);

    await waitFor(() => {
      expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => {
      expect(screen.getByText('33.3% (5x)')).toBeInTheDocument();
    });
    expect(screen.queryByText(/We couldn't load your weaknesses/i)).not.toBeInTheDocument();
  });

  // T14: a stale initial-load rejection arriving AFTER a newer successful load
  // must not replace the rendered grid with the error card.
  it('T14: a stale reject cannot overwrite a newer successful load', async () => {
    const q: Deferred<WeaknessResponse[]>[] = [];
    vi.mocked(api.fetchWeaknesses).mockImplementation(() => {
      const d = deferred<WeaknessResponse[]>();
      q.push(d);
      return d.promise;
    });

    const { rerender } = render(
      <WeaknessesList username="hikaru" minEvalLoss={0.8} onSelectPractice={vi.fn()} />
    );

    await waitFor(() => expect(q.length).toBe(1)); // load A pending

    // Trigger a newer load B by changing a filter prop.
    rerender(
      <WeaknessesList username="hikaru" minEvalLoss={0.5} onSelectPractice={vi.fn()} />
    );
    await waitFor(() => expect(q.length).toBe(2)); // load B pending

    // B (newer) resolves with distinctive data.
    await act(async () => {
      q[1].resolve([makeItem('w-newer', { mistakeRate: 11.1, mistakeCount: 1 })]);
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('11.1% (1x)')).toBeInTheDocument());

    // A (stale) rejects afterwards — must be ignored.
    await act(async () => {
      q[0].reject(new Error('stale HTTP 500'));
      await Promise.resolve();
    });

    expect(screen.getByText('11.1% (1x)')).toBeInTheDocument();
    expect(screen.queryByText(/We couldn't load your weaknesses/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Failed to Load Weaknesses')).not.toBeInTheDocument();
  });

  // T20 (stale RESOLVE): a stale completion must not clear the loading flag NOR
  // release the shared `isFetchingRef` lock for a newer in-flight load. The key
  // concurrency assertion: while newer load B still owns the generation, a stale A
  // completion must not let a concurrent sentinel intersection slip an extra
  // page-1 `fetchWeaknesses` call through the lock. Catches an implementation that
  // guards `setWeaknesses`/`setError` but leaves the loading flag / lock release
  // unguarded in the completion `finally`.
  it('T20: a stale resolve does not drop the spinner or release the fetch lock while a newer load is in flight', async () => {
    const q: Deferred<WeaknessResponse[]>[] = [];
    vi.mocked(api.fetchWeaknesses).mockImplementation(() => {
      const d = deferred<WeaknessResponse[]>();
      q.push(d);
      return d.promise;
    });

    const { rerender } = render(
      <WeaknessesList username="hikaru" minEvalLoss={0.8} onSelectPractice={vi.fn()} />
    );
    await waitFor(() => expect(q.length).toBe(1)); // A pending (owns isLoading + isFetchingRef)
    expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();

    rerender(
      <WeaknessesList username="hikaru" minEvalLoss={0.5} onSelectPractice={vi.fn()} />
    );
    await waitFor(() => expect(q.length).toBe(2)); // B pending, still loading

    // Stale A resolves with a FULL page (so a buggy path would set hasMore=true and
    // render A's grid). Its finally must NOT clear the current spinner nor unlock.
    await act(async () => {
      q[0].resolve(
        Array.from({ length: PAGE_SIZE }, (_, i) => makeItem(`stale-A-${i}`, { mistakeRate: 88.8, mistakeCount: 8 }))
      );
      await Promise.resolve();
    });

    // Loading flag ownership: spinner stays (B still in flight); A's data absent.
    expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();
    expect(screen.queryByText('88.8% (8x)')).not.toBeInTheDocument();

    // Fetch-lock ownership: a sentinel intersection must not issue an extra page
    // request while B owns the generation/lock. Only A and B were requested.
    fireSentinelIntersect();
    await act(async () => {
      await Promise.resolve();
    });
    expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2);

    // Newer B resolves — now the grid appears.
    await act(async () => {
      q[1].resolve([makeItem('w-current', { mistakeRate: 22.2, mistakeCount: 2 })]);
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('22.2% (2x)')).toBeInTheDocument());
  });

  // T20 (stale REJECT): the mirror case for the completion `finally` on the reject
  // path. A stale rejection while a newer load is in flight must not drop the
  // spinner, must not raise the error card for the superseded generation, and must
  // not release the fetch lock (no extra page-1 request may slip through).
  it('T20: a stale reject does not drop the spinner, raise an error, or release the fetch lock while a newer load is in flight', async () => {
    const q: Deferred<WeaknessResponse[]>[] = [];
    vi.mocked(api.fetchWeaknesses).mockImplementation(() => {
      const d = deferred<WeaknessResponse[]>();
      q.push(d);
      return d.promise;
    });

    const { rerender } = render(
      <WeaknessesList username="hikaru" minEvalLoss={0.8} onSelectPractice={vi.fn()} />
    );
    await waitFor(() => expect(q.length).toBe(1)); // A pending
    expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();

    rerender(
      <WeaknessesList username="hikaru" minEvalLoss={0.5} onSelectPractice={vi.fn()} />
    );
    await waitFor(() => expect(q.length).toBe(2)); // B pending, still loading

    // Stale A rejects — must be a total no-op for the superseded generation.
    await act(async () => {
      q[0].reject(new Error('stale HTTP 500'));
      await Promise.resolve();
    });

    // Spinner (owned by B) stays; the stale reject does not surface an error card.
    expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();
    expect(screen.queryByText(/We couldn't load your weaknesses/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Failed to Load Weaknesses')).not.toBeInTheDocument();

    // Fetch-lock ownership: no extra page request may leak through while B owns it.
    fireSentinelIntersect();
    await act(async () => {
      await Promise.resolve();
    });
    expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2);

    // Newer B resolves — the grid appears, proving B still owned the generation.
    await act(async () => {
      q[1].resolve([makeItem('w-current', { mistakeRate: 22.2, mistakeCount: 2 })]);
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('22.2% (2x)')).toBeInTheDocument());
  });

  // T16: a user-triggered pagination failure surfaces an inline error + Retry near
  // the sentinel, preserves retryability (no "no more data" collapse), and Retry
  // appends the next page.
  it('T16: pagination failure shows an inline error + Retry and does not collapse to "no more data"', async () => {
    vi.mocked(api.fetchWeaknesses)
      .mockResolvedValueOnce(fullPage('p0'))
      .mockRejectedValueOnce(new Error('page HTTP 500'))
      .mockResolvedValueOnce([makeItem('p1-only', { averageLoss: 9.99 })]);

    render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

    const loadMore = await screen.findByRole('button', { name: /Load More Weaknesses/i });
    fireEvent.click(loadMore);

    // Inline pagination error region appears with a Retry control.
    const errorRegion = await screen.findByTestId('weaknesses-load-more-error');
    const retry = within(errorRegion).getByRole('button', { name: /Retry/i });

    // Failure must not be silently mapped to a terminal "no more data" state:
    // the manual Load More affordance is replaced by the explicit Retry.
    expect(screen.queryByRole('button', { name: /Load More Weaknesses/i })).not.toBeInTheDocument();

    fireEvent.click(retry);

    await waitFor(() => {
      expect(screen.getByText('-9.99 pawns')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('weaknesses-load-more-error')).not.toBeInTheDocument();
  });

  // T21: the pagination error is a stable, non-auto-retrying state until explicit
  // Retry; automatic sentinel intersections must not re-fire while it is shown.
  it('T21: a pagination error does not auto-retry and is only cleared by explicit Retry', async () => {
    vi.mocked(api.fetchWeaknesses)
      .mockResolvedValueOnce(fullPage('p0'))
      .mockRejectedValueOnce(new Error('page HTTP 500'))
      .mockResolvedValueOnce(fullPage('p1'));

    render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

    const loadMore = await screen.findByRole('button', { name: /Load More Weaknesses/i });
    fireEvent.click(loadMore);

    await screen.findByTestId('weaknesses-load-more-error');
    expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2);

    // Automatic sentinel intersections must NOT trigger further page fetches.
    fireSentinelIntersect();
    fireSentinelIntersect();
    await Promise.resolve();
    expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2);

    // The manual Load More button is suppressed while the error is shown.
    expect(screen.queryByRole('button', { name: /Load More Weaknesses/i })).not.toBeInTheDocument();

    // Explicit Retry clears the error and re-enables pagination.
    const errorRegion = screen.getByTestId('weaknesses-load-more-error');
    fireEvent.click(within(errorRegion).getByRole('button', { name: /Retry/i }));

    await waitFor(() => {
      expect(api.fetchWeaknesses).toHaveBeenCalledTimes(3);
    });
    await waitFor(() => {
      expect(screen.queryByTestId('weaknesses-load-more-error')).not.toBeInTheDocument();
    });

    // With hasMore still true, a subsequent intersection can auto-load again.
    fireSentinelIntersect();
    await waitFor(() => {
      expect(api.fetchWeaknesses).toHaveBeenCalledTimes(4);
    });
  });

  // T22: disconnect invalidates any in-flight weakness generation so a late
  // initial resolve cannot repopulate the list or the parent header count.
  it('T22: a late initial resolve after disconnect does not repopulate list or header count', async () => {
    const onWeaknessCountChange = vi.fn();
    const first = deferred<WeaknessResponse[]>();
    vi.mocked(api.fetchWeaknesses).mockReturnValueOnce(first.promise);

    const { rerender } = render(
      <WeaknessesList
        username="hikaru"
        onSelectPractice={vi.fn()}
        onWeaknessCountChange={onWeaknessCountChange}
      />
    );
    await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(1));

    // Disconnect (account cleared) before the request settles.
    rerender(
      <WeaknessesList
        username={undefined}
        onSelectPractice={vi.fn()}
        onWeaknessCountChange={onWeaknessCountChange}
      />
    );
    await waitFor(() => expect(screen.getByText('No Connected Account')).toBeInTheDocument());

    // The pre-disconnect request resolves with data — must be a total no-op.
    await act(async () => {
      first.resolve([makeItem('w-late', { mistakeRate: 44.4, mistakeCount: 4 })]);
      await Promise.resolve();
    });

    expect(screen.getByText('No Connected Account')).toBeInTheDocument();
    expect(screen.queryByText('44.4% (4x)')).not.toBeInTheDocument();
    // The header count must never be repopulated after disconnect.
    expect(onWeaknessCountChange).not.toHaveBeenCalledWith(1);
    expect(onWeaknessCountChange).toHaveBeenLastCalledWith(0);
  });

  it('T22: a late initial reject after disconnect does not raise the error card', async () => {
    const first = deferred<WeaknessResponse[]>();
    vi.mocked(api.fetchWeaknesses).mockReturnValueOnce(first.promise);

    const { rerender } = render(
      <WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />
    );
    await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(1));

    rerender(<WeaknessesList username={undefined} onSelectPractice={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('No Connected Account')).toBeInTheDocument());

    await act(async () => {
      first.reject(new Error('late HTTP 500'));
      await Promise.resolve();
    });

    // Disconnect is not a load failure: the error card must not appear.
    expect(screen.queryByText(/We couldn't load your weaknesses/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Failed to Load Weaknesses')).not.toBeInTheDocument();
    expect(screen.getByText('No Connected Account')).toBeInTheDocument();
  });

  // T22 (pagination): a late pagination completion after disconnect cannot append
  // rows, flip pagination, set a load-more error, or change the parent count.
  it('T22: a late pagination completion after disconnect is a total no-op', async () => {
    const onWeaknessCountChange = vi.fn();
    const page = deferred<WeaknessResponse[]>();
    vi.mocked(api.fetchWeaknesses)
      .mockResolvedValueOnce(fullPage('p0'))
      .mockReturnValueOnce(page.promise);

    const { rerender } = render(
      <WeaknessesList
        username="hikaru"
        onSelectPractice={vi.fn()}
        onWeaknessCountChange={onWeaknessCountChange}
      />
    );

    const loadMore = await screen.findByRole('button', { name: /Load More Weaknesses/i });
    fireEvent.click(loadMore); // page load in flight
    await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2));

    // Disconnect while page load is pending.
    rerender(
      <WeaknessesList
        username={undefined}
        onSelectPractice={vi.fn()}
        onWeaknessCountChange={onWeaknessCountChange}
      />
    );
    await waitFor(() => expect(screen.getByText('No Connected Account')).toBeInTheDocument());

    // The stale page load resolves — must not repopulate the count.
    await act(async () => {
      page.resolve(fullPage('p1'));
      await Promise.resolve();
    });

    expect(screen.getByText('No Connected Account')).toBeInTheDocument();
    expect(onWeaknessCountChange).toHaveBeenLastCalledWith(0);
    expect(screen.queryByTestId('weaknesses-load-more-error')).not.toBeInTheDocument();
  });

  it('T22: a late pagination rejection after disconnect cannot affect the reconnected query', async () => {
    const onWeaknessCountChange = vi.fn();
    const stalePage = deferred<WeaknessResponse[]>();
    const replacement = deferred<WeaknessResponse[]>();
    const initialRows = fullPage('old').map((item) => ({
      ...item,
      mistakeRate: 66.6,
      mistakeCount: 6,
    }));
    const replacementRows = fullPage('new').map((item) => ({
      ...item,
      mistakeRate: 22.2,
      mistakeCount: 2,
    }));
    vi.mocked(api.fetchWeaknesses)
      .mockResolvedValueOnce(initialRows)
      .mockReturnValueOnce(stalePage.promise)
      .mockReturnValueOnce(replacement.promise)
      .mockResolvedValueOnce([]);

    const props = {
      onSelectPractice: vi.fn(),
      onWeaknessCountChange,
    };
    const { rerender } = render(<WeaknessesList username="hikaru" {...props} />);

    const loadMore = await screen.findByRole('button', { name: /Load More Weaknesses/i });
    await waitFor(() => expect(onWeaknessCountChange).toHaveBeenLastCalledWith(PAGE_SIZE));
    fireEvent.click(loadMore);
    await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.fetchWeaknesses).mock.calls[1][5]).toBe(1);

    rerender(<WeaknessesList username={undefined} {...props} />);
    await waitFor(() => {
      expect(screen.getByText('No Connected Account')).toBeInTheDocument();
      expect(onWeaknessCountChange).toHaveBeenLastCalledWith(0);
    });

    rerender(<WeaknessesList username="magnus" {...props} />);
    await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(3));
    expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();

    await act(async () => {
      stalePage.reject(new Error('stale page HTTP 500'));
      await Promise.resolve();
    });

    expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();
    expect(screen.queryByText('66.6% (6x)')).not.toBeInTheDocument();
    expect(onWeaknessCountChange).toHaveBeenLastCalledWith(0);
    expect(screen.queryByText(/We couldn't load your weaknesses/i)).not.toBeInTheDocument();
    expect(screen.queryByTestId('weaknesses-load-more-error')).not.toBeInTheDocument();
    fireSentinelIntersect();
    await act(async () => {
      await Promise.resolve();
    });
    expect(api.fetchWeaknesses).toHaveBeenCalledTimes(3);

    await act(async () => {
      replacement.resolve(replacementRows);
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getAllByText('22.2% (2x)')).toHaveLength(PAGE_SIZE));
    expect(screen.queryByText('66.6% (6x)')).not.toBeInTheDocument();
    expect(onWeaknessCountChange).toHaveBeenLastCalledWith(PAGE_SIZE);

    fireEvent.click(screen.getByRole('button', { name: /Load More Weaknesses/i }));
    await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(4));
    expect(vi.mocked(api.fetchWeaknesses).mock.calls[3][5]).toBe(1);
  });

  it.each(['resolve', 'reject'] as const)(
    'cleanup ownership: an old weakness load cannot affect a remount when it later %ss',
    async (settlement) => {
      const onWeaknessCountChange = vi.fn();
      const oldLoad = deferred<WeaknessResponse[]>();
      const newLoad = deferred<WeaknessResponse[]>();
      const pageOne = deferred<WeaknessResponse[]>();
      vi.mocked(api.fetchWeaknesses)
        .mockReturnValueOnce(oldLoad.promise)
        .mockReturnValueOnce(newLoad.promise)
        .mockReturnValueOnce(pageOne.promise);

      const firstMount = render(
        <WeaknessesList
          username="hikaru"
          onSelectPractice={vi.fn()}
          onWeaknessCountChange={onWeaknessCountChange}
        />
      );
      await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(1));
      firstMount.unmount();

      render(
        <WeaknessesList
          username="magnus"
          onSelectPractice={vi.fn()}
          onWeaknessCountChange={onWeaknessCountChange}
        />
      );
      await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2));

      await act(async () => {
        if (settlement === 'resolve') {
          oldLoad.resolve([makeItem('old-row', { mistakeRate: 77.7, mistakeCount: 7 })]);
        } else {
          oldLoad.reject(new Error('stale unmounted HTTP 500'));
        }
        await Promise.resolve();
      });

      expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();
      expect(screen.queryByText('77.7% (7x)')).not.toBeInTheDocument();
      expect(screen.queryByText(/We couldn't load your weaknesses/i)).not.toBeInTheDocument();
      expect(api.fetchWeaknesses).toHaveBeenCalledTimes(2);

      await act(async () => {
        newLoad.resolve(fullPage('fresh'));
        await Promise.resolve();
      });
      await waitFor(() => expect(onWeaknessCountChange).toHaveBeenLastCalledWith(PAGE_SIZE));

      fireSentinelIntersect();
      await waitFor(() => expect(api.fetchWeaknesses).toHaveBeenCalledTimes(3));
      expect(vi.mocked(api.fetchWeaknesses).mock.calls[2][5]).toBe(1);
      await act(async () => {
        pageOne.resolve([]);
        await Promise.resolve();
      });
    }
  );
});
