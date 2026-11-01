'use client';

import React, { useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  ChevronDown,
  Clock3,
  Filter,
  LayoutGrid,
  List,
  RefreshCw,
  SlidersHorizontal,
} from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import { ExternalGameLink } from './ExternalGameLink';
import { PositionCard } from './PositionCard';
import { StatusBanner } from './StatusBanner';
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
  onExplorePosition?: (occurrence: LongDecisionOccurrence) => boolean | void;
}

interface SubmittedQuery {
  timeControl: LongDecisionTimeControl;
  severity: 'CRITICAL' | 'SIGNIFICANT' | 'MINOR';
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
  const [reloadToken, setReloadToken] = useState(0);
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [timeControl, setTimeControl] = useState<LongDecisionTimeControl>('RAPID');
  const [severityFilter, setSeverityFilter] = useState<'CRITICAL' | 'SIGNIFICANT' | 'MINOR'>('CRITICAL');
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

  useEffect(() => {
    let active = true;
    if (!accountId || !hasConnectedAccount) {
      requestVersion.current++;
      loadingMore.current = false;
      queueMicrotask(() => {
        if (!active) return;
        setSubmittedAccountId(null);
        setSubmittedQuery(null);
        setOccurrences([]);
        setCurrentPage(0);
        setHasNext(false);
        setIsLoading(false);
        setIsLoadingMore(false);
        setError(null);
        setLoadMoreError(null);
        setExplorationErrorId(null);
      });
      return () => {
        active = false;
      };
    }

    const currentAccountId = accountId;
    const version = ++requestVersion.current;
    const query: SubmittedQuery = { timeControl, severity: severityFilter };
    loadingMore.current = false;

    async function executeSearch() {
      setIsLoading(true);
      setError(null);
      setLoadMoreError(null);
      setExplorationErrorId(null);
      setSubmittedAccountId(currentAccountId);
      setSubmittedQuery(query);
      setOccurrences([]);
      setCurrentPage(0);
      setHasNext(false);
      setIsLoadingMore(false);

      try {
        const result = await fetchLongDecisions(
          currentAccountId,
          query.timeControl,
          undefined,
          0,
          PAGE_SIZE,
          undefined,
          query.severity,
        );
        if (!active || requestVersion.current !== version) return;
        setOccurrences(result.content);
        setCurrentPage(result.page);
        setHasNext(result.hasNext);
      } catch (requestError: unknown) {
        if (!active || requestVersion.current !== version) return;
        setError(errorMessage(requestError));
      } finally {
        if (active && requestVersion.current === version) {
          setIsLoading(false);
        }
      }
    }

    void executeSearch();

    return () => {
      active = false;
    };
  }, [accountId, hasConnectedAccount, timeControl, severityFilter, reloadToken]);

