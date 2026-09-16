import React from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';

/**
 * Issue #113 (AC7/AC13, #79 D2/D7, §3.3) — session bootstrap gating.
 *
 * The app resolves the session from `/api/me` before issuing any personalized
 * request. While the session is unresolved (loading) no `fetchPuzzles`/
 * `fetchWeaknesses` fires even when a stale `chessecho_username` is present in
 * `localStorage`; session state resolves independently; and guest-eligible
 * username-based analysis remains available after an unauthenticated result.
 *
 * Written test-first against the planned `fetchCurrentSession` bootstrap in
 * `Home`, so the gating assertions are expected to be red until production
 * exists (today `Home` fetches directly from the stored username).
 */

interface SessionState {
  status: 'authenticated' | 'unauthenticated' | 'error';
  userId?: string;
  devPrincipal?: boolean;
}

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
    fetchAccounts: vi.fn(),
  };
});

vi.mock('../components/ImportGamesView', () => ({
  ImportGamesView: ({
    onJobStatusUpdate,
  }: {
    onJobStatusUpdate?: (job: api.JobStatusResponse | null) => void;
  }) => (
    <button
      type="button"
      onClick={() =>
        onJobStatusUpdate?.({
          jobId: 'old-live-job',
          status: 'COMPLETED',
          gamesImported: 1,
          gamesSkipped: 0,
          gamesProcessed: 1,
          analysisStatus: 'ANALYZING',
        })
      }
    >
      Seed prior live job
    </button>
  ),
}));

