import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { activeJobStore } from '../utils/browserStores';

const accountApi = vi.hoisted(() => ({
  fetchCurrentSession: vi.fn(),
  fetchAccounts: vi.fn(),
  associateAccount: vi.fn(),
  disconnectAccount: vi.fn(),
  startAccountImportJob: vi.fn(),
  startImportJob: vi.fn(),
  pollJobStatus: vi.fn(),
  fetchPuzzles: vi.fn(),
  fetchWeaknesses: vi.fn(),
  recordPuzzleEvent: vi.fn(),
  submitTrainingAttempt: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return { ...actual, ...accountApi };
});

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options?: {
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
    };
  }) => (
    <button
      type="button"
      onClick={() => options?.onPieceDrop?.({ sourceSquare: 'e2', targetSquare: 'e4' })}
    >
      Play correct move
    </button>
  ),
}));

vi.mock('../components/WeaknessesList', () => ({
  WeaknessesList: ({ username, accountId }: { username?: string; accountId?: string }) => (
    <div
      data-testid="weaknesses-list"
      data-username={username}
      data-account-id={accountId}
    />
  ),
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

const accountScopedPuzzle = {
  puzzleId: 'account-puzzle',
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  playerColor: 'WHITE' as const,
  targetMove: 'e4',
  openingTitle: 'Account-scoped puzzle',
  acceptableMoves: [],
  movesPlayed: [],
  priority: 1,
  timesReached: 1,
  mistakeCount: 1,
  mistakeRate: 100,
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
    vi.mocked(api.disconnectAccount).mockResolvedValue(undefined);
    vi.mocked(api.startAccountImportJob).mockResolvedValue({ jobId: 'account-job', status: 'QUEUED' });
    vi.mocked(api.startImportJob).mockResolvedValue({ jobId: 'guest-job', status: 'QUEUED' });
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
    vi.mocked(api.recordPuzzleEvent).mockResolvedValue(undefined);
    vi.mocked(api.submitTrainingAttempt).mockResolvedValue({
      attemptId: 'attempt-1',
      puzzleId: 'account-puzzle',
      mode: 'STOPWATCH',
      elapsedMs: 1,
      outcome: 'SUBMITTED',
      recordedAt: '2026-01-01T00:00:00Z',
    });
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

  it('shows the connected Chess.com username while selecting weaknesses by account UUID', async () => {
    const accountId = '550e8400-e29b-41d4-a716-446655440470';
    const chessUsername = 'chess-player-470';
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'principal-user-470',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([
      { ...serverAccount, id: accountId, username: chessUsername },
    ]);

    render(<Home />);
    fireEvent.click(await screen.findByRole('button', { name: /Weaknesses Library/i }));

    const weaknessesList = await screen.findByTestId('weaknesses-list');
    expect(weaknessesList).toHaveAttribute('data-username', chessUsername);
    expect(weaknessesList).toHaveAttribute('data-account-id', accountId);
    expect(weaknessesList).not.toHaveTextContent('principal-user-470');
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
      .mockRejectedValueOnce(
        new api.AccountAssociationError(409, 'ACCOUNT_CLAIM_CONFLICT', 'ACCOUNT_CLAIM_CONFLICT')
      )
      .mockRejectedValueOnce(
        new api.AccountAssociationError(409, 'ACCOUNT_CLAIM_CONFLICT', 'ACCOUNT_CLAIM_CONFLICT')
      )
      .mockResolvedValueOnce(serverAccount);

    openImportView();
    fireEvent.change(await screen.findByPlaceholderText(/e\.g\. Hikaru/i), {
      target: { value: 'new-player' },
    });
    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));

    const conflictMessage =
      "This Chess.com account is connected under another ChessEcho sign-in. Verify that you're signed in to the right ChessEcho account, or choose a Chess.com account you can connect.";
    expect(await screen.findByText(conflictMessage)).toBeInTheDocument();
    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
    expect(localStorage.getItem('chessecho_active_account')).toBeNull();
    expect(api.startAccountImportJob).not.toHaveBeenCalled();
    expect(api.startImportJob).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));
    expect(await screen.findByText(conflictMessage)).toBeInTheDocument();
    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
    expect(localStorage.getItem('chessecho_active_account')).toBeNull();
    expect(api.associateAccount).toHaveBeenCalledTimes(2);
    expect(api.startAccountImportJob).not.toHaveBeenCalled();
    expect(api.startImportJob).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    expect(api.associateAccount).toHaveBeenCalledTimes(3);
    expect(screen.getByText('server-player')).toBeInTheDocument();
  });

  it('preserves and can import a different confirmed account after a claim conflict', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([serverAccount]);
    vi.mocked(api.associateAccount).mockRejectedValueOnce(
      new api.AccountAssociationError(409, 'ACCOUNT_CLAIM_CONFLICT', 'ACCOUNT_CLAIM_CONFLICT')
    );

    openImportView();
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    const username = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
    fireEvent.change(username, { target: { value: 'other-player' } });
    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));

    expect(
      await screen.findByText(
        "This Chess.com account is connected under another ChessEcho sign-in. Verify that you're signed in to the right ChessEcho account, or choose a Chess.com account you can connect."
      )
    ).toBeInTheDocument();
    expect(screen.getByText('Connected Chess.com account: server-player')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('chessecho_active_account') ?? '{}')).toMatchObject({
      id: serverAccount.id,
      username: serverAccount.username,
    });
    expect(api.startAccountImportJob).not.toHaveBeenCalled();

    fireEvent.change(username, { target: { value: serverAccount.username } });
    fireEvent.click(screen.getByRole('button', { name: /start import/i }));

    await waitFor(() =>
      expect(api.startAccountImportJob).toHaveBeenCalledWith(
        expect.objectContaining({ accountId: serverAccount.id, username: serverAccount.username })
      )
    );
    expect(api.associateAccount).toHaveBeenCalledTimes(1);
  });

  it.each([
    [409, 'CONFLICT', 'CONFLICT'],
    [400, 'ACCOUNT_CLAIM_CONFLICT', 'ACCOUNT_CLAIM_CONFLICT'],
  ])('keeps the existing error message when status/code do not both match (%s, %s)', async (
    status,
    code,
    message
  ) => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([]);
    vi.mocked(api.associateAccount).mockRejectedValueOnce(
      new api.AccountAssociationError(status, code, message)
    );

    openImportView();
    fireEvent.change(await screen.findByPlaceholderText(/e\.g\. Hikaru/i), {
      target: { value: 'new-player' },
    });
    fireEvent.click(screen.getByRole('button', { name: /connect chess\.com account/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.queryByText(/connected under another ChessEcho sign-in/)).not.toBeInTheDocument();
    expect(api.startAccountImportJob).not.toHaveBeenCalled();
    expect(api.startImportJob).not.toHaveBeenCalled();
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

  it('calls the disconnect endpoint and clears active account state only after it succeeds', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([serverAccount]);
    const disconnect = deferred<void>();
    vi.mocked(api.disconnectAccount).mockReturnValueOnce(disconnect.promise);

    openImportView();
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^disconnect$/i }));

    await waitFor(() => expect(api.disconnectAccount).toHaveBeenCalledWith('server-account'));
    expect(JSON.parse(localStorage.getItem('chessecho_active_account') ?? '{}')).toMatchObject({
      id: 'server-account',
    });

    await act(async () => {
      disconnect.resolve();
      await disconnect.promise;
    });

    await waitFor(() => {
      expect(localStorage.getItem('chessecho_active_account')).toBeNull();
      expect(localStorage.getItem('chessecho_username')).toBeNull();
    });
    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
  });

  it('reconciles a disconnect 404 against an empty account list without reporting success', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    const refreshedAccounts = deferred<api.AccountSummary[]>();
    vi.mocked(api.fetchAccounts)
      .mockResolvedValueOnce([serverAccount])
      .mockReturnValueOnce(refreshedAccounts.promise);
    vi.mocked(api.disconnectAccount).mockRejectedValueOnce(
      new api.DisconnectAccountError('ACCOUNT_NOT_FOUND', 404)
    );

    openImportView();
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    act(() => {
      activeJobStore.set({
        jobId: 'old-job',
        status: 'COMPLETED',
        gamesImported: 1,
        gamesSkipped: 0,
        accountId: serverAccount.id,
      });
    });
    fireEvent.click(screen.getByRole('button', { name: /^disconnect$/i }));

    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalledTimes(2));
    expect(screen.getByText('Loading your connected Chess.com account…')).toBeInTheDocument();
    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /start import/i })).toBeDisabled();
    expect(activeJobStore.getSnapshot()).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /start import/i }));
    expect(api.startAccountImportJob).not.toHaveBeenCalled();

    await act(async () => {
      refreshedAccounts.resolve([]);
      await Promise.resolve();
    });

    expect(await screen.findByText('No Chess.com account connected. Connect an account before importing.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Unable to disconnect account. ACCOUNT_NOT_FOUND');
    expect(localStorage.getItem('chessecho_active_account')).toBeNull();
    expect(localStorage.getItem('chessecho_username')).toBeNull();
    expect(activeJobStore.getSnapshot()).toBeNull();
  });

  it('keeps the selected account connected when it remains in the refreshed list after a 404', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts)
      .mockResolvedValueOnce([serverAccount])
      .mockResolvedValueOnce([serverAccount]);
    vi.mocked(api.disconnectAccount).mockRejectedValueOnce(
      new api.DisconnectAccountError('ACCOUNT_NOT_FOUND', 404)
    );

    openImportView();
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^disconnect$/i }));

    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Unable to disconnect account. ACCOUNT_NOT_FOUND');
    expect(JSON.parse(localStorage.getItem('chessecho_active_account') ?? '{}')).toMatchObject({
      id: serverAccount.id,
    });
  });

  it('selects a confirmed remaining account and clears the old account job after a 404', async () => {
    const remainingAccount: api.AccountSummary = {
      id: 'remaining-account',
      platform: 'CHESS_COM',
      username: 'remaining-player',
    };
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts)
      .mockResolvedValueOnce([serverAccount])
      .mockResolvedValueOnce([remainingAccount]);
    vi.mocked(api.disconnectAccount).mockRejectedValueOnce(
      new api.DisconnectAccountError('ACCOUNT_NOT_FOUND', 404)
    );

    openImportView();
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    act(() => {
      activeJobStore.set({
        jobId: 'old-job',
        status: 'COMPLETED',
        gamesImported: 1,
        gamesSkipped: 0,
        accountId: serverAccount.id,
      });
    });
    fireEvent.click(screen.getByRole('button', { name: /^disconnect$/i }));

    expect(await screen.findByText('remaining-player')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Unable to disconnect account. ACCOUNT_NOT_FOUND');
    expect(JSON.parse(localStorage.getItem('chessecho_active_account') ?? '{}')).toMatchObject({
      id: remainingAccount.id,
      username: remainingAccount.username,
    });
    expect(activeJobStore.getSnapshot()).toBeNull();
  });

  it('shows a retryable account-loading error when 404 reconciliation fails', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts)
      .mockResolvedValueOnce([serverAccount])
      .mockRejectedValueOnce(new Error('Failed to load accounts: 503'))
      .mockResolvedValueOnce([serverAccount]);
    vi.mocked(api.disconnectAccount).mockRejectedValueOnce(
      new api.DisconnectAccountError('ACCOUNT_NOT_FOUND', 404)
    );

    openImportView();
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^disconnect$/i }));

    expect(await screen.findByText(/unable to load your connected account/i)).toBeInTheDocument();
    expect(screen.getByText(/Unable to disconnect account\.\s+ACCOUNT_NOT_FOUND/)).toBeInTheDocument();
    expect(screen.queryByText('Chess.com Connected')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /retry account loading/i }));

    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    expect(api.fetchAccounts).toHaveBeenCalledTimes(3);
    expect(screen.getByRole('alert')).toHaveTextContent('Unable to disconnect account. ACCOUNT_NOT_FOUND');
  });

  it('does not apply a late 404 reconciliation after a different user takes over', async () => {
    const newUserAccount: api.AccountSummary = {
      id: 'new-user-account',
      platform: 'CHESS_COM',
      username: 'new-user-player',
    };
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    }).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-2',
    });
    const refreshedAccounts = deferred<api.AccountSummary[]>();
    vi.mocked(api.fetchAccounts)
      .mockResolvedValueOnce([serverAccount])
      .mockReturnValueOnce(refreshedAccounts.promise)
      .mockResolvedValueOnce([newUserAccount]);
    vi.mocked(api.disconnectAccount).mockRejectedValueOnce(
      new api.DisconnectAccountError('ACCOUNT_NOT_FOUND', 404)
    );

    window.location.hash = '#import';
    const firstSession = render(<Home />);
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^disconnect$/i }));
    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalledTimes(2));

    firstSession.unmount();
    render(<Home />);
    expect(await screen.findByText('new-user-player')).toBeInTheDocument();

    await act(async () => {
      refreshedAccounts.resolve([serverAccount]);
      await Promise.resolve();
    });

    expect(screen.getByText('Chess.com Connected')).toBeInTheDocument();
    expect(screen.queryByText('server-player')).not.toBeInTheDocument();
    expect(screen.getByText('new-user-player')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('chessecho_active_account') ?? '{}')).toMatchObject({
      id: newUserAccount.id,
      username: newUserAccount.username,
    });
    expect(api.fetchAccounts).toHaveBeenCalledTimes(3);
  });

  it.each([
    ['network', new Error('Disconnect unavailable')],
    ['forbidden', new api.DisconnectAccountError('FORBIDDEN', 403)],
    ['server', new api.DisconnectAccountError('SERVER_ERROR', 503)],
  ])('keeps the confirmed account and does not reload it after a generic %s failure', async (_kind, error) => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([serverAccount]);
    vi.mocked(api.disconnectAccount).mockRejectedValueOnce(error);

    openImportView();
    expect(await screen.findByText('Chess.com Connected')).toBeInTheDocument();
    act(() => {
      activeJobStore.set({
        jobId: 'confirmed-job',
        status: 'COMPLETED',
        gamesImported: 1,
        gamesSkipped: 0,
        accountId: serverAccount.id,
      });
    });
    fireEvent.click(screen.getByRole('button', { name: /^disconnect$/i }));

    await waitFor(() => expect(api.disconnectAccount).toHaveBeenCalledWith(serverAccount.id));
    expect(screen.getByRole('alert')).toHaveTextContent(error.message);
    expect(screen.getByText('Chess.com Connected')).toBeInTheDocument();
    expect(api.fetchAccounts).toHaveBeenCalledTimes(1);
    expect(JSON.parse(localStorage.getItem('chessecho_active_account') ?? '{}')).toMatchObject({
      id: serverAccount.id,
    });
    expect(activeJobStore.getSnapshot()?.jobId).toBe('confirmed-job');
  });

  it('includes the selected account ID in authenticated puzzle and timed-training writes', async () => {
    localStorage.setItem('chessecho_session_user', 'user-1');
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([serverAccount]);
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([accountScopedPuzzle]);
    window.location.hash = '#puzzles';

    render(<Home />);

    await waitFor(() => expect(api.fetchAccounts).toHaveBeenCalled());
    await waitFor(() => expect(api.fetchPuzzles).toHaveBeenCalled());
    expect(await screen.findByText('Account-scoped puzzle')).toBeInTheDocument();
    expect(api.recordPuzzleEvent).toHaveBeenCalledWith(
      expect.objectContaining({
        accountId: serverAccount.id,
        positionId: accountScopedPuzzle.puzzleId,
        eventType: 'PRESENTED',
      })
    );

    fireEvent.click(screen.getByRole('button', { name: /Puzzle Settings Show/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Stopwatch' }));
    fireEvent.click(screen.getByRole('button', { name: 'Play correct move' }));

    await waitFor(() =>
      expect(api.submitTrainingAttempt).toHaveBeenCalledWith(
        expect.objectContaining({
          accountId: serverAccount.id,
          puzzleId: accountScopedPuzzle.puzzleId,
        })
      )
    );
  });

  it('omits a persisted account ID from guest timed-training writes', async () => {
    localStorage.setItem('chessecho_username', serverAccount.username);
    localStorage.setItem('chessecho_active_account', JSON.stringify(serverAccount));
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([accountScopedPuzzle]);
    window.location.hash = '#puzzles';

    render(<Home />);

    expect(await screen.findByText('Account-scoped puzzle')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Puzzle Settings Show/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Stopwatch' }));
    fireEvent.click(screen.getByRole('button', { name: 'Play correct move' }));

    await waitFor(() => expect(api.submitTrainingAttempt).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.submitTrainingAttempt).mock.calls[0][0]).not.toHaveProperty('accountId');
  });
});
