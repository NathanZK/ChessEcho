'use client';

import React, { useEffect, useRef, useState } from 'react';
import {
  LongDecisionGridCard,
  LongDecisionListRow,
  formatDecisionDuration,
  formatSeverityLabel,
  severityPillStyles,
} from './LongDecisionCard';
import { StatusBanner } from './StatusBanner';
import {
  fetchLongDecisions,
  type LongDecisionOccurrence,
  type LongDecisionTimeControl,
} from '../services/api';

export {
  formatDecisionDuration,
  formatSeverityLabel,
  severityPillStyles,
};

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
    <section className="space-y-6 max-w-7xl mx-auto" aria-labelledby="decision-timing-title">
      {/* Section Header */}
      <section className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-outline-variant pb-5">
        <div className="space-y-1">
          <div className="inline-flex items-center gap-2 px-2.5 py-1 rounded-full bg-primary/10 border border-primary/20 text-primary text-xs font-mono font-semibold">
            <span className="material-symbols-outlined text-sm" aria-hidden="true">timer</span>
            <span>TEMPO ANALYSIS</span>
          </div>
          <h2 id="decision-timing-title" className="text-xl md:text-2xl font-bold text-on-surface tracking-tight">
            Decision Timing
          </h2>
          <p className="text-xs text-on-surface-variant max-w-2xl leading-relaxed">
            Analyze individual positions where you spent unusually expensive thinking time relative to the time control. Decision timing describes duration only, not move quality.
          </p>
        </div>

        {/* View Switcher & Paging Scope Indicator */}
        <div className="flex items-center space-x-3 shrink-0 self-end sm:self-center">
          <div className="px-3 py-1.5 rounded-xl bg-surface-container-low border border-outline-variant text-xs font-mono flex items-center gap-1.5">
            <span className="text-outline">Loaded:</span>
            <strong data-testid="decision-timing-count-indicator" className="text-primary font-bold">
              {occurrences.length}
            </strong>
            <span className="text-outline text-[11px]">positions</span>
          </div>

          <div className="flex items-center bg-surface-container-lowest p-1 rounded-xl border border-outline-variant">
            <button
              type="button"
              onClick={() => setViewMode('grid')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-mono transition cursor-pointer ${
                viewMode === 'grid'
                  ? 'bg-surface-container text-primary font-semibold shadow-sm'
                  : 'text-outline hover:text-on-surface'
              }`}
            >
              <span className="material-symbols-outlined text-sm" aria-hidden="true">grid_view</span>
              <span>Grid</span>
            </button>
            <button
              type="button"
              onClick={() => setViewMode('list')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-mono transition cursor-pointer ${
                viewMode === 'list'
                  ? 'bg-surface-container text-primary font-semibold shadow-sm'
                  : 'text-outline hover:text-on-surface'
              }`}
            >
              <span className="material-symbols-outlined text-sm" aria-hidden="true">view_list</span>
              <span>List</span>
            </button>
          </div>
        </div>
      </section>

      {/* Filters Bar */}
      <section
        className="bg-surface-container-low border border-outline-variant rounded-2xl p-4 space-y-3 shadow-sm"
        role="region"
        aria-label="Filter decisions"
      >
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center space-x-1.5 text-xs font-mono font-semibold text-outline mr-1">
              <span className="material-symbols-outlined text-base" aria-hidden="true">filter_list</span>
              <span>Filters:</span>
            </div>

            {/* Time Control Filter */}
            <div className="relative">
              <select
                aria-label="Time control"
                value={timeControl}
                onChange={(event) => setTimeControl(event.target.value as LongDecisionTimeControl)}
                className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-xl pl-3 pr-8 py-1.5 text-xs font-mono text-on-surface cursor-pointer outline-none focus:border-primary transition"
              >
                {TIME_CONTROLS.map((control) => (
                  <option key={control} value={control}>
                    {control}
                  </option>
                ))}
              </select>
              <span className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-outline material-symbols-outlined text-sm" aria-hidden="true">
                expand_more
              </span>
            </div>

            {/* Severity Filter */}
            <div className="relative">
              <select
                aria-label="Severity"
                value={severityFilter}
                onChange={(event) =>
                  setSeverityFilter(event.target.value as 'CRITICAL' | 'SIGNIFICANT' | 'MINOR')
                }
                className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-xl pl-3 pr-8 py-1.5 text-xs font-mono text-on-surface cursor-pointer outline-none focus:border-primary transition"
              >
                <option value="CRITICAL">Critical</option>
                <option value="SIGNIFICANT">Significant</option>
                <option value="MINOR">Minor</option>
              </select>
              <span className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-outline material-symbols-outlined text-sm" aria-hidden="true">
                tune
              </span>
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
            <span className="material-symbols-outlined text-3xl" aria-hidden="true">error</span>
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
            <span className="material-symbols-outlined text-3xl" aria-hidden="true">error</span>
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
            <span className="material-symbols-outlined text-sm" aria-hidden="true">refresh</span>
            <span>Retry account loading</span>
          </button>
        </div>
      ) : !hasConnectedAccount ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-primary">
            <span className="material-symbols-outlined text-3xl" aria-hidden="true">timer</span>
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
            <span className="material-symbols-outlined text-3xl" aria-hidden="true">error</span>
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
            <span className="material-symbols-outlined text-sm" aria-hidden="true">refresh</span>
            <span>Retry search</span>
          </button>
        </div>
      ) : occurrences.length === 0 ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-outline">
            <span className="material-symbols-outlined text-3xl" aria-hidden="true">timer_off</span>
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
            {occurrences.map((occurrence) =>
              viewMode === 'grid' ? (
                <LongDecisionGridCard
                  key={occurrence.id}
                  occurrence={occurrence}
                  onExplorePosition={onExplorePosition}
                  explorationErrorId={explorationErrorId}
                  setExplorationErrorId={setExplorationErrorId}
                />
              ) : (
                <LongDecisionListRow
                  key={occurrence.id}
                  occurrence={occurrence}
                  onExplorePosition={onExplorePosition}
                  explorationErrorId={explorationErrorId}
                  setExplorationErrorId={setExplorationErrorId}
                />
              ),
            )}
          </div>
          {loadMoreError && (
            <div className="p-4 bg-surface-container-low rounded-xl border border-error-container text-xs text-error font-mono space-y-2" role="alert">
              <p>Could not load more occurrences. {loadMoreError}</p>
              <button
                type="button"
                className="px-4 py-2 bg-surface-container-high hover:bg-surface-bright text-on-surface text-xs font-mono font-bold rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
                onClick={() => void loadMore()}
              >
                <span className="material-symbols-outlined text-sm" aria-hidden="true">refresh</span>
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
