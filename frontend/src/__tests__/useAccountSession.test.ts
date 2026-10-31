import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useAccountSession } from '@/hooks/useAccountSession';
import * as api from '@/services/api';
import { activeAccountStore, activeUsernameStore } from '@/utils/browserStores';

vi.mock('@/services/api', async () => {
  const actual = await vi.importActual<typeof import('@/services/api')>('@/services/api');
  return {
    ...actual,
    fetchCurrentSession: vi.fn(),
    fetchAccounts: vi.fn(),
    associateAccount: vi.fn(),
    disconnectAccount: vi.fn(),
    logout: vi.fn(),
  };
});

describe('useAccountSession', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    activeAccountStore.set(undefined);
    activeUsernameStore.set(undefined);
  });

  it('initializes in loading state and resolves unauthenticated session', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'unauthenticated',
    });

    const { result } = renderHook(() => useAccountSession());

    expect(result.current.sessionStatus).toBe('loading');
    expect(result.current.accountStatus).toBe('loading');

    await waitFor(() => {
      expect(result.current.sessionStatus).toBe('unauthenticated');
    });

    expect(result.current.accountStatus).toBe('unconnected');
    expect(result.current.sessionGateOpen).toBe(true);
    await expect(result.current.sessionGatePromise).resolves.toBe(true);
  });

  it('loads connected accounts when authenticated', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-123',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([
      {
        id: 'acc-1',
        platform: 'CHESS_COM',
        username: 'chess_grandmaster',
      },
    ]);

    const { result } = renderHook(() => useAccountSession());

    await waitFor(() => {
      expect(result.current.sessionStatus).toBe('authenticated');
      expect(result.current.accountStatus).toBe('connected');
    });

    expect(result.current.sessionUserId).toBe('user-123');
    expect(result.current.connectedAccount?.username).toBe('chess_grandmaster');
    expect(result.current.displayedUsername).toBe('chess_grandmaster');
    expect(activeAccountStore.getSnapshot()?.id).toBe('acc-1');
    expect(activeUsernameStore.getSnapshot()).toBe('chess_grandmaster');
  });

  it('connects a chess account successfully', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-123',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([]);
    vi.mocked(api.associateAccount).mockResolvedValueOnce({
      id: 'acc-2',
      platform: 'CHESS_COM',
      username: 'new_player',
    });

    const { result } = renderHook(() => useAccountSession());

    await waitFor(() => {
      expect(result.current.accountStatus).toBe('unconnected');
    });

    await act(async () => {
      await result.current.connectChessAccount('new_player');
    });

    expect(result.current.accountStatus).toBe('connected');
    expect(result.current.connectedAccount?.username).toBe('new_player');
    expect(activeAccountStore.getSnapshot()?.username).toBe('new_player');
  });

  it('disconnects an account and calls onClearAccountState', async () => {
    const onClearAccountState = vi.fn();
    vi.mocked(api.fetchCurrentSession).mockResolvedValueOnce({
      status: 'authenticated',
      userId: 'user-123',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValueOnce([
      {
        id: 'acc-1',
        platform: 'CHESS_COM',
        username: 'chess_player',
      },
    ]);
    vi.mocked(api.disconnectAccount).mockResolvedValueOnce();

    const { result } = renderHook(() => useAccountSession({ onClearAccountState }));

    await waitFor(() => {
      expect(result.current.accountStatus).toBe('connected');
    });

    await act(async () => {
      await result.current.handleDisconnect();
    });

    expect(result.current.accountStatus).toBe('unconnected');
    expect(onClearAccountState).toHaveBeenCalled();
    expect(activeAccountStore.getSnapshot()).toBeUndefined();
  });
});