vi.mock('../components/WeaknessesList', async () => {
  const actual =
    await vi.importActual<typeof import('../components/WeaknessesList')>(
      '../components/WeaknessesList'
    );
  const ActualWeaknessesList = actual.WeaknessesList;
  return {
    ...actual,
    WeaknessesList: (props: React.ComponentProps<typeof ActualWeaknessesList>) => (
      <>
        <span data-testid="analysis-active">
          {props.isAnalysisActive ? 'active' : 'inactive'}
        </span>
        <ActualWeaknessesList {...props} />
      </>
    ),
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

describe('Session bootstrap gating (Issue #113)', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
    vi.mocked(api.fetchAccounts).mockResolvedValue([
      { id: 'account-1', platform: 'CHESS_COM', username: 'hikaru' },
    ]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('issues no personalized request while the session is still loading, even with a stored username', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    sessionMocks.fetchCurrentSession.mockReturnValue(deferred<SessionState>().promise);

    render(<Home />);
    await act(async () => {
      await Promise.resolve();
    });

    expect(api.fetchPuzzles).not.toHaveBeenCalled();
    expect(api.fetchWeaknesses).not.toHaveBeenCalled();
  });

  it('opens the gate and fetches personalized data once the session is authenticated', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    window.location.hash = '#weaknesses';
    const d = deferred<SessionState>();
    sessionMocks.fetchCurrentSession.mockReturnValue(d.promise);

    render(<Home />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(api.fetchPuzzles).not.toHaveBeenCalled();

    await act(async () => {
      d.resolve({ status: 'authenticated', userId: 'user-1', devPrincipal: false });
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalled();
      expect(api.fetchWeaknesses).toHaveBeenCalledWith(
        'account-1',
        'CHESS_COM',
        'BOTH',
        expect.any(Number),
        expect.any(Number),
        0,
        20
      );
    });
  });

  it('keeps guest-eligible analysis available with a stored username when unauthenticated', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    window.location.hash = '#weaknesses';
    sessionMocks.fetchCurrentSession.mockResolvedValue({ status: 'unauthenticated' } as SessionState);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);

    render(<Home />);

    await waitFor(() => {
      expect(sessionMocks.fetchCurrentSession).toHaveBeenCalled();
    });

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith(
        'hikaru',
        'CHESS_COM',
        'BOTH',
        expect.any(Number),
        expect.any(Number),
        10,
        0
      );
    });

    expect(screen.queryByText(/Chess\.com Connected/i)).not.toBeInTheDocument();
    const signInCta =
      screen.queryByRole('button', { name: /sign in|log in/i }) ?? screen.queryByText(/sign in|log in/i);
    expect(signInCta).toBeTruthy();
  });

  it('clears stale account and job state before bootstrapping a different authenticated user', async () => {
    localStorage.setItem('chessecho_session_user', 'user-1');
    localStorage.setItem('chessecho_username', 'old-player');
    localStorage.setItem(
      'chessecho_active_account',
      JSON.stringify({ id: 'old-account', platform: 'CHESS_COM', username: 'old-player' })
    );
    localStorage.setItem(
      'chessecho_active_job',
      JSON.stringify({ jobId: 'old-job', accountId: 'old-account', status: 'QUEUED' })
    );
    sessionMocks.fetchCurrentSession.mockResolvedValue({
      status: 'authenticated',
      userId: 'user-2',
      devPrincipal: false,
    });
    vi.mocked(api.fetchAccounts).mockResolvedValue([]);
    const session = deferred<SessionState>();
    sessionMocks.fetchCurrentSession.mockReturnValue(session.promise);
    window.location.hash = '#import';

    render(<Home />);

    await act(async () => {
      screen.getByRole('button', { name: 'Seed prior live job' }).click();
      window.location.hash = '#weaknesses';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
      await Promise.resolve();
    });
    expect(screen.getByTestId('analysis-active')).toHaveTextContent('active');

    await act(async () => {
      session.resolve({ status: 'authenticated', userId: 'user-2', devPrincipal: false });
      await Promise.resolve();
    });
    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalled());
    expect(localStorage.getItem('chessecho_session_user')).toBe('user-2');
    expect(localStorage.getItem('chessecho_username')).toBeNull();
    expect(localStorage.getItem('chessecho_active_account')).toBeNull();
    expect(localStorage.getItem('chessecho_active_job')).toBeNull();
    expect(screen.getByTestId('analysis-active')).toHaveTextContent('inactive');
    expect(api.fetchPuzzles).not.toHaveBeenCalledWith(
      'old-player',
      expect.anything(),
      expect.anything(),
      expect.anything(),
      expect.anything(),
      expect.anything(),
      expect.anything()
    );
  });

  it('preserves a never-authenticated guest job when session bootstrap is unauthenticated', async () => {
    localStorage.setItem('chessecho_username', 'guest-player');
    localStorage.setItem(
      'chessecho_active_job',
      JSON.stringify({ jobId: 'guest-job', accountId: 'guest-account', status: 'QUEUED' })
    );
    sessionMocks.fetchCurrentSession.mockResolvedValue({ status: 'unauthenticated' } as SessionState);

    render(<Home />);

    await waitFor(() => expect(sessionMocks.fetchCurrentSession).toHaveBeenCalled());
    expect(localStorage.getItem('chessecho_active_job')).toBe(
      JSON.stringify({ jobId: 'guest-job', accountId: 'guest-account', status: 'QUEUED' })
    );
    expect(localStorage.getItem('chessecho_username')).toBe('guest-player');
  });

  it('clears prior authenticated state when session identity cannot be established', async () => {
    localStorage.setItem('chessecho_session_user', 'user-1');
    localStorage.setItem('chessecho_username', 'old-player');
    localStorage.setItem(
      'chessecho_active_account',
      JSON.stringify({ id: 'old-account', platform: 'CHESS_COM', username: 'old-player' })
    );
    localStorage.setItem(
      'chessecho_active_job',
      JSON.stringify({ jobId: 'old-job', accountId: 'old-account', status: 'QUEUED' })
    );
    sessionMocks.fetchCurrentSession.mockResolvedValue({ status: 'error' } as SessionState);

    render(<Home />);

    await waitFor(() => expect(sessionMocks.fetchCurrentSession).toHaveBeenCalled());
    expect(localStorage.getItem('chessecho_session_user')).toBeNull();
    expect(localStorage.getItem('chessecho_username')).toBeNull();
    expect(localStorage.getItem('chessecho_active_account')).toBeNull();
    expect(localStorage.getItem('chessecho_active_job')).toBeNull();
    expect(api.fetchPuzzles).not.toHaveBeenCalled();
    expect(api.fetchWeaknesses).not.toHaveBeenCalled();
  });
});
