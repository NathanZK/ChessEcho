'use client';

import React, { useEffect, useRef, useState } from 'react';
import {
  ProgressChart,
  chartMeasurements,
} from './PositionProgressChart';
import {
  fetchPositionProgress,
  type PositionProgressBaseline,
  type PositionProgressPoint,
  type PositionProgressResponse,
  type ProgressIntervalState,
  type SessionState,
} from '../services/api';

export interface PositionProgressViewProps {
  positionId: string;
  playerColor: 'WHITE' | 'BLACK';
  minEvalLoss: number;
  accountId?: string;
  sessionStatus: SessionState['status'];
  ecoCode?: string | null;
  openingName?: string | null;
  onBack?: () => void;
  onClose?: () => void;
}

type LoadState =
  | { requestKey: string; status: 'loading' }
  | { requestKey: string; status: 'loaded'; progress: PositionProgressResponse }
  | { requestKey: string; status: 'error' };

function changeText(value: number | null): string {
  if (value === null) return 'Not available yet';
  return `${value > 0 ? '+' : ''}${value}%`;
}

function intervalStateText(state: ProgressIntervalState): string | null {
  switch (state) {
    case 'NO_CHECKPOINT':
      return 'No solved puzzle checkpoint yet.';
    case 'OPEN_AWAITING_EVIDENCE':
      return 'Waiting for a dated game after the latest solve.';
    case 'MEASURED_OPEN':
      return null;
  }
}

function formatRate(rate: number): string {
  return `${Number(rate.toFixed(1))}%`;
}

function displayBaseline(progress: PositionProgressResponse): PositionProgressBaseline | null {
  if (progress.baseline) return progress.baseline;
  const firstInterval = progress.points[0];
  if (!firstInterval) return null;
  return {
    occurredAt: firstInterval.occurredAt,
    mistakeRate: firstInterval.mistakeRate,
    winRate: firstInterval.winRate,
    sourceEncounterCount: firstInterval.attempts,
  };
}

function BaselineSummary({ baseline }: { baseline: PositionProgressBaseline | null }) {
  return (
    <div className="rounded-xl border border-outline-variant bg-surface-container-low p-4 sm:p-5 shadow-sm">
      <h2 className="text-xs font-mono font-semibold uppercase tracking-wide text-on-surface-variant flex items-center gap-1.5">
        <span className="material-symbols-outlined text-sm text-outline" aria-hidden="true">history</span>
        Historical baseline
      </h2>
      {baseline === null ? (
        <p className="mt-2 text-xs font-mono text-outline">No dated baseline evidence yet.</p>
      ) : (
        <>
          <p className="mt-2 text-xs font-mono text-on-surface-variant">{baseline.sourceEncounterCount} dated encounters</p>
          <dl className="mt-3 grid grid-cols-2 gap-3 text-xs font-mono">
            <div className="p-2.5 rounded-lg bg-surface-container-lowest border border-outline-variant">
              <dt className="text-outline text-[11px]">Mistake rate</dt>
              <dd className="mt-1 text-base font-bold text-primary">{formatRate(baseline.mistakeRate)}</dd>
            </div>
            <div className="p-2.5 rounded-lg bg-surface-container-lowest border border-outline-variant">
              <dt className="text-outline text-[11px]">Win rate</dt>
              <dd className="mt-1 text-base font-bold text-sky-400">{formatRate(baseline.winRate)}</dd>
            </div>
          </dl>
          <p className="mt-3 text-[11px] font-mono text-outline">
            Dated through {new Date(baseline.occurredAt).toLocaleDateString()}
          </p>
        </>
      )}
    </div>
  );
}

