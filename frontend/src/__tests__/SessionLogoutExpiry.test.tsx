import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

/**
 * Issue #113 (AC8, #79 D2, §3.3) — logout/expiry clears private state and cannot
 * be repopulated by a late in-flight response.
 *
 * From an authenticated session with a puzzle load in flight, invoking logout
 * must call the session `logout` API, clear the persisted active job, and bump
 * the monotonic puzzle generation so a late `fetchPuzzles` resolve cannot restore
 * the prior user's private data.
 *
 * Written test-first against the planned logout wiring in `Home`, so the
 * `logout`-call and active-job-clearing assertions are expected to be red until
 * production exists (today the disconnect control does not call the session API
 * nor clear the active job store).
 */

const sessionMocks = vi.hoisted(() => ({
  fetchCurrentSession: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchCurrentSession: sessionMocks.fetchCurrentSession,
    logout: sessionMocks.logout,
    fetchPuzzles: vi.fn(),
    fetchWeaknesses: vi.fn(),
  };
});

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (v: T) => void;
}
function deferred<T>(): Deferred<T> {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

const stalePuzzle: Puzzle = {
  puzzleId: 'stale-puzzle-1',
  fen: 'rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1',
  playerColor: 'BLACK',
  targetMove: 'Nf6',
  openingTitle: 'Stale Position',
  acceptableMoves: [],
  movesPlayed: [],
  priority: 1,
  timesReached: 10,
  mistakeCount: 3,
  mistakeRate: 30,
};

describe('Session logout/expiry clearing (Issue #113)', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
    sessionMocks.fetchCurrentSession.mockResolvedValue({
      status: 'authenticated',
      userId: 'user-1',
      devPrincipal: false,
    });
    sessionMocks.logout.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('logout calls the session API, clears the active job, and drops a late puzzle response', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem(
      'chessecho_active_job',
      JSON.stringify({ jobId: 'job-1', status: 'COMPLETED', gamesImported: 5, gamesSkipped: 0 })
    );

    const pending = deferred<Puzzle[]>();
    vi.mocked(api.fetchPuzzles).mockReturnValue(pending.promise);

    render(<Home />);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalled();
    });

    const logoutControl = screen.getByRole('button', { name: /disconnect|log ?out|sign ?out/i });
    fireEvent.click(logoutControl);

    await waitFor(() => {
      expect(sessionMocks.logout).toHaveBeenCalled();
    });

    expect(localStorage.getItem('chessecho_active_job')).toBeNull();

    // A late resolve of the prior in-flight puzzle load must not repopulate.
    await act(async () => {
      pending.resolve([stalePuzzle]);
      await Promise.resolve();
    });

    expect(screen.queryByText(/Stale Position/i)).not.toBeInTheDocument();
    expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
  });
});
