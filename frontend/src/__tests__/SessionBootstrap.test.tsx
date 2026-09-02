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
 * `localStorage`; an authenticated result opens the gate; an unauthenticated
 * (or expired) result keeps private fetches suppressed, shows a sign-in CTA, and
 * the auth indicator is NOT derived from the stored Chess.com username.
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
    });
  });

  it('suppresses personalized fetches and does not derive "Connected" from a stored username when unauthenticated', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    sessionMocks.fetchCurrentSession.mockResolvedValue({ status: 'unauthenticated' } as SessionState);

    render(<Home />);
    await act(async () => {
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(sessionMocks.fetchCurrentSession).toHaveBeenCalled();
    });

    expect(api.fetchPuzzles).not.toHaveBeenCalled();
    expect(screen.queryByText(/Chess\.com Connected/i)).not.toBeInTheDocument();
    const signInCta =
      screen.queryByRole('button', { name: /sign in|log in/i }) ?? screen.queryByText(/sign in|log in/i);
    expect(signInCta).toBeTruthy();
  });
});