function LatestIntervalSummary({ point }: { point: PositionProgressPoint | null }) {
  return (
    <section
      aria-labelledby="latest-interval-heading"
      className="rounded-xl border border-outline-variant bg-surface-container-low p-4 sm:p-5 shadow-sm"
    >
      <h2
        id="latest-interval-heading"
        className="text-xs font-mono font-semibold uppercase tracking-wide text-on-surface-variant flex items-center gap-1.5"
      >
        <span className="material-symbols-outlined text-sm text-outline" aria-hidden="true">update</span>
        Latest measured interval
      </h2>
      {point === null ? (
        <p className="mt-2 text-xs font-mono text-outline">No measured interval data yet.</p>
      ) : (
        <>
          <dl className="mt-3 grid grid-cols-2 gap-3 text-xs font-mono">
            <div className="p-2.5 rounded-lg bg-surface-container-lowest border border-outline-variant">
              <dt className="text-outline text-[11px]">Mistake rate</dt>
              <dd className="mt-1 text-base font-bold text-primary">{formatRate(point.mistakeRate)}</dd>
            </div>
            <div className="p-2.5 rounded-lg bg-surface-container-lowest border border-outline-variant">
              <dt className="text-outline text-[11px]">Win rate</dt>
              <dd className="mt-1 text-base font-bold text-sky-400">{formatRate(point.winRate)}</dd>
            </div>
            <div className="p-2.5 rounded-lg bg-surface-container-lowest border border-outline-variant">
              <dt className="text-outline text-[11px]">Attempts</dt>
              <dd className="mt-1 font-semibold text-on-surface">
                {point.attempts} {point.attempts === 1 ? 'attempt' : 'attempts'}
              </dd>
            </div>
            <div className="p-2.5 rounded-lg bg-surface-container-lowest border border-outline-variant">
              <dt className="text-outline text-[11px]">Interval status</dt>
              <dd className="mt-1 font-semibold text-on-surface">
                {point.open ? 'Open interval' : 'Closed interval'}
              </dd>
            </div>
          </dl>
          <p className="mt-3 text-[11px] font-mono text-outline">
            Latest included game: {new Date(point.occurredAt).toLocaleDateString()}
          </p>
        </>
      )}
    </section>
  );
}