  const retrySearch = () => {
    setReloadToken((prev) => prev + 1);
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
        undefined,
        nextPage,
        PAGE_SIZE,
        undefined,
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
    <section className="space-y-4 max-w-[1536px] mx-auto" aria-labelledby="decision-timing-title">
      {/* Section Header */}
      <section className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-outline-variant pb-4">
        <div className="space-y-1">
          <h2 id="decision-timing-title" className="text-xl md:text-2xl font-bold text-on-surface tracking-tight flex items-center gap-2">
            <Clock3 className="w-6 h-6 text-primary" />
            <span>Decision Timing</span>
          </h2>
          <p className="text-xs text-on-surface-variant max-w-2xl leading-relaxed">
            Analyze individual positions where you spent unusually expensive thinking time relative to the time control. Decision timing describes duration only, not move quality.
          </p>
        </div>

        {/* View Switcher & Paging Scope Indicator */}
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
      </section>

      {/* Filters Bar */}
      <section
        className="bg-surface-container-low border border-outline-variant rounded-xl p-3.5 space-y-2.5 shadow-sm"
        role="region"
        aria-label="Filter decisions"
      >
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2.5">
            <div className="flex items-center space-x-1.5 text-xs font-mono font-semibold text-outline mr-1">
              <Filter className="w-3.5 h-3.5" />
              <span>Filters:</span>
            </div>

            {/* Time Control Filter */}
            <div className="relative">
              <select
                aria-label="Time control"
                value={timeControl}
                onChange={(event) => setTimeControl(event.target.value as LongDecisionTimeControl)}
                className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-lg pl-2.5 pr-7 py-1 text-xs font-mono text-on-surface cursor-pointer outline-none"
              >
                {TIME_CONTROLS.map((control) => (
                  <option key={control} value={control}>
                    {control}
                  </option>
                ))}
              </select>
              <ChevronDown className="pointer-events-none absolute right-1.5 top-1/2 -translate-y-1/2 text-outline w-3.5 h-3.5" />
            </div>

            {/* Severity Filter */}
            <div className="relative">
              <select
                aria-label="Severity"
                value={severityFilter}
                onChange={(event) =>
                  setSeverityFilter(event.target.value as 'CRITICAL' | 'SIGNIFICANT' | 'MINOR')
                }
                className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-lg pl-2.5 pr-7 py-1 text-xs font-mono text-on-surface cursor-pointer outline-none"
              >
                <option value="CRITICAL">Critical</option>
                <option value="SIGNIFICANT">Significant</option>
                <option value="MINOR">Minor</option>
              </select>
              <SlidersHorizontal className="pointer-events-none absolute right-1.5 top-1/2 -translate-y-1/2 text-outline w-3.5 h-3.5" />
            </div>
          </div>
        </div>

        <p className="text-xs text-on-surface-variant font-mono">
          Time control chooses which games to search. Severity filters occurrences by analytical thinking time.
        </p>
      </section>

      {sessionStatus === 'loading' ||
      (sessionStatus === 'authenticated' && accountStatus === 'loading') ? (
        <StatusBanner variant="loading" message="Checking your Chess.com account" />
      ) : sessionStatus === 'error' ? (
        <div
          className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-error-container shadow-xl"
          role="alert"
        >
          <div className="w-14 h-14 rounded-2xl bg-error-container/20 border border-error-container flex items-center justify-center mx-auto text-error">
            <AlertCircle className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">Session Check Failed</h3>
            <p className="text-xs text-error mt-1">
              Unable to check your ChessProbe session. Refresh the page to try again.
            </p>
          </div>
        </div>
      ) : sessionStatus === 'authenticated' && accountStatus === 'error' ? (
        <div
          className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-error-container shadow-xl"
          role="alert"
        >
          <div className="w-14 h-14 rounded-2xl bg-error-container/20 border border-error-container flex items-center justify-center mx-auto text-error">
            <AlertCircle className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">Failed to Load Account</h3>
            <p className="text-xs text-error mt-1">Unable to load your connected Chess.com account.</p>
          </div>
          <button
            type="button"
            onClick={onRetryAccountLoad}
            className="px-4 py-2 bg-surface-container-high hover:bg-surface-bright text-on-surface text-xs font-mono font-bold rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Retry account loading</span>
          </button>
        </div>
      ) : !hasConnectedAccount ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-primary">
            <Clock3 className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">No Connected Account</h3>
            <p className="text-xs text-on-surface-variant mt-1">
              Connect a Chess.com account to search its imported decisions.
            </p>
          </div>
          <button
            type="button"
            onClick={onNavigateImport}
            className="px-4 py-2 bg-primary hover:bg-primary-container text-on-primary-container text-xs font-mono font-bold rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
          >
            Go to Import Games
          </button>
        </div>
      ) : isLoading || !hasCurrentQuery ? (
        <StatusBanner variant="loading" message="Searching long-decision occurrences" />
      ) : error ? (
        <div
          className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-error-container shadow-xl"
          role="alert"
        >
          <div className="w-14 h-14 rounded-2xl bg-error-container/20 border border-error-container flex items-center justify-center mx-auto text-error">
            <AlertCircle className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">Failed to Load Decisions</h3>
            <p className="text-xs text-error mt-1">{error}</p>
          </div>
          <button
            type="button"
            onClick={retrySearch}
            className="px-4 py-2 bg-surface-container-high hover:bg-surface-bright text-on-surface text-xs font-mono font-bold rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Retry search</span>
          </button>
        </div>
      ) : occurrences.length === 0 ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-outline">
            <Clock3 className="w-7 h-7 text-outline" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">No Occurrences Found</h3>
            <p className="text-xs text-on-surface-variant mt-1">
              No occurrences met this threshold or filter criteria in the selected time control.
            </p>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <p className="text-xs text-on-surface-variant font-mono">
            {occurrences.length} occurrence{occurrences.length === 1 ? '' : 's'} shown. Positions are not grouped.
          </p>
          <div className={viewMode === 'grid' ? 'grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5' : 'flex flex-col space-y-4'}>
            {occurrences.map((occurrence) => (
              <PositionCard key={occurrence.id} className="grid gap-4 sm:grid-cols-[180px_1fr]">
                <div className="aspect-square w-full max-w-[180px] overflow-hidden rounded-xl border border-outline-variant">
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
                      <p className="text-xs font-semibold uppercase tracking-wide text-on-surface-variant font-mono">
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
                    <p className="text-lg font-bold text-primary font-mono">
                      {formatDecisionDuration(occurrence.decisionTimeMs)}
                    </p>
                    <p className="text-sm text-on-surface-variant">
                      Move played: <span className="font-semibold text-on-surface">{occurrence.movePlayed}</span>
                    </p>
                  </div>
                  <dl className="space-y-1 text-xs text-on-surface-variant font-mono">
                    <div>
                      <dt className="inline text-outline">Opponent: </dt>
                      <dd className="inline text-on-surface">{occurrence.opponentUsername || 'Unavailable'}</dd>
                    </div>
                    <div>
                      <dt className="inline text-outline">Date: </dt>
                      <dd className="inline text-on-surface">{formatPlayedAt(occurrence.playedAt)}</dd>
                    </div>
                    <div>
                      <dt className="inline text-outline">Ply: </dt>
                      <dd className="inline text-on-surface">{occurrence.plyNumber}</dd>
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
                        className="text-left text-xs font-mono font-semibold text-primary underline decoration-primary/40 underline-offset-4 hover:text-primary-hover cursor-pointer"
                        onClick={() => {
                          const started = onExplorePosition(occurrence);
                          setExplorationErrorId(started === false ? occurrence.id : null);
                        }}
                        type="button"
                      >
                        Explore this position
                      </button>
                      {explorationErrorId === occurrence.id && (
                        <p className="text-xs text-error font-mono" role="alert">
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
            <div className="p-4 bg-surface-container-low rounded-xl border border-error-container text-xs text-error font-mono space-y-2" role="alert">
              <p>Could not load more occurrences. {loadMoreError}</p>
              <button
                type="button"
                className="px-4 py-2 bg-surface-container-high hover:bg-surface-bright text-on-surface text-xs font-mono font-bold rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
                onClick={() => void loadMore()}
              >
                <RefreshCw className="w-3.5 h-3.5" />
                <span>Retry loading more</span>
              </button>
            </div>
          )}
          {hasNext && (
            <div className="pt-2 text-center">
              <button
                type="button"
                className="px-4 py-2 bg-surface-container-low hover:bg-surface-container text-on-surface-variant hover:text-on-surface text-xs font-bold font-mono rounded-xl border border-outline-variant transition cursor-pointer disabled:opacity-50"
                disabled={isLoadingMore}
                onClick={() => void loadMore()}
              >
                {isLoadingMore ? 'Loading more…' : 'Load more'}
              </button>
            </div>
          )}
        </div>
      )}

      {hasCurrentQuery && occurrences.length > 0 && (
        <p className="text-xs text-outline font-mono">This result describes decision time, not move quality.</p>
      )}
    </section>
  );
};
