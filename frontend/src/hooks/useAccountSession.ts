import React, { useState, useSyncExternalStore } from 'react';
import {
  associateAccount,
  fetchAccounts,
  fetchCurrentSession,
  disconnectAccount,
  DisconnectAccountError,
  logout as apiLogout,
  type AccountSummary,
  type SessionState,
} from '@/services/api';
import {
  activeAccountStore,
  activeUsernameStore,
  clearAuthenticatedAccountState,
  reconcileSessionStorageOwner,
} from '@/utils/browserStores';
import type { AccountConnectionStatus } from '@/components/ImportGamesView';

function errorMessageOf(e: unknown): string | undefined {
  if (e instanceof Error) return e.message;
  if (typeof e === 'object' && e !== null && 'message' in e) {
    const message = (e as { message?: unknown }).message;
    return typeof message === 'string' ? message : undefined;
  }
  return undefined;
}

export type UseAccountSessionOptions = {
  onClearAccountState?: () => void;
};

export function useAccountSession(options?: UseAccountSessionOptions) {
  const optionsRef = React.useRef(options);
  React.useLayoutEffect(() => {
    optionsRef.current = options;
  });

  const activeUsername = useSyncExternalStore(
    activeUsernameStore.subscribe,
    activeUsernameStore.getSnapshot,
    activeUsernameStore.getServerSnapshot
  );
  const activeAccount = useSyncExternalStore(
    activeAccountStore.subscribe,
    activeAccountStore.getSnapshot,
    activeAccountStore.getServerSnapshot
  );
  const activeAccountId = activeAccount?.id;

  const [sessionStatus, setSessionStatus] = useState<SessionState['status']>('loading');
  const [sessionUserId, setSessionUserId] = useState<string | undefined>();
  const [accountStatus, setAccountStatus] = useState<AccountConnectionStatus>('loading');
  const [accountError, setAccountError] = useState<string | undefined>(undefined);
  const [disconnectError, setDisconnectError] = useState<string | undefined>(undefined);
  const [isDisconnecting, setIsDisconnecting] = useState(false);

  const accountOwnerIdRef = React.useRef<string | undefined>(undefined);
  const accountRequestVersionRef = React.useRef(0);
  const accountContextMountedRef = React.useRef(true);
  const [sessionGate] = useState(() => {
    let resolveGate!: (open: boolean) => void;
    const promise = new Promise<boolean>((res) => {
      resolveGate = res;
    });
    return { promise, resolve: resolveGate };
  });

  const sessionGateOpen = sessionStatus !== 'loading';

  const clearAccountUiState = React.useCallback(() => {
    activeUsernameStore.set(undefined);
    activeAccountStore.set(undefined);
    setAccountStatus('unconnected');
    optionsRef.current?.onClearAccountState?.();
  }, []);

  const loadOwnedAccounts = React.useCallback(
    async (
      userId: string,
      isCurrent: () => boolean = () => true,
      preferredAccountId?: string
    ) => {
      const requestVersion = ++accountRequestVersionRef.current;
      accountOwnerIdRef.current = userId;
      setAccountStatus('loading');
      setAccountError(undefined);
      const requestIsCurrent = () =>
        isCurrent() &&
        accountRequestVersionRef.current === requestVersion &&
        accountOwnerIdRef.current === userId;
      try {
        const accounts = await fetchAccounts();
        if (!requestIsCurrent()) return;
        if (accounts.length === 0) {
          clearAuthenticatedAccountState();
          clearAccountUiState();
          setAccountStatus('unconnected');
          return;
        }
        const current = activeAccountStore.getSnapshot();
        const selected = current && accounts.some((account) => account.id === current.id)
          ? current
          : accounts.find((account) => account.id === preferredAccountId) ?? accounts[0];
        activeAccountStore.set(selected);
        activeUsernameStore.set(selected.username);
        setAccountStatus('connected');
      } catch (error: unknown) {
        if (!requestIsCurrent()) return;
        clearAuthenticatedAccountState();
        clearAccountUiState();
        setAccountStatus('error');
        setAccountError(errorMessageOf(error) || 'Please retry account loading.');
      } finally {
        if (requestIsCurrent()) sessionGate.resolve(true);
      }
    },
    [clearAccountUiState, sessionGate]
  );

  const retryAccountLoad = React.useCallback(() => {
    const userId = accountOwnerIdRef.current;
    if (userId) void loadOwnedAccounts(userId);
  }, [loadOwnedAccounts]);

  const connectChessAccount = React.useCallback(
    async (username: string): Promise<AccountSummary> => {
      const userId = accountOwnerIdRef.current;
      if (!accountContextMountedRef.current || !userId || accountStatus === 'loading' || accountStatus === 'error') {
        throw new Error('Your authenticated account is not ready. Retry account loading.');
      }
      const requestVersion = accountRequestVersionRef.current;
      const account = await associateAccount('CHESS_COM', username);
      if (
        !accountContextMountedRef.current ||
        accountRequestVersionRef.current !== requestVersion ||
        accountOwnerIdRef.current !== userId
      ) {
        throw new Error('Your session changed before the account could be selected. Please try again.');
      }
      activeAccountStore.set(account);
      activeUsernameStore.set(account.username);
      setAccountStatus('connected');
      setAccountError(undefined);
      return account;
    },
    [accountStatus]
  );

  const reconcileSessionIdentity = React.useCallback(
    (userId: string | undefined) => {
      return reconcileSessionStorageOwner(userId);
    },
    []
  );

  const handleDisconnect = React.useCallback(async () => {
    const accountId = activeAccountStore.getSnapshot()?.id;
    if (!accountId) {
      setDisconnectError('No connected Chess.com account is selected.');
      return;
    }
    const requestVersion = accountRequestVersionRef.current;
    const ownerId = accountOwnerIdRef.current;
    setDisconnectError(undefined);
    setIsDisconnecting(true);
    try {
      await disconnectAccount(accountId);
    } catch (error: unknown) {
      const requestIsCurrent =
        accountRequestVersionRef.current === requestVersion &&
        accountOwnerIdRef.current === ownerId &&
        activeAccountStore.getSnapshot()?.id === accountId;
      if (!requestIsCurrent) return;

      setDisconnectError(errorMessageOf(error) || 'Failed to disconnect Chess.com account.');
      if (error instanceof DisconnectAccountError && error.status === 404 && ownerId) {
        clearAccountUiState();
        await loadOwnedAccounts(ownerId, () => accountContextMountedRef.current, accountId);
      }
      return;
    } finally {
      setIsDisconnecting(false);
    }

    if (
      accountRequestVersionRef.current !== requestVersion ||
      accountOwnerIdRef.current !== ownerId ||
      activeAccountStore.getSnapshot()?.id !== accountId
    ) {
      return;
    }
    setDisconnectError(undefined);
    clearAccountUiState();
  }, [clearAccountUiState, loadOwnedAccounts]);

  const clearSessionState = React.useCallback(() => {
    setSessionUserId(undefined);
    accountRequestVersionRef.current++;
    accountOwnerIdRef.current = undefined;
    setAccountStatus('unconnected');
    setAccountError(undefined);
    reconcileSessionIdentity(undefined);
    clearAccountUiState();
    setDisconnectError(undefined);
  }, [clearAccountUiState, reconcileSessionIdentity]);

  const handleLogout = React.useCallback(() => {
    clearSessionState();
    setSessionStatus('unauthenticated');
    void apiLogout();
  }, [clearSessionState]);

  const handleSetUsername = React.useCallback((user: string | undefined) => {
    activeUsernameStore.set(user);
  }, []);

  React.useEffect(() => {
    let active = true;
    accountContextMountedRef.current = true;
    Promise.resolve(fetchCurrentSession()).then(async (state) => {
      if (!active || !state) return;
      setSessionStatus(state.status);
      setSessionUserId(state.status === 'authenticated' ? state.userId : undefined);
      if (state.status === 'unauthenticated') {
        accountRequestVersionRef.current++;
        accountOwnerIdRef.current = undefined;
        setAccountStatus('unconnected');
        setAccountError(undefined);
        reconcileSessionIdentity(undefined);
        sessionGate.resolve(true);
      } else if (state.status === 'authenticated') {
        if (!reconcileSessionIdentity(state.userId)) {
          accountRequestVersionRef.current++;
          accountOwnerIdRef.current = undefined;
          setAccountStatus('error');
          setAccountError('Unable to verify the authenticated user. Refresh the page and sign in again.');
          sessionGate.resolve(true);
          return;
        }
        if (state.userId) await loadOwnedAccounts(state.userId, () => active);
      } else {
        accountRequestVersionRef.current++;
        accountOwnerIdRef.current = undefined;
        setAccountStatus('unconnected');
        setAccountError(undefined);
        reconcileSessionIdentity(undefined);
        sessionGate.resolve(true);
      }
    });
    return () => {
      active = false;
      accountContextMountedRef.current = false;
    };
  }, [loadOwnedAccounts, reconcileSessionIdentity, sessionGate]);

  const connectedAccount =
    sessionStatus === 'authenticated' && accountStatus === 'connected' ? activeAccount : undefined;
  const displayedUsername =
    connectedAccount?.username ?? (sessionStatus === 'unauthenticated' ? activeUsername : undefined);
  const weaknessDisplayUsername =
    sessionStatus === 'authenticated' ? connectedAccount?.username : activeUsername;

  return {
    sessionStatus,
    sessionUserId,
    accountStatus,
    accountError,
    disconnectError,
    isDisconnecting,
    activeAccount,
    activeAccountId,
    activeUsername,
    connectedAccount,
    displayedUsername,
    weaknessDisplayUsername,
    sessionGateOpen,
    sessionGatePromise: sessionGate.promise,
    loadOwnedAccounts,
    retryAccountLoad,
    connectChessAccount,
    handleDisconnect,
    handleLogout,
    handleSetUsername,
    clearAccountUiState,
    clearSessionState,
  };
}
