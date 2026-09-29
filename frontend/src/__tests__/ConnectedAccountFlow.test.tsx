import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';

const accountApi = vi.hoisted(() => ({
  fetchCurrentSession: vi.fn(),
  fetchAccounts: vi.fn(),
  associateAccount: vi.fn(),
  startAccountImportJob: vi.fn(),
  startImportJob: vi.fn(),
  pollJobStatus: vi.fn(),
  fetchPuzzles: vi.fn(),
  fetchWeaknesses: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return { ...actual, ...accountApi };
});

vi.mock('../components/WeaknessesList', () => ({
  WeaknessesList: () => <div>Weaknesses</div>,
}));

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: Error) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const serverAccount: api.AccountSummary = {
  id: 'server-account',
  platform: 'CHESS_COM',
  username: 'server-player',
};

function openImportView() {
  window.location.hash = '#import';
  render(<Home />);
}

describe('connected Chess.com account flow', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'unauthenticated' });
    vi.mocked(api.fetchAccounts).mockResolvedValue([]);
    vi.mocked(api.associateAccount).mockResolvedValue(serverAccount);
    vi.mocked(api.startAccountImportJob).mockResolvedValue({ jobId: 'account-job', status: 'QUEUED' });
    vi.mocked(api.startImportJob).mockResolvedValue({ jobId: 'guest-job', status: 'QUEUED' });
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
    vi.mocked(api.logout).mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does not trust a cached username or start an import during authenticated account hydration', async () => {
    localStorage.setItem('chessecho_session_user', 'user-1');
    localStorage.setItem('chessecho_username', 'stale-player');
    localStorage.setItem(
      'chessecho_active_account',
      JSON.stringify({ id: 'stale-account', platform: 'CHESS_COM', username: 'stale-player' })
    );
    const session = deferred<api.SessionState>();
    const accounts = deferred<api.AccountSummary[]>();
    vi.mocked(api.fetchCurrentSession).mockReturnValueOnce(session.promise);
    vi.mocked(api.fetchAccounts).mockReturnValueOnce(accounts.promise);

    openImportView();

    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
    await act(async () => {
      session.resolve({ status: 'authenticated', userId: 'user-1' });
      await Promise.resolve();
    });
    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalled());

    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
    expect(screen.queryByText('stale-player')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /start import/i }));
    expect(api.startAccountImportJob).not.toHaveBeenCalled();
    expect(api.startImportJob).not.toHaveBeenCalled();

    await act(async () => {
      accounts.resolve([serverAccount]);
      await Promise.resolve();
    });
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    expect(screen.getByText('server-player')).toBeInTheDocument();
  });

  it('connects explicitly, displays the returned identity, and imports with its account ID', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([]);

    openImportView();
    const username = await screen.findByPlaceholderText(/e\.g\. Hikaru/i);
    fireEvent.change(username, { target: { value: '  new-player  ' } });
    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));

    await waitFor(() => {
      expect(api.associateAccount).toHaveBeenCalledWith('CHESS_COM', 'new-player');
    });
    expect(api.startAccountImportJob).not.toHaveBeenCalled();
    expect(api.startImportJob).not.toHaveBeenCalled();

    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    expect(screen.getByText('server-player')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /start import/i }));

    await waitFor(() => {
      expect(api.startAccountImportJob).toHaveBeenCalledWith(
        expect.objectContaining({ accountId: 'server-account', username: 'server-player' })
      );
    });
    expect(api.startImportJob).not.toHaveBeenCalled();
  });

  it('shows a retryable account-hydration error and recovers from a successful retry', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts)
      .mockRejectedValueOnce(new Error('Failed to load accounts: 503'))
      .mockResolvedValueOnce([serverAccount]);

    openImportView();
    expect(await screen.findByText(/unable to load your connected account/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /retry account loading/i }));

    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    expect(api.fetchAccounts).toHaveBeenCalledTimes(2);
  });

  it('keeps account-association failures visible and retryable without starting an import', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([]);
    vi.mocked(api.associateAccount)
      .mockRejectedValueOnce(new Error('ACCOUNT_CLAIM_CONFLICT'))
      .mockResolvedValueOnce(serverAccount);

    openImportView();
    fireEvent.change(await screen.findByPlaceholderText(/e\.g\. Hikaru/i), {
      target: { value: 'new-player' },
    });
    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));

    expect(await screen.findByText('ACCOUNT_CLAIM_CONFLICT')).toBeInTheDocument();
    expect(api.startAccountImportJob).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
  });

  it('does not restore a late association response after logout', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([serverAccount]);
    const association = deferred<api.AccountSummary>();
    vi.mocked(api.associateAccount).mockReturnValueOnce(association.promise);

    openImportView();
    const username = await screen.findByPlaceholderText(/e\.g\. Hikaru/i);
    fireEvent.change(username, { target: { value: 'another-player' } });
    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));
    await waitFor(() => expect(api.associateAccount).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /sign out/i }));
    await waitFor(() => expect(api.logout).toHaveBeenCalled());

    await act(async () => {
      association.resolve({ id: 'late-account', platform: 'CHESS_COM', username: 'late-player' });
      await Promise.resolve();
    });

    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
    expect(screen.queryByText('late-player')).not.toBeInTheDocument();
    expect(localStorage.getItem('chessecho_active_account')).toBeNull();
  });

  it('does not apply late account hydration after a different user session takes over', async () => {
    const oldAccounts = deferred<api.AccountSummary[]>();
    const newAccount = { id: 'new-user-account', platform: 'CHESS_COM', username: 'new-user-player' };
    vi.mocked(api.fetchCurrentSession)
      .mockResolvedValueOnce({ status: 'authenticated', userId: 'user-1' })
      .mockResolvedValueOnce({ status: 'authenticated', userId: 'user-2' });
    vi.mocked(api.fetchAccounts).mockReturnValueOnce(oldAccounts.promise).mockResolvedValueOnce([newAccount]);

    const firstMount = render(<Home />);
    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalledTimes(1));
    firstMount.unmount();

    openImportView();
    expect(await screen.findByText('new-user-player')).toBeInTheDocument();
    expect(api.fetchAccounts).toHaveBeenCalledTimes(2);

    await act(async () => {
      oldAccounts.resolve([serverAccount]);
      await Promise.resolve();
    });

    expect(screen.getByText('new-user-player')).toBeInTheDocument();
    expect(screen.queryByText('server-player')).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('chessecho_active_account') ?? '{}')).toMatchObject({
      id: 'new-user-account',
      username: 'new-user-player',
    });
  });

  it('preserves username-based imports for guests', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({ status: 'unauthenticated' });

    openImportView();
    fireEvent.change(await screen.findByPlaceholderText(/e\.g\. Hikaru/i), {
      target: { value: 'guest-player' },
    });
    fireEvent.click(screen.getByRole('button', { name: /start import/i }));

    await waitFor(() => expect(api.startImportJob).toHaveBeenCalledWith(
      'guest-player',
      'CHESS_COM',
      ['BLITZ', 'RAPID'],
      'BOTH',
      undefined,
      undefined
    ));
    expect(api.startAccountImportJob).not.toHaveBeenCalled();
  });
});
