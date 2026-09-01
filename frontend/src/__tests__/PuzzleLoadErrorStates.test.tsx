import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

/**
 * Issue #86 – puzzle loading must distinguish loading / success-with-data /
 * success-with-empty / error, must never present a failed load as an empty
 * result, must offer a Retry, and must not let a stale/superseded completion
 * mutate the UI (request-generation ownership).
 *
 * These are written test-first: several assertions target behavior that does not
 * yet exist in production (an explicit puzzle error card + Retry, and a
 * sequence/ownership guard), so they are expected to be red until implementation.
 */

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

const mockPuzzles: Puzzle[] = [
  {
    puzzleId: 'puzzle-id-1',
    fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
    playerColor: 'WHITE',
    targetMove: 'e4',
    openingTitle: "King's Pawn Opening",
    acceptableMoves: [],
    movesPlayed: [{ move: 'd4', timesPlayed: 2, averageLoss: 0.8 }],
    priority: 1.0,
    timesReached: 10,
    mistakeCount: 2,
    mistakeRate: 20.0,
    evalCp: 35,
  },
];

// A second, distinctly-titled successful puzzle set used for the newer (winning)
// load in the overlapping-load tests below.
const magnusPuzzles: Puzzle[] = [
  {
    ...mockPuzzles[0],
    puzzleId: 'magnus-puzzle-1',
    openingTitle: 'Magnus Opening',
  },
];

// A distinctly-titled set used as the *stale* (superseded) completion payload so
// its appearance in the DOM would prove a stale load leaked into the UI.
const stalePuzzles: Puzzle[] = [
  {
    ...mockPuzzles[0],
    puzzleId: 'stale-puzzle-1',
    openingTitle: 'Stale Opening',
  },
];

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

/**
 * Drives a REAL second puzzle-load trigger that stays reachable while `Home`
 * remains mounted: changing the active username through the Import flow. This is
 * the reviewer-approved way to produce a genuine overlapping puzzle load for user
 * A racing a newer load for user B, without relying on Disconnect. The puzzle
 * effect keyed on `activeUsername` re-fires when the import sets the new user.
 */
async function startImportForUsername(name: string): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: /^import games$/i }));
  const input = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
  fireEvent.change(input, { target: { value: name } });
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: /start import/i }));
    await Promise.resolve();
  });
}

function goToPuzzlesTab(): void {
  fireEvent.click(screen.getByRole('button', { name: /^practice puzzles$/i }));
}

