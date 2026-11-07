'use client';

import React from 'react';
import { ChessBoardView } from './ChessBoardView';
import { ExternalGameLink } from './ExternalGameLink';
import {
  type DecisionSeverity,
  type LongDecisionOccurrence,
} from '../services/api';
import { buildExactPlyChessComUrl, isValidChessComUrl } from '../utils/urlUtils';

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

export function formatPlayedAt(playedAt: string | null): string {
  if (!playedAt) return 'Date unavailable';
  const date = new Date(playedAt);
  if (!Number.isFinite(date.getTime())) return 'Date unavailable';
  return date.toLocaleDateString();
}

interface CardProps {
  occurrence: LongDecisionOccurrence;
  onExplorePosition?: (occurrence: LongDecisionOccurrence) => boolean | void;
  explorationErrorId: string | null;
  setExplorationErrorId: (id: string | null) => void;
}

export const LongDecisionGridCard: React.FC<CardProps> = ({
  occurrence,
  onExplorePosition,
  explorationErrorId,
  setExplorationErrorId,
}) => {
  const gameUrl = isValidChessComUrl(occurrence.chessComUrl)
    ? occurrence.chessComUrl.trim()
    : buildExactPlyChessComUrl(
        occurrence.platformGameId,
        occurrence.plyNumber,
        occurrence.timeControl,
      );

  return (
    <div className="bg-surface-container-low border border-outline-variant rounded-2xl p-4 shadow-md flex flex-col justify-between space-y-4">
      {/* Card Header */}
      <div className="flex items-center justify-between border-b border-outline-variant pb-2.5">
        <span className="text-xs font-semibold uppercase tracking-wide text-on-surface-variant font-mono">
          {occurrence.timeControl} · {occurrence.playerColor}
        </span>
        <span
          data-testid={`severity-pill-${occurrence.id}`}
          className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold ${severityPillStyles(
            occurrence.severity,
          )}`}
        >
          <span className="w-1.5 h-1.5 rounded-full bg-current animate-pulse" aria-hidden="true" />
          {formatSeverityLabel(occurrence.severity)}
        </span>
      </div>

      {/* Card Body */}
      <div className="grid gap-4 sm:grid-cols-[180px_1fr]">
        <div className="aspect-square w-full max-w-[180px] overflow-hidden rounded-xl border border-outline-variant mx-auto sm:mx-0">
          <ChessBoardView
            fen={occurrence.fen}
            boardOrientation={occurrence.playerColor}
            allowDragging={false}
            containerClassName="w-full h-full"
            frameClassName="w-full h-full"
          />
        </div>

        <div className="flex min-w-0 flex-col justify-between space-y-2">
          <div className="space-y-1.5">
            <div className="p-2.5 bg-surface-container-lowest border border-outline-variant rounded-xl">
              <span className="text-[10px] font-mono text-outline block uppercase tracking-wider">Decision Time</span>
              <p className="text-base font-bold text-amber-accent font-mono">
                {formatDecisionDuration(occurrence.decisionTimeMs)}
              </p>
            </div>
            <p className="text-xs text-on-surface-variant font-mono">
              Move played: <span className="font-bold text-on-surface font-mono">{occurrence.movePlayed}</span>
            </p>
          </div>

          <dl className="space-y-1 text-xs text-on-surface-variant font-mono bg-surface-container-lowest p-2.5 rounded-xl border border-outline-variant">
            <div className="truncate">
              <dt className="inline text-outline">Opponent: </dt>
              <dd className="inline text-on-surface font-semibold">{occurrence.opponentUsername || 'Unavailable'}</dd>
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
        </div>
      </div>

      {/* Card Footer */}
      <div className="flex items-center justify-between gap-2 pt-2 border-t border-outline-variant text-xs">
        {gameUrl ? (
          <ExternalGameLink
            url={gameUrl}
            label="Open game"
            platform="chesscom"
            rel="noreferrer"
          />
        ) : (
          <span className="text-xs text-on-surface-variant/50 font-mono italic">Game link unavailable</span>
        )}

        {onExplorePosition && (
          <button
            className="inline-flex items-center gap-1.5 text-xs font-mono font-semibold text-primary hover:underline cursor-pointer"
            onClick={() => {
              const started = onExplorePosition(occurrence);
              setExplorationErrorId(started === false ? occurrence.id : null);
            }}
            type="button"
          >
            <span className="material-symbols-outlined text-sm" aria-hidden="true">explore</span>
            <span>Explore this position</span>
          </button>
        )}
      </div>

      {explorationErrorId === occurrence.id && (
        <p className="text-xs text-error font-mono" role="alert">
          Could not start exploration for this position. Your current state was not changed.
        </p>
      )}
    </div>
  );
};

export const LongDecisionListRow: React.FC<CardProps> = ({
  occurrence,
  onExplorePosition,
  explorationErrorId,
  setExplorationErrorId,
}) => {
  const gameUrl = isValidChessComUrl(occurrence.chessComUrl)
    ? occurrence.chessComUrl.trim()
    : buildExactPlyChessComUrl(
        occurrence.platformGameId,
        occurrence.plyNumber,
        occurrence.timeControl,
      );

  return (
    <div className="bg-surface-container-low border border-outline-variant rounded-xl p-3.5 shadow-sm flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
      {/* Left: 88x88 Board Thumbnail + Control tag */}
      <div className="flex items-center gap-3">
        <div className="w-[88px] h-[88px] shrink-0 rounded-lg overflow-hidden border border-outline-variant">
          <ChessBoardView
            fen={occurrence.fen}
            boardOrientation={occurrence.playerColor}
            allowDragging={false}
            containerClassName="w-full h-full"
            frameClassName="w-full h-full"
          />
        </div>
        <div className="space-y-1 font-mono text-xs">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-on-surface uppercase">{occurrence.timeControl}</span>
            <span className="text-outline">·</span>
            <span className="text-on-surface-variant">{occurrence.playerColor}</span>
          </div>
          <span
            data-testid={`severity-pill-${occurrence.id}`}
            className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold ${severityPillStyles(
              occurrence.severity,
            )}`}
          >
            {formatSeverityLabel(occurrence.severity)}
          </span>
          <p className="text-[11px] text-outline">Ply {occurrence.plyNumber}</p>
        </div>
      </div>

      {/* Middle: Decision Time + Move */}
      <div className="space-y-1 font-mono">
        <div className="text-sm font-bold text-amber-accent">
          {formatDecisionDuration(occurrence.decisionTimeMs)}
        </div>
        <div className="text-xs text-on-surface-variant">
          Played: <span className="font-bold text-on-surface">{occurrence.movePlayed}</span>
        </div>
      </div>

      {/* Right-middle: Opponent + Date */}
      <div className="text-xs font-mono text-on-surface-variant space-y-0.5">
        <div>Opponent: <span className="text-on-surface font-semibold">{occurrence.opponentUsername || 'Unavailable'}</span></div>
        <div>Date: <span className="text-on-surface">{formatPlayedAt(occurrence.playedAt)}</span></div>
      </div>

      {/* Right Actions */}
      <div className="flex flex-col sm:items-end gap-1.5 shrink-0">
        {gameUrl && (
          <ExternalGameLink
            url={gameUrl}
            label="Open game"
            platform="chesscom"
            rel="noreferrer"
          />
        )}
        {onExplorePosition && (
          <button
            className="inline-flex items-center gap-1 text-xs font-mono font-semibold text-primary hover:underline cursor-pointer"
            onClick={() => {
              const started = onExplorePosition(occurrence);
              setExplorationErrorId(started === false ? occurrence.id : null);
            }}
            type="button"
          >
            <span className="material-symbols-outlined text-sm" aria-hidden="true">explore</span>
            <span>Explore this position</span>
          </button>
        )}
        {explorationErrorId === occurrence.id && (
          <p className="text-xs text-error font-mono" role="alert">
            Could not start exploration for this position.
          </p>
        )}
      </div>
    </div>
  );
};
