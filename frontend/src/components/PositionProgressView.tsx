'use client';

import { useEffect, useState } from 'react';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import {
  fetchPositionProgress,
  type PositionProgressResponse,
  type SessionState,
} from '../services/api';

interface PositionProgressViewProps {
  positionId: string;
  playerColor: 'WHITE' | 'BLACK';
  sessionStatus: SessionState['status'];
  onBack: () => void;
}

type LoadState =
  | { requestKey: string; status: 'loading' }
  | { requestKey: string; status: 'loaded'; progress: PositionProgressResponse }
  | { requestKey: string; status: 'error' };

function changeText(value: number | null, pointCount: number): string {
  if (pointCount === 1) return 'Not enough history yet';
  if (value === null) return 'Not available yet';
  return `${value > 0 ? '+' : ''}${value}%`;
}

function chartX(index: number, points: PositionProgressResponse['points']): number {
  if (points.length === 1) return 300;
  const first = Date.parse(points[0].occurredAt);
  const last = Date.parse(points[points.length - 1].occurredAt);
  if (first === last) return 42 + (index / (points.length - 1)) * 516;
  return 42 + ((Date.parse(points[index].occurredAt) - first) / (last - first)) * 516;
}

function chartY(rate: number): number {
  return 220 - (rate / 100) * 180;
}

function seriesPoints(
  points: PositionProgressResponse['points'],
  key: 'mistakeRate' | 'winRate',
): string {
  return points.map((point, index) => `${chartX(index, points)},${chartY(point[key])}`).join(' ');
}

function ProgressChart({ progress }: { progress: PositionProgressResponse }) {
  const points = progress.points;
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-950/70 p-3 sm:p-5">
      <div className="mb-3 flex flex-wrap gap-x-5 gap-y-2 text-xs">
        <span className="flex items-center gap-2 text-emerald-300">
          <span className="h-2 w-2 rounded-full bg-emerald-400" />
          Mistake rate
        </span>
        <span className="flex items-center gap-2 text-sky-300">
          <span className="h-2 w-2 rounded-full bg-sky-400" />
          Win rate
        </span>
      </div>
      <svg
        viewBox="0 0 600 270"
        role="img"
        aria-label="Position progress over time"
        aria-describedby="progress-chart-description"
        className="h-auto w-full"
        preserveAspectRatio="none"
      >
        <title>Position progress over time</title>
        <desc>Mistake rate and win rate by encounter, shown as percentages from 0 to 100.</desc>
        {[0, 50, 100].map((rate) => {
          const y = chartY(rate);
          return (
            <g key={rate}>
              <line x1="42" y1={y} x2="558" y2={y} stroke="#334155" strokeDasharray="4 5" />
              <text x="34" y={y + 4} fill="#94a3b8" fontSize="11" textAnchor="end">
                {rate}%
              </text>
            </g>
          );
        })}
        <polyline
          data-testid="progress-series-mistake-rate"
          points={seriesPoints(points, 'mistakeRate')}
          fill="none"
          stroke="#34d399"
          strokeWidth="3"
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        <polyline
          data-testid="progress-series-win-rate"
          points={seriesPoints(points, 'winRate')}
          fill="none"
          stroke="#38bdf8"
          strokeWidth="3"
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        {points.map((point, index) => {
          const x = chartX(index, points);
          return (
            <g key={`${point.occurredAt}-${index}`}>
              <circle
                data-testid="progress-point-mistake-rate"
                cx={x}
                cy={chartY(point.mistakeRate)}
                r="4"
                fill="#34d399"
              >
                <title>
                  {new Date(point.occurredAt).toLocaleDateString()}: mistake rate {point.mistakeRate}%
                </title>
              </circle>
              <circle
                data-testid="progress-point-win-rate"
                cx={x}
                cy={chartY(point.winRate)}
                r="4"
                fill="#38bdf8"
              >
                <title>
                  {new Date(point.occurredAt).toLocaleDateString()}: win rate {point.winRate}%
                </title>
              </circle>
            </g>
          );
        })}
        <text x="42" y="254" fill="#94a3b8" fontSize="11">
          {new Date(points[0].occurredAt).toLocaleDateString()}
        </text>
        {points.length > 1 && (
          <text x="558" y="254" fill="#94a3b8" fontSize="11" textAnchor="end">
            {new Date(points[points.length - 1].occurredAt).toLocaleDateString()}
          </text>
        )}
      </svg>
      <p id="progress-chart-description" className="sr-only">
        Mistake rate is green and win rate is blue. The horizontal axis is encounter date and
        the vertical axis is percentage.
      </p>
    </div>
  );
}