export function PositionProgressView({
  positionId,
  playerColor,
  minEvalLoss,
  accountId,
  sessionStatus,
  ecoCode,
  onBack,
  onClose,
}: PositionProgressViewProps) {
  const [loadState, setLoadState] = useState<LoadState | null>(null);
  const [retryToken, setRetryToken] = useState(0);
  const modalRef = useRef<HTMLDivElement>(null);
  const requestKey = `${positionId}:${playerColor}:${accountId ?? ''}:${minEvalLoss}`;

  const handleClose = React.useCallback(() => {
    if (onClose) {
      onClose();
    } else if (onBack) {
      onBack();
    }
  }, [onClose, onBack]);

  useEffect(() => {
    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        handleClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      document.body.style.overflow = originalOverflow;
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [handleClose]);

  const visibleLoadState =
    loadState?.requestKey === requestKey ? loadState : { status: 'loading' as const };
  const intervalStateMessage =
    visibleLoadState.status === 'loaded'
      ? intervalStateText(visibleLoadState.progress.currentIntervalState)
      : null;
  const loadedProgress = visibleLoadState.status === 'loaded' ? visibleLoadState.progress : null;
  const measurements = loadedProgress ? chartMeasurements(loadedProgress) : [];
  const baseline = loadedProgress ? displayBaseline(loadedProgress) : null;

  useEffect(() => {
    if (sessionStatus !== 'authenticated' || !accountId) return;

    let isCurrentRequest = true;
    fetchPositionProgress(positionId, playerColor, accountId, minEvalLoss)
      .then((progress) => {
        if (isCurrentRequest) setLoadState({ requestKey, status: 'loaded', progress });
      })
      .catch(() => {
        if (isCurrentRequest) setLoadState({ requestKey, status: 'error' });
      });

    return () => {
      isCurrentRequest = false;
    };
  }, [positionId, playerColor, accountId, minEvalLoss, requestKey, retryToken, sessionStatus]);

  return (
    <div
      className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-3 sm:p-6"
      role="dialog"
      aria-modal="true"
      aria-labelledby="progress-heading"
      onClick={(e) => {
        if (e.target === e.currentTarget) handleClose();
      }}
    >
      <div
        ref={modalRef}
        className="w-[95vw] max-w-4xl max-h-[90vh] rounded-2xl border border-outline-variant bg-surface-container shadow-2xl overflow-hidden flex flex-col animate-in fade-in zoom-in-95 duration-150"
      >
        {/* Modal Header */}
        <div className="flex items-center justify-between p-4 sm:p-5 border-b border-outline-variant bg-surface-container-low shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-primary/10 border border-primary/20 flex items-center justify-center text-primary shrink-0">
              <span className="material-symbols-outlined text-2xl" aria-hidden="true">query_stats</span>
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 id="progress-heading" className="text-xl font-bold text-on-surface">
                  Position Progress
                </h2>
                {ecoCode && (
                  <span className="px-2 py-0.5 rounded-full bg-secondary/10 border border-secondary/20 text-secondary text-xs font-mono font-semibold">
                    ECO {ecoCode}
                  </span>
                )}
              </div>
              <p className="text-xs text-on-surface-variant font-mono mt-0.5">
                Your {playerColor.toLowerCase()}-side history at this exact position.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={handleClose}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-mono font-semibold text-outline hover:text-on-surface hover:bg-surface-container transition cursor-pointer"
            >
              <span className="material-symbols-outlined text-sm" aria-hidden="true">arrow_back</span>
              <span>Back to weaknesses</span>
            </button>
            <button
              type="button"
              onClick={handleClose}
              aria-label="Close"
              className="p-1.5 rounded-lg text-outline hover:text-on-surface hover:bg-surface-container transition cursor-pointer"
            >
              <span className="material-symbols-outlined text-xl" aria-hidden="true">close</span>
            </button>
          </div>
        </div>

        {/* Modal Scrollable Content */}
        <div className="overflow-y-auto p-4 sm:p-6 space-y-5 flex-1 min-h-0">
          {sessionStatus === 'loading' && (
            <p role="status" className="rounded-xl border border-outline-variant bg-surface-container-low p-5 text-xs font-mono text-on-surface-variant">
              Checking sign-in…
            </p>
          )}

          {sessionStatus === 'error' && (
            <p role="alert" className="rounded-xl border border-error-container bg-error-container/20 p-5 text-xs font-mono text-error">
              Unable to verify sign-in. Refresh the page and try again.
            </p>
          )}

          {sessionStatus === 'unauthenticated' && (
            <div className="rounded-xl border border-outline-variant bg-surface-container-low p-6 text-center space-y-3">
              <h3 className="text-base font-bold text-on-surface font-sans">Sign in to view progress</h3>
              <p className="text-xs text-on-surface-variant font-mono">
                Position progress is private to your account.
              </p>
              <a
                href="/login"
                className="inline-flex px-4 py-2 bg-primary hover:bg-primary-container text-on-primary-container text-xs font-mono font-bold rounded-xl border border-outline-variant transition"
              >
                Sign in
              </a>
            </div>
          )}

          {sessionStatus === 'authenticated' && !accountId && (
            <p role="status" className="rounded-xl border border-outline-variant bg-surface-container-low p-5 text-xs font-mono text-on-surface-variant">
              Connect a Chess.com account to view position progress.
            </p>
          )}

          {sessionStatus === 'authenticated' && accountId && visibleLoadState.status === 'loading' && (
            <p role="status" className="rounded-xl border border-outline-variant bg-surface-container-low p-5 text-xs font-mono text-on-surface-variant">
              Loading position progress…
            </p>
          )}

          {sessionStatus === 'authenticated' && accountId && visibleLoadState.status === 'error' && (
            <div role="alert" className="rounded-xl border border-error-container bg-surface-container-low p-5 text-center space-y-3">
              <p className="text-xs text-error font-mono">We couldn&apos;t load position progress. Please try again.</p>
              <button
                type="button"
                onClick={() => {
                  setLoadState({ requestKey, status: 'loading' });
                  setRetryToken((token) => token + 1);
                }}
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-surface-container-high hover:bg-surface-bright text-on-surface text-xs font-mono font-bold rounded-xl border border-outline-variant transition cursor-pointer"
              >
                <span className="material-symbols-outlined text-sm" aria-hidden="true">refresh</span>
                <span>Retry</span>
              </button>
            </div>
          )}

          {sessionStatus === 'authenticated' && accountId && visibleLoadState.status === 'loaded' && (
            <div className="space-y-5">
              {/* Progress Assessment Card */}
              <div className="rounded-xl border-l-4 border-l-amber-accent border border-outline-variant bg-surface-container-low p-5 shadow-sm space-y-2">
                <div className="flex items-center gap-2 text-amber-accent text-xs font-mono font-semibold uppercase tracking-wider">
                  <span className="material-symbols-outlined text-base" aria-hidden="true">schedule</span>
                  <h2>Progress assessment</h2>
                </div>
                <p className="text-on-surface font-sans text-sm leading-relaxed">{visibleLoadState.progress.assessment}</p>
              </div>

              {intervalStateMessage !== null && (
                <p
                  className="rounded-xl border border-outline-variant bg-surface-container-low p-4 text-xs font-mono text-on-surface-variant"
                  data-testid="progress-current-interval-state"
                >
                  {intervalStateMessage}
                </p>
              )}

              {/* Baseline vs Latest Interval */}
              <div className="grid gap-4 sm:grid-cols-2">
                <BaselineSummary baseline={baseline} />
                <LatestIntervalSummary
                  point={visibleLoadState.progress.points.at(-1) ?? null}
                />
              </div>

              {visibleLoadState.progress.excludedUndatedEncounters > 0 && (
                <p className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-xs font-mono text-amber-400">
                  {visibleLoadState.progress.excludedUndatedEncounters} undated encounters excluded because
                  game date is unavailable.
                </p>
              )}

              {visibleLoadState.progress.points.length === 0 && (
                <p className="rounded-xl border border-outline-variant bg-surface-container-low p-5 text-xs font-mono text-on-surface-variant">
                  No measured interval observations yet.
                </p>
              )}
              {measurements.length > 0 && <ProgressChart points={measurements} />}

              {/* Metric Change Cards */}
              <dl className="grid gap-4 sm:grid-cols-2 font-mono">
                <div className="rounded-xl border border-outline-variant bg-surface-container-low p-4 shadow-sm">
                  <dt className="text-[11px] font-semibold uppercase tracking-wide text-outline">
                    Mistake-rate change
                  </dt>
                  <dd className={`mt-2 text-xl font-bold ${
                    visibleLoadState.progress.mistakeRateChange !== null && visibleLoadState.progress.mistakeRateChange < 0
                      ? 'text-primary'
                      : visibleLoadState.progress.mistakeRateChange !== null && visibleLoadState.progress.mistakeRateChange > 0
                        ? 'text-rose-400'
                        : 'text-on-surface'
                  }`}>
                    {changeText(visibleLoadState.progress.mistakeRateChange)}
                  </dd>
                </div>
                <div className="rounded-xl border border-outline-variant bg-surface-container-low p-4 shadow-sm">
                  <dt className="text-[11px] font-semibold uppercase tracking-wide text-outline">
                    Win-rate change
                  </dt>
                  <dd className={`mt-2 text-xl font-bold ${
                    visibleLoadState.progress.winRateChange !== null && visibleLoadState.progress.winRateChange > 0
                      ? 'text-primary'
                      : visibleLoadState.progress.winRateChange !== null && visibleLoadState.progress.winRateChange < 0
                        ? 'text-rose-400'
                        : 'text-on-surface'
                  }`}>
                    {changeText(visibleLoadState.progress.winRateChange)}
                  </dd>
                </div>
              </dl>
            </div>
          )}
        </div>

        {/* Modal Footer */}
        <div className="flex items-center justify-between p-4 border-t border-outline-variant bg-surface-container-low shrink-0 text-xs font-mono">
          <span className="text-outline">Sync: Live data from indexed personal encounters</span>
          <button
            type="button"
            onClick={handleClose}
            className="px-4 py-2 bg-surface-container hover:bg-surface-container-high text-on-surface font-semibold rounded-xl border border-outline-variant transition cursor-pointer"
          >
            Close Window
          </button>
        </div>
      </div>
    </div>
  );
}
