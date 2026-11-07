'use client';

import React from 'react';
import { ChessBoardView } from './ChessBoardView';
import { ExternalGameLink } from './ExternalGameLink';
import type { SimilarPositionResult, SimilarPositionOccurrenceDto } from '../services/api';

interface SimilarPositionCardProps {
  result: SimilarPositionResult;
  onExplore: (fen: string) => void;
}

function getResultBadge(result?: string | null) {
  if (!result) return null;
  const normalized = result.trim().toLowerCase();
  if (normalized === 'win' || normalized.startsWith('won')) {
    return {
      label: result,
      icon: 'emoji_events',
      className: 'bg-primary/15 text-emerald-400 border border-primary/30',
    };
  }
  if (normalized === 'loss' || normalized.startsWith('lost') || normalized === 'checkmated') {
    return {
      label: result,
      icon: 'cancel',
      className: 'bg-error-container/20 text-rose-400 border border-error-container/40',
    };
  }
  return {
    label: result,
    icon: 'handshake',
    className: 'bg-amber-accent/15 text-amber-400 border border-amber-accent/30',
  };
}

const OccurrenceDetails: React.FC<{ occurrence?: SimilarPositionOccurrenceDto }> = ({ occurrence }) => {
  if (!occurrence) {
    return (
      <div className="p-4 bg-surface-container-lowest rounded-xl border border-outline-variant text-xs font-mono text-on-surface-variant italic">
        Structure matched in personal archive • Occurrence details not indexed
      </div>
    );
  }

  const badge = getResultBadge(occurrence.gameResult);
  const playedDate = occurrence.playedAt
    ? new Date(occurrence.playedAt).toLocaleDateString()
    : 'Not recorded';

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <div className="space-y-0.5">
          <span className="text-[10px] font-mono text-on-surface-variant block uppercase tracking-wider">
            Played move
          </span>
          <span className="font-mono font-bold text-sm bg-surface-container-lowest px-2.5 py-1 rounded-md text-on-surface border border-outline-variant inline-block">
            {occurrence.playedMove}
          </span>
        </div>
        {badge && (
          <div className="space-y-0.5">
            <span className="text-[10px] font-mono text-on-surface-variant block uppercase tracking-wider">
              Result
            </span>
            <span className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-mono font-bold ${badge.className}`}>
              <span className="material-symbols-outlined text-xs" aria-hidden="true">{badge.icon}</span>
              {badge.label}
            </span>
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 gap-2 text-xs font-mono bg-surface-container-lowest p-3 rounded-xl border border-outline-variant">
        <div className="truncate">
          <span className="text-on-surface-variant text-[10px] block">Opponent</span>
          <span className="text-on-surface font-semibold truncate block">
            {occurrence.opponentUsername || 'Anonymous'}
          </span>
        </div>
        <div>
          <span className="text-on-surface-variant text-[10px] block">Time Control</span>
          <span className="text-on-surface font-semibold">
            {occurrence.timeControl || 'Unknown'}
          </span>
        </div>
        <div>
          <span className="text-on-surface-variant text-[10px] block">Move Location</span>
          <span className="text-on-surface font-semibold">
            Ply {occurrence.plyNumber} • {occurrence.playerColor}
          </span>
        </div>
        <div>
          <span className="text-on-surface-variant text-[10px] block">Date Played</span>
          <span className="text-on-surface font-semibold">{playedDate}</span>
        </div>
      </div>
    </div>
  );
};

export const SimilarPositionCard: React.FC<SimilarPositionCardProps> = ({ result, onExplore }) => {
  const occurrence = result.occurrences?.[0];
  const gameId = occurrence?.platformGameId ? occurrence.platformGameId.split('/').pop() : null;

  return (
    <div className="bg-surface-container-low border border-outline-variant rounded-2xl p-5 shadow-lg space-y-4 flex flex-col justify-between">
      {/* Card Header */}
      <div className="flex items-center justify-between border-b border-outline-variant pb-3">
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1.5 text-xs font-bold font-mono uppercase tracking-wider text-primary">
            <span className="w-2 h-2 rounded-full bg-primary" />
            Similar Position
          </span>
          <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-surface-container border border-outline-variant text-on-surface">
            {occurrence?.playerColor ?? 'WHITE'}
          </span>
        </div>
        <button
          type="button"
          onClick={() => onExplore(result.fen)}
          className="inline-flex items-center gap-1.5 rounded-lg bg-primary/10 border border-primary/30 px-3 py-1.5 text-xs font-semibold font-mono text-primary hover:bg-primary/20 transition cursor-pointer"
        >
          <span className="material-symbols-outlined text-sm">explore</span>
          <span>Explore this position</span>
        </button>
      </div>

      {/* Card Body */}
      <div className="flex flex-col sm:flex-row gap-5">
        <div className="w-[210px] shrink-0 mx-auto sm:mx-0 flex flex-col gap-1.5">
          <div className="aspect-square w-[210px] h-[210px] overflow-hidden rounded-xl border border-outline-variant bg-surface-container-lowest">
            <ChessBoardView
              fen={result.fen}
              boardOrientation={occurrence?.playerColor ?? 'white'}
              allowDragging={false}
              containerClassName="w-full h-full"
              frameClassName="w-full h-full"
            />
          </div>
          <div className="flex items-center justify-between text-[10px] font-mono text-on-surface-variant px-1">
            <span>Orientation: {occurrence?.playerColor ?? 'White'}</span>
            <span>Ply {occurrence?.plyNumber ?? '-'}</span>
          </div>
        </div>

        <div className="flex-1 flex flex-col justify-between space-y-3 min-w-0">
          <OccurrenceDetails occurrence={occurrence} />
        </div>
      </div>

      {/* Card Footer */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 pt-3 border-t border-outline-variant text-xs">
        <div className="font-mono text-on-surface-variant text-[11px] truncate">
          {gameId && <span>Game ID: #{gameId}</span>}
        </div>
        <div>
          {occurrence?.chessComUrl ? (
            <ExternalGameLink
              url={occurrence.chessComUrl}
              label="View move on Chess.com"
              platform="chesscom"
            />
          ) : (
            <span className="text-on-surface-variant/50 font-mono text-[11px] italic">
              Chess.com link unavailable
            </span>
          )}
        </div>
      </div>
    </div>
  );
};