export function PositionProgressView({
  positionId,
  playerColor,
  sessionStatus,
  onBack,
}: PositionProgressViewProps) {
  const [loadState, setLoadState] = useState<LoadState | null>(null);
  const [retryToken, setRetryToken] = useState(0);
  const requestKey = `${positionId}:${playerColor}`;
  const visibleLoadState =
    loadState?.requestKey === requestKey ? loadState : { status: 'loading' as const };

  useEffect(() => {
    if (sessionStatus !== 'authenticated') return;

    let isCurrentRequest = true;
    fetchPositionProgress(positionId, playerColor)
      .then((progress) => {
        if (isCurrentRequest) setLoadState({ requestKey, status: 'loaded', progress });
      })
      .catch(() => {
        if (isCurrentRequest) setLoadState({ requestKey, status: 'error' });
      });

    return () => {
      isCurrentRequest = false;
    };
  }, [positionId, playerColor, requestKey, retryToken, sessionStatus]);

  return (
    <section className="mx-auto w-full max-w-5xl px-4 py-6 lg:px-8" aria-labelledby="progress-heading">
      <button
        type="button"
        onClick={onBack}
        className="mb-5 inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold text-slate-300 hover:bg-slate-900 hover:text-white"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to weaknesses
      </button>

      <div className="mb-6">
        <h1 id="progress-heading" className="text-2xl font-bold text-white">
          Position Progress
        </h1>
        <p className="mt-1 text-sm text-slate-400">
          Your {playerColor.toLowerCase()}-side history at this exact position.
        </p>
      </div>

      {sessionStatus === 'loading' && (
        <p role="status" className="rounded-xl border border-slate-800 bg-slate-900 p-5 text-sm text-slate-300">
          Checking sign-in…
        </p>
      )}

      {sessionStatus === 'error' && (
        <p role="alert" className="rounded-xl border border-rose-900/60 bg-rose-950/30 p-5 text-sm text-rose-200">
          Unable to verify sign-in. Refresh the page and try again.
        </p>
      )}

      {sessionStatus === 'unauthenticated' && (
        <div className="rounded-xl border border-slate-800 bg-slate-900 p-6">
          <h2 className="text-lg font-semibold text-white">Sign in to view progress</h2>
          <p className="mt-2 text-sm text-slate-400">
            Position progress is private to your account.
          </p>
          <a
            href="/login"
            className="mt-4 inline-flex rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-500"
          >
            Sign in
          </a>
        </div>
      )}

      {sessionStatus === 'authenticated' && visibleLoadState.status === 'loading' && (
        <p role="status" className="rounded-xl border border-slate-800 bg-slate-900 p-5 text-sm text-slate-300">
          Loading position progress…
        </p>
      )}

      {sessionStatus === 'authenticated' && visibleLoadState.status === 'error' && (
        <div role="alert" className="rounded-xl border border-rose-900/60 bg-rose-950/30 p-5">
          <p className="text-sm text-rose-200">We couldn&apos;t load position progress. Please try again.</p>
          <button
            type="button"
            onClick={() => {
              setLoadState({ requestKey, status: 'loading' });
              setRetryToken((token) => token + 1);
            }}
            className="mt-4 inline-flex items-center gap-2 rounded-lg bg-slate-800 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-700"
          >
            <RefreshCw className="h-4 w-4" />
            Retry
          </button>
        </div>
      )}

      {sessionStatus === 'authenticated' && visibleLoadState.status === 'loaded' && (
        <div className="space-y-5">
          <div className="rounded-xl border border-emerald-900/60 bg-emerald-950/20 p-5">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-emerald-300">
              Progress assessment
            </h2>
            <p className="mt-2 text-slate-100">{visibleLoadState.progress.assessment}</p>
          </div>

          {visibleLoadState.progress.points.length === 0 ? (
            <p className="rounded-xl border border-slate-800 bg-slate-900 p-5 text-sm text-slate-300">
              No progress history yet.
            </p>
          ) : (
            <>
              {visibleLoadState.progress.points.length === 1 && (
                <p className="rounded-lg border border-slate-800 bg-slate-900/70 p-4 text-sm text-slate-300">
                  One encounter is not enough history to show a trend.
                </p>
              )}
              <ProgressChart progress={visibleLoadState.progress} />
            </>
          )}

          <dl className="grid gap-3 sm:grid-cols-2">
            <div className="rounded-xl border border-slate-800 bg-slate-900 p-4">
              <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                Mistake-rate change
              </dt>
              <dd className="mt-2 text-xl font-bold text-white">
                {changeText(visibleLoadState.progress.mistakeRateChange, visibleLoadState.progress.points.length)}
              </dd>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900 p-4">
              <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                Win-rate change
              </dt>
              <dd className="mt-2 text-xl font-bold text-white">
                {changeText(visibleLoadState.progress.winRateChange, visibleLoadState.progress.points.length)}
              </dd>
            </div>
          </dl>
        </div>
      )}
    </section>
  );
}