describe('Puzzle load error states (Issue #86)', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // T5: failure renders a clear error card, NOT the empty state.
  it('T5: renders an explicit error card (not the empty state) when the initial puzzle load fails', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    vi.mocked(api.fetchPuzzles).mockRejectedValue(new Error('HTTP 500'));

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText(/We couldn't load your puzzles/i)).toBeInTheDocument();
    });

    // A load failure must never masquerade as "you have no puzzles".
    expect(screen.queryByText(/No Practice Puzzles Available/i)).not.toBeInTheDocument();
  });

  // T6: success with data renders the board (existing behavior preserved).
  it('T6: renders puzzle data normally on a successful non-empty response', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    vi.mocked(api.fetchPuzzles).mockResolvedValue(mockPuzzles);

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });
    expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();
  });

  // T7: success with empty data renders the existing empty state, not the error card.
  it('T7: renders the empty state (not an error) on a successful empty response', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    });
    expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();
  });

  // T11: after an error, Retry re-invokes the loader and renders data on success.
  it('T11: Retry after an error re-runs the puzzle load and renders data on success', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    vi.mocked(api.fetchPuzzles)
      .mockRejectedValueOnce(new Error('HTTP 500'))
      .mockResolvedValueOnce(mockPuzzles);

    render(<Home />);

    const retry = await screen.findByRole('button', { name: /Retry/i });
    expect(screen.getByText(/We couldn't load your puzzles/i)).toBeInTheDocument();

    fireEvent.click(retry);

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });
    expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();
  });

  // T15: disconnect clears the error state; a late reject from the pre-disconnect
  // request must not re-raise the error afterward.
  it('T15: disconnecting clears the puzzle error and a late reject does not re-raise it', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    const first = deferred<Puzzle[]>();
    vi.mocked(api.fetchPuzzles).mockReturnValueOnce(first.promise);

    render(<Home />);

    await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalledTimes(1));
    expect(screen.getByText(/Chess\.com Connected/i)).toBeInTheDocument();
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Disconnect/i }));
      first.reject(new Error('late HTTP 500'));
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByText(/Not Connected/i)).toBeInTheDocument();
      expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    });

    expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();
    expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
  });

  // T19: a stale completion (after a superseding generation bump via disconnect)
  // is a total no-op — it must not repopulate data nor drop/flip loading/UI state.
  it('T19: a stale success that resolves after disconnect does not repopulate puzzles', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    const first = deferred<Puzzle[]>();
    vi.mocked(api.fetchPuzzles).mockReturnValueOnce(first.promise);

    render(<Home />);

    await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalledTimes(1));
    expect(screen.getByText(/Chess\.com Connected/i)).toBeInTheDocument();
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Disconnect/i }));
      first.resolve(mockPuzzles);
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByText(/Not Connected/i)).toBeInTheDocument();
      expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    });

    expect(screen.queryByText("King's Pawn Opening")).not.toBeInTheDocument();
    expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    // The stale finally must not leave a stranded spinner either.
    expect(screen.queryByText(/Loading Practice Puzzles/i)).not.toBeInTheDocument();
  });

  // T15 (R14): a puzzle error that is ALREADY VISIBLE must be cleared when the
  // account disconnects — an error is neither "no data" nor a fresh load failure,
  // so disconnecting must render the empty/import state, never the error card.
  it('T15: an already-visible puzzle error is cleared by Disconnect (R14)', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    vi.mocked(api.fetchPuzzles).mockRejectedValue(new Error('HTTP 500'));

    render(<Home />);

    // The error card is rendered first (start from a visible error state).
    await waitFor(() => {
      expect(screen.getByText(/We couldn't load your puzzles/i)).toBeInTheDocument();
    });

    // Disconnect must clear the visible error and show the empty/import state.
    fireEvent.click(screen.getByRole('button', { name: /Disconnect/i }));

    await waitFor(() => {
      expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    });
    expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();
  });

  // T13: a TRUE overlapping puzzle load. Load A (user "hikaru") is still in flight
  // when a newer load B (user "magnus") is triggered via the reachable Import flow.
  // B resolves with data first; then the stale A rejects. The stale rejection must
  // NOT overwrite the newer successful data with an error/empty state.
  it('T13: a stale reject from a superseded puzzle load cannot overwrite the newer success', async () => {
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
    await waitFor(() => expect(q.length).toBe(1)); // load A (hikaru) pending

    await startImportForUsername('magnus');
    await waitFor(() => expect(q.length).toBe(2)); // newer load B (magnus) pending

    // Newer B resolves with distinctive data.
    await act(async () => {
      q[1].resolve(magnusPuzzles);
      await Promise.resolve();
    });

    // Stale A rejects afterwards — must be a total no-op.
    await act(async () => {
      q[0].reject(new Error('stale HTTP 500'));
      await Promise.resolve();
    });

    goToPuzzlesTab();

    await waitFor(() => expect(screen.getByText('Magnus Opening')).toBeInTheDocument());
    expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/No Practice Puzzles Available/i)).not.toBeInTheDocument();
  });

  // T13 (resolve variant): the same overlapping load, but the stale A resolves with
  // its own (different) data after B has already won. Stale success must also be
  // ignored — request-generation ownership governs stale resolves, not just rejects.
  it('T13: a stale resolve from a superseded puzzle load cannot overwrite the newer success', async () => {
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
    await waitFor(() => expect(q.length).toBe(1)); // load A pending

    await startImportForUsername('magnus');
    await waitFor(() => expect(q.length).toBe(2)); // load B pending

    await act(async () => {
      q[1].resolve(magnusPuzzles);
      await Promise.resolve();
    });

    // Stale A resolves with different data afterwards — must be ignored.
    await act(async () => {
      q[0].resolve(stalePuzzles);
      await Promise.resolve();
    });

    goToPuzzlesTab();

    await waitFor(() => expect(screen.getByText('Magnus Opening')).toBeInTheDocument());
    expect(screen.queryByText('Stale Opening')).not.toBeInTheDocument();
  });

  // T19 (real overlapping load): the loading-flag ownership case the plan requires.
  // Load A is settled while a newer load B is STILL PENDING. A's completion must be
  // a total no-op — it must not drop the spinner (B still owns loading) nor apply
  // A's data. Proves the guarded `finally`, not just the data/error setters.
  it('T19: a stale completion while a newer load is still pending does not drop the spinner or apply its data', async () => {
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
    await waitFor(() => expect(q.length).toBe(1)); // A pending
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

    await startImportForUsername('magnus');
    await waitFor(() => expect(q.length).toBe(2)); // B pending, still loading

    // Observe the spinner on the puzzles tab while B is in flight.
    goToPuzzlesTab();
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

    // Stale A resolves with data while B is still pending — must be a no-op:
    // the spinner (owned by the in-flight B) must remain, and A's data must not show.
    await act(async () => {
      q[0].resolve(stalePuzzles);
      await Promise.resolve();
    });

    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
    expect(screen.queryByText('Stale Opening')).not.toBeInTheDocument();

    // Only when the newer B resolves does the UI reflect B.
    await act(async () => {
      q[1].resolve(magnusPuzzles);
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('Magnus Opening')).toBeInTheDocument());
    expect(screen.queryByText(/Loading Practice Puzzles/i)).not.toBeInTheDocument();
  });

  // T19 (reject variant): the same ownership contract for a stale rejection while a
  // newer load is still pending — a stale reject must not drop the spinner nor
  // surface an error for the superseded generation.
  it('T19: a stale reject while a newer load is still pending does not drop the spinner or raise an error', async () => {
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
    await waitFor(() => expect(q.length).toBe(1)); // A pending

    await startImportForUsername('magnus');
    await waitFor(() => expect(q.length).toBe(2)); // B pending

    goToPuzzlesTab();
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

    // Stale A rejects while B is still pending — must be a no-op.
    await act(async () => {
      q[0].reject(new Error('stale HTTP 500'));
      await Promise.resolve();
    });

    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
    expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();

    await act(async () => {
      q[1].resolve(magnusPuzzles);
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('Magnus Opening')).toBeInTheDocument());
  });

  it.each(['resolve', 'reject'] as const)(
    'cleanup ownership: an old puzzle load cannot affect a remount when it later %ss',
    async (settlement) => {
      localStorage.setItem('chessecho_username', 'hikaru');
      const oldLoad = deferred<Puzzle[]>();
      const newLoad = deferred<Puzzle[]>();
      vi.mocked(api.fetchPuzzles)
        .mockReturnValueOnce(oldLoad.promise)
        .mockReturnValueOnce(newLoad.promise);

      const firstMount = render(<Home />);
      await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalledTimes(1));
      expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

      firstMount.unmount();
      localStorage.setItem('chessecho_username', 'magnus');
      render(<Home />);
      await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalledTimes(2));
      expect(screen.getByText(/Chess\.com Connected/i)).toBeInTheDocument();
      expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

      await act(async () => {
        if (settlement === 'resolve') {
          oldLoad.resolve(stalePuzzles);
        } else {
          oldLoad.reject(new Error('stale unmounted HTTP 500'));
        }
        await Promise.resolve();
      });

      expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
      expect(screen.queryByText('Stale Opening')).not.toBeInTheDocument();
      expect(screen.queryByText(/We couldn't load your puzzles/i)).not.toBeInTheDocument();

      await act(async () => {
        newLoad.resolve(magnusPuzzles);
        await Promise.resolve();
      });
      await waitFor(() => expect(screen.getByText('Magnus Opening')).toBeInTheDocument());
      expect(screen.queryByText(/Loading Practice Puzzles/i)).not.toBeInTheDocument();
    }
  );
});
