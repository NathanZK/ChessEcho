'use client';

import React from 'react';
import { Swords, ExternalLink, Clock } from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import { type ClusterMemberPosition } from '../services/api';
import { formatPawnLoss } from '@/utils/formatters';
import { buildExactPlyChessComUrl } from '@/utils/urlUtils';

function getSeverityBadge(severity?: string | null) {
  if (!severity || severity === 'UNKNOWN' || severity === 'UNEVALUATED') {
    return {
      label: 'Unevaluated',
      className: 'bg-surface-container text-outline border border-outline-variant',
    };
  }
  switch (severity) {
    case 'INACCURACY':
      return {
        label: 'Inaccuracy',
        className: 'bg-surface-container-high text-on-surface-variant border border-outline-variant',
      };
    case 'MISTAKE':
      return {
        label: 'Mistake',
        className: 'bg-amber-accent/15 text-amber-400 border border-amber-accent/30',
      };
    case 'BLUNDER':
      return {
        label: 'Blunder',
        className: 'bg-error-container/30 text-rose-400 border border-error-container/50',
      };
    case 'NO_CONCERN':
      return {
        label: 'No Concern',
        className: 'bg-primary/15 text-emerald-400 border border-primary/30',
      };
    default:
      return {
        label: severity,
        className: 'bg-surface-container text-on-surface border border-outline-variant',
      };
  }
}

function getDecisionBadge(severity?: string | null) {
  if (!severity || severity === 'UNKNOWN' || severity === 'UNEVALUATED') {
    return {
      label: 'Unevaluated',
      className: 'bg-surface-container text-outline border border-outline-variant',
    };
  }
  switch (severity) {
    case 'NO_CONCERN':
      return {
        label: 'NO_CONCERN',
        className: 'bg-primary/15 text-emerald-400 border border-primary/30',
      };
    case 'MINOR':
      return {
        label: 'MINOR',
        className: 'bg-amber-accent/15 text-amber-400 border border-amber-accent/30',
      };
    case 'SIGNIFICANT':
      return {
        label: 'SIGNIFICANT',
        className: 'bg-rose-500/15 text-rose-300 border border-rose-500/30',
      };
    case 'CRITICAL':
      return {
        label: 'CRITICAL',
        className: 'bg-error-container/30 text-rose-400 border border-error-container/50',
      };
    default:
      return {
        label: severity,
        className: 'bg-surface-container text-on-surface border border-outline-variant',
      };
  }
}

export interface EvidencePositionCardProps {
  pos: ClusterMemberPosition;
  onPractice?: (pos: ClusterMemberPosition) => void;
}

export const EvidencePositionCard: React.FC<EvidencePositionCardProps> = ({
  pos,
  onPractice,
}) => {
  const boardOrientation = pos.playerColor === 'BLACK' ? 'black' : 'white';
  const severityInfo = getSeverityBadge(pos.mistakeSeverity);
  const decisionInfo = getDecisionBadge(pos.decisionSeverity);
  const chessComUrl = buildExactPlyChessComUrl(pos.platformGameId, pos.plyNumber, pos.timeControl);
  const duration =
    pos.decisionTimeMs != null ? `${(pos.decisionTimeMs / 1000).toFixed(1)}s` : 'Unavailable';

  return (
    <div className="p-4 bg-surface-container-low border border-outline-variant rounded-xl flex flex-col md:flex-row gap-4 items-start md:items-center justify-between">
      <div className="flex items-center gap-4">
        <div className="w-24 h-24 sm:w-28 sm:h-28 shrink-0 rounded-lg overflow-hidden border border-outline-variant bg-surface-container-lowest">
          <ChessBoardView fen={pos.fen} boardOrientation={boardOrientation} />
        </div>
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2 font-mono text-xs">
            <span className="text-outline">Played:</span>
            <span className="font-bold px-2 py-0.5 rounded bg-surface-container-lowest border border-outline-variant text-on-surface">
              {pos.movePlayed}
            </span>
            <span className="text-outline ml-2">Stockfish:</span>
            <span className="font-bold px-2 py-0.5 rounded bg-primary/10 text-primary border border-primary/20">
              {pos.targetMove || 'Pending analysis'}
            </span>
            {pos.acceptableMoves && pos.acceptableMoves.length > 0 && (
              <span className="text-[11px] text-outline ml-1">
                (Also: {pos.acceptableMoves.map((m) => `${m.move} [${formatPawnLoss(m.evalLoss)}]`).join(', ')})
              </span>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-2 font-mono text-xs">
            <span className="text-outline">Loss:</span>
            <span className="font-bold text-on-surface">
              {pos.evalLoss != null ? `${formatPawnLoss(pos.evalLoss)} pawns` : 'Unevaluated'}
            </span>
            <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${severityInfo.className}`}>
              {severityInfo.label}
            </span>
            <span className="text-outline ml-2">Timing:</span>
            <span className="font-bold text-on-surface flex items-center gap-1">
              <Clock className="w-3 h-3 text-outline" />
              {duration}
            </span>
            <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${decisionInfo.className}`}>
              {decisionInfo.label}
            </span>
            {pos.timeControl && (
              <span className="px-1.5 py-0.5 rounded text-[10px] bg-surface-container border border-outline-variant text-outline">
                {pos.timeControl}
              </span>
            )}
          </div>
          <div className="flex items-center gap-2 font-mono text-[11px] text-outline">
            <span>
              Move {pos.moveNumber} (ply {pos.plyNumber})
            </span>
            {chessComUrl ? (
              <a
                href={chessComUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-primary hover:underline"
              >
                <span>View game</span>
                <ExternalLink className="w-3 h-3" />
              </a>
            ) : (
              <span>Game: {pos.platformGameId}</span>
            )}
          </div>
        </div>
      </div>
      {onPractice && (
        <div className="shrink-0 self-end md:self-center">
          <button
            type="button"
            onClick={() => onPractice(pos)}
            className="px-3 py-1.5 bg-primary hover:bg-primary/90 text-on-primary text-xs font-mono font-bold rounded-xl transition flex items-center gap-1.5 shadow-sm cursor-pointer"
          >
            <Swords className="w-3.5 h-3.5" />
            <span>Practice Position</span>
          </button>
        </div>
      )}
    </div>
  );
};
