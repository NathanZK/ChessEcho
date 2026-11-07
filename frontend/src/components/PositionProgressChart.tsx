'use client';

import React from 'react';
import type { PositionProgressResponse } from '../services/api';

export interface ChartMeasurement {
  key: string;
  label: string;
  occurredAt: string;
  mistakeRate: number;
  winRate: number;
  attempts: number;
}

export function chartMeasurements(progress: PositionProgressResponse): ChartMeasurement[] {
  const baseline = progress.baseline
    ? [{
        key: 'baseline',
        label: 'Historical baseline',
        occurredAt: progress.baseline.occurredAt,
        mistakeRate: progress.baseline.mistakeRate,
        winRate: progress.baseline.winRate,
        attempts: progress.baseline.sourceEncounterCount,
      }]
    : [];
  const intervals = progress.points.map((point, index) => ({
    key: point.checkpointId,
    label: progress.baseline || index > 0 ? `Interval ${index + 1}` : `Baseline · Interval ${index + 1}`,
    occurredAt: point.occurredAt,
    mistakeRate: point.mistakeRate,
    winRate: point.winRate,
    attempts: point.attempts,
  }));
  return [...baseline, ...intervals];
}

function chartX(index: number, points: ChartMeasurement[]): number {
  if (points.length === 1) return 300;
  const first = Date.parse(points[0].occurredAt);
  const last = Date.parse(points[points.length - 1].occurredAt);
  if (first === last) return 42 + ((index / (points.length - 1)) * 516);
  return 42 + ((((Date.parse(points[index].occurredAt) - first) / (last - first))) * 516);
}

function chartY(rate: number): number {
  return 220 - ((rate / 100) * 180);
}

function seriesPoints(
  points: ChartMeasurement[],
  key: 'mistakeRate' | 'winRate',
): string {
  return points.map((point, index) => `${chartX(index, points)},${chartY(point[key])}`).join(' ');
}

export function ProgressChart({ points }: { points: ChartMeasurement[] }) {
  return (
    <div className="rounded-xl border border-outline-variant bg-surface-container-lowest p-3 sm:p-5 shadow-sm">
      <div className="mb-3 flex flex-wrap gap-x-5 gap-y-2 text-xs font-mono">
        <span className="flex items-center gap-2 text-primary font-semibold">
          <span className="h-2 w-2 rounded-full bg-primary" />
          Mistake rate
        </span>
        <span className="flex items-center gap-2 text-sky-400 font-semibold">
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
        <desc>
          Mistake rate and win rate by measurement, shown as percentages from 0 to 100. The first point
          is the historical baseline when available, followed by measured intervals.
        </desc>
        {[0, 50, 100].map((rate) => {
          const y = chartY(rate);
          return (
            <g key={rate}>
              <line x1="42" y1={y} x2="558" y2={y} stroke="#2d333b" strokeDasharray="4 5" />
              <text x="34" y={y + 4} fill="#7e998c" fontSize="11" textAnchor="end" fontFamily="monospace">
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
            <g key={point.key}>
              <circle
                data-testid="progress-point-mistake-rate"
                cx={x}
                cy={chartY(point.mistakeRate)}
                r="4"
                fill="#34d399"
              >
                <title>
                  {point.attempts} encounters, {new Date(point.occurredAt).toLocaleDateString()}:
                  mistake rate {point.mistakeRate}%
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
                  {point.attempts} encounters, {new Date(point.occurredAt).toLocaleDateString()}:
                  win rate {point.winRate}%
                </title>
              </circle>
            </g>
          );
        })}
        <text x="42" y="254" fill="#7e998c" fontSize="11" fontFamily="monospace">
          {new Date(points[0].occurredAt).toLocaleDateString()}
        </text>
        {points.length > 1 && (
          <text x="558" y="254" fill="#7e998c" fontSize="11" textAnchor="end" fontFamily="monospace">
            {new Date(points[points.length - 1].occurredAt).toLocaleDateString()}
          </text>
        )}
      </svg>
      <p className="mt-2 text-xs text-outline font-mono">Each label below corresponds to one point in the chart.</p>
      <ul
        className="mt-2 flex flex-wrap gap-2 text-xs text-on-surface-variant font-mono"
        aria-label="Progress measurements shown in chart"
      >
        {points.map((point) => (
          <li
            key={point.key}
            className="rounded-full border border-outline-variant bg-surface-container-low px-3 py-1"
            data-testid={`progress-interval-label-${point.key}`}
          >
            {point.label} · {point.attempts} {point.attempts === 1 ? 'encounter' : 'encounters'} ·{' '}
            {new Date(point.occurredAt).toLocaleDateString()}
          </li>
        ))}
      </ul>
      <p id="progress-chart-description" className="sr-only">
        Mistake rate is green and win rate is blue. The horizontal axis is the latest included
        game date for each training interval, and the vertical axis is percentage.
      </p>
    </div>
  );
}
