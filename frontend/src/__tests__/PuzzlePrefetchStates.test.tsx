import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

/**
 * Issue #86 – approved puzzle next-page **prefetch** invariant (plan §4 item 2, §7).
 *
 * The background prefetch inside `handleNextPuzzle` is not user-clicked UI, so it
 * shows no error card — but it still must not disguise failure as a successful
 * "no more data" terminal state, and its completion-side lock (`isFetchingMorePuzzles`)
 * must be owned by the current request generation:
 *
 *  1. A prefetch **failure** must remain retryable (do NOT collapse to a terminal
 *     `hasMorePuzzles=false`) and must release the lock so a later navigation can
 *     retry the prefetch.
 *  2. A **stale** prefetch that settles after a superseding load (username change)
 *     must be a total no-op — it must not append its rows onto the newer list.
 *
 * Written test-first: the current production code maps a prefetch failure to
 * `setHasMorePuzzles(false)` (terminal) and has no generation guard, so these are
 * expected to be red until implementation.
 */

vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard" />,
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    startImportJob: vi.fn(),
    pollJobStatus: vi.fn(),
    fetchPuzzles: vi.fn(),
    fetchWeaknesses: vi.fn(),
  };
});

function mkPuzzle(id: string, title: string): Puzzle {
  return {
    puzzleId: id,
    fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
    playerColor: 'WHITE',
    targetMove: 'e4',
    openingTitle: title,
    acceptableMoves: [],
    movesPlayed: [],
    priority: 1,
    timesReached: 10,
    mistakeCount: 2,
    mistakeRate: 20,
    evalCp: 35,
  };
}

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

async function flush(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function startImportForUsername(name: string): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: /^import games$/i }));
  const input = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
  fireEvent.change(input, { target: { value: name } });
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: /start import/i }));
    await Promise.resolve();
  });
}

describe('Puzzle next-page prefetch failure/lock invariant (Issue #86)', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // The prefetch failure must NOT be treated as terminal exhaustion. After a
  // failed prefetch, a later navigation must retry the prefetch (which also proves
  // the prefetch lock was released), rather than being permanently disabled.
  it('a failed prefetch is retryable and does not collapse to a terminal "no more" state', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    vi.mocked(api.fetchPuzzles)
      .mockResolvedValueOnce([mkPuzzle('p0', 'Opening Zero'), mkPuzzle('p1', 'Opening One')])
      .mockRejectedValueOnce(new Error('prefetch HTTP 500'))
      .mockResolvedValueOnce([mkPuzzle('p2', 'Opening Two')]);

    render(<Home />);

    const next = await screen.findByRole('button', { name: /Next Puzzle/i });
    await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalledTimes(1));

    // First navigation triggers the page-1 prefetch, which fails.
    await act(async () => {
      fireEvent.click(next);
      await Promise.resolve();
    });
    await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalledTimes(2));
    await flush(); // let the rejection's finally settle (release the lock)

    // Because failure must not collapse to terminal exhaustion, a later navigation
    // retries the prefetch instead of being permanently disabled.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Next Puzzle/i }));
      await Promise.resolve();
    });

    await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalledTimes(3));

    // The retry targeted the same next page (page 1), proving the failure did not
    // advance/terminate pagination state.
    const thirdCallPage = vi.mocked(api.fetchPuzzles).mock.calls[2][6];
    expect(thirdCallPage).toBe(1);
  });

  // A stale prefetch (for the previous username/generation) that settles after a
  // superseding load must be a total no-op: it must not append its rows to the
  // newer generation's list. Uses the reachable Import-flow username change.
  it('a stale prefetch that settles after a username change cannot append to the newer list', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    vi.mocked(api.startImportJob).mockResolvedValue({ jobId: 'job-1', status: 'QUEUED' });
    vi.mocked(api.pollJobStatus).mockResolvedValue({
      jobId: 'job-1',
      status: 'PROCESSING',
      gamesImported: 0,
      gamesSkipped: 0,
    });

    const q: Deferred<Puzzle[]>[] = [];
    vi.mocked(api.fetchPuzzles).mockImplementation(() => {
      const d = deferred<Puzzle[]>();
      q.push(d);
      return d.promise;
    });

    render(<Home />);
    await waitFor(() => expect(q.length).toBe(1)); // initial load A (hikaru)

    // A resolves with two puzzles so navigation reaches the prefetch trigger.
    await act(async () => {
      q[0].resolve([mkPuzzle('a0', 'Alpha Zero'), mkPuzzle('a1', 'Alpha One')]);
      await Promise.resolve();
    });

    // Navigate to start a background prefetch (page 1) that stays pending.
    const next = await screen.findByRole('button', { name: /Next Puzzle/i });
    await act(async () => {
      fireEvent.click(next);
      await Promise.resolve();
    });
    await waitFor(() => expect(q.length).toBe(2)); // stale prefetch pending (q[1])

    // Supersede via a real username change through the Import flow.
    await startImportForUsername('magnus');
    await waitFor(() => expect(q.length).toBe(3)); // newer initial load B (q[2])

    // Newer B wins with a single, distinctly-titled puzzle.
    await act(async () => {
      q[2].resolve([mkPuzzle('m0', 'Magnus Opening')]);
      await Promise.resolve();
    });

    fireEvent.click(screen.getByRole('button', { name: /^practice puzzles$/i }));
    await waitFor(() => expect(screen.getByText('Magnus Opening')).toBeInTheDocument());

    // The STALE prefetch (from the hikaru generation) resolves last. It must not
    // append onto the newer magnus list.
    await act(async () => {
      q[1].resolve([mkPuzzle('stale-pf', 'Stale Prefetch Opening')]);
      await Promise.resolve();
    });

    // Navigate: if the stale rows had leaked in, the next puzzle would surface
    // "Stale Prefetch Opening"; with generation ownership it stays on Magnus.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Next Puzzle/i }));
      await Promise.resolve();
    });

    expect(screen.queryByText('Stale Prefetch Opening')).not.toBeInTheDocument();
    expect(screen.getByText('Magnus Opening')).toBeInTheDocument();
  });
});
