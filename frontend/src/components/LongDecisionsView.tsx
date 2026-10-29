'use client';

import React, { FormEvent, useRef, useState } from 'react';
import { LayoutGrid, List } from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import { ExternalGameLink } from './ExternalGameLink';
import { PositionCard } from './PositionCard';
import { StatusBanner } from './StatusBanner';
import { SectionHeader } from './SectionHeader';
import {
  fetchLongDecisions,
  type DecisionSeverity,
  type LongDecisionOccurrence,
  type LongDecisionTimeControl,
} from '../services/api';
import { buildExactPlyChessComUrl, isValidChessComUrl } from '../utils/urlUtils';

type SessionStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error';
type AccountStatus = 'loading' | 'connected' | 'unconnected' | 'error';

interface LongDecisionsViewProps {
  accountId?: string;
  sessionStatus: SessionStatus;
  accountStatus: AccountStatus;
  onRetryAccountLoad?: () => void;
  onNavigateImport?: () => void;
  onExplorePosition?: (fen: string) => boolean | void;
}

interface SubmittedQuery {
  timeControl: LongDecisionTimeControl;
  thresholdSeconds: number;
  severity?: DecisionSeverity;
}

const PAGE_SIZE = 20;
const TIME_CONTROLS: LongDecisionTimeControl[] = ['BULLET', 'BLITZ', 'RAPID', 'CLASSICAL'];

export function formatDecisionDuration(decisionTimeMs: number): string {
  const seconds = decisionTimeMs / 1_000;
  return `${Number(seconds.toFixed(3))} ${seconds === 1 ? 'second' : 'seconds'}`;
}

export function formatSeverityLabel(severity: DecisionSeverity): string {
  switch (severity) {
    case 'NO_CONCERN':
      return 'No concern';
    case 'MINOR':
      return 'Minor';
    case 'SIGNIFICANT':
      return 'Significant';
    case 'CRITICAL':
      return 'Critical';
    case 'UNKNOWN':
      return 'Unclassified';
  }
}

export function severityPillStyles(severity: DecisionSeverity): string {
  switch (severity) {
    case 'CRITICAL':
      return 'bg-rose-500/10 text-rose-400 border border-rose-500/20';
    case 'SIGNIFICANT':
      return 'bg-amber-500/10 text-amber-400 border border-amber-500/20';
    case 'MINOR':
      return 'bg-blue-500/10 text-blue-400 border border-blue-500/20';
    case 'NO_CONCERN':
      return 'bg-slate-500/10 text-slate-300 border border-slate-700';
    case 'UNKNOWN':
      return 'bg-slate-500/10 text-slate-400 border border-slate-800';
  }
}

function formatPlayedAt(playedAt: string | null): string {
  if (!playedAt) return 'Date unavailable';
  const date = new Date(playedAt);
  if (!Number.isFinite(date.getTime())) return 'Date unavailable';
  return date.toLocaleDateString();
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Please try again.';
}

