'use client';

import React, { useState, useEffect, useRef, useSyncExternalStore } from 'react';
import {
  AccountAssociationError,
  AccountSummary,
  SessionState,
  startImportJob,
  startAccountImportJob,
  pollJobStatus,
  JobStatusResponse,
} from '../services/api';
import { activeJobStore } from '../utils/browserStores';
import { MonthPicker } from './MonthPicker';
import { SectionHeader } from './SectionHeader';

export type AccountConnectionStatus = 'loading' | 'connected' | 'unconnected' | 'error';

interface ImportGamesViewProps {
  connectedUsername?: string;
  connectedAccountId?: string;
  connectedAccount?: AccountSummary;
  sessionStatus?: SessionState['status'];
  accountStatus?: AccountConnectionStatus;
  accountError?: string;
  disconnectError?: string;
  isDisconnecting?: boolean;
  onConnectAccount?: (username: string) => Promise<void>;
  onRetryAccountLoad?: () => void;
  onImportStarted?: (username: string) => void;
  onNavigateTab?: (tab: 'puzzles' | 'weaknesses') => void;
  onDisconnect?: () => void;
  onJobStatusUpdate?: (job: JobStatusResponse | null) => void;
}

export const ImportGamesView: React.FC<ImportGamesViewProps> = ({
  connectedUsername,
  connectedAccountId,
  connectedAccount,
  sessionStatus = 'unauthenticated',
  accountStatus = 'unconnected',
  accountError,
  disconnectError,
  isDisconnecting = false,
  onConnectAccount,
  onRetryAccountLoad,
  onImportStarted,
  onNavigateTab,
  onDisconnect,
  onJobStatusUpdate,
}) => {
  const [username, setUsername] = useState<string>(connectedUsername || '');
  const [timeControls, setTimeControls] = useState<string[]>(['BLITZ', 'RAPID']);
  const [playerColor, setPlayerColor] = useState<'WHITE' | 'BLACK' | 'BOTH'>('BOTH');
  const [fromDate, setFromDate] = useState<string>('');
  const [toDate, setToDate] = useState<string>('');
  const [elapsedSeconds, setElapsedSeconds] = useState<number>(0);

  const activeJob = useSyncExternalStore(
    activeJobStore.subscribe,
    activeJobStore.getSnapshot,
    activeJobStore.getServerSnapshot
  );
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [pollingError, setPollingError] = useState<string | null>(null);
  const [associationError, setAssociationError] = useState<string | null>(null);
  const [isAssociating, setIsAssociating] = useState(false);
  const isAuthenticated = sessionStatus === 'authenticated';
  const usernameMatchesAccount =
    !!connectedAccount &&
    username.trim().toLocaleLowerCase() === connectedAccount.username.toLocaleLowerCase();
  const canStartImport =
    sessionStatus === 'unauthenticated' ||
    (isAuthenticated &&
      accountStatus === 'connected' &&
      !!connectedAccountId &&
      !!connectedAccount &&
      usernameMatchesAccount &&
      !isAssociating);

  // Elapsed timer tracking
  const jobStartTimeRef = useRef<number | null>(null);
  useEffect(() => {
    let timer: NodeJS.Timeout | null = null;
    if (activeJob?.status === 'QUEUED' || activeJob?.status === 'PROCESSING') {
      if (!jobStartTimeRef.current) {
        jobStartTimeRef.current = Date.now();
      }
      timer = setInterval(() => {
        if (jobStartTimeRef.current) {
          setElapsedSeconds(Math.floor((Date.now() - jobStartTimeRef.current) / 1000));
        }
      }, 1000);
    } else {
      jobStartTimeRef.current = null;
    }
    return () => {
      if (timer) clearInterval(timer);
    };
  }, [activeJob?.status]);

  // Sync username input if connectedUsername changes
  const [trackedConnectedUsername, setTrackedConnectedUsername] = useState(connectedUsername);
  if (trackedConnectedUsername !== connectedUsername) {
    setTrackedConnectedUsername(connectedUsername);
    if (connectedUsername) {
      setUsername(connectedUsername);
    }
  }

  const updateActiveJob = (job: JobStatusResponse | null) => {
    activeJobStore.set(job);
    if (onJobStatusUpdate) {
      onJobStatusUpdate(job);
    }
  };

  const updateActiveJobRef = useRef(updateActiveJob);
  useEffect(() => {
    updateActiveJobRef.current = updateActiveJob;
  });

  const prevPolledJobRef = useRef<{ jobId: string; status: string } | null>(
    activeJob ? { jobId: activeJob.jobId, status: activeJob.status } : null
  );

  // Clear activeJob if user explicitly disconnects
  const prevUserRef = useRef(connectedUsername);
  useEffect(() => {
    if (prevUserRef.current && !connectedUsername) {
      updateActiveJobRef.current(null);
    }
    prevUserRef.current = connectedUsername;
  }, [connectedUsername]);

  // Resume polling on mount or tab change if activeJob is still processing
  useEffect(() => {
    const isImportActive = activeJob?.status === 'QUEUED' || activeJob?.status === 'PROCESSING';
    const isAnalysisInFlight =
      activeJob?.status === 'COMPLETED' && activeJob?.analysisStatus === 'ANALYZING';
    if (!activeJob || !(isImportActive || isAnalysisInFlight)) {
      return;
    }
    const interval = setInterval(async () => {
      try {
        const statusUpdate = await pollJobStatus(activeJob.jobId);
        updateActiveJobRef.current(statusUpdate);
        const previousJob = prevPolledJobRef.current;
        const justCompletedImport =
          statusUpdate.status === 'COMPLETED' &&
          (!previousJob ||
            previousJob.jobId !== statusUpdate.jobId ||
            previousJob.status !== 'COMPLETED');
        prevPolledJobRef.current = {
          jobId: statusUpdate.jobId,
          status: statusUpdate.status,
        };
        if (justCompletedImport) {
          const effectiveUser = username.trim() || connectedUsername;
          if (onImportStarted && effectiveUser) {
            onImportStarted(effectiveUser);
          }
        }
        setPollingError(null);
        if (
          statusUpdate.status === 'FAILED' ||
          (statusUpdate.status === 'COMPLETED' && statusUpdate.analysisStatus !== 'ANALYZING')
        ) {
          clearInterval(interval);
        }
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Failed to poll job status';
        console.error('Error polling import job status:', err);
        if (msg.includes('404') || msg.includes('401') || msg.includes('403')) {
          clearInterval(interval);
          updateActiveJobRef.current(null);
          setErrorMessage(
            msg.includes('401') || msg.includes('403')
              ? 'Your account session no longer authorizes this import. You can start a new import.'
              : 'Previous import job is no longer available. You can start a new import.'
          );
          setPollingError(null);
        } else {
          setPollingError(msg);
        }
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [activeJob, connectedUsername, onImportStarted, username, connectedAccountId]);

  const handleTimeControlToggle = (tc: string) => {
    if (timeControls.includes(tc)) {
      if (timeControls.length > 1) {
        setTimeControls(timeControls.filter((item) => item !== tc));
      }
    } else {
      setTimeControls([...timeControls, tc]);
    }
  };

  const isValidMonthFormat = (val: string): boolean => {
    if (!val || !val.trim()) return true;
    return /^20\d{2}-(0[1-9]|1[0-2])$/.test(val.trim());
  };

  const handleConnectAccount = async () => {
    const trimmedUsername = username.trim();
    if (!trimmedUsername || !onConnectAccount) return;
    setAssociationError(null);
    setIsAssociating(true);
    try {
      await onConnectAccount(trimmedUsername);
    } catch (err: unknown) {
      setAssociationError(
        err instanceof AccountAssociationError &&
          err.status === 409 &&
          err.code === 'ACCOUNT_CONNECTION_LIMIT_REACHED' &&
          connectedAccount
          ? `Only one Chess.com account can be connected at a time. Disconnect ${connectedAccount.username} before connecting another account.`
          : err instanceof Error
            ? err.message
            : 'Failed to connect Chess.com account'
      );
    } finally {
      setIsAssociating(false);
    }
  };

  const handleStartImport = async () => {
    const trimmedUser = username.trim();
    if (sessionStatus === 'loading') {
      setErrorMessage('Checking your session. Please wait before importing.');
      return;
    }
    if (sessionStatus === 'error') {
      setErrorMessage('Your session could not be verified. Refresh the page or sign in again.');
      return;
    }
    if (
      isAuthenticated &&
      (accountStatus !== 'connected' || !connectedAccountId || !connectedAccount || !usernameMatchesAccount)
    ) {
      setErrorMessage('Connect the Chess.com username shown above before importing.');
      return;
    }
    if (!trimmedUser) {
      setErrorMessage('Please enter a Chess.com username');
      return;
    }
    if (timeControls.length === 0) {
      setErrorMessage('Please select at least one time control');
      return;
    }
    if (!isValidMonthFormat(fromDate)) {
      setErrorMessage('Please select a valid "From month" (e.g. 2026-08)');
      return;
    }
    if (!isValidMonthFormat(toDate)) {
      setErrorMessage('Please select a valid "To month" (e.g. 2026-09)');
      return;
    }
    setErrorMessage(null);
    try {
      const response = connectedAccountId
        ? await startAccountImportJob({
            accountId: connectedAccountId,
            platform: 'CHESS_COM',
            username: connectedAccount?.username || trimmedUser,
            timeControls,
            playerColor,
            fromDate: fromDate || undefined,
            toDate: toDate || undefined,
          })
        : await startImportJob(
            trimmedUser,
            'CHESS_COM',
            timeControls,
            playerColor,
            fromDate || undefined,
            toDate || undefined
          );

      if (onImportStarted) {
        onImportStarted(isAuthenticated ? connectedAccount!.username : trimmedUser);
      }

      const initialStatus: JobStatusResponse = {
        jobId: response.jobId,
        status: response.status,
        gamesImported: 0,
        gamesSkipped: 0,
        accountId: response.accountId,
        platform: response.platform,
        username: response.username,
        fromDate: response.fromDate,
        toDate: response.toDate,
        timeControls: response.timeControls,
        playerColor: response.playerColor,
        configurationState: response.configurationState,
      };

      updateActiveJob(initialStatus);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to start import job';
      setErrorMessage(msg);
    }
  };

  return (
    <div className="max-w-6xl 2xl:max-w-[1536px] w-full mx-auto px-4 lg:px-8 space-y-6 py-4 text-on-surface">
      {/* Breadcrumb & Engine Status Row */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 border-b border-outline-variant pb-3">
        <div aria-label="Breadcrumb" className="flex items-center space-x-2 text-xs font-mono text-on-surface-variant">
          <span>ChessProbe</span>
          <span>/</span>
          <span>Data Ingestion</span>
          <span>/</span>
          <span className="text-primary font-semibold">Game Importer</span>
        </div>
        <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-surface-container border border-outline-variant text-xs font-mono">
          <span className="w-2 h-2 rounded-full bg-primary animate-pulse" />
          <span className="text-on-surface">Engine API: Stockfish 16.1 NNUE</span>
        </div>
      </div>

      {/* Header */}
      <SectionHeader
        title="Import & Analyze Your Game History"
        description="Import your Chess.com games to automatically detect your recurring opening positions and compute Stockfish evaluations."
        badgeText="CHESS.COM"
      />

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-12 gap-6">

        {/* Form Settings Card (7 cols on desktop) */}
        <div className="xl:col-span-7 bg-surface-container-low p-6 rounded-2xl border border-outline-variant shadow-xl space-y-5">
          <h3 className="text-sm font-bold text-on-surface border-b border-outline-variant pb-3 flex items-center justify-between">
            <span className="flex items-center gap-2">
              <span className="material-symbols-outlined text-primary text-base">tune</span>
              <span>Import Configuration</span>
            </span>
            {isAuthenticated && accountStatus === 'connected' && connectedAccount && (
              <div className="flex items-center gap-2">
                <span className="text-xs text-primary font-mono font-medium">● Connected: {connectedAccount.username}</span>
                {onDisconnect && (
                  <button
                    type="button"
                    onClick={onDisconnect}
                    disabled={isDisconnecting}
                    className="text-[11px] font-semibold font-mono text-on-surface-variant hover:text-error underline disabled:opacity-50 cursor-pointer disabled:cursor-not-allowed"
                  >
                    {isDisconnecting ? 'Disconnecting…' : 'Disconnect'}
                  </button>
                )}
              </div>
            )}
          </h3>
          {disconnectError && (
            <p role="alert" className="rounded-lg border border-error-container bg-error-container/20 p-2.5 text-xs font-mono text-error">
              Unable to disconnect account. {disconnectError}
            </p>
          )}

          {/* Account Container */}
          <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-4 space-y-3">
            <div className="space-y-1.5">
              <label htmlFor="chess-com-username" className="text-xs font-semibold font-mono text-on-surface-variant flex items-center gap-1.5">
                <span className="material-symbols-outlined text-sm" aria-hidden="true">person</span>
                Chess.com Username
              </label>
              <input
                id="chess-com-username"
                aria-label="Chess.com Username"
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="e.g. Hikaru"
                disabled={
                  sessionStatus === 'loading' ||
                  sessionStatus === 'error' ||
                  (isAuthenticated && accountStatus === 'loading')
                }
                className="w-full px-3.5 py-2.5 bg-surface-container-lowest border border-outline-variant focus:border-primary rounded-xl text-sm font-medium text-on-surface outline-none transition font-sans"
              />
            </div>

            {isAuthenticated && (
              <div className="space-y-2 pt-1 border-t border-outline-variant" aria-live="polite">
                {accountStatus === 'loading' && (
                  <p role="status" className="text-xs font-mono text-on-surface-variant">
                    Loading your connected Chess.com account…
                  </p>
                )}
                {accountStatus === 'connected' && connectedAccount && (
                  <div className="flex items-center justify-between">
                    <p role="status" className="text-xs font-mono text-primary flex items-center gap-1.5">
                      <span className="material-symbols-outlined text-sm text-primary">check_circle</span>
                      <span>Connected Chess.com account: {connectedAccount.username}</span>
                    </p>
                    <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-primary/10 text-primary border border-primary/20">
                      Active &amp; Verified
                    </span>
                  </div>
                )}
                {accountStatus === 'unconnected' && (
                  <p role="status" className="text-xs font-mono text-on-surface-variant">
                    No Chess.com account connected. Connect an account before importing.
                  </p>
                )}
                {accountStatus === 'error' && (
                  <div role="alert" className="rounded-lg border border-error-container bg-error-container/20 p-2.5 space-y-2 text-xs font-mono text-error">
                    <p>Unable to load your connected account. {accountError}</p>
                    {onRetryAccountLoad && (
                      <button
                        type="button"
                        onClick={onRetryAccountLoad}
                        className="font-mono font-semibold underline cursor-pointer"
                      >
                        Retry account loading
                      </button>
                    )}
                  </div>
                )}
                {(accountStatus === 'connected' || accountStatus === 'unconnected') && (
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={handleConnectAccount}
                      disabled={!username.trim() || isAssociating}
                      className="inline-flex items-center gap-1.5 text-xs font-mono font-semibold text-primary hover:underline disabled:opacity-50 cursor-pointer disabled:cursor-not-allowed"
                    >
                      <span className="material-symbols-outlined text-sm">link</span>
                      <span>{isAssociating ? 'Connecting account…' : 'Connect Chess.com account'}</span>
                    </button>
                    {accountStatus === 'connected' && onDisconnect && (
                      <button
                        type="button"
                        onClick={onDisconnect}
                        disabled={isDisconnecting}
                        className="inline-flex items-center gap-1 text-xs font-mono font-semibold text-on-surface-variant hover:text-error disabled:opacity-50 cursor-pointer disabled:cursor-not-allowed"
                      >
                        <span className="material-symbols-outlined text-sm">link_off</span>
                        <span>Disconnect</span>
                      </button>
                    )}
                  </div>
                )}
                {associationError && (
                  <p role="alert" className="rounded-lg border border-error-container bg-error-container/20 p-2.5 text-xs font-mono text-error">
                    {associationError}
                  </p>
                )}
                {isAuthenticated && accountStatus === 'connected' && !usernameMatchesAccount && (
                  <p role="status" className="text-xs font-mono text-amber-accent">
                    Connect this username before importing it.
                  </p>
                )}
              </div>
            )}
          </div>

          {/* Multi-Select Time Controls */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label className="text-xs font-semibold font-mono text-on-surface-variant">
                Time Controls (Select one or more)
              </label>
              <span className="text-[11px] text-primary font-mono font-medium">
                Selected: {timeControls.length} of 4
              </span>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              {[
                { id: 'BULLET', label: '🚀 Bullet', sub: '< 3 mins', icon: 'bolt' },
                { id: 'BLITZ', label: '⚡ Blitz', sub: '3 – 5 mins', icon: 'timer' },
                { id: 'RAPID', label: '⏱️ Rapid', sub: '10 – 30 mins', icon: 'schedule' },
                { id: 'CLASSICAL', label: '♟️ Classical', sub: '> 30 mins', icon: 'hourglass_empty' },
              ].map((tc) => {
                const isSelected = timeControls.includes(tc.id);
                return (
                  <button
                    type="button"
                    key={tc.id}
                    onClick={() => handleTimeControlToggle(tc.id)}
                    className={`flex flex-col justify-between p-3 rounded-xl border text-left transition cursor-pointer ${
                      isSelected
                        ? 'bg-primary/10 border-primary text-primary font-bold shadow-sm'
                        : 'bg-surface-container-lowest border-outline-variant text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high'
                    }`}
                  >
                    <div className="flex items-center justify-between w-full">
                      <span className="material-symbols-outlined text-base">{tc.icon}</span>
                      <input
                        type="checkbox"
                        checked={isSelected}
                        readOnly
                        aria-label={`Toggle ${tc.id}`}
                        className="accent-primary pointer-events-none w-3.5 h-3.5"
                      />
                    </div>
                    <div className="mt-2">
                      <div className="text-xs font-semibold">{tc.label}</div>
                      <div className="text-[10px] opacity-75 font-mono">{tc.sub}</div>
                    </div>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Player Color Selection */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold font-mono text-on-surface-variant">
              Player Color
            </label>
            <div className="grid grid-cols-3 gap-2 bg-surface-container-lowest p-1 rounded-xl border border-outline-variant">
              {(['BOTH', 'WHITE', 'BLACK'] as const).map((color) => (
                <button
                  type="button"
                  key={color}
                  aria-label={color}
                  onClick={() => setPlayerColor(color)}
                  className={`py-2 text-xs font-bold rounded-lg uppercase tracking-wider transition cursor-pointer font-mono flex items-center justify-center gap-1.5 ${
                    playerColor === color
                      ? 'bg-primary-container text-on-primary-container shadow-sm'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  {color === 'BOTH' && <span className="material-symbols-outlined text-sm" aria-hidden="true">contrast</span>}
                  {color === 'WHITE' && <span className="w-2.5 h-2.5 rounded-full bg-white border border-gray-400" aria-hidden="true" />}
                  {color === 'BLACK' && <span className="w-2.5 h-2.5 rounded-full bg-gray-900 border border-gray-600" aria-hidden="true" />}
                  <span>{color}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Advanced Date Range Controls */}
          <div className="pt-2 border-t border-outline-variant space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-2 text-xs font-bold font-mono text-on-surface">
                <span className="material-symbols-outlined text-primary text-sm">calendar_month</span>
                <span>Advanced Date Range</span>
              </div>
              <span className="text-[11px] font-mono text-on-surface-variant">Optional date bounds</span>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <MonthPicker
                label="From month"
                value={fromDate}
                onChange={setFromDate}
              />
              <MonthPicker
                label="To month"
                value={toDate}
                onChange={setToDate}
              />
            </div>
          </div>

          {errorMessage && (
            <div role="alert" className="p-3.5 bg-error-container/20 border border-error-container rounded-xl text-xs font-mono font-semibold text-error whitespace-pre-line flex items-center gap-2">
              <span className="material-symbols-outlined text-base">warning</span>
              <span>{errorMessage}</span>
            </div>
          )}

          {/* Submit Action Button */}
          <div className="space-y-2 pt-2">
            <button
              type="button"
              onClick={handleStartImport}
              disabled={!canStartImport || activeJob?.status === 'QUEUED' || activeJob?.status === 'PROCESSING'}
              className="w-full py-3.5 bg-primary-container hover:bg-primary disabled:opacity-50 text-on-primary-container font-bold font-mono text-sm rounded-xl transition shadow-sm flex items-center justify-center space-x-2 cursor-pointer disabled:cursor-not-allowed"
            >
              <span className="material-symbols-outlined text-lg">rocket_launch</span>
              <span>
                {activeJob?.status === 'QUEUED' || activeJob?.status === 'PROCESSING'
                  ? 'Import Job Active...'
                  : 'Start Import & Engine Analysis'}
              </span>
            </button>
            <p className="text-[11px] text-center text-on-surface-variant font-mono">
              Positions are verified and deduplicated by FEN hashes prior to Stockfish depth evaluation.
            </p>
          </div>
        </div>

        {/* Live Job Status Monitor Card (5 cols on desktop) */}
        <div className="xl:col-span-5 bg-surface-container-low p-6 rounded-2xl border border-outline-variant shadow-xl flex flex-col justify-between space-y-4">
          <div>
            <h3 className="text-sm font-bold text-on-surface border-b border-outline-variant pb-3 flex items-center justify-between">
              <span className="flex items-center gap-2">
                <span className="material-symbols-outlined text-primary text-base">monitoring</span>
                <span>Live Job Status Monitor</span>
              </span>
              {activeJob && (
                <span className="text-xs font-mono text-on-surface-variant flex items-center gap-1">
                  <span className="material-symbols-outlined text-xs">timer</span>
                  <span>{elapsedSeconds}s</span>
                </span>
              )}
            </h3>

            {!activeJob ? (
              <div className="py-12 text-center space-y-4">
                <div className="w-16 h-16 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-on-surface-variant shadow-inner">
                  <span className="material-symbols-outlined text-3xl">cloud_download</span>
                </div>
                <div className="space-y-1">
                  <h4 className="text-sm font-semibold font-mono text-on-surface">No Active Import Job</h4>
                  <p className="text-xs text-on-surface-variant max-w-xs mx-auto">
                    Fill in your Chess.com username and click &quot;Start Import &amp; Engine Analysis&quot; to monitor live progress.
                  </p>
                </div>
                <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-surface-container-lowest border border-outline-variant text-[11px] font-mono text-on-surface-variant">
                  <span className="w-1.5 h-1.5 rounded-full bg-primary" />
                  <span>Pipeline queue ready for execution</span>
                </div>
              </div>
            ) : (
              <div className="space-y-5 pt-3">
                <div className="flex items-center justify-between text-xs font-bold">
                  <span className="text-on-surface flex items-center gap-2 truncate max-w-[200px] font-mono">
                    {(activeJob.status === 'QUEUED' || activeJob.status === 'PROCESSING') && (
                      <span className="w-2.5 h-2.5 rounded-full bg-amber-accent animate-ping" />
                    )}
                    {activeJob.status === 'COMPLETED' && (
                      <span className="material-symbols-outlined text-primary text-base">task_alt</span>
                    )}
                    {activeJob.status === 'FAILED' && (
                      <span className="material-symbols-outlined text-error text-base">error</span>
                    )}
                    <span>Import Progress</span>
                  </span>
                  <span
                    className={`px-2.5 py-1 rounded-md text-[10px] uppercase font-bold font-mono tracking-wide ${
                      activeJob.status === 'COMPLETED'
                        ? 'bg-primary/20 text-primary border border-primary/30'
                        : activeJob.status === 'FAILED'
                        ? 'bg-error-container/20 text-error border border-error-container/30'
                        : 'bg-amber-accent/20 text-amber-accent border border-amber-accent/30'
                    }`}
                  >
                    {activeJob.status}
                  </span>
                </div>

                {/* Shimmer activity progress bar */}
                {(activeJob.status === 'QUEUED' || activeJob.status === 'PROCESSING') && (
                  <div className="w-full bg-surface-container-lowest h-2 rounded-full overflow-hidden border border-outline-variant relative">
                    <div className="h-full bg-gradient-to-r from-primary/20 via-primary to-primary/20 animate-pulse w-full" />
                  </div>
                )}

                {/* Metrics Grid */}
                <div className="grid grid-cols-2 gap-3 pt-2 text-center">
                  <div className="bg-surface-container-lowest p-3.5 rounded-xl border border-outline-variant">
                    <span className="text-[10px] font-mono text-on-surface-variant uppercase tracking-wider">Imported</span>
                    <div className="text-base font-bold text-primary mt-1 font-mono">
                      {activeJob.gamesImported.toLocaleString()}
                    </div>
                  </div>

                  <div className="bg-surface-container-lowest p-3.5 rounded-xl border border-outline-variant">
                    <span className="text-[10px] font-mono text-on-surface-variant uppercase tracking-wider">Already imported</span>
                    <div className="text-base font-bold text-on-surface mt-1 font-mono">
                      {activeJob.gamesSkipped.toLocaleString()}
                    </div>
                  </div>
                </div>

                {/* Engine Analysis Indicator */}
                {activeJob.analysisStatus === 'ANALYZING' && (
                  <div className="p-3 bg-primary/10 border border-primary/30 rounded-xl flex items-center gap-2.5 text-xs font-mono text-primary">
                    <span className="material-symbols-outlined animate-spin text-base">sync</span>
                    <span>Stockfish 16.1 engine analysis active in background...</span>
                  </div>
                )}

                {activeJob.status === 'COMPLETED' && (
                  <div className="p-4 bg-surface-container border border-primary/40 rounded-xl space-y-3">
                    <div className="text-xs font-bold font-mono text-primary flex items-center gap-1.5">
                      <span className="material-symbols-outlined text-primary text-base">task_alt</span>
                      <span>Import Completed Successfully!</span>
                    </div>
                    <div className="text-[11px] text-on-surface-variant">
                      {activeJob.analysisStatus === 'ANALYZING' ? (
                        <p>Stockfish is analyzing candidate positions in the background.</p>
                      ) : activeJob.analysisStatus === 'COMPLETED' ? (
                        <p>Analysis complete.</p>
                      ) : activeJob.analysisStatus === 'FAILED' ? (
                        <p className="font-semibold text-error">Analysis failed.</p>
                      ) : (
                        <p>
                          Your games have been imported into the database and board positions
                          extracted. Stockfish is continuously analyzing candidate positions in the
                          background.
                        </p>
                      )}
                    </div>
                    <div className="flex items-center gap-2 pt-1">
                      <button
                        type="button"
                        onClick={() => {
                          const effectiveUsername = connectedUsername || username;
                          if (onImportStarted && effectiveUsername) {
                            onImportStarted(effectiveUsername);
                          }
                          if (onNavigateTab) {
                            onNavigateTab('puzzles');
                          }
                        }}
                        className="px-3.5 py-2 bg-primary-container hover:bg-primary text-on-primary-container font-mono font-bold text-xs rounded-lg transition shadow-sm cursor-pointer"
                      >
                        Practice Puzzles
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          const effectiveUsername = connectedUsername || username;
                          if (onImportStarted && effectiveUsername) {
                            onImportStarted(effectiveUsername);
                          }
                          if (onNavigateTab) {
                            onNavigateTab('weaknesses');
                          }
                        }}
                        className="px-3.5 py-2 bg-surface-container-high hover:bg-surface-container-highest text-on-surface font-mono font-bold text-xs rounded-lg transition shadow-sm border border-outline-variant cursor-pointer"
                      >
                        View Weaknesses
                      </button>
                    </div>
                  </div>
                )}

                {activeJob.status === 'FAILED' && (
                  <div className="p-4 bg-error-container/10 border border-error/40 rounded-xl space-y-3">
                    <div className="text-xs font-bold font-mono text-error flex items-center gap-1.5">
                      <span className="material-symbols-outlined text-error text-base">error</span>
                      <span>Import Job Failed</span>
                    </div>
                    <p className="text-xs text-on-surface-variant font-mono">
                      {activeJob.errorMessage || 'An error occurred during game ingestion.'}
                    </p>
                    <button
                      type="button"
                      onClick={handleStartImport}
                      className="px-3.5 py-1.5 bg-error-container hover:bg-error text-on-error font-mono font-bold text-xs rounded-lg transition cursor-pointer"
                    >
                      Retry Import
                    </button>
                  </div>
                )}

                {activeJob.errorMessage && activeJob.status !== 'FAILED' && (
                  <div role="alert" className="bg-error-container/20 p-3 rounded-xl border border-error-container text-xs font-mono text-error whitespace-pre-line">
                    Error: {activeJob.errorMessage}
                  </div>
                )}

                {pollingError && (
                  <div role="alert" className="bg-error-container/20 p-3 rounded-xl border border-error-container text-xs font-mono text-error whitespace-pre-line">
                    ⚠️ {pollingError}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