export const LongDecisionsView: React.FC<LongDecisionsViewProps> = ({
  accountId,
  sessionStatus,
  accountStatus,
  onRetryAccountLoad,
  onNavigateImport,
  onExplorePosition,
}) => {
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [timeControl, setTimeControl] = useState<LongDecisionTimeControl>('BULLET');
  const [severityFilter, setSeverityFilter] = useState<
    'ALL' | 'NO_CONCERN' | 'MINOR' | 'SIGNIFICANT' | 'CRITICAL'
  >('ALL');
  const [thresholdInput, setThresholdInput] = useState('30');
  const [thresholdError, setThresholdError] = useState<string | null>(null);
  const [submittedQuery, setSubmittedQuery] = useState<SubmittedQuery | null>(null);
  const [occurrences, setOccurrences] = useState<LongDecisionOccurrence[]>([]);
  const [currentPage, setCurrentPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const [submittedAccountId, setSubmittedAccountId] = useState<string | null>(null);
  const [explorationErrorId, setExplorationErrorId] = useState<string | null>(null);
  const requestVersion = useRef(0);
  const loadingMore = useRef(false);

  const hasConnectedAccount =
    sessionStatus === 'authenticated' && accountStatus === 'connected' && !!accountId;
  const hasCurrentQuery = submittedQuery !== null && submittedAccountId === accountId;

  const runSearch = async (query: SubmittedQuery) => {
    if (!accountId || !hasConnectedAccount) return;
    const version = ++requestVersion.current;
    loadingMore.current = false;
    setSubmittedAccountId(accountId);
    setSubmittedQuery(query);
    setOccurrences([]);
    setCurrentPage(0);
    setHasNext(false);
    setIsLoading(true);
    setIsLoadingMore(false);
    setError(null);
    setLoadMoreError(null);
    setExplorationErrorId(null);

    try {
      const result = await fetchLongDecisions(
        accountId,
        query.timeControl,
        query.thresholdSeconds,
        0,
        PAGE_SIZE,
        query.thresholdSeconds,
        query.severity,
      );
      if (requestVersion.current !== version) return;
      setOccurrences(result.content);
      setCurrentPage(result.page);
      setHasNext(result.hasNext);
    } catch (requestError: unknown) {
      if (requestVersion.current === version) setError(errorMessage(requestError));
    } finally {
      if (requestVersion.current === version) setIsLoading(false);
    }
  };

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const seconds = Number(thresholdInput);
    if (!Number.isSafeInteger(seconds) || seconds <= 0 || seconds > 2_147_483_647) {
      setThresholdError('Enter a positive whole number of seconds.');
      return;
    }
    if (!hasConnectedAccount) return;

    setThresholdError(null);
    const selectedSeverity = severityFilter === 'ALL' ? undefined : severityFilter;
    void runSearch({
      timeControl,
      thresholdSeconds: seconds,
      severity: selectedSeverity,
    });
  };

  const loadMore = async () => {
    if (!accountId || !submittedQuery || !hasNext || loadingMore.current || isLoadingMore) return;
    const version = requestVersion.current;
    const nextPage = currentPage + 1;
    loadingMore.current = true;
    setIsLoadingMore(true);
    setLoadMoreError(null);

    try {
      const result = await fetchLongDecisions(
        accountId,
        submittedQuery.timeControl,
        submittedQuery.thresholdSeconds,
        nextPage,
        PAGE_SIZE,
        submittedQuery.thresholdSeconds,
        submittedQuery.severity,
      );
      if (requestVersion.current !== version) return;
      setOccurrences((current) => [...current, ...result.content]);
      setCurrentPage(result.page);
      setHasNext(result.hasNext);
    } catch (requestError: unknown) {
      if (requestVersion.current === version) setLoadMoreError(errorMessage(requestError));
    } finally {
      if (requestVersion.current === version) {
        loadingMore.current = false;
        setIsLoadingMore(false);
      }
    }
  };

  return (
    <section
      className="mx-auto w-full max-w-[1536px] space-y-4 px-4 py-6 lg:px-8"
      aria-labelledby="decision-timing-title"
    >
      <SectionHeader
        id="decision-timing-title"
        title="Decision Timing"
        description="Analyze individual positions where you spent unusually expensive thinking time relative to the time control. Decision timing describes duration only, not move quality."
        actions={
          <div className="flex items-center space-x-3 shrink-0 self-end sm:self-center">
            <div className="px-2.5 py-1 rounded bg-surface-container border border-outline-variant text-xs font-mono">
              <span className="text-outline">Loaded: </span>
              <strong data-testid="decision-timing-count-indicator" className="text-primary">
                {occurrences.length}
              </strong>
              <span className="text-outline text-[10px]"> positions</span>
            </div>

            <div className="flex items-center bg-surface-container-lowest p-0.5 rounded border border-outline-variant">
              <button
                type="button"
                onClick={() => setViewMode('grid')}
                className={`flex items-center gap-1 px-2.5 py-1 rounded text-xs font-mono transition cursor-pointer ${
                  viewMode === 'grid'
                    ? 'bg-surface-container text-primary font-semibold'
                    : 'text-outline hover:text-on-surface'
                }`}
              >
                <LayoutGrid className="w-3.5 h-3.5" />
                <span>Grid</span>
              </button>
              <button
                type="button"
                onClick={() => setViewMode('list')}
                className={`flex items-center gap-1 px-2.5 py-1 rounded text-xs font-mono transition cursor-pointer ${
                  viewMode === 'list'
                    ? 'bg-surface-container text-primary font-semibold'
                    : 'text-outline hover:text-on-surface'
                }`}
              >
                <List className="w-3.5 h-3.5" />
                <span>List</span>
              </button>
            </div>
          </div>
        }
      />

      <form
        className="grid gap-4 rounded-xl border border-outline-variant bg-surface-container-low p-5 sm:grid-cols-4 sm:items-end"
        noValidate
        onSubmit={handleSearch}
      >
        <label className="block space-y-2 text-sm font-semibold text-slate-200">
          <span>Time control</span>
          <select
            aria-label="Time control"
            className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white"
            value={timeControl}
            onChange={(event) => setTimeControl(event.target.value as LongDecisionTimeControl)}
          >
            {TIME_CONTROLS.map((control) => (
              <option key={control} value={control}>
                {control}
              </option>
            ))}
          </select>
        </label>

        <label className="block space-y-2 text-sm font-semibold text-slate-200">
          <span>Severity</span>
          <select
            aria-label="Severity"
            className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white"
            value={severityFilter}
            onChange={(event) =>
              setSeverityFilter(
                event.target.value as
                  | 'ALL'
                  | 'NO_CONCERN'
                  | 'MINOR'
                  | 'SIGNIFICANT'
                  | 'CRITICAL',
              )
            }
          >
            <option value="ALL">All Severities</option>
            <option value="NO_CONCERN">No concern</option>
            <option value="MINOR">Minor</option>
            <option value="SIGNIFICANT">Significant</option>
            <option value="CRITICAL">Critical</option>
          </select>
        </label>

        <label className="block space-y-2 text-sm font-semibold text-slate-200">
          <span>Decision-time threshold (seconds)</span>
          <input
            aria-label="Decision-time threshold (seconds)"
            className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white"
            type="number"
            min="1"
            max="2147483647"
            step="1"
            inputMode="numeric"
            value={thresholdInput}
            aria-invalid={thresholdError !== null}
            onChange={(event) => {
              setThresholdInput(event.target.value);
              setThresholdError(null);
            }}
          />
        </label>

        <button
          className="rounded-lg bg-emerald-600 px-5 py-2.5 text-sm font-bold text-white transition hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
          type="submit"
          disabled={!hasConnectedAccount}
        >
          Search
        </button>
        <p className="text-xs leading-5 text-slate-400 sm:col-span-4">
          Time control chooses which games to search. The threshold and severity filter occurrences within those games.
        </p>
        {thresholdError && (
          <p className="text-sm text-rose-300 sm:col-span-4" role="alert">
            {thresholdError}
          </p>
        )}
      </form>

      {sessionStatus === 'loading' ||
      (sessionStatus === 'authenticated' && accountStatus === 'loading') ? (
        <p className="rounded-xl border border-slate-800 bg-slate-900/50 p-5 text-sm text-slate-300" role="status">
          Checking your Chess.com account
        </p>
      ) : sessionStatus === 'error' ? (
        <p className="rounded-xl border border-rose-900/60 bg-rose-950/30 p-5 text-sm text-rose-200" role="alert">
          Unable to check your ChessProbe session. Refresh the page to try again.
        </p>
      ) : sessionStatus === 'authenticated' && accountStatus === 'error' ? (
        <div className="rounded-xl border border-rose-900/60 bg-rose-950/30 p-5 text-sm text-rose-200" role="alert">
          <p>Unable to load your connected Chess.com account.</p>
          <button
            className="mt-3 rounded-lg bg-slate-800 px-4 py-2 font-semibold text-white"
            onClick={onRetryAccountLoad}
            type="button"
          >
            Retry account loading
          </button>
        </div>
      ) : !hasConnectedAccount ? (
        <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-6 text-center">
          <h3 className="font-semibold text-white">No Connected Account</h3>
          <p className="mt-2 text-sm text-slate-400">Connect a Chess.com account to search its imported decisions.</p>
          <button
            className="mt-4 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-bold text-white hover:bg-emerald-500"
            onClick={onNavigateImport}
            type="button"
          >
            Go to Import Games
          </button>
        </div>
      ) : isLoading && hasCurrentQuery ? (
        <StatusBanner variant="loading" message="Searching long-decision occurrences" />
      ) : error && hasCurrentQuery ? (
        <StatusBanner
          variant="error"
          message={`Could not load long-decision occurrences. ${error}`}
          action={submittedQuery ? { label: 'Retry search', onClick: () => void runSearch(submittedQuery) } : undefined}
        />
      ) : !hasCurrentQuery ? (
        <p className="rounded-xl border border-slate-800 bg-slate-900/50 p-5 text-sm text-slate-400">
          Choose a time control and threshold, then search your imported games.
        </p>
      ) : occurrences.length === 0 ? (
        <p className="rounded-xl border border-slate-800 bg-slate-900/50 p-5 text-sm text-slate-300">
          No occurrences met this threshold or filter criteria in the selected time control.
        </p>
      ) : (
        <div className="space-y-4">
          <p className="text-sm text-slate-400">
            {occurrences.length} occurrence{occurrences.length === 1 ? '' : 's'} shown. Positions are not grouped.
          </p>
          <div className={viewMode === 'grid' ? 'grid gap-4 lg:grid-cols-2' : 'flex flex-col space-y-4'}>
            {occurrences.map((occurrence) => (
              <PositionCard key={occurrence.id} className="grid gap-4 sm:grid-cols-[180px_1fr]">
                <div className="aspect-square w-full max-w-[180px] overflow-hidden rounded-xl border border-slate-700">
                  <ChessBoardView
                    fen={occurrence.fen}
                    boardOrientation={occurrence.playerColor}
                    allowDragging={false}
                    containerClassName="w-full h-full"
                    frameClassName="w-full h-full"
                  />
                </div>
                <div className="flex min-w-0 flex-col gap-3">
                  <div className="space-y-1">
                    <div className="flex items-center justify-between gap-2">
                      <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                        {occurrence.timeControl} · {occurrence.playerColor}
                      </p>
                      <span
                        data-testid={`severity-pill-${occurrence.id}`}
                        className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${severityPillStyles(
                          occurrence.severity,
                        )}`}
                      >
                        {formatSeverityLabel(occurrence.severity)}
                      </span>
                    </div>
                    <p className="text-lg font-bold text-emerald-300">
                      {formatDecisionDuration(occurrence.decisionTimeMs)}
                    </p>
                    <p className="text-sm text-slate-300">
                      Move played: <span className="font-semibold text-white">{occurrence.movePlayed}</span>
                    </p>
                  </div>
                  <dl className="space-y-1 text-xs text-slate-400">
                    <div>
                      <dt className="inline">Opponent: </dt>
                      <dd className="inline">{occurrence.opponentUsername || 'Unavailable'}</dd>
                    </div>
                    <div>
                      <dt className="inline">Date: </dt>
                      <dd className="inline">{formatPlayedAt(occurrence.playedAt)}</dd>
                    </div>
                    <div>
                      <dt className="inline">Ply: </dt>
                      <dd className="inline">{occurrence.plyNumber}</dd>
                    </div>
                  </dl>
                  {(() => {
                    const gameUrl = isValidChessComUrl(occurrence.chessComUrl)
                      ? occurrence.chessComUrl.trim()
                      : buildExactPlyChessComUrl(
                          occurrence.platformGameId,
                          occurrence.plyNumber,
                          occurrence.timeControl,
                        );
                    if (!gameUrl) {
                      return null;
                    }
                    return (
                      <ExternalGameLink
                        url={gameUrl}
                        label="Open game"
                        platform="chesscom"
                        rel="noreferrer"
                        className="mt-auto"
                      />
                    );
                  })()}
                  {onExplorePosition && (
                    <>
                      <button
                        className="text-left text-sm font-semibold text-emerald-300 underline decoration-emerald-700 underline-offset-4 hover:text-emerald-200"
                        onClick={() => {
                          const started = onExplorePosition(occurrence.fen);
                          setExplorationErrorId(started === false ? occurrence.id : null);
                        }}
                        type="button"
                      >
                        Explore this position
                      </button>
                      {explorationErrorId === occurrence.id && (
                        <p className="text-sm text-rose-300" role="alert">
                          Could not start exploration for this position. Your current state was not changed.
                        </p>
                      )}
                    </>
                  )}
                </div>
              </PositionCard>
            ))}
          </div>
          {loadMoreError && (
            <div className="text-sm text-rose-300" role="alert">
              <p>Could not load more occurrences. {loadMoreError}</p>
              <button
                className="mt-2 rounded-lg bg-slate-800 px-4 py-2 font-semibold text-white"
                onClick={() => void loadMore()}
                type="button"
              >
                Retry loading more
              </button>
            </div>
          )}
          {hasNext && (
            <button
              className="rounded-lg border border-slate-700 bg-slate-900 px-5 py-2.5 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
              disabled={isLoadingMore}
              onClick={() => void loadMore()}
              type="button"
            >
              {isLoadingMore ? 'Loading more…' : 'Load more'}
            </button>
          )}
        </div>
      )}

      {hasCurrentQuery && occurrences.length > 0 && (
        <p className="text-xs text-slate-500">This result describes decision time, not move quality.</p>
      )}
    </section>
  );
};
